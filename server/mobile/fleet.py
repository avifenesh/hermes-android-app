"""Fleet: every coding agent on this machine in one list, steerable from the phone.

Source of truth is ``claude-ctrl`` (the owner's agent view): ``list --json`` for the sessions
(Claude Code background/interactive, Codex, in tmux or bare terminals), ``tail`` for a
screen/activity preview and ``send`` for replies. Its status agent keeps
``~/.cache/claude-ctrl/status.json`` current; we watch that file (cheap) and push the phone
when a session moves to *waiting on you*. Hermes' own runs (mobile chats, other platforms)
are merged in from the adapter and state.db so the phone sees one fleet.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import os
import re
import shutil
import time
from pathlib import Path
from typing import Any, Dict, List, Optional

from . import sig as SG

logger = logging.getLogger(__name__)

HOME = Path.home()
STATUS = Path(os.environ.get("HM_FLEET_STATUS") or HOME / ".cache/claude-ctrl/status.json")
ANSI = re.compile(r"\x1b\[[0-9;?]*[A-Za-z]|\x1b\][^\x07]*\x07")
WAIT = {"waiting", "needs_input", "awaiting", "permission", "input"}
WORK = {"working", "busy", "running", "thinking"}


def ctrl_bin() -> Optional[str]:
    p = os.environ.get("HM_FLEET_CTRL") or shutil.which("claude-ctrl") or str(HOME / ".local/bin/claude-ctrl")
    return p if p and Path(p).exists() else None


def _env() -> Dict[str, str]:
    e = dict(os.environ)
    e["PATH"] = f"{HOME}/.local/bin:{HOME}/.cargo/bin:/usr/local/bin:/usr/bin:/bin:" + e.get("PATH", "")
    e.setdefault("TMUX_TMPDIR", f"/tmp")
    return e


async def _run(*args: str, timeout: float = 30) -> tuple[int, str, str]:
    b = ctrl_bin()
    if not b:
        return 127, "", "claude-ctrl not installed"
    p = await asyncio.create_subprocess_exec(b, *args, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
                                             env=_env(), cwd=str(HOME))
    try:
        out, err = await asyncio.wait_for(p.communicate(), timeout)
    except asyncio.TimeoutError:
        p.kill()
        return 124, "", "timed out"
    return p.returncode or 0, out.decode(errors="replace"), err.decode(errors="replace")


def agent_id(a: Dict[str, Any]) -> str:
    return a.get("session") or a.get("thread") or (("job:" + a["job"]) if a.get("job") else "") or a.get("pane") or a.get("name") or "?"


def _environ(pid: int) -> Dict[str, str]:
    try:
        raw = Path(f"/proc/{pid}/environ").read_bytes()
    except OSError:
        return {}
    return dict(kv.split("=", 1) for kv in raw.decode(errors="replace").split("\0") if "=" in kv)


def kitty_target(a: Dict[str, Any]) -> Optional[tuple[str, str]]:
    """(socket, window id) when the session runs inside a kitty window with remote control on.

    The session process inherits KITTY_LISTEN_ON / KITTY_WINDOW_ID from its shell, so no tree walk is needed.
    Kitty's ``send-text`` writes through kitty itself, which sidesteps the kernel's TIOCSTI ban.
    """
    pid = int(a.get("pid") or 0)
    if not pid or a.get("pane"):
        return None
    env = _environ(pid)
    sock, win = env.get("KITTY_LISTEN_ON"), env.get("KITTY_WINDOW_ID")
    return (sock, win) if sock and win and shutil.which("kitty") else None


def desktop_session(a: Dict[str, Any]) -> bool:
    pid = int(a.get("pid") or 0)
    return bool(pid) and "CLAUDE_CODE_DESKTOP_APP_VERSION" in _environ(pid)


def _claude_settings_unguarded() -> bool:
    for f in (HOME / ".claude/settings.json", HOME / ".claude/settings.local.json"):
        try:
            d = json.loads(f.read_text())
        except Exception:
            continue
        perm = d.get("permissions") or {}
        if d.get("skipDangerousModePermissionPrompt") or perm.get("defaultMode") in ("bypassPermissions", "auto", "acceptEdits"):
            return True
    return False


def _codex_config_unguarded() -> bool:
    try:
        t = (HOME / ".codex/config.toml").read_text()
    except OSError:
        return False
    return bool(re.search(r'^\s*approval_policy\s*=\s*"never"', t, re.M) or re.search(r'danger-full-access', t))


def unguarded(a: Dict[str, Any]) -> bool:
    """A session that acts without asking: whatever we type, it executes. Flags on the command line win; otherwise
    the harness's own defaults decide (Claude settings defaultMode / skip prompt, Codex approval_policy=never)."""
    pid = int(a.get("pid") or 0)
    argv: List[str] = []
    if pid:
        try:
            argv = Path(f"/proc/{pid}/cmdline").read_bytes().decode(errors="replace").split("\0")
        except OSError:
            pass
    j = " ".join(argv)
    if any(x in j for x in ("bypassPermissions", "--dangerously-skip-permissions", "danger-full-access",
                            "--dangerously-bypass-approvals-and-sandbox", "--yolo", "approval_policy=never", "--full-auto")):
        return True
    if "--permission-mode" in argv and argv[argv.index("--permission-mode") + 1:][:1] in (["default"], ["plan"]):
        return False
    tool = a.get("tool") or a.get("kind")
    if tool == "claude":
        return _claude_settings_unguarded()
    if tool == "codex":
        return _codex_config_unguarded()
    return True


