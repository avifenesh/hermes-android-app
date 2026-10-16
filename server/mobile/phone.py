"""Phone control: the agent drives the paired Android phone through the app's Accessibility service.

Same shape as computer-use-linux: ``state`` returns the on-screen element tree (indexed) plus a screenshot, then
``tap``/``type``/``swipe``/``scroll``/``key``/``open_app`` act on element indices or coordinates.

Transport: the tool runs on the agent thread and queues a device-bound, expiring command on the adapter loop.
Only the armed device receives an SSE/FCM wake hint; it fetches commands from ``/v1/phone/pending``.
The phone executes it in ``PhoneService`` and POSTs the result to ``/v1/phone/result``. Only the device that
armed control (``/v1/phone/arm``) may answer; disarming or revoking the device drops control.
"""
from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid
from typing import Any, Dict, Optional

from . import ident as ID

logger = logging.getLogger(__name__)

ACTIONS = ["state", "screenshot", "tap", "long_press", "swipe", "scroll", "type", "key", "open_app", "list_apps", "list_windows", "activate_window", "wait"]
ALIASES = {"get_app_state": "state", "click": "tap", "type_text": "type", "set_value": "type", "press_key": "key", "drag": "swipe"}
KEYS = ["back", "home", "recents", "notifications", "quick_settings", "enter", "power_dialog", "lock"]

SCHEMA = {
    "name": "phone_use",
    "description": (
        f"Control {ID.user_ref()}'s Android phone (the paired Hermes app's Accessibility service): read the screen, tap, type, "
        "swipe, scroll, press system keys, open apps. Start with action=state: it returns indexed on-screen elements "
        "(role, text, id, bounds, checked/selected/focused, clickable/editable/scrollable) and a screenshot. "
        "By default state reads the focused screen without keyboard/background noise; scope=all and include_keyboard=true widen it. "
        "Prefer index over x/y. For OEM controls that ignore Accessibility clicks, use tap method=gesture. "
        "A successful action means dispatch, not a verified effect: re-read state and verify the requested value. "
        "Coordinates are physical screen pixels. Follow the user's "
        "authorized intent; clarify only if a consequential action is outside that intent. Fails with 'phone not armed' until control is turned on in the app "
        "(Me > This phone > Control)."),
    "parameters": {
        "type": "object",
        "properties": {
            "action": {"type": "string", "enum": ACTIONS + list(ALIASES) + ["perform_action"]},
            "operation": {"type": "string", "description": "perform_action: one of the supported action names"},
            "window_id": {"type": "integer", "description": "activate_window: window id from list_windows; alternatively supply app"},
            "value": {"type": "string", "description": "set_value: text to set on an editable element"},
            "index": {"type": "integer", "description": "Element index from the latest state result"},
            "x": {"type": "integer"}, "y": {"type": "integer"},
            "x2": {"type": "integer", "description": "swipe end x"}, "y2": {"type": "integer", "description": "swipe end y"},
            "duration_ms": {"type": "integer", "description": "swipe/long_press duration (default 300/700)"},
            "direction": {"type": "string", "enum": ["up", "down", "left", "right"], "description": "scroll direction (content moves to reveal this side)"},
            "text": {"type": "string", "description": "type: text to set (replaces field content unless append=true)"},
            "append": {"type": "boolean"},
            "submit": {"type": "boolean", "description": "type: press IME enter after setting text"},
            "key": {"type": "string", "enum": KEYS},
            "app": {"type": "string", "description": "open_app: package name or app label"},
            "query": {"type": "string", "description": "list_apps: filter by label/package substring"},
            "screenshot": {"type": "boolean", "description": "state: include screenshot (default true)"},
            "max_nodes": {"type": "integer", "description": "state: element cap (default 250)"},
            "scope": {"type": "string", "enum": ["focused", "all"], "description": "state: focused foreground screen (default) or all windows, foreground first"},
            "include_keyboard": {"type": "boolean", "description": "state: include input-method window elements (default false)"},
            "method": {"type": "string", "enum": ["auto", "gesture", "accessibility"], "description": "tap/long_press: auto (default) uses one gesture for checkable controls, otherwise Accessibility with fallback on explicit rejection; gesture forces physical tap; accessibility never falls back"},
            "seconds": {"type": "number", "description": "wait: seconds (max 10)"},
        },
        "required": ["action"],
    },
}


