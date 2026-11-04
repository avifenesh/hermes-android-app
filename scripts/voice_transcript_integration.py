"""Self-isolated voice-transcript regressions; run with Hermes' managed Python.

A voice message goes through the real phone ingress (``post_from_app``) and the real core STT
enrichment and echo (``GatewayRunner._enrich_inbound_voice``, real locale catalogs) into the real
adapter ``send`` and SQLite store. Only the STT provider call is patched. No gateway, provider,
live data or phone is used.
"""
import atexit
import asyncio
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
SCRATCH = Path.home() / ".hermes/cache/scratch"
SCRATCH.mkdir(parents=True, exist_ok=True)
_HOME = tempfile.TemporaryDirectory(prefix="voice-transcripts-", dir=SCRATCH)
atexit.register(shutil.rmtree, _HOME.name, ignore_errors=True)
os.environ["HERMES_HOME"] = _HOME.name
os.environ["HERMES_ENABLE_PROJECT_PLUGINS"] = "false"
os.environ["HERMES_DISABLE_LAZY_INSTALLS"] = "1"
os.environ.pop("HERMES_LANGUAGE", None)
assert Path(_HOME.name).resolve() != (Path.home() / ".hermes").resolve()
sys.path.insert(0, str(Path.home() / ".hermes/hermes-agent"))
import hermes_bootstrap  # noqa: E402,F401
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
from mobile import adapter as A  # noqa: E402
from mobile import store as S  # noqa: E402
from gateway.run import GatewayRunner  # noqa: E402
from agent.i18n import SUPPORTED_LANGUAGES, t  # noqa: E402
import tools.transcription_tools as STT  # noqa: E402

assert S.data_dir().resolve().is_relative_to(Path(_HOME.name).resolve())


def heard(text):
    return patch.object(STT, "transcribe_audio", return_value={"success": True, "transcript": text})


def unheard():
    failed = {"success": False, "error": "synthetic STT failure"}
    return patch.multiple(STT, transcribe_audio=lambda *a, **k: failed, transcribe_audio_local_fallback=lambda *a, **k: failed)