AGENT_BINS = ("claude", "codex", "node", "bun", "opencode", "hermes", "python3", "python")


def _pane_runs_agent(pane: str) -> bool:
    """True when the tmux pane's foreground process is still an agent, not a shell it exited to.

    The fleet list comes from a scan seconds old; typed text landing in a bare shell would run as a command,
    outside every approval gate. tmux reports the pane's foreground command directly."""
    try:
        import subprocess
        out = subprocess.run(["tmux", "display-message", "-p", "-t", pane, "#{pane_current_command}"],
                             capture_output=True, text=True, timeout=5).stdout.strip()
    except Exception:
        return False
    return os.path.basename(out) in AGENT_BINS or out.startswith(("claude", "codex"))


def _pid_runs_agent(pid: int) -> bool:
    try:
        argv = Path(f"/proc/{pid}/cmdline").read_bytes().split(b"\0")
    except OSError:
        return False
    return any(os.path.basename(x.decode(errors="replace")).startswith(("claude", "codex", "opencode", "hermes"))
               or b"claude" in x or b"codex" in x for x in argv[:4])


def can_reply(a: Dict[str, Any]) -> bool:
    if a.get("live") and kitty_target(a):
        return True
    if not a.get("live"):
        return bool(a.get("session") or a.get("thread"))
    if a.get("pane") or a.get("job"):
        return True
    return a.get("tool") == "codex" and bool(a.get("thread"))


def view(a: Dict[str, Any]) -> Dict[str, Any]:
    state = a.get("shown") or a.get("label") or a.get("state") or ""
    group = a.get("group") or ""
    need = "waiting" in group or state in WAIT
    return {
        "id": agent_id(a), "kind": a.get("tool") or "agent", "name": a.get("name") or "",
        "state": "waiting" if need else state, "group": "waiting" if need else ("working" if group == "working" else
                                                                                  ("done" if group == "done" else "recent")),
        "detail": a.get("detail") or "", "where": a.get("where") or "", "cwd": a.get("cwd") or "",
        "project": Path(a.get("cwd") or "").name, "model": a.get("model") or a.get("provider") or "",
        "started": a.get("t") or 0, "last": a.get("last") or 0, "live": bool(a.get("live")),
        "prs": a.get("prs") or [], "hold": a.get("hold") or "", "can_reply": can_reply(a),
        "can_stop": bool(a.get("live") and (a.get("pane") or kitty_target(a))),
        "unguarded": bool(a.get("live") and unguarded(a)),
        "via": "tmux" if a.get("pane") else ("kitty" if a.get("live") and kitty_target(a) else
                                             ("desktop" if a.get("live") and desktop_session(a) else "")),
    }