class Phone:
    def __init__(self, adapter):
        self.ad = adapter
        self.pending: Dict[str, asyncio.Future] = {}
        self.cmds: Dict[str, Dict[str, Any]] = {}
        self._serial = asyncio.Lock()
        self._arms: Dict[str, Dict[str, Any]] = {}

    def cancel(self, reason: str, device: Optional[str] = None) -> None:
        for cid, cmd in list(self.cmds.items()):
            if device is not None and cmd["device"] != device:
                continue
            self.cmds.pop(cid, None)
            self._arms.pop(cid, None)
            fut = self.pending.pop(cid, None)
            if fut is not None and not fut.done():
                fut.set_result({"ok": False, "error": reason})

    def arm(self, device: str) -> None:
        self.cancel("phone control re-armed")
        self.ad.store.kv_put("phone:armed", {"device": device, "since": time.time(), "session": uuid.uuid4().hex})

    def disarm(self, device: str, session: Optional[str] = None) -> None:
        st = self.ad.store.kv_get("phone:armed")
        if session is not None and (not isinstance(st, dict) or st.get("session") != session):
            return
        if isinstance(st, dict) and st.get("device") == device:
            self.ad.store.kv_put("phone:armed", None)
        self.cancel("phone control disarmed or revoked", device)

    def commands(self, device: str) -> list:
        st = self.armed()
        if not st or st["device"] != device:
            return []
        return [dict(cmd) for cid, cmd in self.cmds.items()
                if cmd["device"] == device and cmd["expires"] > time.time()
                and self._arms.get(cid) == st
                and cid in self.pending and not self.pending[cid].done()]

    # ---------------------------------------------------------------- arm state (kv, survives restarts)
    def armed(self) -> Optional[Dict[str, Any]]:
        st = self.ad.store.kv_get("phone:armed")
        if not isinstance(st, dict) or not st.get("device"):
            return None
        if not self.ad.store.device_active(st["device"]):
            return None
        return st

    async def run(self, cmd: Dict[str, Any], timeout: float) -> Dict[str, Any]:
        st = self.armed()
        if not st:
            return {"ok": False, "error": "phone not armed: turn on Me > This phone > Control in the Hermes app"}
        cid = uuid.uuid4().hex
        started = time.time()
        wake = None
        try:
            # Budget includes the queue. A queued call never changes its originating arm/device.
            async with asyncio.timeout(timeout):
                async with self._serial:
                    if self.armed() != st:
                        return {"ok": False, "error": "phone control changed while queued"}
                    fut = asyncio.get_running_loop().create_future()
                    self.pending[cid] = fut
                    now = time.time()
                    cmd = {**cmd, "id": cid, "device": st["device"], "ts": now,
                           "expires": min(now + timeout, started + timeout)}
                    self.cmds[cid] = cmd
                    self._arms[cid] = st
                    # SSE is a wake hint only. Never distribute action/text/screenshot data.
                    self.ad._emit({"type": "phone", "device": st["device"], "id": cid}, device=st["device"])
                    from .adapter import push_wake
                    wake = asyncio.create_task(push_wake(self.ad.store, "phone", "", device_id=st["device"]))
                    while not fut.done():
                        if self.armed() != st:
                            self.cancel("phone control disarmed or revoked", st["device"])
                            break
                        await asyncio.wait({fut}, timeout=0.2)
                    return fut.result()
        except TimeoutError:
            return {"ok": False, "error": f"phone did not answer within {timeout:g}s (app offline, screen off, or service disabled)"}
        finally:
            self.cmds.pop(cid, None)
            self._arms.pop(cid, None)
            fut = self.pending.pop(cid, None)
            if fut is not None and not fut.done():
                fut.cancel()
            if wake is not None:
                wake.cancel()

    def resolve(self, device_id: str, body: Dict[str, Any]) -> bool:
        cid = str(body.get("id") or "")
        if not any(cmd["id"] == cid for cmd in self.commands(device_id)):
            return False
        self.cmds.pop(cid, None)
        self.pending[cid].set_result(body)
        return True


