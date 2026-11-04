"""Mirror regressions for cron/webhook delivery rows and Telegram group sessions.

Run with Hermes' managed Python, directly in a fresh process:
  ~/.hermes/tools/python-*/bin/python3 scripts/mirror_delivery_integration.py

Hermes core mirrors a delivered cron brief or webhook result into the target
session as a USER turn ("[Cron delivery: NAME]\\n..."), so strict-alternation
providers stay valid. Those rows are not the user speaking. This harness writes
them with core's own delivery-mirror functions into a disposable state.db and
checks what the plugin's mirror imports into a disposable mobile.db. Group
sessions are mirrored read-only: the plugin refuses phone sends into them.
No gateway, provider, live data or phone is used.
"""
import atexit
import os
from pathlib import Path
import shutil
import sqlite3
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, patch

SCRATCH = Path.home() / ".hermes/cache/scratch"
SCRATCH.mkdir(parents=True, exist_ok=True)
# hermes_bootstrap may re-exec this script under the managed runtime (os.execv), which skips exit handlers. The
# re-run adopts the isolated home its first run made instead of orphaning it; either run removes it at exit.
_prior = Path(os.environ.get("HERMES_HOME") or SCRATCH)
_home = (str(_prior) if _prior.parent == SCRATCH and _prior.name.startswith("mirror-delivery-") and _prior.is_dir()
         else tempfile.mkdtemp(prefix="mirror-delivery-", dir=SCRATCH))
atexit.register(shutil.rmtree, _home, ignore_errors=True)
_HOME = SimpleNamespace(name=_home, cleanup=lambda: shutil.rmtree(_home, ignore_errors=True))
if "hermes_bootstrap" in sys.modules or "hermes_constants" in sys.modules:
    raise RuntimeError("Run directly in a fresh managed-Python process")
os.environ["HERMES_HOME"] = _HOME.name
os.environ["HERMES_ENABLE_PROJECT_PLUGINS"] = "false"
os.environ["HERMES_DISABLE_LAZY_INSTALLS"] = "1"
assert Path(_HOME.name).resolve() != (Path.home() / ".hermes").resolve()
sys.path.insert(0, str(Path.home() / ".hermes/hermes-agent"))
import hermes_bootstrap  # noqa: E402,F401
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
from aiohttp import web  # noqa: E402
from aiohttp.test_utils import TestClient, TestServer  # noqa: E402
from cron.scheduler_delivery import _maybe_mirror_cron_delivery  # noqa: E402
from gateway.platforms.webhook import WebhookAdapter  # noqa: E402
from hermes_state import SessionDB  # noqa: E402
from mobile import adapter as AD  # noqa: E402
from mobile import control as C  # noqa: E402
from mobile import control2 as C2  # noqa: E402
from mobile import fleet as FL  # noqa: E402
from mobile import mirror as MR  # noqa: E402
from mobile import store as S  # noqa: E402

HOME = Path(_HOME.name)
assert S.data_dir().resolve().is_relative_to(HOME.resolve())
STATE = HOME / "state.db"
_n = [0]


def uid(prefix: str) -> str:
    _n[0] += 1
    return f"{prefix}-{os.getpid()}-{_n[0]}"


class Fixture:
    """Sessions in the shared isolated state.db, a fresh mobile.db per test."""

    @classmethod
    def setUpClass(cls):
        cls.sdb = SessionDB(STATE)

    @classmethod
    def tearDownClass(cls):
        cls.sdb.close()

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory(dir=_HOME.name)
        self.addCleanup(self.dir.cleanup)
        self.store = S.Store(Path(self.dir.name) / "mobile.db")
        self.addCleanup(self.store._db.close)
        self.mirror = MR.Mirror(self.store, STATE, profiles=[("", HOME)])

    def say(self, sid: str, role: str, text: str) -> None:
        """A turn as the gateway stores it: an inbound user message carries the platform's message id."""
        self.sdb.append_message(sid, role, text, platform_message_id=uid("pm") if role == "user" else None)

    def session(self, source: str, chat_id: str, chat_type: str = "dm", turns=(), user_id: str = "owner") -> str:
        sid = uid("s")
        # A gateway session row: core's delivery mirror finds its target by this origin.
        self.sdb.create_session(sid, source, chat_id=chat_id, chat_type=chat_type, user_id=user_id,
                                session_key=f"agent:main:{source}:{chat_type}:{chat_id}")
        for role, text in turns:
            self.say(sid, role, text)
        return sid

    def later(self, seconds: float = 120):
        """The mirror pass runs a little later, past its settle window."""
        real = time.time
        return patch.object(MR, "time", SimpleNamespace(time=lambda: real() + seconds))

    def rows(self, cid: str):
        return [(m["role"], m["content"], m["meta"]) for m in self.store.messages(cid, limit=500)]

    def group_chat(self, chat_type: str = "group"):
        sid = self.session("telegram", uid("grp"), chat_type=chat_type, user_id="member",
                           turns=[("user", "Team lunch?"), ("assistant", "Noon works."), ("user", "Booked")])
        self.mirror._conv("", HOME, {})
        return sid, "h-" + sid


