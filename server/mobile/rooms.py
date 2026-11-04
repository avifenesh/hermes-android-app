"""Gateway-hosted Group Chat rooms through Hermes' canonical group controls.

A room is not a mobile chat. It lives in Hermes' hosted-room store and runs on Hermes' durable room
driver; the phone is one more client of the same rooms Hermes Desktop shows. Every read and write
goes through ``AuthorityConnection.dispatch`` (``gateway/session_group_controls.py``), so room
ownership, roster validation, idempotent sends, attachment storage and member turns stay Hermes' own.

Rooms are owned per principal. The phone acts for the host's local owner: the principal Hermes gives
a same-user local client such as Desktop (``uid:<n>``, ``gateway/control_socket.py``). Rooms created
on either surface therefore appear on both. The plugin keeps only a per-room read cursor in its own
store, and polls room state for the SSE stream while a phone is listening.
"""
from __future__ import annotations

import asyncio
import base64
import importlib.util
import itertools
import logging
import mimetypes
import os
import re
import time
import uuid
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

from . import sig as SG
from . import store as S

logger = logging.getLogger(__name__)

# Never session:create: that would bind this connection as a native legacy-history importer.
CAPABILITIES = ("session:read", "session:submit", "session:control", "session:approve")
MESSAGE_KINDS = frozenset({"message", "message.user", "message.member"})
NOTICE_KINDS = frozenset({
    "turn.failed", "turn.cancelled", "turn.deferred", "member.unavailable", "room.stop_requested",
    "room.disbanded", "room.renamed", "authority.lost"})
TAIL = 40               # displayable events kept per room for previews and unread counts
TAIL_WINDOW = 200       # raw log events read to rebuild a tail
POLL_SECONDS = 2.0
ACTIVE_SECONDS = 600    # rooms that changed this recently also get their driver state polled
MAX_PAGE = 200
MAX_ATTACHMENTS = 8
_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_STATUS = {
    "invalid_params": 400, "permission_denied": 403, "profile_mismatch": 404, "room_history_expired": 410,
    "authority_conflict": 409, "stale_generation": 409, "unknown_execution": 409, "session_busy": 409,
    "unsupported_operation": 409, "runtime_coordination_required": 503, "runtime_draining": 503,
    "runtime_retired": 503, "storage_unavailable": 503}
_MESSAGES = {
    "invalid_params": "Hermes refused the request",
    "permission_denied": "Group chat not found",
    "profile_mismatch": "Group chat not found",
    "room_history_expired": "This group chat was deleted",
    "runtime_coordination_required": "The gateway's group chat worker is not running",
    "runtime_draining": "The gateway is restarting",
    "storage_unavailable": "Group chat storage is unavailable",
    "stale_generation": "That action is no longer pending",
    "unknown_execution": "That turn's outcome is unknown; discard it instead",
    "session_busy": "That member is still working",
}


class RoomError(Exception):
    def __init__(self, message: str, status: int = 400, reason: str = "", detail: str = ""):
        super().__init__(message)
        self.message, self.status, self.reason, self.detail = message, status, reason, detail

    def body(self) -> Dict[str, Any]:
        out: Dict[str, Any] = {"error": self.message}
        if self.reason:
            out["reason"] = self.reason
        if self.detail:
            out["detail"] = self.detail
        return out


def owner_subject() -> Optional[str]:
    """The principal Hermes gives a same-user local client (``control_socket._posix_peer_subject``)."""
    getuid = getattr(os, "getuid", None)
    return f"uid:{getuid()}" if getuid is not None else None


def core_supported() -> bool:
    try:
        return all(importlib.util.find_spec(m) is not None for m in (
            "gateway.hosted_rooms", "gateway.session_group_controls", "gateway.session_controls"))
    except (ImportError, ValueError):
        return False


def identifier(value: Any, label: str) -> str:
    text = str(value or "").strip()
    if not _ID_RE.fullmatch(text):
        raise RoomError(f"invalid {label}", 400)
    return text


def member_view(m: Dict[str, Any]) -> Dict[str, Any]:
    mid = str(m.get("member_id") or m.get("profile") or "")
    return {"id": mid, "profile": str(m.get("profile") or mid), "handle": str(m.get("handle") or mid),
            "name": str(m.get("display_name") or m.get("handle") or mid)}


