"""Self-isolated voice-transcript regressions; run with Hermes' managed Python.

A voice message goes through the real phone ingress (``post_from_app``) into the core's own code:
STT enrichment and echo (``GatewayRunner._enrich_inbound_voice``, real locale catalogs) and, for
messages that arrive while a turn runs, the busy handler (``_handle_active_session_busy_message``:
queue, interrupt demoted to queue, steer and interrupt, with its real acks and reply anchors).
Replies go through the base adapter's ``_send_with_retry`` into the real adapter ``send`` and
SQLite store. Patched: the STT provider call, the authorization and skill lookups the busy
handler asks first, and the base adapter's session dispatch (the test plays the gateway's turn
order: typing, transcription, answer, stop, drain). No gateway, provider, live data or phone.
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
os.environ.pop("HERMES_GATEWAY_BUSY_ACK_ENABLED", None)
assert Path(_HOME.name).resolve() != (Path.home() / ".hermes").resolve()
sys.path.insert(0, str(Path.home() / ".hermes/hermes-agent"))
import hermes_bootstrap  # noqa: E402,F401
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
from mobile import adapter as A  # noqa: E402
from mobile import store as S  # noqa: E402
from mobile import transcripts as TR  # noqa: E402
import gateway.run as GR  # noqa: E402
from gateway.config import Platform  # noqa: E402
from gateway.run import GatewayRunner  # noqa: E402
from agent.i18n import SUPPORTED_LANGUAGES, t  # noqa: E402
import tools.transcription_tools as STT  # noqa: E402

HOME = Path(_HOME.name).resolve()
assert S.data_dir().resolve().is_relative_to(HOME)
# The busy ack's one-time onboarding hint writes config.yaml under the core's process home.
assert Path(GR._hermes_home).resolve() == HOME, GR._hermes_home
# What the plugin's registration does in a real gateway: make "mobile" a Platform value.
MOBILE = Platform._add_pseudo_member("mobile") if "mobile" not in Platform._value2member_map_ else Platform("mobile")
ECHO = "\U0001f399\ufe0f"


def heard(text):
    return patch.object(STT, "transcribe_audio", return_value={"success": True, "transcript": text})


def unheard():
    failed = {"success": False, "error": "synthetic STT failure"}
    return patch.multiple(STT, transcribe_audio=lambda *a, **k: failed, transcribe_audio_local_fallback=lambda *a, **k: failed)


class Agent:
    """The running turn's agent, as far as the busy handler inspects it."""

    def __init__(self, subagents: bool = False):
        self._active_children = [object()] if subagents else []
        self.steered, self.interrupted = [], []

    def get_activity_summary(self):
        return {}

    def steer(self, text):
        self.steered.append(text)
        return True

    def interrupt(self, text):
        self.interrupted.append(text)


