"""Live cross-session conversations through Hermes' canonical session authority.

The phone is another viewer of the real session, not a mirrored chat that takes the
session over. History pages come from the same display projection as the desktop;
submissions, approvals, and clarify answers use the same native RPCs.
"""
from __future__ import annotations

import asyncio
from collections import OrderedDict
import itertools
import logging
from pathlib import Path
from typing import Any, Callable, Optional
import uuid

from . import store as S

logger = logging.getLogger(__name__)
MAX_VIEWS = 12


class LiveSessionError(Exception):
    def __init__(self, reason: str, status: int = 400):
        super().__init__(reason)
        self.reason = reason
        self.status = status


class LiveTransport:
    """Bounded fanout transport that relays native events to the mobile SSE stream."""

    def __init__(self, owner: "LiveSessions", authority):
        self.owner = owner
        self.authority = authority
        self.desktop_local = False
        self.closed = False

    def write(self, frame: dict) -> bool:
        if self.closed:
            return False
        params = frame.get("params") if isinstance(frame, dict) else None
        if not isinstance(params, dict) or frame.get("method") != "event":
            return True
        loop = self.owner._loop
        if loop is None or loop.is_closed():
            return False
        event = dict(params)
        sid = str(event.get("session_id") or "")
        requested = self.owner._aliases.get((id(self.authority), sid), sid)
        try:
            loop.call_soon_threadsafe(self.owner.adapter._emit, {
                "type": "session_event", "session_id": requested, "event": event})
            return True
        except RuntimeError:
            return False

    def close(self) -> None:
        self.closed = True


class LiveView:
    def __init__(self, requested_id: str, authority, connection, transport):
        self.requested_id = requested_id
        self.authority = authority
        self.connection = connection
        self.transport = transport
        self.session_id = requested_id
        self.subscription_id: Optional[str] = None
        self.display: list[dict] = []
        self.running = False
        self.generation = 0
        self.prompts: list[dict] = []
        self.pending: list[dict] = []
        self.info: dict = {}
        self.loaded = False


