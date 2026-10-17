"""Self-check: catch a Hermes update that broke the plugin before the phone hits it.

Two passes. (1) Every read-only GET endpoint runs in-process through the real aiohttp router with a fake authed
device; a non-2xx status, a raised exception or an ``error`` key in the JSON counts as a failure. (2) Every
absolute ``from X import Y`` / ``import X`` in the plugin's own source is resolved against the running Hermes,
plus attribute uses on imported modules (``J.pause_job``), so a renamed private helper shows up even when the
handler that calls it swallows the error.

The gateway runs it ~20 s after connect and stores the result in kv ``selfcheck``; ``hermes mobile check`` runs
the same thing from the CLI without the gateway.
"""
from __future__ import annotations

import ast
import asyncio
import importlib
import json
import logging
import time
import traceback
from pathlib import Path
from typing import Any, Dict, List, Optional

from . import ident as ID

logger = logging.getLogger(__name__)

ENDPOINTS = ["/v1/me", "/v1/sync?since=0", "/v1/chats", "/v1/commands", "/v1/cron", "/v1/pulse", "/v1/sessions",
             "/v1/models", "/v1/settings", "/v1/settings/all", "/v1/skills", "/v1/plugins", "/v1/mcp", "/v1/memory",
             "/v1/usage", "/v1/fleet", "/v1/profiles", "/v1/projects", "/v1/cron-delivery", "/v1/wake",
             "/v1/hub/skills/installed", "/v1/hub/mcp", "/v1/hub/jobs", "/v1/radar"]
KV = "selfcheck"
DEVICE = {"id": "selfcheck", "name": "selfcheck", "created": 0, "pub": "", "apub": "", "revoked": 0}
PKG_DIR = Path(__file__).resolve().parent

# This fixed helper executes inside Kitty, not in the gateway interpreter.
# Validate these four SDK symbols through the real Kitty integration harness.
# Keep auditing its syntax, stdlib imports and any unknown SDK symbols here.
KITTY_RUNTIME_IMPORTS = frozenset({
    ("kitty_terminal.py", "kittens.tui.handler", "result_handler"),
    ("kitty_terminal.py", "kitty.fast_data_types", "PRESS"),
    ("kitty_terminal.py", "kitty.fast_data_types", "RELEASE"),
    ("kitty_terminal.py", "kitty.fast_data_types", "send_mouse_event"),
})


def _short(e: BaseException) -> str:
    return f"{type(e).__name__}: {e}"[:300]


# ---------------------------------------------------------------- imports
def _resolve(mod: str, name: str):
    m = importlib.import_module(mod)
    if hasattr(m, name):
        return getattr(m, name)
    try:
        return importlib.import_module(f"{mod}.{name}")
    except ModuleNotFoundError as e:
        if e.name in (f"{mod}.{name}", mod):
            raise ImportError(f"cannot import name '{name}' from '{mod}'") from None
        raise


def import_problems(pkg_dir: Path = PKG_DIR) -> List[Dict[str, str]]:
    """Absolute imports and module-attribute uses in the plugin source that don't resolve in this Python."""
    out: List[Dict[str, str]] = []
    seen: set = set()

    def bad(where: str, what: str, err: str) -> None:
        if what not in seen:
            seen.add(what)
            out.append({"name": what, "where": where, "error": err[:300]})

    for f in sorted(pkg_dir.glob("*.py")):
        try:
            tree = ast.parse(f.read_text(), str(f))
        except SyntaxError as e:
            bad(f.name, f.name, _short(e))
            continue
        mods: Dict[str, Any] = {}  # local alias -> module object, for attribute checks
        for node in ast.walk(tree):
            if isinstance(node, ast.ImportFrom) and node.level == 0 and node.module and node.module != "__future__":
                for a in node.names:
                    if (f.name, node.module, a.name) in KITTY_RUNTIME_IMPORTS:
                        continue
                    try:
                        obj = _resolve(node.module, a.name)
                    except Exception as e:
                        bad(f.name, f"{node.module}.{a.name}", _short(e))
                        continue
                    if type(obj).__name__ == "module":
                        mods[a.asname or a.name] = obj
            elif isinstance(node, ast.Import):
                for a in node.names:
                    try:
                        obj = importlib.import_module(a.name)
                    except Exception as e:
                        bad(f.name, a.name, _short(e))
                        continue
                    if a.asname:
                        mods[a.asname] = obj
        for node in ast.walk(tree):
            if isinstance(node, ast.Attribute) and isinstance(node.value, ast.Name) and node.value.id in mods:
                m = mods[node.value.id]
                if not m.__name__.split(".")[0] in ("os", "sys", "json", "re", "time", "asyncio") and not hasattr(m, node.attr):
                    try:
                        importlib.import_module(f"{m.__name__}.{node.attr}")
                    except Exception:
                        bad(f.name, f"{m.__name__}.{node.attr}", f"AttributeError: module '{m.__name__}' has no attribute '{node.attr}'")
    return out


