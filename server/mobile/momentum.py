"""Momentum on the radar page: the agent system's own report, from ``momentum report --json``.

momentum (https://github.com/agent-sh/momentum) keeps improving an agent system. Its report (version 1) says
what needs the owner, what broke, what is running, what shipped and what it decided on the owner's behalf. The
radar page shows it above the scout's findings.

This module only reads. It runs the command with a short timeout, keeps the last good copy (in memory and in the
mobile data dir, so a restart during an outage still has one) and says when the copy it serves is stale. It never
writes to momentum and never notifies anyone.

Standard library only: the route test loads it without a gateway.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import os
import shutil
import subprocess
import time
import unicodedata
from pathlib import Path
from typing import Any, Dict, Optional
from urllib.parse import urlsplit

logger = logging.getLogger(__name__)

VERSION = 1
SECTIONS = ("needs_you", "problems", "running", "shipped", "decided")
# Report v1 promises under 1 s warm. Five times that covers a cold start and stays far below the app's 60 s read.
TIMEOUT_S = 5.0
# The Me badge and the radar page ask together, and pull-to-refresh can follow: one run answers all of them.
REUSE_S = 15.0
MAX_ITEMS = 20  # v1 arrays hold at most 20 items
MAX_LINKS = 10
CACHE_FILE = "momentum.json"


# ------------------------------------------------------------------ the report, as plain text
def _text(v: Any, limit: int) -> str:
    """One plain line: whitespace collapsed, control characters dropped, cut at ``limit`` characters."""
    if isinstance(v, bool) or not isinstance(v, (str, int, float)):
        return ""
    s = "".join(ch for ch in " ".join(str(v).split()) if unicodedata.category(ch) != "Cc")
    return s if len(s) <= limit else s[:limit - 1].rstrip() + "…"


def _link(o: Any) -> Optional[Dict[str, str]]:
    if not isinstance(o, dict):
        return None
    url = str(o.get("url") or "").strip()
    if (len(url) > 2000 or not url.lower().startswith(("https://", "http://"))
            or any(c.isspace() or unicodedata.category(c) == "Cc" for c in url)):
        return None
    try:
        host = urlsplit(url).hostname
    except ValueError:
        return None
    if not host:
        return None
    return {"label": _text(o.get("label"), 40) or host, "url": url}


def _item(o: Any) -> Optional[Dict[str, Any]]:
    if not isinstance(o, dict):
        return None
    title = _text(o.get("title"), 120)
    if not title:
        return None
    item: Dict[str, Any] = {"id": _text(o.get("id"), 200) or "t-" + hashlib.sha1(title.encode()).hexdigest()[:12],
                            "title": title}
    for k, limit in (("detail", 400), ("effect", 200), ("at", 40), ("source", 40)):
        v = _text(o.get(k), limit)
        if v:
            item[k] = v
    raw_links = o.get("links") if isinstance(o.get("links"), list) else []
    item["links"] = [ln for ln in map(_link, raw_links[:MAX_LINKS]) if ln]
    return item


def normalize(raw: Any) -> Dict[str, Any]:
    """The report reduced to what v1 promises, every field plain text. Raises ValueError for anything else."""
    if not isinstance(raw, dict):
        raise ValueError("the report is not a JSON object")
    if raw.get("version") != VERSION:
        raise ValueError(f"report version {raw.get('version')!r}; this plugin reads version {VERSION}")
    counts = raw.get("counts") if isinstance(raw.get("counts"), dict) else {}
    out: Dict[str, Any] = {"version": VERSION, "generated_at": _text(raw.get("generated_at"), 40)}
    total: Dict[str, int] = {}
    for k in SECTIONS:
        arr = raw.get(k)
        if arr is None:
            arr = []
        if not isinstance(arr, list):
            raise ValueError(f"{k} is not a list")
        out[k] = [i for i in map(_item, arr[:MAX_ITEMS]) if i]
        n = counts.get(k)
        # counts cover everything while the arrays stop at 20; never report fewer than are listed.
        total[k] = max(n, len(out[k])) if isinstance(n, int) and not isinstance(n, bool) else len(out[k])
    out["counts"] = total
    return out


# ------------------------------------------------------------------ the command
def binary() -> Optional[str]:
    """MOMENTUM_BIN, else PATH, else ~/.local/bin (service managers often start the gateway with a short PATH)."""
    env = os.environ.get("MOMENTUM_BIN", "").strip()
    if env:
        return env
    found = shutil.which("momentum")
    if found:
        return found
    local = Path.home() / ".local" / "bin" / "momentum"
    return str(local) if local.is_file() and os.access(local, os.X_OK) else None


def _last_line(b: bytes) -> str:
    lines = [ln for ln in b.decode("utf-8", "replace").splitlines() if ln.strip()]
    return _text(lines[-1], 200) if lines else ""


def run(exe: str, timeout: float = TIMEOUT_S) -> Dict[str, Any]:
    """One ``momentum report --json`` call, normalized. Raises with a one-line reason."""
    try:
        p = subprocess.run([exe, "report", "--json"], stdin=subprocess.DEVNULL, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise RuntimeError(f"momentum report timed out after {timeout:g} s") from None
    except OSError as e:
        raise RuntimeError(f"momentum did not start: {e.strerror or e}") from None
    if p.returncode != 0:
        why = _last_line(p.stderr) or _last_line(p.stdout)
        raise RuntimeError(f"momentum report exited {p.returncode}" + (f": {why}" if why else ""))
    try:
        raw = json.loads(p.stdout)
    except ValueError as e:
        raise ValueError(f"momentum report printed invalid JSON ({e})") from None
    return normalize(raw)


# ------------------------------------------------------------------ the copy the phone gets
class Reader:
    """One run at a time, reused for REUSE_S; when a run fails, the last good copy marked stale."""

    def __init__(self, cache: Optional[Path] = None, write: bool = True):
        self.cache = cache
        self.write = write
        self._lock = asyncio.Lock()
        self._good: Optional[Dict[str, Any]] = None  # {"report", "fetched_at"}
        self._tried: Optional[Dict[str, Any]] = None  # {"at", "ok", "error", "missing"}
        self._loaded = False
        self._logged: Optional[str] = None

    async def get(self, refresh: bool = False) -> Dict[str, Any]:
        asked = time.time()
        async with self._lock:
            if not self._loaded:
                self._loaded = True
                self._good = await asyncio.to_thread(self._read_cache)
            t = self._tried
            # A run that ended while this request waited answers it, even a forced refresh.
            if t is None or (t["at"] < asked and (refresh or asked - t["at"] >= REUSE_S)):
                await self._refresh()
            return self.view()

    async def _refresh(self) -> None:
        exe = binary()
        if exe is None:
            self._tried = {"at": time.time(), "ok": False, "missing": True,
                           "error": "momentum is not installed on this machine"}
            return
        try:
            report = await asyncio.to_thread(run, exe, TIMEOUT_S)
        except Exception as e:  # any failure keeps the last good copy; the reason travels with it
            err = _text(str(e), 300) or type(e).__name__
            self._tried = {"at": time.time(), "ok": False, "error": err}
            if err != self._logged:
                logger.warning("[mobile] momentum report: %s", err)
                self._logged = err
            return
        now = time.time()
        self._good = {"report": report, "fetched_at": now}
        self._tried = {"at": now, "ok": True}
        self._logged = None
        if self.write:
            await asyncio.to_thread(self._write_cache)

    def view(self) -> Dict[str, Any]:
        t, g = self._tried or {}, self._good
        if t.get("missing") and g is None:
            return {"available": False, "report": None, "fetched_at": None, "checked_at": t.get("at"),
                    "stale": False, "error": None}
        return {"available": True,
                "report": g["report"] if g else None,
                "fetched_at": g["fetched_at"] if g else None,
                "checked_at": t.get("at"),
                "stale": g is not None and not t.get("ok", False),
                "error": None if t.get("ok") else t.get("error")}

    def _read_cache(self) -> Optional[Dict[str, Any]]:
        if not self.cache:
            return None
        try:
            d = json.loads(self.cache.read_text(encoding="utf-8"))
            return {"report": normalize(d["report"]), "fetched_at": float(d["fetched_at"])}
        except FileNotFoundError:
            return None
        except Exception as e:
            logger.warning("[mobile] momentum: ignoring an unreadable saved copy: %s", e)
            return None

    def _write_cache(self) -> None:
        if not self.cache or not self._good:
            return
        tmp = self.cache.with_name(self.cache.name + ".tmp")
        try:
            fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                json.dump(self._good, f)
            os.replace(tmp, self.cache)
        except OSError as e:
            logger.warning("[mobile] momentum: could not save the last good copy: %s", e)


def routes(r, j, data_dir: Path, write: bool = True) -> Reader:
    """``GET /v1/radar/momentum`` (``?refresh=1`` skips the reuse window). Under /v1/radar so the app's radar gate
    covers it."""
    reader = Reader(Path(data_dir) / CACHE_FILE, write=write)

    async def h_momentum(request):
        return j(await reader.get(refresh=request.query.get("refresh") == "1"))

    r.add_get("/v1/radar/momentum", h_momentum)
    return reader