# ---------------------------------------------------------------- tool (agent thread)
def _fmt(res: Dict[str, Any], action: str):
    if not res.get("ok"):
        return json.dumps({"error": res.get("error") or "failed"})
    r = res.get("result", {})
    img = res.get("image")
    text = r if isinstance(r, str) else json.dumps(r, ensure_ascii=False)
    if not img:
        return text
    return {"_multimodal": True, "text_summary": text,
            "content": [{"type": "text", "text": text},
                        {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{img}"}}]}


def handle(args: Dict[str, Any], **_: Any):
    from . import activity as A
    ad = A._LIVE
    loop = getattr(ad, "_mobile_loop", None) if ad else None
    if ad is None or loop is None or loop.is_closed() or getattr(ad, "_phone", None) is None:
        return json.dumps({"error": "mobile adapter not running"})
    args = dict(args)
    action = str(args.get("action") or "")
    if action == "perform_action":
        action = str(args.pop("operation", ""))
    action = ALIASES.get(action, action)
    args["action"] = action
    if "value" in args and "text" not in args:
        args["text"] = args.pop("value")
    if action not in ACTIONS:
        return json.dumps({"error": f"unknown action {action!r}; one of {ACTIONS}"})
    for field, values in (("scope", ("focused", "all")), ("method", ("auto", "gesture", "accessibility"))):
        if args.get(field) is not None and args[field] not in values:
            return json.dumps({"error": f"invalid {field}; one of {values}"})
    if args.get("include_keyboard") is not None and not isinstance(args["include_keyboard"], bool):
        return json.dumps({"error": "invalid include_keyboard; expected boolean"})
    timeout = 40.0 if action in ("state", "screenshot", "list_apps", "list_windows") else 25.0
    fut = asyncio.run_coroutine_threadsafe(ad._phone.run({k: v for k, v in args.items() if v is not None}, timeout), loop)
    try:
        res = fut.result(timeout + 5)
    except Exception as e:
        fut.cancel()
        return json.dumps({"error": f"phone call failed: {e}"})
    return _fmt(res, action)


def check() -> bool:
    return True


# ---------------------------------------------------------------- routes
def routes(r, adapter, j) -> None:
    from . import control as C

    async def _body(request) -> Dict[str, Any]:
        if not request.body_exists:
            return {}
        try:
            b = await request.json()
            return b if isinstance(b, dict) else {}
        except Exception:
            return {}

    async def h_status(request):
        st = adapter._phone.armed()
        dev = request["device"]["id"]
        return j({"armed": bool(st), "this": bool(st and st["device"] == dev), "since": (st or {}).get("since"),
                  "session": st.get("session") if st and st["device"] == dev else None})

    async def h_arm(request):
        b = await _body(request)
        on = bool(b.get("on"))
        dev = request["device"]["id"]
        if on:
            # Arming hands the agent this phone: same biometric signature as other power-widening settings.
            if not C.guard_ok(request, "phone", "arm", True):
                return j({"ok": False, "error": "biometric confirmation required", "need_sig": True}, 403)
            adapter._phone.arm(dev)
        else:
            adapter._phone.disarm(dev, str(b["session"]) if b.get("session") else None)
        st = adapter._phone.armed()
        return j({"ok": True, "armed": bool(st), "this": bool(st and st["device"] == dev),
                  "session": st.get("session") if st and st["device"] == dev else None})

    async def h_result(request):
        b = await _body(request)
        if not adapter._phone.resolve(request["device"]["id"], b):
            return j({"ok": False, "error": "no such pending command for this device"}, 409)
        return j({"ok": True})

    async def h_pending(request):
        st = adapter._phone.armed()
        if not st or st["device"] != request["device"]["id"]:
            return j({"cmds": []})
        return j({"cmds": adapter._phone.commands(request["device"]["id"])})

    r.add_get("/v1/phone", h_status)
    r.add_get("/v1/phone/pending", h_pending)
    r.add_post("/v1/phone/arm", h_arm)
    r.add_post("/v1/phone/result", h_result)
