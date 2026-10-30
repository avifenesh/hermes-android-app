"""Self-isolated cron reply regressions; run with Hermes' managed Python.

Exercises real delivery/store/inbound event paths and the gateway's actual
per-turn reply formatter. No gateway, provider, live data or phone required.
"""
import atexit
import asyncio
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, patch

if "hermes_bootstrap" in sys.modules or "hermes_constants" in sys.modules:
    raise RuntimeError("Run directly in a fresh managed-Python process")
# Under the Hermes home, where the managed runtime finds its dependency environment.
SCRATCH = Path.home() / ".hermes/cache/scratch"
SCRATCH.mkdir(parents=True, exist_ok=True)
_HOME = tempfile.TemporaryDirectory(prefix="cron-replies-", dir=SCRATCH)
atexit.register(shutil.rmtree, _HOME.name, ignore_errors=True)
os.environ["HERMES_HOME"] = _HOME.name
os.environ["HERMES_ENABLE_PROJECT_PLUGINS"] = "false"
os.environ["HERMES_DISABLE_LAZY_INSTALLS"] = "1"
assert Path(_HOME.name).resolve() != (Path.home() / ".hermes").resolve()
sys.path.insert(0, str(Path.home() / ".hermes/hermes-agent"))
import hermes_bootstrap  # noqa: E402,F401
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
from mobile import adapter as A
from mobile import store as S
from gateway.run_inbound import GatewayInboundMixin

assert S.data_dir().resolve().is_relative_to(Path(_HOME.name).resolve())


