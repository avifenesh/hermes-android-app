"""Hermes Mobile platform adapter: HTTP + SSE API for the Hermes Android app."""
from __future__ import annotations

import asyncio
import json
import logging
import mimetypes
import os
import shutil
import socket
import sqlite3
import time
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

from gateway.config import Platform
from gateway.platforms.base import BasePlatformAdapter, SendResult
from gateway.platforms.event import MessageEvent, MessageType

from . import activity as A
from . import control as C
from . import control2 as C2
from . import fleet as FL
from . import market as MK
from . import momentum as MO
from . import ident as ID
from . import profiles as PR
from . import phone as PH
from . import projects as PJ
from . import radar as RD
from . import recall as RC
from . import selfcheck as SC
from . import usage as U
from . import sig as SG
from . import store as S
from . import wake as W

logger = logging.getLogger(__name__)

DEFAULT_BIND = "127.0.0.1:47930"
HOME_DEFAULT = "digests"
OWNER = "owner"
_TRUTHY = {"1", "true", "yes", "on"}


def _env(name: str, default: str = "") -> str:
    try:
        from gateway.platforms._shared import get_scoped_secret
        v = get_scoped_secret(name, "")
        if v:
            return str(v)
    except Exception:
        pass
    return os.environ.get(name, default)


def _public_url_default() -> str:
    try:
        host = (S.data_dir() / "public_host").read_text().strip()
        return f"https://{host}" if host else ""
    except Exception:
        return ""


def _cf_access() -> Dict[str, str]:
    try:
        d = json.loads((S.data_dir() / "cf-access.json").read_text())
        return {"i": d.get("client_id", ""), "s": d.get("client_secret", "")}
    except Exception:
        return {}


def _profile_name() -> str:
    home = S.hermes_home()
    return home.name if home.parent.name == "profiles" else "default"


def _job_name(job_id: str) -> str:
    try:
        d = json.loads((S.hermes_home() / "cron" / "jobs.json").read_text())
        for j in (d["jobs"] if isinstance(d, dict) and "jobs" in d else d):
            if str(j.get("id")) == job_id:
                return str(j.get("name") or job_id)
    except Exception:
        pass
    return ""


async def _body(request) -> Dict[str, Any]:
    """JSON body or {}. Not ``can_read_body``: the auth middleware already read the body to verify its signature,
    so the stream is at EOF and can_read_body is False even though request.read()/json() return the cached bytes."""
    if not request.body_exists:
        return {}
    try:
        b = await request.json()
        return b if isinstance(b, dict) else {}
    except Exception:
        return {}


def route_delivery(store: S.Store, chat_id: str, content: str, home: str, job_id: str = "") -> Dict[str, str]:
    """Cron deliveries: one Inbox thread per job (named after the job). Other home-channel sends: per title."""
    if chat_id and chat_id != home:
        return {"chat_id": chat_id, "title": None, "kind": "chat"}
    name = _job_name(job_id) if job_id else ""
    if name:
        return {"chat_id": f"cron-job-{job_id}", "title": name, "kind": "digest"}
    title = S.digest_title(content)
    return {"chat_id": f"cron-{S._digest_slug(title)}", "title": title, "kind": "digest"}


def _copy_media(src: str) -> Dict[str, str]:
    p = Path(src)
    name = S.new_id("f") + (p.suffix.lower() or "")
    dst = S.data_dir() / "media" / name
    shutil.copyfile(p, dst)
    mime = mimetypes.guess_type(p.name)[0] or "application/octet-stream"
    return {"media": name, "mime": mime, "file_name": p.name, "size": str(dst.stat().st_size)}


def _slash_catalog() -> List[Dict[str, Any]]:
    """Gateway-dispatchable slash commands for the composer popover: core, then plugin, then skill."""
    out: List[Dict[str, Any]] = []
    seen: set = set()

    def add(name: str, desc: str, args: str = "", cat: str = "", aliases=(), subs=()) -> None:
        name = (name or "").lstrip("/")
        if name and name not in seen:
            seen.add(name)
            out.append({"name": name, "description": (desc or "").strip(), "args": args or "", "category": cat,
                        "aliases": [a for a in aliases if a], "subcommands": list(subs)})

    try:
        from hermes_cli.commands_platforms import _gateway_available_commands
        for c in _gateway_available_commands():
            add(c.name, c.description, c.args_hint, c.category, c.aliases, c.subcommands)
    except Exception as e:
        logger.warning("[mobile] core command catalog failed: %s", e)
    try:
        from hermes_cli.plugins import get_plugin_commands
        for n, m in sorted((get_plugin_commands() or {}).items()):
            add(n, str(m.get("description") or ""), str(m.get("args_hint") or ""), "Plugin")
    except Exception as e:
        logger.debug("[mobile] plugin commands failed: %s", e)
    try:
        from agent.skill_commands import get_skill_commands
        for k, info in sorted((get_skill_commands() or {}).items()):
            add(k, str(info.get("description") or ""), "", "Skill")
    except Exception as e:
        logger.debug("[mobile] skill commands failed: %s", e)
    return out