class MirrorDelivery(Fixture, unittest.TestCase):
    def test_cron_delivery_in_a_telegram_session_is_an_agent_row(self):
        chat = uid("tg")
        sid = self.session("telegram", chat, turns=[("user", "What is on today?"), ("assistant", "Two meetings.")])
        _maybe_mirror_cron_delivery({"id": "brief", "name": "Morning brief"}, "telegram", chat,
                                    "Weather: clear. Standup at 10.", enabled=True)
        self.say(sid, "user", "Thanks, move standup to 11")
        stored = self.sdb.get_messages(sid)
        self.assertTrue(any(m["role"] == "user" and str(m["content"]).startswith("[Cron delivery: Morning brief]")
                            for m in stored), "core must have mirrored the brief as a user turn")
        self.mirror._conv("", HOME, {})
        rows = self.rows("h-" + sid)
        self.assertFalse([r for r in rows if r[0] == "user" and "[Cron delivery" in r[1]],
                         "a delivery row must never become a user bubble")
        self.assertFalse([r for r in rows if r[0] == "user" and "Weather: clear" in r[1]])
        brief = [r for r in rows if "Weather: clear" in r[1]]
        self.assertEqual(len(brief), 1, rows)
        self.assertEqual(brief[0][0], "agent")
        self.assertEqual(brief[0][1], "Weather: clear. Standup at 10.")
        # Tagged as a delivery seen on that surface, not as one of the plugin's own cron reports.
        self.assertEqual((brief[0][2].get("mirror"), brief[0][2].get("delivery_mirror")), ("telegram", "cron"))
        self.assertIn(("user", "Thanks, move standup to 11"), [(r[0], r[1]) for r in rows])
        # A phone reply in this mirrored conversation must not get the brief injected as "the report you reply to".
        self.assertIsNone(self.store.reply_context("h-" + sid))

    def test_webhook_delivery_in_a_telegram_session_is_an_agent_row(self):
        chat = uid("tg")
        sid = self.session("telegram", chat, turns=[("user", "Watch the deploys"), ("assistant", "Watching.")])
        WebhookAdapter._mirror_delivery(None, "telegram", chat, "Deploy 41 finished green.",
                                        {"mirror": True, "route": "deploys"}, None)
        self.assertTrue(any(str(m["content"]).startswith("[Webhook delivery: deploys]")
                            for m in self.sdb.get_messages(sid)), "core must have mirrored the webhook result")
        self.mirror._conv("", HOME, {})
        rows = self.rows("h-" + sid)
        hook = [r for r in rows if "Deploy 41" in r[1]]
        self.assertEqual(len(hook), 1, rows)
        self.assertEqual((hook[0][0], hook[0][1], hook[0][2].get("delivery_mirror")), ("agent", "Deploy 41 finished green.", "webhook"))

    def test_job_name_with_brackets_is_stripped_whole(self):
        chat = uid("tg")
        sid = self.session("telegram", chat, turns=[("user", "Ship it?"), ("assistant", "Shipping.")])
        _maybe_mirror_cron_delivery({"id": "rep", "name": "Report [prod]"}, "telegram", chat, "All green.", enabled=True)
        self.assertTrue(any(str(m["content"]).startswith("[Cron delivery: Report [prod]]\n") for m in self.sdb.get_messages(sid)))
        self.mirror._conv("", HOME, {})
        rep = [r for r in self.rows("h-" + sid) if "All green" in r[1]]
        self.assertEqual([(r[0], r[1]) for r in rep], [("agent", "All green.")])

    def test_a_typed_delivery_label_stays_the_users_message(self):
        # Only core's own mirror write (no platform message id) is a delivery. Text a person typed, label and all,
        # is theirs: in a DM or in a group.
        chat = uid("tg")
        sid = self.session("telegram", chat, turns=[("user", "Hi"), ("assistant", "Hello.")])
        self.say(sid, "user", "[Cron delivery: Morning brief]\nI wrote this myself")
        group = self.session("telegram", uid("grp"), chat_type="group", user_id="member",
                             turns=[("user", "Lunch?"), ("assistant", "Noon.")])
        self.say(group, "user", "[Webhook delivery: deploys]\nTotally an alert")
        self.mirror._conv("", HOME, {})
        for cid, text in (("h-" + sid, "I wrote this myself"), ("h-" + group, "Totally an alert")):
            with self.subTest(chat=cid):
                hit = [r for r in self.rows(cid) if text in r[1]]
                self.assertEqual(len(hit), 1, self.rows(cid))
                self.assertEqual(hit[0][0], "user")
                self.assertNotIn("delivery_mirror", hit[0][2])

    def test_back_sync_drops_delivery_rows_the_phone_already_has(self):
        cid = uid("c")
        report = "# Nightly report\nAll 12 checks passed. Disk at 61 percent."
        self.store.ensure_chat(cid, title="Reports")
        self.store.add_message(cid, "user", "Send me the nightly report here")
        self.store.add_message(cid, "agent", report)  # what the phone got from the delivery itself
        sid = self.session("mobile", cid, turns=[("user", "Send me the nightly report here"), ("assistant", "Will do.")])
        _maybe_mirror_cron_delivery({"id": "nightly", "name": "Nightly"}, "mobile", cid, report, enabled=True)
        self.assertTrue(any(str(m["content"]).startswith("[Cron delivery: Nightly]") for m in self.sdb.get_messages(sid)))
        before = self.rows(cid)
        with self.later():
            self.mirror._back(HOME)
        added = self.rows(cid)[len(before):]
        self.assertFalse([r for r in added if r[0] == "user"], f"no user row may be imported: {added}")
        self.assertFalse([r for r in added if "Nightly report" in r[1] or "12 checks" in r[1]], added)

    def test_group_sessions_are_mirrored_read_only(self):
        group = self.session("telegram", uid("grp"), chat_type="group", user_id="member",
                             turns=[("user", "Team lunch?"), ("assistant", "Noon works."), ("user", "Booked")])
        forum = self.session("telegram", uid("frm"), chat_type="forum", user_id="member",
                             turns=[("user", "Topic A"), ("assistant", "Noted."), ("user", "More")])
        dm = self.session("telegram", uid("dm"), turns=[("user", "Private note"), ("assistant", "Saved."), ("user", "Thanks")])
        self.mirror._conv("", HOME, {})
        chats = {c["id"]: c for c in self.store.list_chats(include_archived=True)}
        self.assertEqual(chats["h-" + group].get("readonly"), "telegram:group")
        self.assertEqual(chats["h-" + forum].get("readonly"), "telegram:forum")
        self.assertEqual(chats["h-" + dm].get("readonly"), "")
        self.assertIn("Noon works.", [r[1] for r in self.rows("h-" + group)])

    def test_back_sync_imports_deliveries_into_a_taken_over_telegram_chat(self):
        # The phone took this Telegram DM over; back-sync follows its original session. A cron brief delivered into
        # that Telegram chat has no phone copy: it must arrive as an agent row, not be skipped for good.
        chat = uid("tg")
        sid = self.session("telegram", chat, turns=[("user", "Any news?"), ("assistant", "Not yet."), ("user", "Ok")])
        self.mirror._conv("", HOME, {})
        self.assertEqual(self.mirror.take_over("h-" + sid), sid)
        _maybe_mirror_cron_delivery({"id": "brief", "name": "Evening brief"}, "telegram", chat, "Two PRs merged.", enabled=True)
        with self.later():
            self.mirror._back(HOME)
        rows = [r for r in self.rows("h-" + sid) if "Two PRs merged." in r[1]]
        self.assertEqual([(r[0], r[1], r[2].get("delivery_mirror")) for r in rows], [("agent", "Two PRs merged.", "cron")])
        self.assertFalse([r for r in self.rows("h-" + sid) if "[Cron delivery" in r[1]])

    def test_the_read_only_pass_reads_each_profile_once_and_backs_off(self):
        # Many old rows, one profile unreadable. One connection per profile, a finished profile is not read again,
        # and the unreadable one is retried after a backoff rather than restarting the whole pass every cycle.
        lab = HOME / "profiles" / "lab"
        lab.mkdir(parents=True, exist_ok=True)
        ldb = SessionDB(lab / "state.db")
        self.addCleanup(ldb.close)
        mirror = MR.Mirror(self.store, STATE, profiles=[("", HOME), ("lab", lab)])
        groups = [self.session("telegram", uid("grp"), chat_type="group", user_id="member",
                               turns=[("user", "Lunch?"), ("assistant", "Noon."), ("user", "Ok")]) for _ in range(3)]
        lab_group = uid("s")
        ldb.create_session(lab_group, "telegram", chat_id=uid("grp"), chat_type="group", user_id="member",
                           session_key="agent:main:telegram:group:z")
        for sid, prof in [(g, "") for g in groups] + [(lab_group, "lab")]:
            cid = f"h-{prof}-{sid}" if prof else "h-" + sid
            self.store.ensure_chat(cid, title="Old")
            mirror._tx("INSERT INTO mirror(chat_id,root,tip,source,last_id,taken,profile) VALUES(?,?,?,?,0,0,?)",
                       (cid, sid, sid, "telegram", prof))
        opens = {"": 0, "lab": 0}
        broken = {"lab": True}
        real_open = getattr(MR, "_open", MR.Mirror._src)

        def counted(home):
            prof = "lab" if Path(home) == lab else ""
            opens[prof] += 1
            if broken.get(prof):
                raise sqlite3.OperationalError("database disk image is malformed")
            return real_open(home)

        real = time.time
        clock = [real()]
        # The read-only opener under both names (Mirror._src delegates to _open; older code had only Mirror._src).
        with patch.object(MR, "_open", counted, create=True), patch.object(MR.Mirror, "_src", staticmethod(counted)), \
                patch.object(MR, "time", SimpleNamespace(time=lambda: clock[0])):
            mirror._flag_shared_mirrors()
            self.assertEqual([self.store.get_chat("h-" + g)["readonly"] for g in groups], ["telegram:group"] * 3,
                             "a readable profile is flagged although another one is not")
            self.assertEqual(opens, {"": 1, "lab": 1}, "one connection per profile, not one per row")
            self.assertFalse(self.store.kv_get(MR.SHARED_PASS))
            clock[0] += 8  # the next mirror pass
            mirror._flag_shared_mirrors()
            self.assertEqual(opens, {"": 1, "lab": 1}, "the finished profile is remembered; the broken one backs off")
            broken["lab"] = False
            clock[0] += 3600
            mirror._flag_shared_mirrors()
        self.assertEqual(self.store.get_chat(f"h-lab-{lab_group}")["readonly"], "telegram:group")
        self.assertEqual(opens, {"": 1, "lab": 2})
        self.assertTrue(self.store.kv_get(MR.SHARED_PASS))

    def test_every_existing_group_mirror_is_flagged_once(self):
        # After an upgrade: group mirrors an older plugin left writable, aged out of chains()'s newest entries,
        # untaken, also in a named profile. One pass over every mirror row flags them all.
        lab = HOME / "profiles" / "lab"
        lab.mkdir(parents=True, exist_ok=True)
        ldb = SessionDB(lab / "state.db")
        self.addCleanup(ldb.close)
        mirror = MR.Mirror(self.store, STATE, profiles=[("", HOME), ("lab", lab)])
        old_group = self.session("telegram", uid("grp"), chat_type="group", user_id="member",
                                 turns=[("user", "Team lunch?"), ("assistant", "Noon works."), ("user", "Booked")])
        lab_group = uid("s")
        ldb.create_session(lab_group, "telegram", chat_id=uid("grp"), chat_type="supergroup", user_id="member",
                           session_key="agent:main:telegram:group:x")
        for role, text in (("user", "Ship?"), ("assistant", "Shipped."), ("user", "Thanks")):
            ldb.append_message(lab_group, role, text, platform_message_id=uid("pm") if role == "user" else None)
        for cid, sid, prof in (("h-" + old_group, old_group, ""), (f"h-lab-{lab_group}", lab_group, "lab")):
            self.store.ensure_chat(cid, title="Old group")  # as the older plugin left them: mirrored, writable
            mirror._tx("INSERT INTO mirror(chat_id,root,tip,source,last_id,taken,profile) VALUES(?,?,?,?,0,0,?)",
                       (cid, sid, sid, "telegram", prof))
        # Newer conversations push both out of the newest entries chains() visits.
        self.session("telegram", uid("dm"), turns=[("user", "Hi"), ("assistant", "Hello."), ("user", "Bye")])
        newer = uid("s")
        ldb.create_session(newer, "telegram", chat_id=uid("dm"), chat_type="dm", user_id="owner", session_key="agent:main:telegram:dm:y")
        for role, text in (("user", "Hi"), ("assistant", "Hello."), ("user", "Bye")):
            ldb.append_message(newer, role, text, platform_message_id=uid("pm") if role == "user" else None)
        with patch.object(MR, "MAX_CHATS", 1):
            mirror.sync_once()
            self.assertEqual(self.store.get_chat("h-" + old_group)["readonly"], "telegram:group")
            self.assertEqual(self.store.get_chat(f"h-lab-{lab_group}")["readonly"], "telegram:supergroup")
            seq = self.store.get_chat("h-" + old_group)["seq"]
            mirror.sync_once()
            self.assertEqual(self.store.get_chat("h-" + old_group)["seq"], seq, "once: nothing churns later")

    def test_group_chats_taken_over_earlier_become_read_only_once(self):
        group = self.session("telegram", uid("grp"), chat_type="group", user_id="member",
                             turns=[("user", "Team lunch?"), ("assistant", "Noon works."), ("user", "Booked")])
        dm = self.session("telegram", uid("dm"), turns=[("user", "Private"), ("assistant", "Ok."), ("user", "Thanks")])
        for sid in (group, dm):
            # Taken over by an older plugin: a phone chat now, which conversation import skips for good.
            self.store.ensure_chat("h-" + sid, title="Taken")
            self.mirror._tx("INSERT INTO mirror(chat_id,root,tip,source,last_id,taken,profile) VALUES(?,?,?,?,0,1,'')",
                            ("h-" + sid, sid, sid, "telegram"))
        seq = self.store.seq()
        self.mirror.sync_once()
        chat = self.store.get_chat("h-" + group)
        self.assertEqual(chat["readonly"], "telegram:group")
        self.assertGreater(chat["seq"], seq, "the phone must sync the flag")
        self.assertEqual(self.store.get_chat("h-" + dm)["readonly"], "")
        self.mirror.sync_once()
        self.assertEqual(self.store.get_chat("h-" + group)["seq"], chat["seq"], "a one-time pass: nothing churns later")

    def test_a_previously_mirrored_group_chat_keeps_updating_read_only(self):
        sid = self.session("telegram", uid("grp"), chat_type="group", user_id="member",
                           turns=[("user", "Team lunch?"), ("assistant", "Noon works."), ("user", "Booked")])
        self.mirror._conv("", HOME, {})
        cid = "h-" + sid
        # As an older plugin left it: mirrored, not read-only.
        self.mirror._tx("UPDATE chats SET readonly='' WHERE id=?", (cid,))
        seq = self.store.seq()
        self.say(sid, "assistant", "Table for six at noon.")
        known = {r[0]: (r[1], r[2], r[3]) for r in self.store._db.execute("SELECT chat_id, tip, last_id, taken FROM mirror")}
        self.mirror._conv("", HOME, known)
        self.assertIn("Table for six at noon.", [r[1] for r in self.rows(cid)])
        chat = self.store.get_chat(cid)
        self.assertEqual(chat.get("readonly"), "telegram:group")
        self.assertGreater(chat["seq"], seq, "the phone must sync the read-only flag")

    def test_a_previously_mirrored_group_chat_is_not_resumed(self):
        group = self.session("telegram", uid("grp"), chat_type="group", user_id="member",
                             turns=[("user", "Team lunch?"), ("assistant", "Noon works."), ("user", "Booked")])
        dm = self.session("telegram", uid("dm"), turns=[("user", "Private"), ("assistant", "Ok."), ("user", "Thanks")])
        # Mirrored by an older plugin before group sessions were filtered out.
        for sid in (group, dm):
            self.mirror._tx("INSERT INTO mirror(chat_id,root,tip,source,last_id,taken,profile) VALUES(?,?,?,?,0,0,'')",
                            ("h-" + sid, sid, sid, "telegram"))
        self.assertIsNone(self.mirror.take_over("h-" + group))
        self.assertEqual(self.mirror.take_over("h-" + dm), dm)