class LiveSessions:
    def __init__(self, adapter, *, authorities: Optional[Callable[[], Any]] = None,
                 connection_factory: Optional[Callable[[Any], Any]] = None):
        self.adapter = adapter
        self._authorities = authorities
        self._connection_factory = connection_factory
        self._views: OrderedDict[str, LiveView] = OrderedDict()
        self._connections: dict[int, Any] = {}
        self._aliases: dict[tuple[int, str], str] = {}
        self._ids = itertools.count(1)
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._lock = asyncio.Lock()

    def _authority(self, sid: str):
        if self._authorities is not None:
            authority = self._authorities()
            if authority is None:
                raise LiveSessionError("session runtime unavailable", 503)
            return authority
        from gateway.session_authorities import active_authority, authority_for_home
        runner = self.adapter.gateway_runner
        home = S.hermes_home()
        if sid.startswith("profile:"):
            # Reserved for a possible explicit prefix; the session list uses '<profile>:<id>'.
            pass
        if ":" in sid:
            profile, _ = sid.split(":", 1)
            root = home.parent.parent if home.parent.name == "profiles" else home
            home = root / "profiles" / profile
        authority = authority_for_home(runner, home) or (active_authority(runner) if home == S.hermes_home() else None)
        if authority is None:
            raise LiveSessionError("session profile is not running", 404)
        return authority

    def _connect(self, authority):
        key = id(authority)
        if key in self._connections:
            return self._connections[key]
        transport = LiveTransport(self, authority)
        if self._connection_factory is not None:
            connection = self._connection_factory(authority)
            connection.transport = transport
        else:
            from gateway.session_controls import AuthorityConnection
            identity = {
                "user_id": "mobile", "provider": "local",
                "profile_id": authority.profile_id, "instance_id": authority.instance_id,
                "capabilities": ["session:read", "session:submit",
                                 "session:approve", "session:respond"],
                "native_bootstrap": True,
            }
            # Trusted in-process transport gate: required to attach another surface's
            # session. The explicit capability list above still excludes session control.
            connection = AuthorityConnection(authority, transport, identity, operator=True)
        self._connections[key] = connection
        return connection

    async def _dispatch(self, view: LiveView, method: str, params: dict) -> dict:
        response = await view.connection.dispatch({
            "jsonrpc": "2.0", "id": next(self._ids), "method": method, "params": params})
        if "error" in response:
            error = response["error"] or {}
            data = error.get("data") or {}
            reason = str(data.get("reason") or error.get("message") or "session request failed")
            status = 404 if reason in {"not_found", "profile_mismatch"} else 409 if reason in {
                "session_busy", "session_superseded", "revision_conflict", "stale_generation",
                "admission_conflict"} else 403 if reason == "permission_denied" else 503 if reason in {
                "runtime_draining", "runtime_retired", "storage_unavailable"} else 400
            raise LiveSessionError(reason, status)
        return response.get("result") or {}

    async def _view(self, sid: str) -> LiveView:
        if not sid or len(sid) > 600:
            raise LiveSessionError("invalid session id")
        if sid in self._views:
            view = self._views.pop(sid)
            self._views[sid] = view
            return view
        authority = self._authority(sid)
        connection = self._connect(authority)
        view = LiveView(sid, authority, connection, connection.transport)
        self._views[sid] = view
        if len(self._views) > MAX_VIEWS:
            _, old = self._views.popitem(last=False)
            await self._detach(old)
        return view

    async def _resume(self, view: LiveView) -> None:
        result = await self._dispatch(view, "session.resume", {
            "session_id": view.session_id, "display_projection": True})
        view.session_id = str(result.get("session_id") or view.session_id)
        view.subscription_id = result.get("subscription_id")
        view.display = list(result.get("display_messages") or [])
        view.running = bool(result.get("running"))
        view.generation = int(result.get("execution_generation") or 0)
        view.prompts = [dict(prompt) for prompt in (result.get("prompts") or [])]
        for prompt in view.prompts:
            prompt["generation"] = int(prompt.get("generation") or prompt.get("execution_generation") or 0)
        view.pending = list(result.get("pending") or [])
        view.info = dict(result.get("info") or {})
        view.loaded = True
        self._aliases[(id(view.authority), view.session_id)] = view.requested_id

    async def _detach(self, view: LiveView) -> None:
        self._aliases.pop((id(view.authority), view.session_id), None)
        if view.subscription_id:
            try:
                await self._dispatch(view, "session.detach", {
                    "session_id": view.session_id, "subscription_id": view.subscription_id})
            except LiveSessionError:
                pass
        view.subscription_id = None

    @staticmethod
    def _page(view: LiveView, before: Optional[int], limit: int) -> dict:
        end = len(view.display) if before is None else min(before, len(view.display))
        start = max(0, end - limit)
        messages = []
        for position in range(start, end):
            message = dict(view.display[position])
            message["id"] = str(message.get("row_id") or f"{view.session_id}:{position}")
            message["position"] = position
            messages.append(message)
        live = view.authority.sessions.get(view.session_id)
        read_only = bool(getattr(live, "superseded", False))
        return {
            "session_id": view.session_id, "messages": messages,
            "nextBefore": start if start > 0 else None, "hasMore": start > 0,
            "running": view.running, "generation": view.generation,
            "prompts": view.prompts, "pending": view.pending,
            "readOnly": read_only,
            "info": {key: view.info[key] for key in ("title", "model", "source", "profile_name") if view.info.get(key) is not None},
        }

    async def messages(self, sid: str, *, before: Optional[int] = None, limit: int = 40,
                       refresh: bool = False) -> dict:
        if not 1 <= limit <= 100:
            raise LiveSessionError("limit must be between 1 and 100")
        if before is not None and before < 0:
            raise LiveSessionError("invalid page cursor")
        if self._loop is None:
            self._loop = asyncio.get_running_loop()
        async with self._lock:
            view = await self._view(sid)
            # Older pages read the same immutable snapshot, so live appends never move a cursor.
            if refresh or not view.loaded:
                await self._resume(view)
            return self._page(view, before, limit)

    async def send(self, sid: str, text: str, *, submission_id: str = "") -> dict:
        text = text.strip()
        if not text:
            raise LiveSessionError("empty message")
        if len(text) > 60000:
            raise LiveSessionError("message is too long")
        async with self._lock:
            view = await self._view(sid)
            if not view.loaded:
                await self._resume(view)
            return await self._dispatch(view, "prompt.submit", {
                "session_id": view.session_id, "text": text, "queued": True,
                "submission_id": submission_id or uuid.uuid4().hex})

    async def respond(self, sid: str, kind: str, prompt_id: str, generation: int,
                      *, choice: str = "", answer: str = "", question_id: str = "") -> dict:
        if kind not in {"approval", "clarify"} or not prompt_id or generation < 1:
            raise LiveSessionError("invalid prompt response")
        async with self._lock:
            view = await self._view(sid)
            if not view.loaded:
                await self._resume(view)
            if kind == "approval":
                if choice not in {"once", "session", "always", "deny"}:
                    raise LiveSessionError("bad choice")
                method, params = "approval.respond", {"choice": choice}
            else:
                if not answer.strip() or len(answer) > 16384:
                    raise LiveSessionError("invalid answer")
                if question_id:
                    return await self._dispatch(view, "clarify.lock", {
                        "session_id": view.session_id, "request_id": prompt_id,
                        "question_id": question_id, "answer": answer})
                method, params = "clarify.respond", {"answer": answer}
            return await self._dispatch(view, method, {
                "session_id": view.session_id, "prompt_id": prompt_id,
                "execution_generation": generation, **params})

    async def close(self, sid: str) -> None:
        async with self._lock:
            view = self._views.pop(sid, None)
            if view is not None:
                await self._detach(view)

    async def close_all(self) -> None:
        async with self._lock:
            self._views.clear()
            self._aliases.clear()
            connections = list(self._connections.values())
            self._connections.clear()
            for connection in connections:
                try:
                    await connection.close()
                except Exception:
                    logger.debug("[mobile] live session connection close failed", exc_info=True)