# ---------------------------------------------------------------- endpoints
async def _call(app, path: str, timeout: float) -> Dict[str, Any]:
    from aiohttp.test_utils import make_mocked_request
    probe = make_mocked_request("GET", path, app=app)
    mi = await app.router.resolve(probe)
    if getattr(mi, "http_exception", None) is not None:
        return {"path": path, "status": mi.http_exception.status, "error": "route not mounted"}
    req = make_mocked_request("GET", path, app=app, match_info=dict(mi))
    req["device"] = dict(DEVICE)
    t0 = time.time()
    try:
        with ID.launch_scope():  # what the auth middleware binds for real requests
            resp = await asyncio.wait_for(mi.handler(req), timeout)
    except asyncio.TimeoutError:
        return {"path": path, "status": 504, "error": f"timed out after {timeout:.0f}s"}
    except Exception as e:
        logger.warning("[mobile] selfcheck %s raised", path, exc_info=True)
        return {"path": path, "status": 500, "error": _short(e), "trace": traceback.format_exc()[-1500:]}
    status, err = resp.status, ""
    try:
        data = json.loads(resp.body) if getattr(resp, "body", None) else None
        if isinstance(data, dict) and data.get("error"):
            err = str(data["error"])[:300]
    except Exception:
        pass
    r = {"path": path, "status": status, "ms": int((time.time() - t0) * 1000)}
    if status >= 400 or err:
        r["error"] = err or f"HTTP {status}"
    return r


async def run(app, *, timeout: float = 60) -> Dict[str, Any]:
    ok: List[str] = []
    failures: List[Dict[str, Any]] = []
    ms: Dict[str, int] = {}
    for p in ENDPOINTS:
        r = await _call(app, p, timeout)
        ms[p] = r.get("ms", 0)
        (failures.append(r) if r.get("error") else ok.append(p))
    imports = await asyncio.to_thread(import_problems)
    return {"ts": time.time(), "ok": ok, "failures": failures, "imports": imports, "ms": ms}


def keys(res: Optional[Dict[str, Any]]) -> set:
    if not res:
        return set()
    return {f"{f['path']}|{f.get('error', '')[:80]}" for f in res.get("failures", [])} | \
           {f"import|{i['name']}" for i in res.get("imports", [])}


def degraded(res: Optional[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Flat list for /v1/health: endpoint failures, then unresolved imports."""
    if not res:
        return []
    out = [{"path": f["path"], "status": f.get("status", 0), "error": f.get("error", "")} for f in res.get("failures", [])]
    out += [{"path": f"import {i['name']}", "status": 0, "error": i["error"]} for i in res.get("imports", [])]
    return out


def table(res: Dict[str, Any]) -> str:
    lines = [f"{'endpoint':<22} {'status':>6}  {'ms':>6}  error"]
    fails = {f["path"]: f for f in res["failures"]}
    for p in ENDPOINTS:
        f = fails.get(p)
        lines.append(f"{p:<22} {(f or {}).get('status', 200):>6}  {res.get('ms', {}).get(p, ''):>6}  {(f or {}).get('error', 'ok')}")
    for i in res["imports"]:
        lines.append(f"import {i['name']} ({i['where']}): {i['error']}")
    n = len(res["failures"]) + len(res["imports"])
    lines.append(f"\n{len(res['ok'])}/{len(ENDPOINTS)} endpoints ok, {len(res['imports'])} import problems"
                 + ("" if n else ". All good."))
    return "\n".join(lines)


# ---------------------------------------------------------------- gateway side
class SelfCheck:
    def __init__(self, ad, delay: float = 20):
        self.ad = ad
        self.delay = delay
        self.last: Optional[Dict[str, Any]] = None
        self._task: Optional[asyncio.Task] = None

    def start(self, app) -> None:
        self.last = self.ad.store.kv_get(KV)
        self._task = asyncio.create_task(self._later(app))

    def stop(self) -> None:
        if self._task:
            self._task.cancel()

    async def _later(self, app) -> None:
        try:
            await asyncio.sleep(self.delay)
            await self.check(app)
        except asyncio.CancelledError:
            pass
        except Exception:
            logger.warning("[mobile] selfcheck crashed", exc_info=True)

    async def check(self, app) -> Dict[str, Any]:
        prev = self.ad.store.kv_get(KV)
        res = await run(app)
        self.last = res
        self.ad.store.kv_put(KV, res)
        new = keys(res) - keys(prev)
        for f in res["failures"]:
            logger.warning("[mobile] selfcheck: %s -> %s %s\n%s", f["path"], f.get("status"), f.get("error"), f.get("trace", ""))
        for i in res["imports"]:
            logger.warning("[mobile] selfcheck: %s (%s) does not resolve: %s", i["name"], i["where"], i["error"])
        if not res["failures"] and not res["imports"]:
            logger.info("[mobile] selfcheck: %d endpoints ok", len(res["ok"]))
        if new:
            self._report(res, new)
        return res

    def _report(self, res: Dict[str, Any], new: set) -> None:
        ad = self.ad
        lines = [f"Mobile plugin self-check found {len(new)} new problem{'s' if len(new) != 1 else ''}:"]
        for d in degraded(res):
            key = f"import|{d['path'][7:]}" if d["path"].startswith("import ") else None
            if key in new or any(k.startswith(d["path"] + "|") for k in new):
                lines.append(f"- {d['path']}" + (f" ({d['status']})" if d["status"] else "") + f": {d['error']}")
        lines.append("Details: GET /v1/selfcheck or `hermes mobile check`.")
        title = None if ad.store.get_chat(ad._home) else "Hermes"
        msg = ad.store.add_message(ad._home, "agent", "\n".join(lines), kind="status",
                                   meta={"quiet": True, "selfcheck": True}, title=title)
        ad._emit_msg(msg, push="message")