class VoiceTranscriptIntegration(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.dir = tempfile.TemporaryDirectory(dir=_HOME.name)
        self.addCleanup(self.dir.cleanup)
        self.store = S.Store(Path(self.dir.name) / "mobile.db")
        self.addCleanup(self.store._db.close)
        (S.data_dir() / "media").mkdir(parents=True, exist_ok=True)
        # Fresh core config per test: the first busy ack carries the multi-line onboarding hint.
        (HOME / "config.yaml").unlink(missing_ok=True)
        self.events, self.pushes, self.profile, self.mode = [], [], "", "queue"
        self.sse: asyncio.Queue = asyncio.Queue()
        ad = self.ad = object.__new__(A.MobileAdapter)
        ad.store = self.store
        ad._home = "digests"
        ad._working = {}
        ad._subs = {self.sse}
        ad._pending_messages = {}
        ad.platform = MOBILE
        ad.gateway_runner = None
        ad._profiles = SimpleNamespace(profile_of=lambda cid: self.profile, busy=lambda cid: False, send=AsyncMock())
        ad._mirror = None
        ad._activity = SimpleNamespace(on_message=lambda msg: None)
        ad._meter = SimpleNamespace(for_reply=AsyncMock(return_value=None))
        ad._push = lambda kind, cid: self.pushes.append((kind, cid))
        ad.handle_message = AsyncMock(side_effect=self.events.append)
        runner = self.runner = object.__new__(GatewayRunner)
        runner.config = SimpleNamespace(stt_enabled=True, stt_echo_transcripts=True)
        runner._delivery_adapter_for = lambda source: ad
        runner._draining = False
        runner._is_user_authorized_for_source = lambda source: True
        runner._admit_bot_message_for_source = lambda source: True
        runner._effective_busy_input_mode = lambda source: self.mode
        runner._route_plaintext_approval_while_busy = AsyncMock(return_value=False)
        runner._busy_skill_turn = AsyncMock(return_value=(False, None))

    # ------------------------------------------------------------ the gateway's turn order
    def clip(self, name):
        media = S.new_id("u") + ".ogg"
        (S.data_dir() / "media" / media).write_bytes(b"OggS synthetic clip " + name.encode())
        return {"id": media, "kind": "voice", "mime": "audio/ogg", "size": 24, "file_name": name}

    async def voice(self, cid, *clips, text=""):
        msg = await self.ad.post_from_app(cid, text, atts=list(clips))
        await asyncio.sleep(0)
        return msg, self.events[-1]

    async def text(self, cid, words):
        msg = await self.ad.post_from_app(cid, words)
        await asyncio.sleep(0)
        return msg, self.events[-1]

    @staticmethod
    def key(cid):
        return "agent:main:mobile:dm:" + cid

    async def start_turn(self, cid, event, agent=None):
        """The gateway takes ``event`` as the running turn: typing starts, the busy handler sees an agent."""
        await self.ad.send_typing(cid)
        turn = self.runner._session_state(self.key(cid)).turn
        turn.agent, turn.event = agent or Agent(), event
        return turn.agent

    async def transcribe(self, event):
        """The core's own inbound STT step: transcribe, then echo through the delivering adapter."""
        return await self.runner._enrich_inbound_voice(event, event.source, event.text, list(event.media_urls))

    async def answer(self, event, words):
        """The turn's final text, anchored the way the base adapter anchors a final response."""
        return await self.ad._send_with_retry(chat_id=event.source.chat_id, content=words,
                                              reply_to=GatewayRunner._reply_anchor_for_event(event))

    async def end_turn(self, cid):
        self.runner._session_state(self.key(cid)).turn.agent = None
        await self.ad.stop_typing(cid)

    async def busy(self, event):
        """A message that arrives while the turn runs goes through the core's busy handler."""
        self.assertTrue(await self.runner._handle_active_session_busy_message(event, self.key(event.source.chat_id)))

    def drain(self, cid):
        """The next queued turn, in the core's FIFO order: the pending slot, then queued follow-ups."""
        key = self.key(cid)
        nxt = self.ad._pending_messages.pop(key, None)
        queued = self.runner._session_state(key).conversation.queued_events
        if queued:
            self.ad._pending_messages[key] = queued.pop(0)
        return nxt

    async def reap_after(self, seconds):
        """One pass of the adapter's typing reaper, ``seconds`` after the last typing tick."""
        for w in self.ad._working.values():
            w["last"] -= seconds
        calls = 0

        async def one_tick(_):
            nonlocal calls
            calls += 1
            if calls > 1:
                raise asyncio.CancelledError

        with patch.object(A.asyncio, "sleep", one_tick):
            with self.assertRaises(asyncio.CancelledError):
                await self.ad._reap_loop()

    def transcript(self, msg):
        return (self.store.get_message(msg["id"])["meta"].get("attachments") or [{}])[0].get("transcript")

    def atts(self, msg_id):
        return self.store.get_message(msg_id)["meta"].get("attachments") or []

    def chat_rows(self, cid):
        return self.store.messages(cid, limit=50)

    def echo_rows(self, cid, words):
        return [m for m in self.chat_rows(cid) if m["content"] == f'{ECHO} "{words}"']

    def chat(self, cid):
        return next(c for c in self.store.list_chats(include_archived=True) if c["id"] == cid)

    # ------------------------------------------------------------ pairing
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
        self.assertIsNone(A.TR.echo_text(ECHO + " Recording started"))
        self.assertIsNone(A.TR.echo_text(ECHO + ' ""'))

    async def test_failed_clip_never_takes_the_next_transcript(self):
        first, event = await self.voice("c-fail", self.clip("a.ogg"))
        await self.start_turn("c-fail", event)
        with unheard():
            await self.transcribe(event)
        await self.answer(event, "I could not hear that one.")
        await self.end_turn("c-fail")
        second, event = await self.voice("c-fail", self.clip("b.ogg"))
        await self.start_turn("c-fail", event)
        with heard("second note"):
            await self.transcribe(event)
        self.assertEqual(self.transcript(second), "second note")
        self.assertIsNone(self.transcript(first))
        self.assertEqual(self.ad._stt.waiting("c-fail"), [])

    async def test_an_unanswered_failed_clip_never_takes_a_later_transcript(self):
        first, event = await self.voice("c-run", self.clip("a.ogg"))
        await self.start_turn("c-run", event)
        with unheard():
            await self.transcribe(event)
        await self.end_turn("c-run")  # stopped or failed: no answer, so nothing proves the clip is past STT
        second, e2 = await self.voice("c-run", self.clip("b.ogg"))
        await self.start_turn("c-run", e2)
        with heard("after the run"):
            await self.transcribe(e2)
        self.assertIsNone(self.transcript(first))
        # Ambiguous, so the words stay visible as the plain echo bubble rather than risk the wrong note.
        self.assertIn(self.transcript(second), (None, "after the run"))
        if self.transcript(second) is None:
            self.assertEqual([m["kind"] for m in self.echo_rows("c-run", "after the run")], ["text"])
        await self.answer(e2, "Done.")
        await self.end_turn("c-run")
        third, e3 = await self.voice("c-run", self.clip("c.ogg"))
        await self.start_turn("c-run", e3)
        with heard("third time"):
            await self.transcribe(e3)
        self.assertEqual(self.transcript(third), "third time")
        self.assertIsNone(self.transcript(first))

    async def test_two_waiting_messages_keep_plain_echo_bubbles(self):
        first, e1 = await self.voice("c-race", self.clip("a.ogg"))
        second, e2 = await self.voice("c-race", self.clip("b.ogg"))
        with heard("which one"):
            await self.transcribe(e1)
        for m in (first, second):
            self.assertNotIn("transcript", self.atts(m["id"])[0])
        echo = [m for m in self.chat_rows("c-race") if m["role"] == "agent"]
        self.assertEqual([(m["kind"], m["content"]) for m in echo], [("text", f'{ECHO} "which one"')])
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
        result = await self.ad.send("c-prof", f'{ECHO} "from elsewhere"')
        self.assertEqual(self.store.get_message(result.message_id)["kind"], "text")
        self.assertEqual((self.ad._stt.waiting("c-prof"), self.ad._stt.waiting("c-text")), ([], []))

    # ------------------------------------------------------------ the core's busy path
    async def test_a_queued_note_keeps_its_transcript_through_the_busy_ack(self):
        a, ae = await self.text("c-q1", "run the report")
        await self.start_turn("c-q1", ae)
        v, ve = await self.voice("c-q1", self.clip("later.ogg"))
        await self.busy(ve)
        ack = [m for m in self.chat_rows("c-q1") if m["role"] == "agent"]
        self.assertEqual(len(ack), 1)
        self.assertEqual(ack[0]["meta"].get("reply_to"), v["id"])  # anchored to the queued note, before its STT
        # The first ack carries the onboarding hint: multi-line, so it is stored as text, not a notice.
        self.assertEqual(ack[0]["kind"], "text")
        await self.answer(ae, "Report sent.")
        await self.end_turn("c-q1")
        self.assertIs(self.drain("c-q1"), ve)
        await self.start_turn("c-q1", ve)
        with heard("and email it to Dana"):
            await self.transcribe(ve)
        self.assertEqual(self.transcript(v), "and email it to Dana")

    async def test_a_second_queued_note_never_takes_the_first_transcript(self):
        a, ae = await self.text("c-q2", "deploy the site")
        await self.start_turn("c-q2", ae)
        v1, e1 = await self.voice("c-q2", self.clip("one.ogg"))
        await self.busy(e1)  # acked, anchored to v1
        v2, e2 = await self.voice("c-q2", self.clip("two.ogg"))
        await self.busy(e2)  # within the 30 s debounce: no ack
        self.assertEqual(len([m for m in self.chat_rows("c-q2") if m["role"] == "agent"]), 1)
        await self.answer(ae, "Deployed.")
        await self.end_turn("c-q2")
        self.assertIs(self.drain("c-q2"), e1)
        await self.start_turn("c-q2", e1)
        with heard("FIRST note words"):
            await self.transcribe(e1)
        self.assertNotEqual(self.transcript(v2), "FIRST note words")
        self.assertIn(self.transcript(v1), (None, "FIRST note words"))
        hidden = [m for m in self.chat_rows("c-q2") if m["meta"].get("transcript_of") == v2["id"]]
        self.assertEqual(hidden, [])
        await self.answer(e1, "Noted.")
        await self.end_turn("c-q2")
        self.assertIs(self.drain("c-q2"), e2)
        await self.start_turn("c-q2", e2)
        with heard("SECOND note words"):
            await self.transcribe(e2)
        self.assertEqual(self.transcript(v2), "SECOND note words")
        self.assertNotEqual(self.transcript(v1), "SECOND note words")

    async def test_interrupt_demoted_to_queue_while_subagents_run(self):
        from agent.onboarding import BUSY_INPUT_FLAG, mark_seen
        mark_seen(HOME / "config.yaml", BUSY_INPUT_FLAG)  # a single-line ack this time
        self.mode = "interrupt"
        a, ae = await self.text("c-sub", "research the paper")
        agent = await self.start_turn("c-sub", ae, Agent(subagents=True))
        v, ve = await self.voice("c-sub", self.clip("sub.ogg"))
        await self.busy(ve)
        self.assertEqual(agent.interrupted, [])  # demoted: the subagents keep running
        ack = [m for m in self.chat_rows("c-sub") if m["role"] == "agent"]
        self.assertEqual((len(ack), ack[0]["kind"], ack[0]["meta"].get("reply_to")), (1, "status", v["id"]))
        await self.answer(ae, "Here is the summary.")
        await self.end_turn("c-sub")
        self.assertIs(self.drain("c-sub"), ve)
        await self.start_turn("c-sub", ve)
        with heard("compare it with last year"):
            await self.transcribe(ve)
        self.assertEqual(self.transcript(v), "compare it with last year")

    async def test_steered_and_interrupting_notes_pair_before_their_acks(self):
        for mode, cid in (("steer", "c-steer"), ("interrupt", "c-int")):
            with self.subTest(mode=mode):
                self.mode = mode
                a, ae = await self.text(cid, "write the summary")
                agent = await self.start_turn(cid, ae)
                v, ve = await self.voice(cid, self.clip(mode + ".ogg"))
                with heard("make it shorter " + mode):
                    await self.busy(ve)
                self.assertEqual(self.transcript(v), "make it shorter " + mode)
                self.assertTrue(agent.steered if mode == "steer" else agent.interrupted)
                await self.answer(ae, "Shorter now.")
                await self.end_turn(cid)

    async def test_a_typing_pause_does_not_drop_a_queued_note(self):
        a, ae = await self.text("c-gap", "run the migration")
        await self.start_turn("c-gap", ae)
        v, ve = await self.voice("c-gap", self.clip("gap.ogg"))
        with patch.dict(os.environ, {"HERMES_GATEWAY_BUSY_ACK_ENABLED": "false"}):  # only the typing gap at play
            await self.busy(ve)
        self.assertEqual([m for m in self.chat_rows("c-gap") if m["role"] == "agent"], [])
        await self.reap_after(20)  # an approval wait pauses typing past the reaper's 14 s
        await self.ad.send_typing("c-gap")  # approved: typing resumes for the same turn
        await self.answer(ae, "Migrated.")
        await self.end_turn("c-gap")
        self.assertIs(self.drain("c-gap"), ve)
        await self.start_turn("c-gap", ve)
        with heard("then restart the worker"):
            await self.transcribe(ve)
        self.assertEqual(self.transcript(v), "then restart the worker")

    # ------------------------------------------------------------ what is never an echo
    async def test_answers_and_interim_lines_shaped_like_an_echo_keep_their_bubble(self):
        msg, event = await self.voice("c-shape", self.clip("a.ogg"))
        await self.start_turn("c-shape", event)
        with unheard():
            await self.transcribe(event)
        res = await self.answer(event, f'{ECHO} "Recording saved"')
        row = self.store.get_message(res.message_id)
        self.assertEqual((row["kind"], row["meta"].get("transcript_of")), ("text", None))
        res = await self.ad.send("c-shape", f'{ECHO} "Listening"', metadata={"_interim_send": True})
        self.assertIsNone(self.store.get_message(res.message_id)["meta"].get("transcript_of"))
        self.assertIsNone(self.transcript(msg))

    # ------------------------------------------------------------ inbox preview
    async def test_the_echo_is_never_the_inbox_preview_or_last_speaker(self):
        msg, event = await self.voice("c-prev", self.clip("a.ogg"))
        self.assertEqual(self.chat("c-prev")["preview"], "Voice message")
        with heard("hello there\nsecond line"):
            await self.transcribe(event)
        row = self.chat("c-prev")
        self.assertEqual((row["preview"], row["last_role"]), ("hello there", "user"))
        # A plain (unpaired) echo bubble is a real agent message and still previews as one.
        await self.voice("c-prev", self.clip("b.ogg"))
        await self.voice("c-prev", self.clip("c.ogg"))
        await self.ad.send("c-prev", f'{ECHO} "which"')
        self.assertEqual(self.chat("c-prev")["last_role"], "agent")

    # ------------------------------------------------------------ bounds
    def test_the_waiting_bound_matches_the_core_and_its_drop_is_never_trusted(self):
        self.assertEqual(self.ad._stt._cap, GatewayRunner._BUSY_QUEUE_MAX_PENDING + 2)
        p = TR.Pairing(cap=1)
        p.expect("c", "old", 1.0, [0])
        p.expect("c", "new", 2.0, [0])
        self.assertEqual((p.waiting("c"), p.unsure("c")), (["new"], 1))
        self.assertIsNone(p.take("c"))  # may be the dropped note's echo
        self.assertEqual(p.take("c"), ("new", 0))
        p.expect("c", "x", 3.0, [0])
        p.answered("c", "x", run_since=2.5)  # sent during the answering run: not proof it is past STT
        self.assertEqual(p.waiting("c"), ["x"])
        p.answered("c", "x", run_since=None)
        self.assertEqual(p.waiting("c"), ["x"])
        p.answered("c", "x", run_since=3.5)
        self.assertEqual(p.waiting("c"), [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
