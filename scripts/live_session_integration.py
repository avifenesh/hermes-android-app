"""Contract checks for live cross-session conversations (no live gateway or device)."""
from __future__ import annotations

import asyncio
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))
sys.path.insert(0, str(Path.home() / ".hermes/hermes-agent"))

from mobile.live_sessions import LiveSessions  # noqa: E402


class FakeAuthority:
    profile_id = "/fake/default"
    instance_id = "fake-instance"

    def __init__(self):
        self.sessions = {}


class FakeConnection:
    def __init__(self, authority, history):
        self.authority = authority
        self.history = history
        self.calls = []
        self.transport = None

    async def dispatch(self, request):
        method = request["method"]
        params = request["params"]
        self.calls.append((method, params))
        if method == "session.resume":
            sid = params["session_id"]
            messages = list(self.history)
            return {"result": {
                "session_id": sid,
                "display_messages": messages,
                "running": False,
                "execution_generation": 7,
                "prompts": [],
                "pending": [],
                "subscription_id": "sub-1",
            }}
        if method == "prompt.submit":
            return {"result": {"admission_id": "admission-1", "status": "queued", **params}}
        if method in {"approval.respond", "clarify.respond"}:
            return {"result": {"status": "resolved", "prompt_id": params["prompt_id"]}}
        if method == "session.interrupt":
            return {"result": {"execution_state": "idle", "execution_generation": params["execution_generation"]}}
        if method == "session.detach":
            return {"result": {"detached": True}}
        raise AssertionError(method)


class FakeAdapter:
    def __init__(self):
        self.gateway_runner = object()
        self.events = []

    def _emit(self, event):
        self.events.append(event)


def rows(count):
    return [{"role": "user" if i % 2 == 0 else "assistant", "text": f"message {i}", "row_id": i + 1}
            for i in range(count)]


class LiveSessionContract(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.adapter = FakeAdapter()
        self.authority = FakeAuthority()
        self.connection = FakeConnection(self.authority, rows(5))
        self.live = LiveSessions(self.adapter, authorities=lambda: self.authority,
                                 connection_factory=lambda authority: self.connection)
        self.live._loop = asyncio.get_running_loop()

    async def test_pages_are_stable_and_live_refresh_does_not_move_old_cursor(self):
        first = await self.live.messages("cli-1", limit=2)
        self.assertEqual([m["text"] for m in first["messages"]], ["message 3", "message 4"])
        self.assertEqual(first["nextBefore"], 3)
        self.assertEqual([m["position"] for m in first["messages"]], [3, 4])
        self.connection.history.append(rows(6)[-1])
        older = await self.live.messages("cli-1", before=first["nextBefore"], limit=2)
        self.assertEqual([m["text"] for m in older["messages"]], ["message 1", "message 2"])
        self.assertEqual(older["nextBefore"], 1)
        latest = await self.live.messages("cli-1", limit=2, refresh=True)
        self.assertEqual([m["text"] for m in latest["messages"]], ["message 4", "message 5"])
        self.assertEqual(latest["nextBefore"], 4)

    async def test_send_uses_native_admission_not_a_mirror(self):
        await self.live.messages("cli-1", limit=2)
        result = await self.live.send("cli-1", "continue", submission_id="phone-1")
        self.assertEqual(result["admission_id"], "admission-1")
        method, params = self.connection.calls[-1]
        self.assertEqual(method, "prompt.submit")
        self.assertEqual(params, {"session_id": "cli-1", "text": "continue", "queued": True,
                                  "submission_id": "phone-1"})

    async def test_choices_use_the_same_native_controls(self):
        await self.live.messages("cli-1", limit=2)
        await self.live.respond("cli-1", "approval", "prompt-1", 7, choice="once")
        await self.live.respond("cli-1", "clarify", "prompt-2", 7, answer="second")
        methods = [call[0] for call in self.connection.calls[-2:]]
        self.assertEqual(methods, ["approval.respond", "clarify.respond"])

    async def test_native_event_is_forwarded_to_the_phone_stream(self):
        view = await self.live._view("cli-1")
        view.connection.transport.write({"method": "event", "params": {
            "type": "message.delta", "session_id": "cli-1", "payload": {"text": "live"}}})
        await asyncio.sleep(0)
        self.assertEqual(self.adapter.events[-1]["type"], "session_event")
        self.assertEqual(self.adapter.events[-1]["session_id"], "cli-1")
        self.assertEqual(self.adapter.events[-1]["event"]["type"], "message.delta")


if __name__ == "__main__":
    unittest.main(verbosity=2)