class ReadOnlySends(Fixture, unittest.IsolatedAsyncioTestCase):
    """The plugin refuses phone sends into a read-only group mirror: no resume, no new private session."""

    async def asyncSetUp(self):
        self.ad = object.__new__(AD.MobileAdapter)
        self.ad.store = self.store
        self.ad._mirror = self.mirror
        self.ad._working = {}
        self.ad._profiles = SimpleNamespace(profile_of=lambda cid: "", busy=lambda cid: False, send=AsyncMock(),
                                            stop=AsyncMock(return_value=False))
        self.ad._emit_msg = lambda *a, **kw: None
        self.ad.build_source = lambda **kw: SimpleNamespace(platform="mobile", **kw)
        self.ad.handle_message = AsyncMock()
        self.ad._message_handler = AsyncMock(return_value="ok")

        @web.middleware
        async def as_device(request, handler):
            request["device"] = {"id": "d-synthetic"}
            return await handler(request)

        app = web.Application(middlewares=[as_device])
        app.router.add_post("/v1/chats/{cid}/messages", self.ad.h_post_message)
        app.router.add_post("/v1/chats/{cid}/stop", self.ad.h_stop)
        ctl = C.Control(self.ad)
        C.routes(app.router, ctl, self.ad._json)
        C2.routes(app.router, ctl, self.ad._json)
        FL.routes(app.router, FL.Fleet(self.ad), self.ad._json)
        self.client = TestClient(TestServer(app))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()

    async def test_sends_into_a_group_mirror_are_refused(self):
        sid, cid = self.group_chat()
        before = self.rows(cid)
        with self.assertRaises(AD.ReadOnlyChat):
            await self.ad.post_from_app(cid, "On my way")
        for path, body in ((f"/v1/chats/{cid}/messages", {"text": "On my way"}),
                           (f"/v1/chats/{cid}/messages", {"text": "faster", "mode": "steer"}),
                           (f"/v1/chats/{cid}/model", {"model": "claude-opus-5-5"}),
                           (f"/v1/chats/{cid}/confirm", {"id": "x", "choice": "once"}),
                           # Session commands would run in a private session of their own: /new, /undo, /compress
                           # and /title change it, and even the reads describe that session, not the group's.
                           *((f"/v1/chats/{cid}/session", {"action": a, "arg": "x" if a == "title" else ""})
                             for a in ("new", "undo", "compress", "title", "save", "status", "context")),
                           (f"/v1/chats/{cid}/stop", {}),
                           (f"/v1/fleet/hm:{cid}/send", {"text": "steer it"}),
                           (f"/v1/fleet/hm:{cid}/stop", {})):
            with self.subTest(path=path, body=body):
                async with self.client.post(path, json=body) as r:
                    data = await r.json()
                    self.assertEqual(r.status, 409, data)
                    self.assertIn("Telegram", data.get("error", ""))
        self.assertEqual(self.rows(cid), before, "nothing stored")
        self.ad.handle_message.assert_not_called()
        self.ad._message_handler.assert_not_called()
        taken = self.store._db.execute("SELECT taken FROM mirror WHERE chat_id=?", (cid,)).fetchone()[0]
        self.assertEqual(taken, 0, "the group session is never taken over")

    async def test_a_send_checks_the_source_session_live(self):
        # Defense in depth: a group mirror whose flag is still empty (not reached by any pass yet) is refused anyway,
        # from its source session's chat type, and keeps the flag from then on.
        sid, cid = self.group_chat()
        self.mirror._tx("UPDATE chats SET readonly='' WHERE id=?", (cid,))
        before = self.rows(cid)
        with self.assertRaises(AD.ReadOnlyChat):
            await self.ad.post_from_app(cid, "On my way")
        self.assertEqual(self.rows(cid), before)
        self.ad.handle_message.assert_not_called()
        self.assertEqual(self.store.get_chat(cid)["readonly"], "telegram:group")

    async def test_a_private_mirror_still_takes_replies(self):
        sid = self.session("telegram", uid("dm"), turns=[("user", "Private"), ("assistant", "Ok."), ("user", "Thanks")])
        self.mirror._conv("", HOME, {})
        msg = await self.ad.post_from_app("h-" + sid, "Follow up")
        self.assertEqual(msg["role"], "user")