class CronReplyIntegration(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.dir = tempfile.TemporaryDirectory(dir=_HOME.name)
        self.addCleanup(self.dir.cleanup)
        self.store = S.Store(Path(self.dir.name) / "mobile.db")
        self.addCleanup(self.store._db.close)
        self.events = []
        self.ad = object.__new__(A.MobileAdapter)
        self.ad.store = self.store
        self.ad._home = "digests"
        self.ad._working = {}
        self.ad._profiles = SimpleNamespace(profile_of=lambda cid: "", busy=lambda cid: False, send=AsyncMock())
        self.ad._mirror = None
        self.ad._emit_msg = lambda *a, **kw: None
        self.ad._activity = SimpleNamespace(on_message=lambda msg: None)
        self.ad.build_source = lambda **kw: SimpleNamespace(platform="mobile", **kw)
        self.ad.handle_message = AsyncMock(side_effect=self.events.append)
        self.ad._meter = SimpleNamespace(for_reply=AsyncMock(return_value=None))

    async def reply(self, cid, text="Explain item seven", raw=None, mode="send"):
        await self.ad.post_from_app(cid, text, mode=mode, raw=raw)
        await asyncio.sleep(0)
        return self.events[-1]

    def payload(self, event):
        return GatewayInboundMixin._prepend_inbound_reply_context(event, event.source, event.text)

    async def test_connected_delivery_default_full_report_and_no_history_mutation(self):
        report = "# Scheduled report\n" + "details\n" * 12000 + "ITEM SEVEN END"
        result = await self.ad.send("digests", report)
        self.assertTrue(result.success, result)
        original = self.store.get_message(result.message_id)
        event = await self.reply(original["chat_id"])
        self.assertEqual(event.reply_to_text, report)
        self.assertEqual(event.reply_to_message_id, result.message_id)
        self.assertTrue(event.reply_to_is_own_message)
        self.assertEqual(event.text, "Explain item seven")
        self.assertIn(report, self.payload(event))
        self.assertEqual(original, self.store.get_message(result.message_id))
        self.assertTrue(A.MobileAdapter.splits_long_messages)

    async def test_standalone_delivery_survives_store_reopen(self):
        with patch.object(A.S, "Store", return_value=self.store), patch.object(A, "push_wake", new=AsyncMock()):
            result = await A._standalone_send(None, "digests", "# Night report\nThe answer is quartz")
        self.assertTrue(result["success"], result)
        other = S.Store(self.store.path)
        self.addCleanup(other._db.close)
        self.ad.store = other
        event = await self.reply(result["chat_id"])
        self.assertIn("quartz", self.payload(event))

    async def test_mirrored_report_duplicates_and_noise(self):
        cid = "p-lab-job"
        report = "# Mirrored report\nitem seven: violet"
        for _ in range(2):
            self.store.add_message(cid, "agent", report, chat_kind="digest", meta={"mirror": "cron", "job": "job"})
        for kind in ("trace", "status", "activity", "file"):
            self.store.add_message(cid, "agent", "NOISE", kind=kind)
        event = await self.reply(cid)
        self.assertEqual(event.reply_to_text, report)
        self.assertEqual(self.payload(event).count(report), 1)
        self.assertNotIn("NOISE", self.payload(event))

    async def test_explicit_reply_and_cross_chat_rejection(self):
        old = self.store.add_message("a", "agent", "Older selected report", chat_kind="digest")
        self.store.add_message("a", "agent", "Newer report")
        event = await self.reply("a", raw={"text": "why?", "reply_to": old["id"]})
        self.assertEqual(event.reply_to_text, old["content"])
        event = await self.reply("b", raw={"text": "why?", "reply_to": old["id"]})
        self.assertIsNone(event.reply_to_text)
        event = await self.reply("a", raw={"reply_to_message_id": old["id"]})
        self.assertEqual(event.reply_to_text, old["content"])

    async def test_normal_chat_commands_and_undone_are_not_context(self):
        self.store.add_message("normal", "agent", "Unrelated normal answer")
        self.assertIsNone((await self.reply("normal")).reply_to_text)
        self.store.add_message("digest", "agent", "Old undone report", chat_kind="digest", meta={"undone": True})
        self.assertIsNone((await self.reply("digest")).reply_to_text)
        self.store.add_message("digest", "agent", "Valid report")
        self.assertIsNone((await self.reply("digest", text="/new")).reply_to_text)
        self.assertIsNone((await self.reply("digest", text="/model fake")).reply_to_text)

    async def test_new_report_after_conversation_and_direct_delivery(self):
        self.store.add_message("direct", "user", "Earlier turn")
        result = await self.ad.send("direct", "Directed cron report", metadata={"job_id": "synthetic"})
        self.assertTrue(result.success, result)
        event = await self.reply("direct")
        self.assertEqual(event.reply_to_message_id, result.message_id)
        self.store.add_message("direct", "agent", "Normal answer", meta={"reply_to": "user-id"})
        self.assertIsNone((await self.reply("direct")).reply_to_text)

    async def test_profile_report_context_reaches_child_prompt(self):
        cid = "p-lab-job"
        self.store.add_message(cid, "agent", "Profile cron result: amber", chat_kind="digest", meta={"mirror": "cron"})
        self.ad._profiles.profile_of = lambda c: "lab"
        await self.ad.post_from_app(cid, "Explain this")
        prompt = self.ad._profiles.send.call_args.args[2]
        self.assertIn("Profile cron result: amber", prompt)
        self.assertTrue(prompt.endswith("Explain this"))
        self.assertEqual(self.events, [])

    async def test_real_mirror_import_and_profile_queue_prompt(self):
        from mobile.mirror import Mirror
        home = Path(self.dir.name) / "profile"
        output = home / "cron/output/job"
        output.mkdir(parents=True)
        (home / "cron/jobs.json").write_text(json.dumps({"jobs": [
            {"id": "job", "name": "Synthetic report", "deliver": "mobile"}]}))
        report = "# Imported report\n" + "long detail\n" * 6000 + "FINAL ITEM"
        f = output / "2026-01-01.md"
        f.write_text("**Status:** success\n---\n" + report)
        os.utime(f, (100, 100))
        mirror = Mirror(self.store, home / "state.db", profiles=[("lab", home)])
        imported = mirror._cron("lab", home, {})
        self.assertEqual(len(imported), 1)
        cid = "p-lab-job"
        self.ad._mirror = mirror
        self.ad._profiles.profile_of = mirror.profile_of
        await self.ad.post_from_app(cid, "Explain FINAL ITEM", mode="queue")
        prompt = self.ad._profiles.send.call_args.args[2]
        self.assertIn(report.strip(), prompt)
        self.assertEqual(prompt.count("FINAL ITEM"), 2)
        # Profile responses are not new cron reports, even in a digest thread.
        self.store.add_message(cid, "agent", "Normal profile answer", meta={"profile": "lab", "partial": False})
        await self.ad.post_from_app(cid, "Continue")
        self.assertEqual(self.ad._profiles.send.call_args.args[2], "Continue")

    async def test_explicit_user_quote_noise_and_untrusted_text(self):
        user = self.store.add_message("a", "user", "Specific user question")
        event = await self.reply("a", raw={"reply_to": user["id"], "reply_to_text": "FORGED"})
        self.assertEqual(event.reply_to_text, user["content"])
        self.assertFalse(event.reply_to_is_own_message)
        self.assertNotIn("FORGED", self.payload(event))
        noise = self.store.add_message("a", "agent", "Running tools", kind="status")
        self.assertIsNone((await self.reply("a", raw={"reply_to": noise["id"]})).reply_to_text)
        self.store.add_message("a", "agent", "Fallback must not leak", chat_kind="digest", meta={"delivery": True})
        self.assertIsNone((await self.reply("a", raw={"reply_to": "missing"})).reply_to_text)

    async def test_latest_report_wins_and_no_status_history_replay(self):
        self.store.add_message("d", "agent", "Old report", chat_kind="digest")
        self.store.add_message("d", "agent", "Latest report")
        for _ in range(100):
            self.store.add_message("d", "agent", "Progress", kind="status")
        event = await self.reply("d")
        self.assertEqual(event.reply_to_text, "Latest report")
        self.assertNotIn("Old report", self.payload(event))
        self.assertNotIn("Progress", self.payload(event))
        answered = await self.ad.send("d", "Normal gateway answer", reply_to=event.message_id)
        self.assertTrue(answered.success)
        self.assertIsNone((await self.reply("d", text="Continue")).reply_to_text)

    async def test_queue_and_steer_reach_actual_busy_consumers(self):
        from gateway.run_busy import GatewayBusySessionMixin
        self.store.add_message("d", "agent", "Queued report context", chat_kind="digest")
        queued, steered = [], []
        running = SimpleNamespace(steer=lambda text: None)
        runner = SimpleNamespace(
            _peek_session_state=lambda key: SimpleNamespace(turn=SimpleNamespace(agent=running)),
            _delivery_adapter_for=lambda source: self.ad,
            _enqueue_fifo=lambda key, event, adapter: queued.append(event),
            _queue_depth=lambda *a, **kw: len(queued),
            _steer_text_with_origin=lambda text, event: text,
            _steer_running_agent=lambda agent, text: steered.append(text) or True,
            _fold_into_running_turn=lambda *a: None,
            _agent_has_active_subagents=lambda agent: False,
            # No session authority here, so a queued command turn takes the in-process slot (_enqueue_fifo).
            _command_turn_admitted=lambda event, key: False,
        )
        runner._queue_command_turn = lambda *a, **kw: GatewayBusySessionMixin._queue_command_turn(runner, *a, **kw)
        event = await self.reply("d", mode="queue")
        await GatewayBusySessionMixin._busy_queue_command(runner, event, "d", event.source)
        self.assertIn("Queued report context", self.payload(queued[-1]))
        event = await self.reply("d", mode="steer")
        await GatewayBusySessionMixin._busy_steer_command(runner, event, "d", event.source)
        self.assertIn("Queued report context", steered[-1])
        self.assertEqual(steered[-1].count("Queued report context"), 1)
        self.assertTrue(steered[-1].endswith("Explain item seven"))
        runner._peek_session_state = lambda key: None
        await GatewayBusySessionMixin._busy_steer_command(runner, event, "d", event.source)
        self.assertIn("Queued report context", self.payload(queued[-1]))
        self.assertEqual(self.payload(queued[-1]).count("Queued report context"), 1)


if __name__ == "__main__":
    try:
        unittest.main(verbosity=2)
    finally:
        _HOME.cleanup()