class VoiceTranscriptIntegration(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.dir = tempfile.TemporaryDirectory(dir=_HOME.name)
        self.addCleanup(self.dir.cleanup)
        self.store = S.Store(Path(self.dir.name) / "mobile.db")
        self.addCleanup(self.store._db.close)
        (S.data_dir() / "media").mkdir(parents=True, exist_ok=True)
        self.events, self.pushes, self.profile = [], [], ""
        self.sse: asyncio.Queue = asyncio.Queue()
        ad = self.ad = object.__new__(A.MobileAdapter)
        ad.store = self.store
        ad._home = "digests"
        ad._working = {}
        ad._subs = {self.sse}
        ad._profiles = SimpleNamespace(profile_of=lambda cid: self.profile, busy=lambda cid: False, send=AsyncMock())
        ad._mirror = None
        ad._activity = SimpleNamespace(on_message=lambda msg: None)
        ad._meter = SimpleNamespace(for_reply=AsyncMock(return_value=None))
        ad._push = lambda kind, cid: self.pushes.append((kind, cid))
        ad.build_source = lambda **kw: SimpleNamespace(platform="mobile", thread_id=None, **kw)
        ad.handle_message = AsyncMock(side_effect=self.events.append)
        runner = self.runner = object.__new__(GatewayRunner)
        runner.config = SimpleNamespace(stt_enabled=True, stt_echo_transcripts=True)
        runner._delivery_adapter_for = lambda source: ad

    def clip(self, name):
        media = S.new_id("u") + ".ogg"
        (S.data_dir() / "media" / media).write_bytes(b"OggS synthetic clip " + name.encode())
        return {"id": media, "kind": "voice", "mime": "audio/ogg", "size": 24, "file_name": name}

    async def voice(self, cid, *clips, text=""):
        msg = await self.ad.post_from_app(cid, text, atts=list(clips))
        await asyncio.sleep(0)
        return msg, self.events[-1]

    async def transcribe(self, event):
        """The core's own inbound STT step: transcribe, then echo through the delivering adapter."""
        return await self.runner._enrich_inbound_voice(event, event.source, event.text, list(event.media_urls))

    def atts(self, msg_id):
        return self.store.get_message(msg_id)["meta"].get("attachments") or []

    def chat_rows(self, cid):
        return self.store.messages(cid, limit=50)

    async def test_core_echo_lands_on_its_voice_message(self):
        seq = self.store.changes(0)["seq"]
        msg, event = await self.voice("c-voice", self.clip("note.ogg"))
        unread = self.store.get_chat("c-voice")["unread"]
        with heard('turn the "porch" lights off\nplease'):
            enriched = await self.transcribe(event)
        self.assertIn("porch", enriched)
        att = self.atts(msg["id"])
        self.assertEqual(len(att), 1)
        self.assertEqual(att[0].get("transcript"), 'turn the "porch" lights off\nplease')
        self.assertEqual(att[0]["id"], event.media_urls[0].rsplit("/", 1)[-1])
        echo = [m for m in self.chat_rows("c-voice") if m["role"] == "agent"]
        self.assertEqual(len(echo), 1)
        self.assertEqual(echo[0]["kind"], "status")
        self.assertEqual(echo[0]["meta"], {"quiet": True, "transcript_of": msg["id"]})
        self.assertEqual(self.store.get_chat("c-voice")["unread"], unread)
        self.assertEqual(self.pushes, [])
        # Both rows reach the phone: live over SSE and on the next /v1/sync delta.
        live = []
        while not self.sse.empty():
            live.append(self.sse.get_nowait())
        edited = [e["message"] for e in live if e.get("type") == "message" and e["message"]["id"] == msg["id"]]
        self.assertEqual(edited[-1]["meta"]["attachments"][0]["transcript"], att[0]["transcript"])
        synced = {m["id"]: m for m in self.store.changes(seq)["messages"]}
        self.assertEqual(synced[msg["id"]]["meta"]["attachments"][0]["transcript"], att[0]["transcript"])
        self.assertEqual(synced[echo[0]["id"]]["meta"]["transcript_of"], msg["id"])
        self.assertEqual(self.ad._stt.waiting("c-voice"), [])

    def test_every_core_catalog_echo_is_recognized(self):
        sample = 'say "hi" to Dana\nthen stop'
        langs = sorted(SUPPORTED_LANGUAGES)
        self.assertIn("en", langs)
        for lang in langs:
            with self.subTest(lang=lang):
                echo = t("gateway.voice.transcript_echo_short", lang=lang, text=sample)
                self.assertNotEqual(echo, "gateway.voice.transcript_echo_short")
                self.assertEqual(A.TR.echo_text(echo), sample)
        self.assertIsNone(A.TR.echo_text("\U0001f399\ufe0f Recording started"))
        self.assertIsNone(A.TR.echo_text('\U0001f399\ufe0f ""'))

    async def test_failed_clip_never_takes_the_next_transcript(self):
        first, event = await self.voice("c-fail", self.clip("a.ogg"))
        with unheard():
            await self.transcribe(event)
        await self.ad.send("c-fail", "I could not hear that one.", reply_to=first["id"])
        second, event = await self.voice("c-fail", self.clip("b.ogg"))
        with heard("second note"):
            await self.transcribe(event)
        self.assertEqual(self.atts(second["id"])[0].get("transcript"), "second note")
        self.assertNotIn("transcript", self.atts(first["id"])[0])
        self.assertEqual(self.ad._stt.waiting("c-fail"), [])

    async def test_run_end_releases_a_clip_that_got_no_echo(self):
        first, event = await self.voice("c-run", self.clip("a.ogg"))
        await self.ad.send_typing("c-run")
        with unheard():
            await self.transcribe(event)
        await self.ad.stop_typing("c-run")
        second, event = await self.voice("c-run", self.clip("b.ogg"))
        with heard("after the run"):
            await self.transcribe(event)
        self.assertEqual(self.atts(second["id"])[0].get("transcript"), "after the run")
        self.assertNotIn("transcript", self.atts(first["id"])[0])

    async def test_a_run_end_keeps_clips_sent_during_the_run(self):
        await self.ad.send_typing("c-busy")
        self.ad._working["c-busy"]["since"] -= 5
        msg, event = await self.voice("c-busy", self.clip("mid-run.ogg"))
        await self.ad.stop_typing("c-busy")
        with heard("queued note"):
            await self.transcribe(event)
        self.assertEqual(self.atts(msg["id"])[0].get("transcript"), "queued note")

    async def test_two_waiting_messages_keep_plain_echo_bubbles(self):
        first, e1 = await self.voice("c-race", self.clip("a.ogg"))
        second, e2 = await self.voice("c-race", self.clip("b.ogg"))
        with heard("which one"):
            await self.transcribe(e1)
        for m in (first, second):
            self.assertNotIn("transcript", self.atts(m["id"])[0])
        echo = [m for m in self.chat_rows("c-race") if m["role"] == "agent"]
        self.assertEqual([(m["kind"], m["content"]) for m in echo], [("text", '\U0001f399\ufe0f "which one"')])
        self.assertEqual(self.pushes, [("message", "c-race")])

    async def test_a_message_with_two_clips_is_never_paired(self):
        msg, event = await self.voice("c-multi", self.clip("a.ogg"), self.clip("b.ogg"))
        self.assertEqual(len(event.media_urls), 2)
        with heard("one of two"):
            await self.transcribe(event)
        self.assertTrue(all("transcript" not in a for a in self.atts(msg["id"])))
        # One echo per transcribed clip, both left as the plain bubbles they always were.
        self.assertEqual([m["kind"] for m in self.chat_rows("c-multi") if m["role"] == "agent"], ["text", "text"])

    async def test_profile_chats_and_text_messages_are_not_tracked(self):
        self.profile = "seller"
        await self.ad.post_from_app("c-prof", "", atts=[self.clip("p.ogg")])
        self.profile = ""
        await self.ad.post_from_app("c-text", "just text")
        result = await self.ad.send("c-prof", '\U0001f399\ufe0f "from elsewhere"')
        self.assertEqual(self.store.get_message(result.message_id)["kind"], "text")
        self.assertEqual((self.ad._stt.waiting("c-prof"), self.ad._stt.waiting("c-text")), ([], []))


if __name__ == "__main__":
    unittest.main(verbosity=2)