class FirstRequestAfterStart(Fixture, unittest.IsolatedAsyncioTestCase):
    """After an upgrade the mirror is created lazily (by /health, /sync or /events). A write can come first, e.g. a
    notification's Reply: it must still be refused for an old group mirror the plugin has not flagged yet."""

    async def asyncSetUp(self):
        # An older plugin mirrored a Telegram group: chat and mirror row, writable, no read-only pass recorded.
        self.sid = self.session("telegram", uid("grp"), chat_type="group", user_id="member",
                                turns=[("user", "Team lunch?"), ("assistant", "Noon works."), ("user", "Booked")])
        self.cid = "h-" + self.sid
        self.store.ensure_chat(self.cid, title="Team")
        self.mirror._tx("INSERT INTO mirror(chat_id,root,tip,source,last_id,taken,profile) VALUES(?,?,?,?,0,0,'')",
                        (self.cid, self.sid, self.sid, "telegram"))
        self.assertEqual(self.store.get_chat(self.cid)["readonly"], "")
        self.assertFalse(self.store.kv_get(MR.SHARED_PASS))
        # A freshly started adapter: connect() ran, nothing has created the mirror yet.
        self.ad = object.__new__(AD.MobileAdapter)
        self.ad.store = self.store
        self.ad._working = {}
        self.ad._profiles = SimpleNamespace(profile_of=lambda cid: "", busy=lambda cid: False, send=AsyncMock(),
                                            stop=AsyncMock(return_value=False))
        self.ad._emit_msg = lambda *a, **kw: None
        self.ad.build_source = lambda **kw: SimpleNamespace(platform="mobile", **kw)
        self.ad.handle_message = AsyncMock()
        self.assertIsNone(getattr(self.ad, "_mirror", None))

        @web.middleware
        async def as_device(request, handler):
            request["device"] = {"id": "d-synthetic"}
            return await handler(request)

        app = web.Application(middlewares=[as_device])
        app.router.add_post("/v1/chats/{cid}/messages", self.ad.h_post_message)
        self.client = TestClient(TestServer(app))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()
        task = getattr(self.ad, "_mirror_task", None)
        if task:
            task.cancel()

    async def send(self):
        async with self.client.post(f"/v1/chats/{self.cid}/messages", json={"text": "On my way"}) as r:
            return r.status, await r.json()

    async def test_the_first_request_is_a_reply_into_an_unflagged_group_mirror(self):
        status, body = await self.send()
        self.assertEqual(status, 409, body)
        self.assertIn("Telegram", body.get("error", ""))
        # Creating the mirror imports the group's own conversation into the chat; the phone's reply is not stored.
        self.assertNotIn("On my way", [r[1] for r in self.rows(self.cid)])
        self.ad.handle_message.assert_not_called()
        self.assertEqual(self.store.get_chat(self.cid)["readonly"], "telegram:group")

    async def test_a_failed_mirror_start_still_checks_the_source(self):
        # No mirror object is needed to read the source session: the group is refused for what it is.
        with patch.object(MR, "Mirror", side_effect=RuntimeError("mirror init failed")):
            status, body = await self.send()
        self.assertEqual(status, 409, body)
        self.assertIn("Telegram", body.get("error", ""))
        self.assertNotIn("On my way", [r[1] for r in self.rows(self.cid)])
        self.ad.handle_message.assert_not_called()
        self.assertEqual(self.store.get_chat(self.cid)["readonly"], "telegram:group")