class MobileAdapter(BasePlatformAdapter):
    MAX_MESSAGE_LENGTH = 60000
    # No external message API to obey (local store + SSE to our own app), so the
    # gateway-level 4000-char cron truncation must not apply — full output flows through.
    splits_long_messages = True

    def __init__(self, config):
        super().__init__(config=config, platform=Platform("mobile"))
        extra = dict(getattr(config, "extra", None) or {})
        self._bind = extra.get("bind") or _env("MOBILE_BIND") or DEFAULT_BIND
        self._public_url = (extra.get("public_url") or _env("MOBILE_PUBLIC_URL") or _public_url_default()).rstrip("/")
        self._home = extra.get("home_channel") or _env("MOBILE_HOME_CHANNEL") or HOME_DEFAULT
        self.store: Optional[S.Store] = None
        self._runner = None
        self._subs: set = set()
        self._working: Dict[str, Dict[str, float]] = {}
        self._fcm_creds = None
        self._pair_hits: Dict[str, List[float]] = {}
        self._replay = SG.Replay()
        self._reaper: Optional[asyncio.Task] = None
        self._mobile_loop: Optional[asyncio.AbstractEventLoop] = None
        self._activity = A.Tracker(self)
        self._meter = U.Meter(self)
        self._recall_pending: Dict[str, Any] = {}  # chat_id -> (notice msg_id, ts) awaiting its injected text
        self._recall_text: Dict[str, Any] = {}     # chat_id -> (text, ts) captured before its notice landed
        self._control = C.Control(self)
        self._selfcheck = SC.SelfCheck(self)

    # ------------------------------------------------------------ lifecycle
    def _build_app(self, cli: bool = False):
        """The aiohttp app with every route mounted. ``cli`` builds it for `hermes mobile check` beside a running
        gateway: no mirror pass and no wake-state rewrite, so the check only reads live data."""
        from aiohttp import web
        self.store = S.Store()
        if cli:
            self._mirror = False

        @web.middleware
        async def auth_mw(request, handler):
            return await self._auth_mw(request, handler)

        app = web.Application(client_max_size=64 * 1024 * 1024, middlewares=[auth_mw])
        r = app.router
        r.add_get("/v1/health", self.h_health)
        r.add_post("/v1/pair", self.h_pair)
        r.add_get("/pair", self.h_pair_page)
        r.add_get("/v1/me", self.h_me)
        r.add_get("/v1/sync", self.h_sync)
        r.add_get("/v1/events", self.h_events)
        r.add_get("/v1/chats", self.h_chats)
        r.add_post("/v1/chats", self.h_new_chat)
        r.add_patch("/v1/chats/{cid}", self.h_patch_chat)
        r.add_get("/v1/chats/{cid}/messages", self.h_messages)
        r.add_post("/v1/chats/{cid}/messages", self.h_post_message)
        r.add_post("/v1/chats/{cid}/read", self.h_read)
        r.add_post("/v1/chats/{cid}/stop", self.h_stop)
        r.add_get("/v1/chats/{cid}/run", self.h_run)
        r.add_get("/v1/activity/{mid}", self.h_activity)
        r.add_get("/v1/chats/{cid}/usage", self.h_usage)
        r.add_post("/v1/approvals/{aid}", self.h_approve)
        r.add_post("/v1/upload", self.h_upload)
        r.add_get("/v1/media/{name}", self.h_media)
        r.add_post("/v1/tts", self.h_tts)
        r.add_get("/v1/cron", self.h_cron)
        r.add_post("/v1/cron/{jid}/{action}", self.h_cron_action)
        r.add_get("/v1/cron/{jid}/output", self.h_cron_output)
        r.add_get("/v1/pulse", self.h_pulse)
        r.add_get("/v1/sessions", self.h_sessions)
        r.add_get("/v1/sessions/{sid}/messages", self.h_session_messages)
        r.add_get("/v1/commands", self.h_commands)
        r.add_post("/v1/fcm", self.h_fcm)
        r.add_get("/v1/push-config", self.h_push_config)
        r.add_get("/v1/devices", self.h_devices)
        r.add_delete("/v1/devices/{did}", self.h_revoke)
        r.add_get("/v1/selfcheck", self.h_selfcheck)
        self._wake = W.Wake(self, readonly=cli)
        W.routes(r, self._wake, self._json)
        C.routes(r, self._control, self._json)
        C2.routes(r, self._control, self._json)
        self._fleet = FL.Fleet(self)
        FL.routes(r, self._fleet, self._json)
        self._profiles = PR.ProfileChats(self)
        PR.routes(r, self._profiles, self._json)
        PJ.routes(r, self._json)
        self._phone = PH.Phone(self)
        PH.routes(r, self, self._json)
        self._hub = MK.routes(r, self._json)
        RD.routes(r, self, self._json)
        MO.routes(r, self._json, S.data_dir(), write=not cli)
        return app

    async def connect(self, *, is_reconnect: bool = False) -> bool:
        from aiohttp import web
        app = self._build_app()
        host, _, port = self._bind.rpartition(":")
        self._runner = web.AppRunner(app, access_log=None)
        await self._runner.setup()
        site = web.TCPSite(self._runner, host or "127.0.0.1", int(port), reuse_address=True)
        await site.start()
        with ID.launch_scope():  # tasks copy the context: background loops keep the launch profile's scope
            self._reaper = asyncio.create_task(self._reap_loop())
            self._wake.start()
            self._fleet.start()
            self._selfcheck.start(app)
        self._mobile_loop = asyncio.get_running_loop()
        A.bind(self)
        U.bind(self)
        RC.bind(self)
        logger.info("[mobile] API listening on %s (public %s)", self._bind, self._public_url or "-")
        if hasattr(self, "_mark_connected"):
            self._mark_connected()
        return True

    async def disconnect(self) -> None:
        if getattr(self, "_phone", None):
            st = self._phone.armed()
            if st:
                self._phone.disarm(st["device"])
            self._phone.cancel("mobile adapter disconnected")
        A.unbind(self)
        U.unbind(self)
        RC.unbind(self)
        self._selfcheck.stop()
        if self._reaper:
            self._reaper.cancel()
        if getattr(self, "_wake", None):
            self._wake.stop()
        if getattr(self, "_fleet", None):
            self._fleet.stop()
        if self._runner:
            await self._runner.cleanup()
            self._runner = None

    # ------------------------------------------------------------ events
    def _emit(self, ev: Dict[str, Any], device: Optional[str] = None) -> None:
        for q in list(self._subs):
            if device is not None and getattr(q, "_phone_device", None) != device:
                continue
            try:
                q.put_nowait(ev)
            except asyncio.QueueFull:
                pass

    def _emit_msg(self, msg: Optional[Dict[str, Any]], push: Optional[str] = "message") -> None:
        if not msg:
            return
        chat = self.store.get_chat(msg["chat_id"])
        self._emit({"type": "message", "seq": msg["seq"], "message": msg, "chat": chat})
        if push and msg["role"] != "user" and msg["kind"] not in ("trace", "activity"):
            self._push(push, msg["chat_id"])

    def _working_list(self) -> List[Dict[str, Any]]:
        return [{"chat_id": c, "since": w["since"]} for c, w in self._working.items()]

    async def _reap_loop(self) -> None:
        while True:
            await asyncio.sleep(4)
            now = time.time()
            for cid, w in list(self._working.items()):
                if now - w["last"] > 14:
                    self._working.pop(cid, None)
                    self._emit({"type": "working", "chat_id": cid, "working": False})

    # ------------------------------------------------------------ outbound (gateway -> phone)
    def _classify(self, chat_id: str, content: str, metadata: Optional[Dict[str, Any]]):
        steps = S.parse_trace(content) if chat_id in self._working or (metadata or {}).get("_interim_send") else None
        if steps and not any(s["tool"][:1].isupper() for s in steps):
            return "trace", {"steps": steps}
        if (metadata or {}).get("_interim_send") or S.is_notice(content):
            return "status", {}
        return "text", {}

    async def send(self, chat_id: str, content: str, reply_to: Optional[str] = None,
                   metadata: Optional[Dict[str, Any]] = None) -> SendResult:
        try:
            route = route_delivery(self.store, chat_id, content, self._home, str((metadata or {}).get("job_id") or ""))
            kind, meta = self._classify(route["chat_id"], content, metadata)
            if kind == "text" and (route["kind"] == "digest" or (metadata or {}).get("job_id")):
                meta["delivery"] = True
            if reply_to:
                meta["reply_to"] = reply_to
            if kind == "text" and route["kind"] != "digest" and reply_to:
                asked = self.store.get_message(reply_to)
                if asked and asked["role"] == "user" and asked["kind"] == "text" and not asked["content"].startswith("/"):
                    usage = await self._meter.for_reply(route["chat_id"], asked["ts"])
                    if usage:
                        meta["usage"] = usage
            msg = self.store.add_message(route["chat_id"], "agent", content, kind=kind, meta=meta,
                                         title=route["title"], chat_kind=route["kind"])
            if kind == "status" and RC.is_recall_notice(content):
                self._note_recall_notice(route["chat_id"], msg["id"])
            self._emit_msg(msg, push="digest" if route["kind"] == "digest" else (None if kind == "trace" else "message"))
            self._activity.on_message(msg)
            return SendResult(success=True, message_id=msg["id"])
        except Exception as e:
            logger.exception("[mobile] send failed")
            return SendResult(success=False, error=str(e))

    # ------------------------------------------------------------ recall pairing
    _RECALL_TTL = 300.0

    def _attach_recall(self, msg_id: str, text: str) -> None:
        msg = self.store.edit_message(msg_id, meta={"recall": text})
        if msg:
            self._emit({"type": "message", "seq": msg["seq"], "message": msg,
                        "chat": self.store.get_chat(msg["chat_id"])})

    def _note_recall_notice(self, chat_id: str, msg_id: str) -> None:
        """A '🧠 … recalled N memories' notice was stored; pair it with its captured text."""
        stash = self._recall_text.pop(chat_id, None)
        if stash and time.time() - stash[1] < self._RECALL_TTL:
            self._attach_recall(msg_id, stash[0])
        else:
            self._recall_pending[chat_id] = (msg_id, time.time())

    def _on_recall(self, chat_id: str, text: str) -> None:
        """Loop-side: the pre_api_request hook captured this turn's injected memory block."""
        pend = self._recall_pending.pop(chat_id, None)
        if pend and time.time() - pend[1] < self._RECALL_TTL:
            self._attach_recall(pend[0], text)
        else:
            self._recall_text[chat_id] = (text, time.time())

    async def edit_message(self, chat_id: str, message_id: str, content: str, *, finalize: bool = False) -> SendResult:
        cur = self.store.get_message(message_id)
        if not cur:
            return SendResult(success=False, error="unknown message")
        kind, meta = self._classify(cur["chat_id"], content, None)
        if cur["kind"] == "status" and kind == "text":
            kind = "status"
        msg = self.store.edit_message(message_id, content=content, kind=kind, meta=meta)
        self._emit({"type": "message", "seq": msg["seq"], "message": msg, "chat": self.store.get_chat(msg["chat_id"])})
        return SendResult(success=True, message_id=message_id)

    async def delete_message(self, chat_id: str, message_id: str) -> bool:
        msg = self.store.edit_message(message_id, content="", kind="deleted")
        if msg:
            self._emit({"type": "message", "seq": msg["seq"], "message": msg})
        return bool(msg)

    async def send_typing(self, chat_id: str, metadata=None) -> None:
        now = time.time()
        w = self._working.get(chat_id)
        if w:
            w["last"] = now
            return
        self._working[chat_id] = {"since": now, "last": now}
        self._emit({"type": "working", "chat_id": chat_id, "working": True, "since": now})

    async def stop_typing(self, chat_id: str) -> None:
        if self._working.pop(chat_id, None):
            self._emit({"type": "working", "chat_id": chat_id, "working": False})

    async def get_chat_info(self, chat_id: str) -> Dict[str, Any]:
        c = self.store.get_chat(chat_id) if self.store else None
        return {"name": (c or {}).get("title") or chat_id, "type": "dm", "chat_id": chat_id}

    def _approval_request_id(self, prompt) -> str:
        """Hermes' own request_id for this card, so Approve resolves exactly the command shown.

        The prompt doesn't carry the id, but the entry is already queued when the prompt is sent. Match on the
        command as redacted for display (the same function the runner used), skipping ids another card holds.
        Identical commands are interchangeable, so any unbound match is the right one. '' when nothing matches
        (old Hermes); h_approve then falls back to oldest-first with its 409 guard."""
        try:
            from tools import approval as AP
            from gateway.run import _redact_approval_command
            with AP._lock:
                entries = list(AP._gateway_queues.get(prompt.session_key, []))
            bound = {r[0] for r in self.store._db.execute(
                "SELECT request_id FROM approvals WHERE session_key=? AND state='pending' AND request_id!=''",
                (prompt.session_key,))}
            for e in entries:
                rid = str(e.data.get("request_id") or "")
                if rid and rid not in bound and _redact_approval_command(e.data.get("command", "")) == (prompt.command or ""):
                    return rid
        except Exception as e:
            logger.warning("[mobile] approval request_id lookup failed: %s", e)
        return ""

    async def _send_exec_approval_prompt(self, prompt) -> SendResult:
        try:
            actions = [list(a) for a in (prompt.actions or [])]
            appr = self.store.add_approval(prompt.chat_id, prompt.session_key, prompt.command or "",
                                           prompt.description or "", actions, self._approval_request_id(prompt))
            ctx = self._approval_context(prompt.chat_id)
            msg = self.store.add_message(prompt.chat_id, "agent", prompt.text or "", kind="approval",
                                         meta={"approval_id": appr["id"], "command": prompt.command,
                                               "description": prompt.description, "actions": actions,
                                               "smart_denied": bool(getattr(prompt, "smart_denied", False)), **ctx})
            self._emit({"type": "approval", "seq": appr["seq"], "approval": appr})
            self._emit_msg(msg, push="approval")
            return SendResult(success=True, message_id=msg["id"])
        except Exception as e:
            logger.exception("[mobile] approval prompt failed")
            return SendResult(success=False, error=str(e))

    def _approval_context(self, chat_id: str) -> Dict[str, Any]:
        """What the agent was doing when it asked: the turn's recent edits (with the last diff) and commands.
        Shown above the Approve button and in the notification so the decision isn't blind."""
        try:
            rows = self.store._db.execute(
                "SELECT tool, label, detail, path, diff, added, removed, status FROM calls WHERE chat_id=? AND ts>?"
                " ORDER BY ts DESC LIMIT 12", (chat_id, time.time() - 1800)).fetchall()
        except Exception:
            return {}
        recent, diff = [], ""
        for r in rows:
            recent.append({"tool": r["tool"], "label": r["label"], "path": r["path"] or "",
                           "added": r["added"] or 0, "removed": r["removed"] or 0, "status": r["status"]})
            if not diff and r["diff"]:
                diff = A._cap(A._redact(r["diff"]), 3000, 600) if hasattr(A, "_cap") else r["diff"][:3600]
        files = sorted({x["path"] for x in recent if x["path"]})
        return {"recent": recent[:8], "diff": diff, "files": files[:12]}

    async def _send_file(self, chat_id: str, path: str, kind: str, caption: Optional[str], extra: Dict[str, Any]) -> SendResult:
        try:
            meta = _copy_media(path)
            meta.update(extra)
            route = route_delivery(self.store, chat_id, caption or meta["file_name"], self._home)
            msg = self.store.add_message(route["chat_id"], "agent", caption or "", kind=kind, meta=meta,
                                         title=route["title"], chat_kind=route["kind"])
            self._emit_msg(msg)
            return SendResult(success=True, message_id=msg["id"])
        except Exception as e:
            logger.exception("[mobile] media send failed")
            return SendResult(success=False, error=str(e))

    async def send_image_file(self, chat_id, image_path, caption=None, reply_to=None, metadata=None, **kw) -> SendResult:
        return await self._send_file(chat_id, image_path, "image", caption, {})

    async def send_document(self, chat_id, file_path, caption=None, file_name=None, reply_to=None, metadata=None, **kw) -> SendResult:
        return await self._send_file(chat_id, file_path, "file", caption, {"file_name": file_name or Path(file_path).name})

    async def send_voice(self, chat_id, audio_path, caption=None, reply_to=None, metadata=None, **kw) -> SendResult:
        return await self._send_file(chat_id, audio_path, "voice", caption, {})

    async def send_video(self, chat_id, video_path, caption=None, reply_to=None, metadata=None, **kw) -> SendResult:
        return await self._send_file(chat_id, video_path, "file", caption, {})

    async def send_image(self, chat_id, image_url, caption=None, reply_to=None, metadata=None) -> SendResult:
        msg = self.store.add_message(chat_id, "agent", caption or "", kind="image", meta={"url": image_url})
        self._emit_msg(msg)
        return SendResult(success=True, message_id=msg["id"])

    # ------------------------------------------------------------ HTTP plumbing
    async def _auth_mw(self, request, handler):
        with ID.launch_scope():
            return await self._auth(request, handler)

    async def _auth(self, request, handler):
        from aiohttp import web
        if request.path in ("/v1/health", "/v1/pair", "/pair"):
            return await handler(request)
        auth = request.headers.get("Authorization", "")
        token = auth[7:].strip() if auth.lower().startswith("bearer ") else ""
        dev = self.store.device_for_token(token)
        if not dev:
            return web.json_response({"error": "unauthorized"}, status=401)
        body = await request.read() if request.body_exists else b""
        ts, sig = request.headers.get("X-HM-Ts", ""), request.headers.get("X-HM-Sig", "")
        msg = SG.canonical(request.method, request.raw_path, ts, body)
        if not self._replay.fresh(sig, pub=dev.get("pub"), msg=msg, ts=ts, scope="request", store=self.store):
            logger.warning("[mobile] rejected unsigned/invalid request %s %s from %s", request.method, request.path, dev["id"])
            return web.json_response({"error": "bad signature"}, status=401)
        request["device"] = dev
        request["signature_replay"] = self._replay
        request["signature_store"] = self.store
        return await handler(request)

    @staticmethod
    def _json(data, status=200):
        from aiohttp import web
        return web.json_response(data, status=status, dumps=lambda o: json.dumps(o, default=str))

    async def h_health(self, request):
        self._ensure_mirror()
        return self._json({"ok": True, "name": socket.gethostname(), "profile": _profile_name(), "api": 1,
                           "security": {"signature_replay": SG.REPLAY_VERSION},
                           "degraded": SC.degraded(self._selfcheck.last)})

    async def h_selfcheck(self, request):
        if request.query.get("run") == "1":
            return self._json(await self._selfcheck.check(request.app))
        return self._json(self._selfcheck.last or {"ts": None, "ok": [], "failures": [], "imports": []})

    async def h_pair(self, request):
        ip = request.headers.get("CF-Connecting-IP") or request.remote or "?"
        hits = [t for t in self._pair_hits.get(ip, []) if time.time() - t < 300]
        if len(hits) >= 10:
            return self._json({"error": "too many attempts"}, 429)
        self._pair_hits[ip] = hits + [time.time()]
        allhits = [t for v in self._pair_hits.values() for t in v if time.time() - t < 300]
        if len(allhits) > 40:  # spread across IPs (CF-Connecting-IP is client-controlled off the tunnel): global cap
            return self._json({"error": "too many attempts"}, 429)
        body = await request.json()
        pub, apub = str(body.get("pub") or ""), str(body.get("apub") or "")
        if not (SG.load_pub(pub) and SG.load_pub(apub)):
            return self._json({"error": "device keys required (P-256 SPKI, base64)"}, 400)
        res = self.store.claim_pair_code(str(body.get("code", "")), str(body.get("device_name", "phone")), pub, apub,
                                         nolock=body.get("lock") is False)
        if not res:
            return self._json({"error": "invalid or expired code"}, 403)
        logger.info("[mobile] paired device %s", res["device_id"])
        return self._json({**res, "cf": _cf_access(), "server": {"name": socket.gethostname(), "profile": _profile_name()}})

    async def h_pair_page(self, request):
        from aiohttp import web
        import html as _h
        code = request.query.get("c", "")
        base = self._public_url or f"{request.scheme}://{request.host}"
        from urllib.parse import quote
        link = f"hermes://pair?u={quote(base, safe='')}&c={quote(code, safe='')}&n={quote(socket.gethostname())}"
        page = f"""<!doctype html><meta name=viewport content="width=device-width,initial-scale=1"><title>Pair Hermes</title>
<body style="background:#0C0C0E;color:#F3F0EA;font-family:system-ui;display:flex;flex-direction:column;align-items:center;justify-content:center;height:90vh;gap:18px">
<div style="font:italic 44px Georgia,serif">Hermes</div><div style="color:#88847D">Pair this phone with {_h.escape(socket.gethostname())}</div>
<a href="{_h.escape(link)}" style="background:#D4F75C;color:#0C0C0E;padding:16px 28px;border-radius:18px;font-weight:600;text-decoration:none;font-size:17px">Open in Hermes</a></body>"""
        return web.Response(text=page, content_type="text/html")

    def _features(self) -> Dict[str, bool]:
        """Cheap local presence checks for optional surfaces (no network, no mkdir). Projects scan is cached 60s."""
        radar_db = Path(os.environ.get("RADAR_HOME") or Path.home() / ".hermes" / "radar") / "radar.db"
        try:
            fleet = FL.ctrl_bin() is not None or FL.STATUS.exists()
        except Exception:
            fleet = False
        wake = getattr(self, "_wake", None)
        wake_wa = bool((getattr(wake, "cfg", None) or {}).get("owner_phone")) if wake else False
        now = time.time()
        cache = getattr(self, "_feat_cache", None)
        if cache and now - cache[0] < 60:
            projects = cache[1]
        else:
            projects = False
            try:
                if PJ.ROOT.is_dir():
                    projects = any(d.is_dir() and not d.name.startswith(".") and (d / ".git").exists()
                                   for d in PJ.ROOT.iterdir())
            except OSError:
                projects = False
            self._feat_cache = (now, projects)
        # The radar page also carries momentum's report, so either one turns it on.
        radar = radar_db.exists() or MO.binary() is not None
        return {"radar": radar, "fleet": fleet, "wake_wa": wake_wa, "projects": projects}

    def _meta(self) -> Dict[str, Any]:
        model = ""
        try:
            from hermes_cli.config import load_config
            cfg = load_config() or {}
            m = cfg.get("model")
            model = (m.get("default") or m.get("model") or "") if isinstance(m, dict) else str(m or "")
        except Exception:
            pass
        profiles = ["default"]
        pdir = Path.home() / ".hermes" / "profiles"
        if pdir.is_dir():
            profiles += sorted(p.name for p in pdir.iterdir() if (p / "config.yaml").exists())
        return {"name": socket.gethostname(), "profile": _profile_name(), "model": model,
                "agent_name": ID.agent_name(), "user_name": ID.user_name(),
                "models": self._recent_models(model), "profiles": profiles, "public_url": self._public_url,
                "features": self._features(), "security": {"signature_replay": SG.REPLAY_VERSION}}

    async def h_me(self, request):
        d = request["device"]
        return self._json({"device": {k: d[k] for k in ("id", "name", "created")}, **self._meta()})

    async def h_sync(self, request):
        self._ensure_mirror()
        since = int(request.query.get("since", "0") or 0)
        data = self.store.changes(since)
        data["working"] = self._working_list()
        return self._json(data)

    async def h_events(self, request):
        self._ensure_mirror()
        from aiohttp import web
        resp = web.StreamResponse(headers={"Content-Type": "text/event-stream", "Cache-Control": "no-cache",
                                           "X-Accel-Buffering": "no"})
        await resp.prepare(request)
        q: asyncio.Queue = asyncio.Queue(maxsize=1000)
        setattr(q, "_phone_device", request["device"]["id"])
        self._subs.add(q)
        try:
            hello = {"type": "hello", "seq": self.store.seq(), "working": self._working_list()}
            await resp.write(f"data: {json.dumps(hello)}\n\n".encode())
            while True:
                try:
                    ev = await asyncio.wait_for(q.get(), timeout=15)
                except asyncio.TimeoutError:
                    ev = {"type": "seq", "seq": self.store.seq(), "working": self._working_list()}
                # A revoked device must not keep its stream: re-check on every event and keepalive.
                if not self.store.device_active((request.get("device") or {}).get("id", "")):
                    break
                if ev.get("type") == "phone" and not any(
                        c["id"] == ev.get("id") for c in self._phone.commands(request["device"]["id"])):
                    continue
                await resp.write(f"data: {json.dumps(ev, default=str)}\n\n".encode())
        except (ConnectionResetError, asyncio.CancelledError, RuntimeError):
            pass
        finally:
            self._subs.discard(q)
        return resp

    async def h_chats(self, request):
        return self._json({"chats": self.store.list_chats(include_archived=request.query.get("archived") == "1"),
                           "working": self._working_list()})

    async def h_new_chat(self, request):
        body = await _body(request)
        prof = str(body.get("profile") or "")
        if prof and prof != "default" and prof not in PR.profile_names():
            return self._json({"error": f"unknown profile {prof}"}, 400)
        chat = self.store.ensure_chat(S.new_id("c"), title=(body.get("title") or "New chat")[:80])
        if prof and prof != "default":
            self._profiles.bind(chat["id"], prof)
            chat = self.store.get_chat(chat["id"])
        self._emit({"type": "chat", "seq": chat["seq"], "chat": chat})
        return self._json({"chat": chat})

    async def h_patch_chat(self, request):
        body = await request.json()
        body = {k: v for k, v in (body or {}).items() if k in ("title", "pinned", "archived", "unread")}
        chat = self.store.update_chat(request.match_info["cid"], **body)
        if chat:
            self._emit({"type": "chat", "seq": chat["seq"], "chat": chat})
        return self._json({"chat": chat})

    async def h_messages(self, request):
        before = request.query.get("before")
        return self._json({"messages": self.store.messages(request.match_info["cid"], limit=int(request.query.get("limit", 80)),
                                                           before_ts=float(before) if before else None)})

    async def h_read(self, request):
        chat = self.store.update_chat(request.match_info["cid"], unread=0)
        if chat:
            self._emit({"type": "chat", "seq": chat["seq"], "chat": chat})
        return self._json({"ok": True})

    async def h_post_message(self, request):
        cid = request.match_info["cid"]
        body = await request.json()
        text = str(body.get("text") or "").strip()
        atts = body.get("attachments") or []
        if not text and not atts:
            return self._json({"error": "empty"}, 400)
        msg = await self.post_from_app(cid, text, body.get("mode") or "send", atts, raw=body)
        return self._json({"message": msg})

    async def post_from_app(self, cid: str, text: str, mode: str = "send", atts: Optional[List[Dict[str, Any]]] = None,
                            raw: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        """A message typed on the phone (chat composer, fleet steer, notification reply, voice): store, echo, dispatch."""
        atts = atts or []
        body = raw or {"text": text, "mode": mode}
        chat = self.store.get_chat(cid)
        if not chat:
            chat = self.store.ensure_chat(cid, title=(text[:48] or "New chat"))
        elif chat.get("title") in (None, "", "New chat") and text and not text.startswith("/"):
            chat = self.store.update_chat(cid, title=text[:48])
        meta: Dict[str, Any] = {"mode": mode}
        if atts:
            meta["attachments"] = atts
        kind = {"steer": "steer", "queue": "queued"}.get(mode, "text")
        prof = self._profiles.profile_of(cid)
        if prof:  # a one-shot child: anything sent while it runs waits its turn, steer included
            kind = "queued" if self._profiles.busy(cid) else "text"
        # Snapshot before storing this user turn. Never trust client-supplied quote
        # text or resolve anchors outside this chat/profile's stored timeline.
        anchor = body.get("reply_to_message_id", body.get("reply_to"))
        quoted = self.store.reply_context(cid, anchor) if not text.startswith("/") else None
        if quoted:
            meta["reply_to"] = quoted["id"]
        msg = self.store.add_message(cid, "user", text, kind=kind, meta=meta)
        self._emit_msg(msg, push=None)
        wire = {"steer": f"/steer {text}", "queue": f"/queue {text}"}.get(mode, text)
        media_urls, media_types, mtype = [], [], MessageType.COMMAND if wire.startswith("/") else MessageType.TEXT
        for a in atts:
            p = S.data_dir() / "media" / Path(str(a.get("id", ""))).name
            if p.exists():
                media_urls.append(str(p))
                media_types.append(a.get("mime") or mimetypes.guess_type(p.name)[0] or "application/octet-stream")
                k = a.get("kind")
                mtype = {"voice": MessageType.VOICE, "image": MessageType.PHOTO}.get(k, MessageType.DOCUMENT)
        source = self.build_source(chat_id=cid, chat_name=chat.get("title"), chat_type="dm", user_id=OWNER,
                                   user_name=ID.user_name() or None, message_id=msg["id"])
        event = MessageEvent(text=wire, message_type=mtype, source=source, message_id=msg["id"],
                             raw_message=body, media_urls=media_urls, media_types=media_types)
        if quoted:
            event.reply_to_message_id = quoted["id"]
            event.reply_to_text = quoted["content"]
            event.reply_to_is_own_message = quoted["role"] == "agent"
            if mode == "steer" and not prof:
                # The busy /steer handler consumes command args, not reply fields;
                # its turn-boundary queue fallback also drops those fields.
                from gateway.run_inbound import GatewayInboundMixin
                event.text = "/steer " + GatewayInboundMixin._prepend_inbound_reply_context(event, source, text)
                event.reply_to_text = None  # already in the new steer payload
        if prof:
            if kind == "queued" and mode == "steer":
                note = self.store.add_message(cid, "agent", f"{prof} runs one-shot and can't be steered mid-run; "
                                              "this runs next.", kind="status", meta={"quiet": True})
                self._emit_msg(note, push=None)
            # Profile chats use the CLI rather than MessageEvent. Apply the same
            # gateway-supported per-turn envelope before queueing that new prompt.
            prompt = text
            if quoted:
                from gateway.run_inbound import GatewayInboundMixin
                prompt = GatewayInboundMixin._prepend_inbound_reply_context(event, source, text)
            await self._profiles.send(cid, prof, prompt, atts)
            return msg
        tip = self._mirror.take_over(cid) if cid.startswith("h-") and getattr(self, "_mirror", None) else None
        if tip:
            resume = MessageEvent(text=f"/resume {tip} --all", message_type=MessageType.COMMAND, source=source,
                                  message_id=S.new_id("rs"))

            async def _continue() -> None:
                await self.handle_message(resume)
                await asyncio.sleep(2.5)
                await self.handle_message(event)
            asyncio.create_task(_continue())
            return msg
        asyncio.create_task(self.handle_message(event))
        return msg

    async def h_stop(self, request):
        await self.stop_chat(request.match_info["cid"])
        return self._json({"ok": True})

    async def stop_chat(self, cid: str) -> None:
        if await self._profiles.stop(cid):
            return
        chat = self.store.get_chat(cid) or {}
        source = self.build_source(chat_id=cid, chat_name=chat.get("title"), chat_type="dm", user_id=OWNER, user_name=ID.user_name() or None)
        await self.handle_message(MessageEvent(text="/stop", message_type=MessageType.COMMAND, source=source,
                                               message_id=S.new_id("stop")))

    async def h_approve(self, request):
        aid = request.match_info["aid"]
        body = await request.json()
        choice = str(body.get("choice") or "")
        if choice not in ("once", "session", "always", "deny"):
            return self._json({"error": "bad choice"}, 400)
        # A phone with no screen lock signs approvals with a key nothing protects: let it approve one command at a
        # time, never widen what runs unchecked for the session or forever.
        if choice in ("session", "always") and (request["device"].get("nolock") or "") == "1":
            return self._json({"error": "This phone has no screen lock, so it can only approve once"}, 403)
        if choice != "deny":
            amsg = f"approve\n{aid}\n{choice}\n{request.headers.get('X-HM-Ts', '')}".encode()
            if not SG.approval_ok(request, amsg):
                return self._json({"error": "biometric signature required"}, 403)
        row = self.store.approval_row(aid)
        if not row:
            return self._json({"error": "unknown approval"}, 404)
        if row["state"] != "pending":
            return self._json({"approval": self.store.get_approval(aid), "resolved": 0})
        rid = row["request_id"] if "request_id" in row.keys() else ""
        # With the card's request_id, Hermes resolves exactly this command. Without one (old Hermes), it resolves the
        # session's oldest pending entry, so only the oldest card may be answered.
        older = None if rid else self.store._db.execute("SELECT id, command FROM approvals WHERE session_key=? AND state='pending' AND ts<?"
                                       " ORDER BY ts LIMIT 1", (row["session_key"], row["ts"])).fetchone()
        if older:
            return self._json({"error": "An earlier approval in this session is still waiting; answer that one first: "
                                        + (older["command"] or "")[:120], "older": older["id"]}, 409)
        count = 0
        try:
            from tools.approval import resolve_gateway_approval
            count = resolve_gateway_approval(row["session_key"], choice, request_id=rid or None)
        except Exception as e:
            logger.error("[mobile] resolve approval failed: %s", e)
        state = ("denied" if choice == "deny" else "approved") if count else "expired"
        appr = self.store.set_approval(aid, state, choice)
        self._emit({"type": "approval", "seq": appr["seq"], "approval": appr})
        return self._json({"approval": appr, "resolved": count})

    async def h_upload(self, request):
        data = await request.read()
        if not data:
            return self._json({"error": "empty upload"}, 400)
        fname = Path(request.query.get("name") or "upload.bin").name
        name = S.new_id("u") + (Path(fname).suffix.lower() or "")
        dst = S.data_dir() / "media" / name
        dst.write_bytes(data)
        mime = request.query.get("mime") or mimetypes.guess_type(fname)[0] or "application/octet-stream"
        return self._json({"id": name, "mime": mime, "size": len(data), "file_name": fname})

    async def h_tts(self, request):
        """Talk mode voice: synthesize ``text`` with Hermes' configured TTS (tts.provider / tts.<provider>.voice_id)
        into the media dir. Returns ``{"ids": [...], "provider": ...}``; the app fetches each via /v1/media and plays them in order."""
        b = await _body(request)
        text = str(b.get("text") or "").strip()[:20000]
        if not text:
            return self._json({"error": "text required"}, 400)
        from tools.tts_tool import text_to_speech_tool
        base = S.data_dir() / "media" / (S.new_id("tts") + ".mp3")
        base.parent.mkdir(parents=True, exist_ok=True)
        try:
            res = json.loads(await asyncio.to_thread(text_to_speech_tool, text, str(base)))
        except Exception as e:
            logger.warning("[mobile] tts failed: %s", e)
            return self._json({"error": f"tts failed: {e}"}, 502)
        if not res.get("success"):
            return self._json({"error": str(res.get("error") or "tts failed")}, 502)
        media = (S.data_dir() / "media").resolve()
        cutoff = time.time() - 6 * 3600   # spoken replies are throwaway: prune old ones
        for old in media.glob("tts-*"):
            try:
                if old.stat().st_mtime < cutoff:
                    old.unlink()
            except OSError:
                pass
        ids = []
        for fp in res.get("file_paths") or [res.get("file_path")]:
            src = Path(fp).resolve()
            if src.parent != media:
                dst = media / (S.new_id("tts") + src.suffix)
                shutil.copyfile(src, dst); src = dst
            ids.append(src.name)
        return self._json({"ids": ids, "provider": res.get("provider")})

    async def h_media(self, request):
        from aiohttp import web
        p = S.data_dir() / "media" / Path(request.match_info["name"]).name
        if not p.exists():
            return self._json({"error": "not found"}, 404)
        return web.FileResponse(p)

    # ------------------------------------------------------------ run / cron / pulse / sessions
    @staticmethod
    def _state_db():
        p = S.hermes_home() / "state.db"
        c = sqlite3.connect(f"file:{p}?mode=ro", uri=True, timeout=5)
        c.row_factory = sqlite3.Row
        return c

    def _session_for_chat(self, cid: str) -> Optional[Dict[str, Any]]:
        try:
            with self._state_db() as c:
                r = c.execute("SELECT * FROM sessions WHERE source='mobile' AND chat_id=? ORDER BY started_at DESC LIMIT 1",
                              (cid,)).fetchone()
                return dict(r) if r else None
        except Exception:
            return None

    async def h_run(self, request):
        cid = request.match_info["cid"]
        msgs = self.store.messages(cid, limit=120)
        start = 0
        for i, m in enumerate(msgs):
            if m["role"] == "user" and m["kind"] == "text":
                start = i
        turn = msgs[start:]
        steps = []
        for m in turn:
            if m["kind"] == "trace":
                for s in m["meta"].get("steps", []):
                    steps.append({**s, "ts": m["ts"]})
            elif m["kind"] == "activity":
                for c in self.store.calls(m["id"], light=True):
                    steps.append({"tool": c["tool"], "what": c["label"], "ts": c["ts"], "status": c["status"]})
        sess = self._session_for_chat(cid) or {}
        cost = sess.get("actual_cost_usd") or sess.get("estimated_cost_usd") or 0
        toks = sum(int(sess.get(k) or 0) for k in ("input_tokens", "output_tokens", "cache_read_tokens"))
        w = self._working.get(cid)
        return self._json({"chat_id": cid, "working": bool(w), "since": (w or {}).get("since") or (turn[0]["ts"] if turn else None),
                           "steps": steps, "model": sess.get("model"), "tokens": toks, "cost": cost,
                           "usage": self.store.get_turn(cid), "session": await asyncio.to_thread(self._session_usage, cid),
                           "turn": turn, "approvals": [a for a in self.store.pending_approvals() if a["chat_id"] == cid]})

    @staticmethod
    def _job_view(j: Dict[str, Any]) -> Dict[str, Any]:
        keys = ("id", "name", "schedule", "schedule_display", "enabled", "deliver", "model", "last_run_at", "next_run_at",
                "last_status", "last_error", "failure_streak", "paused_reason", "created_at")
        v = {k: j.get(k) for k in keys}
        if isinstance(v["schedule"], dict):
            v["schedule"] = v["schedule"].get("display") or v["schedule"].get("expr") or json.dumps(v["schedule"])
        v["prompt"] = (j.get("prompt") or "")[:600]
        return v

    async def h_cron(self, request):
        try:
            from cron.jobs import list_jobs
            jobs = await asyncio.to_thread(list_jobs, True)
        except Exception as e:
            return self._json({"error": f"cron unavailable: {e}"}, 500)
        out = [self._job_view(j) for j in jobs]
        hist_root = S.hermes_home() / "cron" / "output"
        for v in out:
            d = hist_root / str(v["id"])
            v["runs"] = sorted((p.stem for p in d.glob("*.md")), reverse=True)[:14] if d.is_dir() else []
        return self._json({"jobs": out, "now": time.time()})

    async def h_cron_action(self, request):
        jid, action = request.match_info["jid"], request.match_info["action"]
        from cron import jobs as J
        fn = {"pause": J.pause_job, "resume": J.resume_job, "run": J.trigger_job}.get(action)
        if not fn:
            return self._json({"error": "bad action"}, 400)
        res = await asyncio.to_thread(fn, jid)
        if res is None:
            return self._json({"error": "unknown job"}, 404)
        return self._json({"job": self._job_view(res)})

    async def h_cron_output(self, request):
        d = S.hermes_home() / "cron" / "output" / Path(request.match_info["jid"]).name
        files = sorted(d.glob("*.md"), reverse=True) if d.is_dir() else []
        run = request.query.get("run")
        f = next((p for p in files if p.stem == run), files[0] if files else None)
        if not f:
            return self._json({"error": "no output yet"}, 404)
        return self._json({"run": f.stem, "text": f.read_text(errors="replace")[:60000]})

    async def h_pulse(self, request):
        now = time.time()
        day0 = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0).timestamp()
        spend, runs, by_day, live = 0.0, 0, [0.0] * 7, []
        try:
            with self._state_db() as c:
                rows = c.execute("SELECT source, started_at, ended_at, model, title, chat_id,"
                                 " COALESCE(actual_cost_usd, estimated_cost_usd, 0) AS cost FROM sessions WHERE started_at>?",
                                 (day0 - 6 * 86400,)).fetchall()
                for r in rows:
                    i = 6 - int((day0 - (r["started_at"] or day0)) // 86400 + (1 if (r["started_at"] or day0) < day0 else 0))
                    if 0 <= i < 7:
                        by_day[i] += float(r["cost"] or 0)
                    if (r["started_at"] or 0) >= day0:
                        spend += float(r["cost"] or 0)
                        runs += 1
                for r in c.execute("SELECT s.id, s.source, s.title, s.model, s.started_at, MAX(m.timestamp) AS last"
                                   " FROM sessions s JOIN messages m ON m.session_id=s.id WHERE s.ended_at IS NULL"
                                   " AND s.started_at>? GROUP BY s.id HAVING last>? ORDER BY last DESC LIMIT 8",
                                   (now - 86400, now - 300)):
                    live.append(dict(r))
        except Exception as e:
            logger.debug("[mobile] pulse state.db read failed: %s", e)
        failures = 0
        try:
            from cron.jobs import list_jobs
            for j in await asyncio.to_thread(list_jobs, False):
                if (j.get("last_status") or "ok") not in ("ok", "success") and str(j.get("last_run_at") or "") >= datetime.now().strftime("%Y-%m-%d"):
                    failures += 1
        except Exception:
            pass
        return self._json({"spend_today": round(spend, 4), "runs_today": runs, "failures_today": failures,
                           "spend_7d": [round(x, 4) for x in by_day], "live_sessions": live,
                           "working": self._working_list(), "approvals": self.store.pending_approvals()})

    async def h_sessions(self, request):
        try:
            with self._state_db() as c:
                rows = c.execute("SELECT id, source, title, model, started_at, ended_at,"
                                 " COALESCE(actual_cost_usd, estimated_cost_usd, 0) AS cost FROM sessions"
                                 " WHERE source NOT IN ('cron','subagent','mobile') ORDER BY started_at DESC LIMIT 60").fetchall()
            return self._json({"sessions": [dict(r) for r in rows]})
        except Exception as e:
            return self._json({"error": str(e)}, 500)

    async def h_session_messages(self, request):
        try:
            with self._state_db() as c:
                rows = c.execute("SELECT role, content, tool_name, timestamp FROM messages WHERE session_id=?"
                                 " AND role IN ('user','assistant') AND COALESCE(content,'')!='' ORDER BY id DESC LIMIT 200",
                                 (request.match_info["sid"],)).fetchall()
            return self._json({"messages": [dict(r) for r in rows][::-1]})
        except Exception as e:
            return self._json({"error": str(e)}, 500)

    def _session_usage(self, cid: str) -> Optional[Dict[str, Any]]:
        s = self._session_for_chat(cid)
        if not s:
            return None
        keys = ("input_tokens", "output_tokens", "cache_read_tokens", "cache_write_tokens", "reasoning_tokens")
        out = {k: int(s.get(k) or 0) for k in keys}
        out.update(model=s.get("model") or "", calls=int(s.get("api_call_count") or 0), session_id=s.get("id"),
                   tool_calls=int(s.get("tool_call_count") or 0), messages=int(s.get("message_count") or 0))
        cost = s.get("actual_cost_usd") or (s.get("estimated_cost_usd") if s.get("cost_status") not in (None, "", "unknown") else None)
        status = "actual" if s.get("actual_cost_usd") else "estimated"
        if not cost:
            p = U.price_session(out["model"], s.get("billing_provider") or "", s.get("billing_base_url") or "", out)
            cost, status = p["cost"], p["cost_status"]
        out["cost"] = float(cost) if cost is not None else None
        out["cost_status"] = status if cost is not None else "unknown"
        prompt = out["input_tokens"] + out["cache_read_tokens"] + out["cache_write_tokens"]
        out["prompt_tokens"] = prompt
        out["cache_hit"] = round(out["cache_read_tokens"] / prompt, 4) if prompt else None
        return out

    async def h_usage(self, request):
        cid = request.match_info["cid"]
        sess = await asyncio.to_thread(self._session_usage, cid)
        return self._json({"chat_id": cid, "turn": self.store.get_turn(cid), "session": sess})

    async def h_activity(self, request):
        d = self._activity.detail(request.match_info["mid"])
        return self._json(d) if d else self._json({"error": "not found"}, 404)

    async def h_commands(self, request):
        return self._json({"commands": await asyncio.to_thread(_slash_catalog), **self._meta()})

    def _recent_models(self, current: str) -> List[str]:
        seen = [current] if current else []
        try:
            with self._state_db() as c:
                for r in c.execute("SELECT model, MAX(started_at) t FROM sessions WHERE model IS NOT NULL AND model!=''"
                                   " AND started_at>? GROUP BY model ORDER BY t DESC LIMIT 8", (time.time() - 30 * 86400,)):
                    if r["model"] not in seen:
                        seen.append(r["model"])
        except Exception:
            pass
        return seen[:6]

    async def h_fcm(self, request):
        body = await request.json()
        self.store.set_fcm(request["device"]["id"], str(body.get("token") or ""))
        return self._json({"ok": True, "push": _fcm_config() is not None})

    async def h_push_config(self, request):
        p = S.data_dir() / "fcm-client.json"
        cfg = None
        if p.exists() and _fcm_config():
            try:
                cfg = json.loads(p.read_text())
            except Exception:
                cfg = None
        return self._json({"enabled": bool(cfg), "config": cfg or {}})

    async def h_devices(self, request):
        return self._json({"devices": self.store.devices(), "this": request["device"]["id"]})

    async def h_revoke(self, request):
        did = request.match_info["did"]
        ok = self.store.revoke(did)
        if getattr(self, "_phone", None):
            self._phone.disarm(did)
        return self._json({"ok": ok})

    # ------------------------------------------------------------ mirror of other surfaces
    def _ensure_mirror(self) -> None:
        if getattr(self, "_mirror", None) is not None:
            return
        try:
            self._mirror = MR.Mirror(self.store, S.hermes_home() / "state.db")
            self._mirror.busy = lambda cid: bool(self._working.get(cid))
            n = len(self._mirror.sync_once())
            logger.info("[mobile] mirrored %d messages from other surfaces", n)
            self._mirror_task = asyncio.get_running_loop().create_task(self._mirror_loop())
        except Exception as e:
            logger.warning("[mobile] mirror init failed: %s", e)
            self._mirror = False

    async def _mirror_loop(self) -> None:
        while True:
            await asyncio.sleep(8)
            try:
                for mid in self._mirror.sync_once():
                    m = self.store.get_message(mid)
                    if m:
                        ch = self.store.get_chat(m["chat_id"]) or {}
                        self._emit_msg(m, push="digest" if ch.get("kind") == "digest" else None)
            except asyncio.CancelledError:
                raise
            except Exception as e:
                logger.debug("[mobile] mirror pass failed: %s", e)

    # ------------------------------------------------------------ push (FCM data-only wake-ups; no content)
    def _push(self, kind: str, chat_id: str) -> None:
        asyncio.create_task(push_wake(self.store, kind, chat_id))


_FCM_CACHE: Dict[str, Any] = {}


def _fcm_config() -> Optional[Dict[str, Any]]:
    p = S.data_dir() / "fcm-service-account.json"
    if not p.exists():
        return None
    try:
        return json.loads(p.read_text())
    except Exception:
        return None


def _fcm_token(info: Dict[str, Any]) -> Optional[str]:
    tok = _FCM_CACHE.get("tok")
    if tok and _FCM_CACHE.get("exp", 0) > time.time() + 60:
        return tok
    from google.oauth2 import service_account
    from google.auth.transport.requests import Request
    creds = service_account.Credentials.from_service_account_info(
        info, scopes=["https://www.googleapis.com/auth/firebase.messaging"])
    creds.refresh(Request())
    _FCM_CACHE.update(tok=creds.token, exp=creds.expiry.timestamp() if creds.expiry else time.time() + 3000)
    return creds.token


async def push_wake(store: S.Store, kind: str, chat_id: str, device_id: Optional[str] = None) -> int:
    info = _fcm_config()
    if not info:
        return 0
    targets = [r["fcm_token"] for r in store._db.execute(
        "SELECT fcm_token FROM devices WHERE revoked=0 AND fcm_token!='' AND (? IS NULL OR id=?)",
        (device_id, device_id))]
    if not targets:
        return 0
    try:
        import httpx
        token = await asyncio.to_thread(_fcm_token, info)
        url = f"https://fcm.googleapis.com/v1/projects/{info['project_id']}/messages:send"
        sent = 0
        async with httpx.AsyncClient(timeout=10) as cl:
            for t in targets:
                payload = {"message": {"token": t, "data": {"t": kind, "c": chat_id, "s": str(store.seq())},
                                       "android": {"priority": "HIGH" if kind in ("approval", "message", "fleet", "phone") else "NORMAL",
                                                   "ttl": "900s", "collapse_key": kind}}}
                r = await cl.post(url, json=payload, headers={"Authorization": f"Bearer {token}"})
                if r.status_code == 200:
                    sent += 1
                elif r.status_code == 404:
                    store._db.execute("UPDATE devices SET fcm_token='' WHERE fcm_token=?", (t,))
                else:
                    logger.warning("[mobile] FCM %s: %s", r.status_code, r.text[:200])
        return sent
    except Exception as e:
        logger.warning("[mobile] push failed: %s", e)
        return 0


# ---------------------------------------------------------------- out-of-process cron delivery
async def _standalone_send(pconfig, chat_id: str, message: str, *, thread_id: Optional[str] = None,
                           media_files: Optional[List[str]] = None, force_document: bool = False) -> Dict[str, Any]:
    try:
        store = S.Store()
        home = _env("MOBILE_HOME_CHANNEL") or HOME_DEFAULT
        route = route_delivery(store, chat_id, message, home)
        msg = store.add_message(route["chat_id"], "agent", message, meta={"delivery": True},
                                title=route["title"], chat_kind=route["kind"])
        for f in media_files or []:
            try:
                meta = _copy_media(f)
                store.add_message(route["chat_id"], "agent", "", kind="image" if meta["mime"].startswith("image/") else "file", meta=meta)
            except Exception:
                pass
        await push_wake(store, "digest" if route["kind"] == "digest" else "message", route["chat_id"])
        return {"success": True, "platform": "mobile", "chat_id": route["chat_id"], "message_id": msg["id"]}
    except Exception as e:
        return {"success": False, "error": f"mobile standalone send failed: {e}"}


# ---------------------------------------------------------------- CLI: hermes mobile ...
def _cli_setup(parser) -> None:
    sub = parser.add_subparsers(dest="mobile_cmd")
    p = sub.add_parser("pair", help="Show a one-time pairing QR for the Android app")
    p.add_argument("--url", default="", help="Public URL the phone uses (default MOBILE_PUBLIC_URL)")
    p.add_argument("--ttl", type=int, default=600)
    sub.add_parser("devices", help="List paired devices")
    r = sub.add_parser("revoke", help="Revoke a paired device")
    r.add_argument("device_id")
    sub.add_parser("status", help="Show mobile API status")
    sub.add_parser("check", help="Call every read-only endpoint in-process and resolve the plugin's Hermes imports")


def _cli_handler(args) -> int:
    import subprocess
    from urllib.parse import quote
    store = S.Store()
    cmd = getattr(args, "mobile_cmd", None) or "status"
    if cmd == "pair":
        url = (args.url or _env("MOBILE_PUBLIC_URL") or _public_url_default()).rstrip("/")
        if not url:
            print("Set MOBILE_PUBLIC_URL (e.g. https://myhost.tailnet.ts.net:9445) or pass --url")
            return 2
        code = store.create_pair_code(args.ttl)
        cf = _cf_access()
        link = f"hermes://pair?u={quote(url, safe='')}&c={quote(code, safe='')}&n={quote(socket.gethostname())}"
        lf = S.data_dir() / "pair-link.txt"
        fd = os.open(str(lf), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        os.write(fd, link.encode())
        os.close(fd)
        png = S.data_dir() / "pair.png"
        try:
            subprocess.run(["qrencode", "-t", "ANSIUTF8", "-m", "2", link], check=False)
            subprocess.run(["qrencode", "-o", str(png), "-s", "12", "-m", "3", link], check=False)
        except FileNotFoundError:
            print("(install qrencode for a terminal QR)")
        print(f"\nScan with Hermes on your phone (valid {args.ttl // 60} min).")
        print(f"Or paste the pairing link into the app (saved to {lf}).")
        print(f"QR image: {png}")
        return 0
    if cmd == "devices":
        for d in store.devices():
            seen = datetime.fromtimestamp(d["last_seen"]).strftime("%Y-%m-%d %H:%M") if d["last_seen"] else "-"
            print(f"{d['id']}  {d['name']:<24} last seen {seen}{'  REVOKED' if d['revoked'] else ''}")
        return 0
    if cmd == "check":
        return _cli_check()
    if cmd == "revoke":
        print("revoked" if store.revoke(args.device_id) else "no such device")
        return 0
    bind = _env("MOBILE_BIND") or DEFAULT_BIND
    try:
        import urllib.request
        with urllib.request.urlopen(f"http://{bind}/v1/health", timeout=3) as r:
            print(f"mobile API up on {bind}: {r.read().decode()}")
    except Exception as e:
        print(f"mobile API not reachable on {bind}: {e}")
    print(f"public url: {_env('MOBILE_PUBLIC_URL') or '(unset)'}; devices: {len(store.devices())}; push: {'on' if _fcm_config() else 'off'}")
    return 0


def _cli_check() -> int:
    """Same check the gateway runs after connect, against the live data, without the gateway."""
    from gateway.config import PlatformConfig
    logging.getLogger("mobile").setLevel(logging.ERROR)

    async def go():
        ad = MobileAdapter(PlatformConfig(enabled=True))
        app = ad._build_app(cli=True)
        return await SC.run(app)
    res = asyncio.run(go())
    print(SC.table(res))
    return 1 if res["failures"] or res["imports"] else 0


# ---------------------------------------------------------------- registration
def _enabled() -> bool:
    return _env("MOBILE_ENABLED").strip().lower() in _TRUTHY


def _env_enablement() -> Optional[dict]:
    if not _enabled():
        return None
    return {"bind": _env("MOBILE_BIND") or DEFAULT_BIND, "public_url": _env("MOBILE_PUBLIC_URL"),
            "home_channel": _env("MOBILE_HOME_CHANNEL") or HOME_DEFAULT}


def check_requirements() -> bool:
    try:
        import aiohttp  # noqa: F401
        return True
    except Exception:
        return False


def validate_config(config) -> bool:
    return _enabled() or bool((getattr(config, "extra", None) or {}).get("bind"))


def is_connected(config) -> bool:
    return validate_config(config)


def register(ctx) -> None:
    ctx.register_platform(
        name="mobile", label="Hermes Mobile", adapter_factory=lambda cfg: MobileAdapter(cfg),
        check_fn=check_requirements, validate_config=validate_config, is_connected=is_connected,
        required_env=[], install_hint="aiohttp ships with Hermes",
        env_enablement_fn=_env_enablement, cron_deliver_env_var="MOBILE_HOME_CHANNEL",
        standalone_sender_fn=_standalone_send,
        allowed_users_env="MOBILE_ALLOWED_USERS", allow_all_env="MOBILE_ALLOW_ALL_USERS",
        max_message_length=MobileAdapter.MAX_MESSAGE_LENGTH, emoji="\U0001F4F1", pii_safe=True,
        platform_hint=(f"You are talking to {ID.user_ref()} through the Hermes Android app (their primary mobile client). "
                       "Full GitHub-flavored markdown renders: headings, lists, tables, code blocks. "
                       "Images and files you send show inline. Keep mobile replies tight."))
    for hook, fn in (("pre_tool_call", A.pre_hook), ("post_tool_call", A.post_hook),
                     ("on_interim_message", A.interim_hook), ("post_api_request", U.post_api_hook),
                     ("pre_api_request", RC.pre_api_hook)):
        try:
            ctx.register_hook(hook, fn)
        except Exception as e:
            logger.warning("[mobile] hook %s not registered: %s", hook, e)
    try:
        ctx.register_tool(name="phone_use", toolset="phone_use", schema=PH.SCHEMA, handler=PH.handle,
                          check_fn=PH.check, emoji="\U0001F4F1",
                          description="Drive the paired Android phone through the Hermes app's Accessibility service")
    except Exception as e:
        logger.warning("[mobile] phone_use tool not registered: %s", e)
    try:
        ctx.register_middleware("llm_execution", U.llm_middleware)
    except Exception as e:
        logger.warning("[mobile] llm middleware not registered: %s", e)
    try:
        ctx.register_cli_command(name="mobile", help="Hermes Android app: pairing and devices",
                                 setup_fn=_cli_setup, handler_fn=_cli_handler,
                                 description="Pair the Hermes Android app and manage devices")
    except Exception as e:
        logger.debug("[mobile] CLI registration skipped: %s", e)

from . import mirror as MR  # noqa: E402
