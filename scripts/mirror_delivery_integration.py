"""Mirror regressions for cron/webhook delivery rows and Telegram group sessions.

Run with Hermes' managed Python, directly in a fresh process:
  ~/.hermes/tools/python-*/bin/python3 scripts/mirror_delivery_integration.py

Hermes core mirrors a delivered cron brief or webhook result into the target
session as a USER turn ("[Cron delivery: NAME]\\n..."), so strict-alternation
providers stay valid. Those rows are not the user speaking. This harness writes
them with core's own delivery-mirror functions into a disposable state.db and
checks what the plugin's mirror imports into a disposable mobile.db.
No gateway, provider, live data or phone is used.
"""
import atexit
import os
from pathlib import Path
import shutil
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

if "hermes_bootstrap" in sys.modules or "hermes_constants" in sys.modules:
    raise RuntimeError("Run directly in a fresh managed-Python process")
SCRATCH = Path.home() / ".hermes/cache/scratch"
SCRATCH.mkdir(parents=True, exist_ok=True)
_HOME = tempfile.TemporaryDirectory(prefix="mirror-delivery-", dir=SCRATCH)
atexit.register(shutil.rmtree, _HOME.name, ignore_errors=True)
os.environ["HERMES_HOME"] = _HOME.name
os.environ["HERMES_ENABLE_PROJECT_PLUGINS"] = "false"
os.environ["HERMES_DISABLE_LAZY_INSTALLS"] = "1"
assert Path(_HOME.name).resolve() != (Path.home() / ".hermes").resolve()
sys.path.insert(0, str(Path.home() / ".hermes/hermes-agent"))
import hermes_bootstrap  # noqa: E402,F401
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
from cron.scheduler_delivery import _maybe_mirror_cron_delivery  # noqa: E402
from gateway.platforms.webhook import WebhookAdapter  # noqa: E402
from hermes_state import SessionDB  # noqa: E402
from mobile import mirror as MR  # noqa: E402
from mobile import store as S  # noqa: E402

HOME = Path(_HOME.name)
assert S.data_dir().resolve().is_relative_to(HOME.resolve())
STATE = HOME / "state.db"
_n = [0]


def uid(prefix: str) -> str:
    _n[0] += 1
    return f"{prefix}-{os.getpid()}-{_n[0]}"


class MirrorDelivery(unittest.TestCase):
    """Each test uses its own sessions in the shared isolated state.db and a fresh mobile.db."""

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

    def session(self, source: str, chat_id: str, chat_type: str = "dm", turns=(), user_id: str = "owner") -> str:
        sid = uid("s")
        # A gateway session row: core's delivery mirror finds its target by this origin.
        self.sdb.create_session(sid, source, chat_id=chat_id, chat_type=chat_type, user_id=user_id,
                                session_key=f"agent:main:{source}:{chat_type}:{chat_id}")
        for role, text in turns:
            self.sdb.append_message(sid, role, text)
        return sid

    def later(self, seconds: float = 120):
        """The mirror pass runs a little later, past its settle window."""
        real = time.time
        return patch.object(MR, "time", SimpleNamespace(time=lambda: real() + seconds))

    def rows(self, cid: str):
        return [(m["role"], m["content"], m["meta"]) for m in self.store.messages(cid, limit=500)]

    def test_cron_delivery_in_a_telegram_session_is_an_agent_row(self):
        chat = uid("tg")
        sid = self.session("telegram", chat, turns=[("user", "What is on today?"), ("assistant", "Two meetings.")])
        _maybe_mirror_cron_delivery({"id": "brief", "name": "Morning brief"}, "telegram", chat,
                                    "Weather: clear. Standup at 10.", enabled=True)
        self.sdb.append_message(sid, "user", "Thanks, move standup to 11")
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
        self.assertEqual(brief[0][2].get("mirror"), "cron")
        self.assertIn(("user", "Thanks, move standup to 11"), [(r[0], r[1]) for r in rows])

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
        self.assertEqual((hook[0][0], hook[0][1], hook[0][2].get("mirror")), ("agent", "Deploy 41 finished green.", "webhook"))

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

    def test_telegram_group_sessions_are_not_mirrored(self):
        group = self.session("telegram", uid("grp"), chat_type="group", user_id="member",
                             turns=[("user", "Team lunch?"), ("assistant", "Noon works."), ("user", "Booked")])
        forum = self.session("telegram", uid("frm"), chat_type="forum", user_id="member",
                             turns=[("user", "Topic A"), ("assistant", "Noted."), ("user", "More")])
        dm = self.session("telegram", uid("dm"), turns=[("user", "Private note"), ("assistant", "Saved."), ("user", "Thanks")])
        self.mirror._conv("", HOME, {})
        chats = {c["id"] for c in self.store.list_chats(include_archived=True)}
        self.assertIn("h-" + dm, chats)
        self.assertNotIn("h-" + group, chats, "a group conversation must not land in the phone's private lane")
        self.assertNotIn("h-" + forum, chats)

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


if __name__ == "__main__":
    try:
        unittest.main(verbosity=2)
    finally:
        _HOME.cleanup()