class MirrorSourceFailures(Fixture, unittest.IsolatedAsyncioTestCase):
    """Private mirrors keep an empty flag. A failed mirror start or a source that is briefly unreadable must not
    block their replies for good; a source that is gone is refused with words that don't promise a retry."""

    async def asyncSetUp(self):
        self.ad = object.__new__(AD.MobileAdapter)
        self.ad.store = self.store
        self.ad._working = {}
        self.ad._profiles = SimpleNamespace(profile_of=lambda cid: "", busy=lambda cid: False, send=AsyncMock(),
                                            stop=AsyncMock(return_value=False))
        self.ad._emit_msg = lambda *a, **kw: None
        self.ad.build_source = lambda **kw: SimpleNamespace(platform="mobile", **kw)
        self.ad.handle_message = AsyncMock()

        @web.middleware
        async def as_device(request, handler):
            request["device"] = {"id": "d-synthetic"}
            return await handler(request)

        app = web.Application(middlewares=[as_device])
        app.router.add_post("/v1/chats/{cid}/messages", self.ad.h_post_message)
        self.client = TestClient(TestServer(app))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()
        task = getattr(self.ad, "_mirror_task", None)
        if task:
            task.cancel()

    def old_mirror(self, sid: str, prof: str = "") -> str:
        cid = f"h-{prof}-{sid}" if prof else "h-" + sid
        self.store.ensure_chat(cid, title="Mirrored")
        self.mirror._tx("INSERT INTO mirror(chat_id,root,tip,source,last_id,taken,profile) VALUES(?,?,?,?,0,0,?)",
                        (cid, sid, sid, "telegram", prof))
        return cid

    async def send(self, cid: str, text: str):
        async with self.client.post(f"/v1/chats/{cid}/messages", json={"text": text}) as r:
            return r.status, await r.json()

    async def test_a_failed_mirror_start_does_not_block_private_replies_for_good(self):
        dm = self.old_mirror(self.session("telegram", uid("dm"), turns=[("user", "Hi"), ("assistant", "Ok."), ("user", "Thanks")]))
        with patch.object(MR, "Mirror", side_effect=RuntimeError("mirror init failed")):
            status, body = await self.send(dm, "Reply one")
        self.assertEqual(status, 200, body)
        self.assertFalse(self.ad._mirror)
        # The source is briefly unreadable (a writer's lock, a sync tool): refused with a retry hint ...
        def locked(home):
            raise sqlite3.OperationalError("database is locked")

        with patch.object(MR, "_open", locked):
            status, body = await self.send(dm, "Reply two")
        self.assertEqual(status, 409, body)
        self.assertIn("Try again shortly", body.get("error", ""))
        # ... and it recovers by itself, no restart.
        status, body = await self.send(dm, "Reply three")
        self.assertEqual(status, 200, body)
        # The mirror is created again after its backoff, so replies resume the conversation again.
        real = time.time
        with patch.object(AD, "time", SimpleNamespace(time=lambda: real() + 3600)):
            status, body = await self.send(dm, "Reply four")
        self.assertEqual(status, 200, body)
        self.assertIsInstance(self.ad._mirror, MR.Mirror)

    async def test_a_mirror_whose_profile_was_deleted_does_not_promise_a_retry(self):
        gone = self.old_mirror(uid("s"), prof="deleted")
        self.assertFalse((HOME / "profiles" / "deleted" / "state.db").exists())
        status, body = await self.send(gone, "Anyone?")
        self.assertEqual(status, 409, body)
        self.assertNotIn("shortly", body.get("error", ""))
        self.assertIn("no longer", body.get("error", ""))
        self.assertNotIn("Anyone?", [r[1] for r in self.rows(gone)])
        self.ad.handle_message.assert_not_called()


if __name__ == "__main__":
    try:
        unittest.main(verbosity=2)
    finally:
        _HOME.cleanup()