async def kitty(sock: str, *args: str, timeout: float = 10) -> tuple[int, str]:
    p = await asyncio.create_subprocess_exec("kitty", "@", "--to", sock, *args, stdout=asyncio.subprocess.PIPE,
                                             stderr=asyncio.subprocess.STDOUT, env=_env())
    try:
        out, _ = await asyncio.wait_for(p.communicate(), timeout)
    except asyncio.TimeoutError:
        p.kill()
        return 124, "kitty timed out"
    return p.returncode or 0, out.decode(errors="replace")


class Fleet:
    def __init__(self, ad):
        self.ad = ad
        self._cache: tuple[float, List[Dict[str, Any]], List[Dict[str, Any]]] = (0.0, [], [])
        self._task: Optional[asyncio.Task] = None
        self._states: Dict[str, str] = {}
        self._mtime = 0.0
        self.events: List[Dict[str, Any]] = []   # recent transitions to waiting (for background sync)

    # ---------------------------------------------------------------- collection
    async def raw(self, recent: bool = False, max_age: float = 4.0) -> List[Dict[str, Any]]:
        now = time.time()
        t, cur, rec = self._cache
        if now - t < max_age and (rec or not recent):
            return rec if recent else cur
        rc, out, err = await _run("list", *(["-a"] if recent else []), "--json")
        if rc != 0:
            raise RuntimeError((err or out).strip()[:300] or f"claude-ctrl exited {rc}")
        data = json.loads(out or "[]")
        live = [a for a in data if a.get("live")]
        self._cache = (now, live, data if recent else [])
        return data if recent else live

    async def find(self, aid: str) -> Optional[Dict[str, Any]]:
        for a in await self.raw(recent=True, max_age=0):
            if agent_id(a) == aid:
                return a
        return None

    def hermes_runs(self) -> List[Dict[str, Any]]:
        ad = self.ad
        out = []
        for cid, w in list(ad._working.items()):
            ch = ad.store.get_chat(cid) or {}
            u = ad.store.get_turn(cid) or {}
            out.append({"id": "hm:" + cid, "kind": "hermes", "name": ch.get("title") or "Hermes run", "state": "working",
                        "group": "working", "detail": "", "where": "mobile", "cwd": "", "project": "", "model": "",
                        "started": w.get("since", 0), "last": w.get("last", 0), "live": True, "prs": [], "hold": "",
                        "can_reply": True, "can_stop": True, "chat_id": cid,
                        "usage": u if isinstance(u, dict) else {}})
        try:
            now = time.time()
            with ad._state_db() as c:
                for r in c.execute("SELECT s.id, s.source, s.title, s.model, s.started_at, MAX(m.timestamp) AS last"
                                   " FROM sessions s JOIN messages m ON m.session_id=s.id WHERE s.ended_at IS NULL"
                                   " AND s.source NOT IN ('mobile','subagent') AND s.started_at>? GROUP BY s.id HAVING last>?"
                                   " ORDER BY last DESC LIMIT 8", (now - 86400, now - 300)):
                    out.append({"id": "hs:" + r["id"], "kind": "hermes", "name": r["title"] or f"{r['source']} session",
                                "state": "working", "group": "working", "detail": r["source"], "where": r["source"],
                                "cwd": "", "project": "", "model": (r["model"] or "").split("/")[-1],
                                "started": r["started_at"] or 0, "last": r["last"] or 0, "live": True, "prs": [],
                                "hold": "", "can_reply": False, "can_stop": False, "session_id": r["id"],
                                "source": r["source"]})
        except Exception as e:
            logger.debug("[mobile] fleet state.db read failed: %s", e)
        return out

    async def snapshot(self, recent: bool = False) -> Dict[str, Any]:
        err = None
        agents: List[Dict[str, Any]] = []
        try:
            agents = [view(a) for a in await self.raw(recent=recent)]
        except Exception as e:
            err = str(e)
        agents = self.hermes_runs() + agents
        order = {"waiting": 0, "working": 1, "done": 2, "recent": 3}
        agents.sort(key=lambda a: (order.get(a["group"], 4), -(a["last"] or 0)))
        counts = {g: sum(1 for a in agents if a["group"] == g) for g in order}
        return {"agents": agents, "counts": counts, "error": err, "events": self.events[-20:],
                "status_beat": self._beat(), "at": time.time()}

    def _beat(self) -> float:
        try:
            return float(json.loads(STATUS.read_text()).get("beat") or 0)
        except Exception:
            return 0.0

    # ---------------------------------------------------------------- actions
    async def tail(self, aid: str, n: int = 60) -> Dict[str, Any]:
        if aid.startswith("hm:"):
            return {"lines": [], "chat_id": aid[3:]}
        if aid.startswith("hs:"):
            sid = aid[3:]
            with self.ad._state_db() as c:
                rows = c.execute("SELECT role, content FROM messages WHERE session_id=? AND role IN ('user','assistant')"
                                 " AND COALESCE(content,'')!='' ORDER BY id DESC LIMIT ?", (sid, min(n, 40))).fetchall()
            return {"lines": [f"{r['role'].upper()}: {r['content'][:600]}" for r in rows][::-1]}
        a = await self.find(aid)
        if not a:
            return {"error": "no such session"}
        rc, out, err = await _run("tail", self._q(a), str(max(5, min(n, 200))), timeout=20)
        if rc != 0:
            return {"error": (err or out).strip()[:300]}
        lines = [ANSI.sub("", ln).rstrip() for ln in out.splitlines()]
        return {"lines": lines[-n:], "agent": view(a)}

    @staticmethod
    def _q(a: Dict[str, Any]) -> str:
        # claude-ctrl resolves by id prefix (job/thread/session) or pane; fall back to the name
        return a.get("job") or a.get("thread") or a.get("session") or a.get("pane") or a.get("name")

    async def send(self, aid: str, text: str) -> Dict[str, Any]:
        text = (text or "").strip()
        if not text:
            return {"ok": False, "error": "empty"}
        if aid.startswith("hm:"):
            cid = aid[3:]
            msg = await self.ad.post_from_app(cid, text, "steer")
            return {"ok": msg is not None, "line": "steered"}
        a = await self.find(aid)
        if not a:
            return {"ok": False, "error": "no such session"}
        if not can_reply(a):
            why = ("lives in the Claude desktop app; reply there (or enable /remote-control in it)" if desktop_session(a)
                   else "runs in a terminal without remote control; reply there")
            return {"ok": False, "error": f"{a.get('name') or 'it'} {why}"}
        # Re-check right before typing: a live session's agent may have exited back to its shell since the scan.
        if a.get("live"):
            pane, pid = a.get("pane") or "", int(a.get("pid") or 0)
            alive = await asyncio.to_thread(_pane_runs_agent, pane) if pane else (bool(pid) and _pid_runs_agent(pid))
            if not alive:
                self._cache = (0.0, [], [])
                return {"ok": False, "error": f"{a.get('name') or 'it'} isn't running an agent any more; not typing into a shell"}
        rc, out, err = await _run("send", self._q(a), text, timeout=120)
        line = (out or err).strip().splitlines()[-1:] or [""]
        ok = rc == 0 and not line[0].startswith(("claude-ctrl:", "could not")) and " lives in the " not in line[0] \
            and "reply in its terminal" not in line[0]
        self._cache = (0.0, [], [])
        return {"ok": ok, "line": line[0][:200], **({} if ok else {"error": line[0][:200] or f"exit {rc}"})}

    async def interrupt(self, aid: str) -> Dict[str, Any]:
        if aid.startswith("hm:"):
            await self.ad.stop_chat(aid[3:])
            return {"ok": True}
        a = await self.find(aid)
        kt = kitty_target(a) if a and a.get("live") else None
        if kt:
            rc, out = await kitty(kt[0], "send-key", "--match", f"id:{kt[1]}", "escape")
            return {"ok": rc == 0, **({} if rc == 0 else {"error": out.strip()[:200]})}
        if not a or not a.get("pane"):
            return {"ok": False, "error": "this session can't be interrupted from here"}
        p = await asyncio.create_subprocess_exec("tmux", "send-keys", "-t", a["pane"], "Escape", env=_env())
        await p.wait()
        return {"ok": p.returncode == 0}

    # ---------------------------------------------------------------- watcher
    def start(self) -> None:
        if self._task is None and ctrl_bin():
            self._task = asyncio.create_task(self._watch())

    def stop(self) -> None:
        if self._task:
            self._task.cancel()
            self._task = None

    async def _watch(self) -> None:
        first = True
        while True:
            try:
                await asyncio.sleep(3)
                m = STATUS.stat().st_mtime if STATUS.exists() else 0
                if m == self._mtime:
                    continue
                self._mtime = m
                sessions = (json.loads(STATUS.read_text()) or {}).get("sessions") or {}
                now = time.time()
                changed = False
                for k, s in sessions.items():
                    st = s.get("state") or ""
                    prev = self._states.get(k)
                    self._states[k] = st
                    if first or prev == st:
                        continue
                    changed = True
                    if st in WAIT and (now - float(s.get("since") or s.get("at") or 0)) < 600:
                        ev = {"id": k, "name": s.get("name") or "", "status": s.get("status") or "", "tool": s.get("tool") or "",
                              "state": st, "at": now}
                        # Owner's choice 2026-10-06: no phone notifications for coding sessions; list still refreshes.
                        _ = ev
                if changed:
                    self._cache = (0.0, [], [])
                    self.ad._emit({"type": "fleet", "changed": True})
                first = False
            except asyncio.CancelledError:
                raise
            except Exception as e:
                logger.debug("[mobile] fleet watch: %s", e)
                await asyncio.sleep(10)