def _attachment_view(a: Dict[str, Any]) -> Dict[str, Any]:
    return {k: a.get(k) for k in ("attachment_id", "kind", "name", "mime", "size") if a.get(k) is not None}


def project_event(ev: Dict[str, Any], roster: Dict[str, Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    """One room-log event as the phone renders it, or None for driver bookkeeping (turn.started, ...)."""
    kind = str(ev.get("kind") or "")
    payload = ev.get("payload") if isinstance(ev.get("payload"), dict) else {}
    actor = ev.get("actor") if isinstance(ev.get("actor"), dict) else {}
    base = {"seq": int(ev.get("seq") or 0), "id": str(ev.get("event_id") or ""), "ts": float(ev.get("created_at") or 0)}
    if kind in MESSAGE_KINDS:
        member = str(actor.get("id") or "") if actor.get("kind") == "member" else ""
        known = roster.get(member) or {}
        out = {**base, "kind": "message", "role": "member" if member else "user", "member": member,
               "name": str(actor.get("display_name") or known.get("name") or member) if member else "",
               "text": str(payload.get("text") or payload.get("content") or ""),
               "thread": str(payload.get("thread_id") or "")}
        atts = [_attachment_view(a) for a in payload.get("attachments") or [] if isinstance(a, dict)]
        if atts:
            out["attachments"] = atts
        return out
    bounded = kind == "room.activity" and payload.get("status") == "bounded"
    if kind not in NOTICE_KINDS and not bounded:
        return None
    member = str(payload.get("member_id") or "")
    detail = payload.get("error") or payload.get("reason") or payload.get("reason_code") or payload.get("name") or ""
    return {**base, "kind": "notice", "notice": "room.bounded" if bounded else kind, "member": member,
            "name": str((roster.get(member) or {}).get("name") or member), "detail": str(detail)[:400]}


def driver_view(status: Optional[Dict[str, Any]]) -> Dict[str, Any]:
    if not isinstance(status, dict):
        return {"running": False, "working": False, "blocked": False, "members": [], "pending": []}
    pending = []
    for a in status.get("pending_actions") or []:
        if not isinstance(a, dict) or a.get("kind") not in ("approval", "retry", "discard") or not a.get("member_id"):
            continue
        item = {"kind": a["kind"], "member": str(a["member_id"]), "task_id": str(a.get("task_id") or ""),
                "generation": int(a.get("execution_generation") or 0)}
        approval = a.get("approval") if isinstance(a.get("approval"), dict) else {}
        if a["kind"] == "approval":
            item.update(request_id=str(a.get("request_id") or approval.get("request_id") or ""),
                        command=str(approval.get("command") or "")[:4000],
                        description=str(approval.get("description") or "")[:1000])
        pending.append(item)
    members = [{"id": str(m.get("member_id") or ""), "status": str(m.get("status") or "")}
               for m in status.get("member_activity") or [] if isinstance(m, dict)]
    return {"running": bool(status.get("running")), "working": bool(status.get("working")),
            "blocked": bool(status.get("blocked")), "members": members, "pending": pending}


class _Sink:
    """Transport for the room connection: group controls answer inline and push no session events."""
    desktop_local = False

    def write(self, frame: dict) -> bool:
        return True

    def close(self) -> None:
        pass


class Rooms:
    def __init__(self, adapter, *, authority: Optional[Callable[[], Any]] = None,
                 connection_factory: Optional[Callable[[Any], Any]] = None):
        self.ad = adapter
        self._authority_fn = authority
        self._factory = connection_factory
        self._conn = None
        self._conn_authority = None
        self._ids = itertools.count(1)
        self._tails: Dict[str, Dict[str, Any]] = {}
        self._working: Dict[str, List[Dict[str, Any]]] = {}
        self._emitted: Dict[str, Any] = {}
        self._tail_lock = asyncio.Lock()
        self._task: Optional[asyncio.Task] = None

    # ------------------------------------------------------------ core access
    def authority(self):
        if self._authority_fn is not None:
            return self._authority_fn()
        runner = getattr(self.ad, "gateway_runner", None)
        if runner is None:
            return None
        try:
            from gateway.session_authorities import active_authority
            return active_authority(runner)
        except Exception:
            return None

    def supported(self) -> bool:
        return core_supported() and owner_subject() is not None and self.authority() is not None

    def _connection(self):
        authority = self.authority()
        if authority is None or owner_subject() is None or not core_supported():
            raise RoomError("Group chats need a Hermes gateway with hosted rooms", 404, "unsupported")
        if self._conn is not None and self._conn_authority is authority:
            return self._conn
        if self._factory is not None:
            conn = self._factory(authority)
        else:
            from gateway.session_controls import AuthorityConnection
            identity = {"user_id": owner_subject(), "provider": "local", "profile_id": authority.profile_id,
                        "instance_id": authority.instance_id, "capabilities": list(CAPABILITIES),
                        "native_bootstrap": True, "profile_scope": "host"}
            conn = AuthorityConnection(authority, _Sink(), identity)
        self._conn, self._conn_authority = conn, authority
        return conn

    async def call(self, method: str, params: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        conn = self._connection()
        params = dict(params or {})
        try:
            resp = await conn.dispatch({"jsonrpc": "2.0", "id": f"mobile-rooms-{next(self._ids)}",
                                        "method": method, "params": params})
        except RoomError:
            raise
        except RuntimeError as e:  # e.g. "room work is still stopping; retry deletion after Stop completes"
            raise RoomError(str(e) or "Hermes refused the request", 409, "conflict")
        except Exception as e:
            logger.warning("[mobile] %s failed: %s", method, e)
            raise RoomError("Group chat request failed", 500, "error")
        if not isinstance(resp, dict):
            raise RoomError("Group chat request failed", 502, "error")
        if "error" in resp:
            err = resp.get("error") or {}
            data = err.get("data") if isinstance(err.get("data"), dict) else {}
            reason = str(data.get("reason") or err.get("message") or "error")
            if err.get("code") == -32601:
                raise RoomError("This Hermes version does not support that group chat action", 501, "unsupported")
            status = _STATUS.get(reason, 400)
            if reason == "permission_denied" and "room_id" in params:
                status = 404  # another principal's room, or none: the phone cannot tell them apart
            message = _MESSAGES.get(reason, str(err.get("message") or "Hermes refused the request"))
            raise RoomError(message, status, reason, str(data.get("detail") or ""))
        return resp.get("result") or {}

    # ------------------------------------------------------------ projections
    def _read_seq(self, room_id: str) -> Optional[int]:
        v = self.ad.store.kv_get("room_read:" + room_id)
        try:
            return int(v) if v is not None else None
        except (TypeError, ValueError):
            return None

    def _set_read(self, room_id: str, seq: int) -> int:
        cur = self._read_seq(room_id)
        if cur is None or seq > cur:
            self.ad.store.kv_put("room_read:" + room_id, int(seq))
            return seq
        return cur

    async def _log_window(self, room_id: str, start: int, end: int) -> List[Dict[str, Any]]:
        """Raw events with ``start < seq <= end``, following core's byte-bounded pages."""
        out: List[Dict[str, Any]] = []
        cursor = start
        while cursor < end:
            page = await self.call("groups.log", {"room_id": room_id, "since_seq": cursor,
                                                  "limit": min(500, end - cursor)})
            events = [e for e in page.get("events") or [] if int(e.get("seq") or 0) <= end]
            if not events:
                break
            out += events
            cursor = int(events[-1]["seq"])
        return out

    async def _tail(self, room: Dict[str, Any]) -> Dict[str, Any]:
        rid, latest = str(room["room_id"]), int(room.get("latest_seq") or 0)
        roster = {m["id"]: m for m in map(member_view, room.get("members") or [])}
        async with self._tail_lock:
            cached = self._tails.get(rid)
            if cached and cached["latest"] == latest and cached["roster"] == roster:
                return cached
            start = cached["latest"] if cached and cached["roster"] == roster and cached["latest"] <= latest else 0
            start = max(start, latest - TAIL_WINDOW)
            raw = await self._log_window(rid, start, latest)
            fresh = [p for p in (project_event(e, roster) for e in raw) if p is not None]
            events = (cached["events"] if cached and start > 0 and cached["roster"] == roster else []) + fresh
            tail = {"latest": latest, "roster": roster, "events": events[-TAIL:], "from": start}
            self._tails[rid] = tail
            return tail

    async def summary(self, room: Dict[str, Any]) -> Dict[str, Any]:
        rid, latest = str(room["room_id"]), int(room.get("latest_seq") or 0)
        tail = await self._tail(room)
        read = self._read_seq(rid)
        if read is None:  # First sight: history written before the phone saw the room is not news.
            read = self._set_read(rid, latest)
        messages = [e for e in tail["events"] if e["kind"] == "message"]
        last = messages[-1] if messages else None
        unread = sum(1 for e in messages if e["role"] == "member" and e["seq"] > read)
        out = {"id": rid, "name": str(room.get("name") or "Group chat"),
               "members": [member_view(m) for m in room.get("members") or []],
               "latest_seq": latest, "read_seq": read, "unread": unread,
               "updated": float(room.get("updated_at") or 0), "created": float(room.get("created_at") or 0),
               "working": [m["id"] for m in self._working.get(rid, []) if m.get("status") != "queued"]}
        if last:
            text = last["text"].strip() or ("Attachment" if last.get("attachments") else "")
            out["preview"] = {"seq": last["seq"], "role": last["role"], "name": last["name"],
                              "text": " ".join(text.split())[:160], "ts": last["ts"]}
        return out

    async def _rooms(self) -> List[Dict[str, Any]]:
        rooms: List[Dict[str, Any]] = []
        offset = 0
        while True:
            page = await self.call("groups.list", {"limit": 100, "offset": offset})
            rooms += [r for r in page.get("rooms") or [] if isinstance(r, dict) and not r.get("disbanded_at")]
            nxt = page.get("next_offset")
            if not isinstance(nxt, int) or nxt <= offset or len(rooms) > 1000:
                return rooms
            offset = nxt

    # ------------------------------------------------------------ API
    async def listing(self) -> Dict[str, Any]:
        limits = {"max_members": 6, "min_members": 2}
        if not self.supported():  # an empty answer, so the self-check and older clients see no failure
            return {"supported": False, "rooms": [], "driver": False, "bots": [], **limits}
        caps = await self.call("groups.capabilities", {})
        rooms = [await self.summary(r) for r in await self._rooms()]
        rooms.sort(key=lambda r: r["updated"], reverse=True)
        return {"supported": True, "rooms": rooms, "driver": bool(caps.get("driver")),
                "bots": await self.bots(caps), **limits}

    async def bots(self, caps: Optional[Dict[str, Any]] = None) -> List[Dict[str, Any]]:
        """Profiles a new room may seat (``seatable_profiles``), with their Bot names."""
        caps = caps if caps is not None else await self.call("groups.capabilities", {})
        seatable = [str(p) for p in caps.get("seatable_profiles") or []]
        names: Dict[str, str] = {}
        try:
            for row in (await self.call("profiles.list", {"include_sessions": False})).get("profiles") or []:
                if isinstance(row, dict) and row.get("name"):
                    names.setdefault(str(row["name"]), str(row.get("display_name") or row["name"]))
        except RoomError:
            pass
        return [{"profile": p, "name": names.get(p) or p} for p in seatable]

    async def detail(self, room_id: str) -> Dict[str, Any]:
        state = await self.call("groups.state", {"room_id": identifier(room_id, "room id")})
        room = state.get("room") or {}
        driver = driver_view(state.get("driver_status"))
        self._working[str(room.get("room_id"))] = driver["members"]
        return {"room": await self.summary(room), "driver": driver}

    async def history(self, room_id: str, *, before: Optional[int] = None, after: Optional[int] = None,
                      limit: int = 60) -> Dict[str, Any]:
        rid = identifier(room_id, "room id")
        if not 1 <= limit <= MAX_PAGE:
            raise RoomError(f"limit must be between 1 and {MAX_PAGE}")
        state = await self.call("groups.state", {"room_id": rid})
        room = state.get("room") or {}
        latest = int(room.get("latest_seq") or 0)
        roster = {m["id"]: m for m in map(member_view, room.get("members") or [])}
        if after is not None:
            if after < 0:
                raise RoomError("invalid page cursor")
            start, end = min(after, latest), min(latest, min(after, latest) + limit)
        else:
            end = latest if before is None else max(0, min(before - 1, latest))
            start = max(0, end - limit)
        raw = await self._log_window(rid, start, end)
        events = [p for p in (project_event(e, roster) for e in raw) if p is not None]
        return {"room_id": rid, "events": events, "latest_seq": latest, "from_seq": start, "to_seq": end,
                "before": start + 1 if start > 0 else None, "has_more_after": end < latest,
                "members": list(roster.values())}

    async def send(self, room_id: str, body: Dict[str, Any]) -> Dict[str, Any]:
        rid = identifier(room_id, "room id")
        client_id = identifier(body.get("client_id") or uuid.uuid4().hex, "client id")
        text = str(body.get("text") or "")
        if len(text.encode("utf-8")) > 64 * 1024:
            raise RoomError("Message is too long")
        payload: Dict[str, Any] = {"text": text.strip(), "thread_id": identifier(body.get("thread_id") or client_id, "thread id")}
        atts = body.get("attachments") or []
        if not isinstance(atts, list) or len(atts) > MAX_ATTACHMENTS:
            raise RoomError(f"At most {MAX_ATTACHMENTS} attachments per message")
        if atts:
            payload["attachments"] = [await self._upload(rid, f"{client_id}.a{i}", a) for i, a in enumerate(atts)]
        if not payload["text"] and not atts:
            raise RoomError("empty message")
        result = await self.call("groups.send", {"room_id": rid, "event_id": client_id, "payload": payload})
        event = result.get("event") or {}
        state = await self.call("groups.state", {"room_id": rid})
        roster = {m["id"]: m for m in map(member_view, (state.get("room") or {}).get("members") or [])}
        projected = project_event(event, roster) if event else None
        if projected:
            self._set_read(rid, projected["seq"])
        await self._announce(state.get("room") or {})
        return {"event": projected, "accepted": bool(result.get("accepted")), "client_id": client_id}

    async def _upload(self, room_id: str, upload_id: str, a: Any) -> Dict[str, Any]:
        if not isinstance(a, dict):
            raise RoomError("invalid attachment")
        name = Path(str(a.get("id") or a.get("media") or "")).name
        path = S.data_dir() / "media" / name
        if not name or not path.is_file():
            raise RoomError("Attachment is no longer on the gateway; attach it again")
        data = await asyncio.to_thread(path.read_bytes)
        file_name = Path(str(a.get("file_name") or a.get("name") or name)).name[:200] or name
        mime = str(a.get("mime") or mimetypes.guess_type(file_name)[0] or "application/octet-stream")
        kind = "image" if mime.startswith("image/") else "pdf" if mime == "application/pdf" else "file"
        stored = await self.call("groups.attachment.upload", {
            "room_id": room_id, "upload_id": upload_id, "kind": kind, "name": file_name, "mime": mime,
            "data_base64": base64.b64encode(data).decode("ascii")})
        # A message manifest carries exactly these fields; the stored record has more.
        return {k: stored.get(k) for k in ("attachment_id", "kind", "name", "size", "mime")}

    async def download(self, room_id: str, event_id: str, attachment_id: str) -> Dict[str, Any]:
        saved = await self.call("groups.attachment.download", {
            "room_id": identifier(room_id, "room id"), "event_id": str(event_id or "")[:200],
            "attachment_id": str(attachment_id or "")[:80]})
        try:
            data = base64.b64decode(str(saved.get("data_base64") or ""), validate=True)
        except ValueError:
            raise RoomError("attachment is unreadable", 502)
        return {"data": data, "mime": str(saved.get("mime") or "application/octet-stream"),
                "name": Path(str(saved.get("name") or "attachment")).name}

    async def create(self, body: Dict[str, Any]) -> Dict[str, Any]:
        name = str(body.get("name") or "").strip()
        if not name or len(name) > 200:
            raise RoomError("A group chat needs a name")
        profiles = body.get("members")
        if not isinstance(profiles, list) or not all(isinstance(p, str) for p in profiles):
            raise RoomError("members must be a list of Bot profiles")
        if not 2 <= len(profiles) <= 6 or len({p.casefold() for p in profiles}) != len(profiles):
            raise RoomError("Pick two to six different Bots")
        names = {b["profile"]: b["name"] for b in await self.bots()}
        roster = []
        for p in profiles:
            member = {"member_id": p, "profile": p, "handle": p, "target": {"kind": "local", "profile": p}}
            if names.get(p) and names[p] != p:
                member["display_name"] = names[p][:200]
            roster.append(member)
        room_id = identifier(body.get("room_id") or str(uuid.uuid4()), "room id")
        result = await self.call("groups.create", {"room_id": room_id, "name": name, "members": roster})
        room = result.get("room") or {}
        summary = await self.summary(room)
        self._emit(summary)
        return {"room": summary}

    async def rename(self, room_id: str, name: str) -> Dict[str, Any]:
        name = str(name or "").strip()
        if not name or len(name) > 200:
            raise RoomError("A group chat needs a name")
        result = await self.call("groups.rename", {"room_id": identifier(room_id, "room id"),
                                                   "event_id": "rename-" + uuid.uuid4().hex, "name": name})
        summary = await self.summary(result.get("room") or {})
        self._emit(summary)
        return {"room": summary}

    async def disband(self, room_id: str) -> Dict[str, Any]:
        rid = identifier(room_id, "room id")
        result = await self.call("groups.disband", {"room_id": rid, "cancel_id": "mobile-" + uuid.uuid4().hex})
        self._forget(rid)
        return {"ok": True, "tombstone": result.get("tombstone") or {}}

    async def stop(self, room_id: str) -> Dict[str, Any]:
        result = await self.call("groups.stop", {"room_id": identifier(room_id, "room id"),
                                                 "cancel_id": "mobile-" + uuid.uuid4().hex})
        return {"ok": True, "cancelled": int(result.get("cancelled") or 0)}

    async def act(self, room_id: str, body: Dict[str, Any]) -> Dict[str, Any]:
        rid = identifier(room_id, "room id")
        kind = str(body.get("kind") or "")
        params = {"room_id": rid, "member_id": identifier(body.get("member"), "member"),
                  "task_id": str(body.get("task_id") or "")[:200],
                  "execution_generation": int(body.get("generation") or 0)}
        if kind == "approval":
            params.update(choice=str(body.get("choice") or ""), request_id=str(body.get("request_id") or "")[:200])
            return await self.call("groups.approve", params)
        if kind in ("retry", "discard"):
            return await self.call("groups." + kind, params)
        raise RoomError("unknown action")

    async def mark_read(self, room_id: str, seq: int) -> Dict[str, Any]:
        state = await self.call("groups.state", {"room_id": identifier(room_id, "room id")})
        room = state.get("room") or {}
        read = self._set_read(str(room.get("room_id")), max(0, min(int(seq), int(room.get("latest_seq") or 0))))
        await self._announce(room)
        return {"ok": True, "read_seq": read}

    # ------------------------------------------------------------ live updates
    def _emit(self, summary: Dict[str, Any]) -> None:
        key = (summary["latest_seq"], summary["name"], summary["unread"], summary["read_seq"],
               tuple(summary["working"]), tuple(m["id"] for m in summary["members"]))
        self._emitted[summary["id"]] = key
        self.ad._emit({"type": "room", "room": summary})

    def _forget(self, room_id: str) -> None:
        self._tails.pop(room_id, None)
        self._working.pop(room_id, None)
        self._emitted.pop(room_id, None)
        self.ad._emit({"type": "room_removed", "room_id": room_id})

    async def _announce(self, room: Dict[str, Any]) -> None:
        if room.get("room_id"):
            self._emit(await self.summary(room))

    async def tick(self) -> None:
        now = time.time()
        rooms = await self._rooms()
        for room in rooms:
            rid = str(room["room_id"])
            if float(room.get("updated_at") or 0) > now - ACTIVE_SECONDS or self._working.get(rid):
                try:
                    state = await self.call("groups.state", {"room_id": rid})
                    self._working[rid] = driver_view(state.get("driver_status"))["members"]
                except RoomError:
                    self._working.pop(rid, None)
            summary = await self.summary(room)
            key = (summary["latest_seq"], summary["name"], summary["unread"], summary["read_seq"],
                   tuple(summary["working"]), tuple(m["id"] for m in summary["members"]))
            if self._emitted.get(rid) != key:
                self._emit(summary)
        for gone in set(self._emitted) - {str(r["room_id"]) for r in rooms}:
            self._forget(gone)

    async def _loop(self) -> None:
        while True:
            await asyncio.sleep(POLL_SECONDS)
            # Only while a phone listens, and only once the gateway's session runtime is up.
            if not getattr(self.ad, "_subs", None) or not self.supported():
                continue
            try:
                await self.tick()
            except asyncio.CancelledError:
                raise
            except RoomError as e:
                logger.debug("[mobile] room poll: %s", e.message)
            except Exception as e:
                logger.debug("[mobile] room poll failed: %s", e)

    def start(self) -> None:
        if self._task is None:
            self._task = asyncio.get_running_loop().create_task(self._loop())

    def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()
            self._task = None


def routes(r, rooms: Rooms, j) -> None:
    from aiohttp import web

    def fail(e: RoomError):
        return j(e.body(), e.status)

    async def body(request) -> Dict[str, Any]:
        try:
            b = await request.json() if request.body_exists else {}
        except Exception:
            b = {}
        return b if isinstance(b, dict) else {}

    def guarded(fn):
        async def handler(request):
            try:
                return await fn(request)
            except RoomError as e:
                return fail(e)
            except (TypeError, ValueError):
                return fail(RoomError("invalid request"))
        return handler

    @guarded
    async def h_list(request):
        return j(await rooms.listing())

    @guarded
    async def h_create(request):
        return j(await rooms.create(await body(request)))

    @guarded
    async def h_detail(request):
        return j(await rooms.detail(request.match_info["rid"]))

    @guarded
    async def h_patch(request):
        return j(await rooms.rename(request.match_info["rid"], (await body(request)).get("name")))

    @guarded
    async def h_delete(request):
        return j(await rooms.disband(request.match_info["rid"]))

    @guarded
    async def h_events(request):
        q = request.query
        before = int(q["before"]) if q.get("before") else None
        after = int(q["after"]) if q.get("after") else None
        return j(await rooms.history(request.match_info["rid"], before=before, after=after,
                                     limit=int(q.get("limit") or 60)))

    @guarded
    async def h_send(request):
        return j(await rooms.send(request.match_info["rid"], await body(request)))

    @guarded
    async def h_read(request):
        return j(await rooms.mark_read(request.match_info["rid"], int((await body(request)).get("seq") or 0)))

    @guarded
    async def h_stop(request):
        return j(await rooms.stop(request.match_info["rid"]))

    @guarded
    async def h_action(request):
        b = await body(request)
        rid = request.match_info["rid"]
        if b.get("kind") == "approval":
            choice = str(b.get("choice") or "")
            if choice not in ("once", "deny"):
                return j({"error": "room approvals answer once or deny"}, 400)
            # Same protected key as Inbox approvals, bound to this room, member and request.
            if choice != "deny":
                msg = (f"approve\n{rid}:{b.get('member')}:{b.get('request_id')}\n{choice}\n"
                       f"{request.headers.get('X-HM-Ts', '')}").encode()
                if not SG.approval_ok(request, msg):
                    return j({"error": "biometric signature required", "need_sig": True}, 403)
        return j(await rooms.act(rid, b))

    @guarded
    async def h_file(request):
        f = await rooms.download(request.match_info["rid"], request.match_info["eid"], request.match_info["aid"])
        safe = f["name"].replace('"', "")
        return web.Response(body=f["data"], content_type=f["mime"].split(";")[0] or "application/octet-stream",
                            headers={"Content-Disposition": f'attachment; filename="{safe}"'})

    r.add_get("/v1/rooms", h_list)
    r.add_post("/v1/rooms", h_create)
    r.add_get("/v1/rooms/{rid}", h_detail)
    r.add_patch("/v1/rooms/{rid}", h_patch)
    r.add_delete("/v1/rooms/{rid}", h_delete)
    r.add_get("/v1/rooms/{rid}/events", h_events)
    r.add_post("/v1/rooms/{rid}/messages", h_send)
    r.add_post("/v1/rooms/{rid}/read", h_read)
    r.add_post("/v1/rooms/{rid}/stop", h_stop)
    r.add_post("/v1/rooms/{rid}/actions", h_action)
    r.add_get("/v1/rooms/{rid}/files/{eid}/{aid}", h_file)