def routes(r, fleet: Fleet, j) -> None:
    async def h_fleet(request):
        return j(await fleet.snapshot(recent=request.query.get("recent") == "1"))

    async def h_tail(request):
        return j(await fleet.tail(request.match_info["aid"], int(request.query.get("n") or 60)))

    async def h_send(request):
        aid = request.match_info["aid"]
        body = await request.json()
        text = str(body.get("text") or "")
        if not aid.startswith(("hm:", "hs:")):
            a = await fleet.find(aid)
            # A session with its permission prompts off runs whatever it's told: typing into it is the same power as
            # approving a command, so it needs the same biometric signature, bound to the exact text.
            if a and unguarded(a):
                ts = request.headers.get("X-HM-Ts", "")
                msg = f"fleet\n{aid}\n{hashlib.sha256(text.encode()).hexdigest()}\n{ts}".encode()
                if not SG.approval_ok(request, msg):
                    return j({"ok": False, "error": "biometric confirmation required", "need_sig": True}, 403)
        res = await fleet.send(aid, text)
        return j(res, 200 if res.get("ok") else 409)

    async def h_stop(request):
        res = await fleet.interrupt(request.match_info["aid"])
        return j(res, 200 if res.get("ok") else 409)

    r.add_get("/v1/fleet", h_fleet)
    r.add_get("/v1/fleet/{aid}/tail", h_tail)
    r.add_post("/v1/fleet/{aid}/send", h_send)
    r.add_post("/v1/fleet/{aid}/stop", h_stop)
