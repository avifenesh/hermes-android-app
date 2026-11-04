"""Group Chat room routes against Hermes' real room store and room driver. No live gateway, device or data.

Run directly with Hermes' managed Python (the interpreter `hermes` runs):
    env -u HERMES_HOME "$HERMES_PYTHON" scripts/rooms_integration.py [--server-source DIR]

Contract checks (in process): the plugin's rooms routes behind the real app and auth middleware, with a
scripted authority connection: the features.rooms flag, event projection, the unread count and read cursor,
pending actions in the summary, error mapping, per-room failure isolation, the attachment size check and
the room-approval signature domain.

Gateway checks (subprocess): a real `gateway run` in a disposable HOME, HERMES_HOME and lock directory,
serving two Bot profiles, with the mobile plugin from this checkout and a loopback stub model. Requests
signed like the app's create a room, send, page history, receive the SSE room event, watch both Bots
answer through Hermes' own room driver, stop a running turn, approve a member's real command request,
and, after the gateway is killed mid-turn, refuse a retry of the unknown turn and discard it. Requests
without a token, without a signature or from a revoked device are refused. A harness killed by a signal
stops its gateway. The gateway runs in the committed dependency environment, so the disposable home
installs nothing. ``--server-source`` points both parts at another plugin tree (red runs against main).

``--serve [SECONDS]`` runs no checks: it brings the same disposable gateway up with one seeded room and
prints a one-use pairing link for an emulator (http://10.0.2.2:PORT), then removes everything.
"""
import os
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
# Its own Hermes root outside ~/.hermes; may relaunch once into Hermes' dependency environment.
from isolated_home import CHECKOUT, LIVE, enter, owner_environment  # noqa: E402
_HOME = enter("mobile-rooms-")
ROOT = Path(_HOME.name)

SERVER = Path(__file__).resolve().parents[1] / "server"
if "--server-source" in sys.argv:
    SERVER = Path(sys.argv[sys.argv.index("--server-source") + 1]).resolve()
    del sys.argv[sys.argv.index("--server-source"):sys.argv.index("--server-source") + 2]
sys.path.insert(0, str(SERVER))

import asyncio  # noqa: E402
import atexit  # noqa: E402
import base64  # noqa: E402
import hashlib  # noqa: E402
import json  # noqa: E402
import queue  # noqa: E402
import shutil  # noqa: E402
import signal  # noqa: E402
import socket  # noqa: E402
import subprocess  # noqa: E402
import threading  # noqa: E402
import time  # noqa: E402
import unittest  # noqa: E402
import urllib.error  # noqa: E402
import urllib.request  # noqa: E402
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer  # noqa: E402
from unittest.mock import patch  # noqa: E402

from aiohttp.test_utils import TestClient, TestServer  # noqa: E402
from cryptography.hazmat.primitives import hashes, serialization  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import ec  # noqa: E402
from gateway.config import PlatformConfig  # noqa: E402
from gateway.platform_registry import PlatformEntry, platform_registry  # noqa: E402
from mobile import adapter as AD, selfcheck as SC, store as S  # noqa: E402

try:
    from mobile import rooms as RM  # noqa: E402
except ImportError:  # a plugin tree from before rooms: every check below must fail, not skip
    RM = None

assert S.data_dir().resolve().is_relative_to(ROOT.resolve())
platform_registry.register(PlatformEntry(name="mobile", label="Hermes Mobile", adapter_factory=AD.MobileAdapter,
                                         check_fn=AD.check_requirements, source="plugin", plugin_name="mobile"))

# Gateways this harness started, each in its own process group. atexit runs these before the home
# cleanup registered by enter(); a signal turns into KeyboardInterrupt so unittest stops and exits.
_CHILDREN: list = []


def _stop_children() -> None:
    for proc in _CHILDREN:
        if proc.poll() is None:
            try:
                os.killpg(proc.pid, signal.SIGTERM)
                proc.wait(timeout=30)
            except (ProcessLookupError, subprocess.TimeoutExpired):
                try:
                    os.killpg(proc.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                proc.wait(timeout=10)


def _interrupted(signum, _frame):
    raise KeyboardInterrupt(f"signal {signum}")


atexit.register(_stop_children)
signal.signal(signal.SIGTERM, _interrupted)
signal.signal(signal.SIGHUP, _interrupted)


def public(key):
    return base64.b64encode(key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()


def signature(key, message: bytes) -> str:
    return base64.b64encode(key.sign(message, ec.ECDSA(hashes.SHA256()))).decode()


def canonical(method, path, ts, body: bytes) -> bytes:
    return f"{method}\n{path}\n{ts}\n{hashlib.sha256(body).hexdigest()}".encode()


def room_approval(room, member, request, choice, ts) -> bytes:
    """The room-approval proof the app signs with its biometric key (its own domain and replay scope)."""
    return f"room-approve\n{room}\n{member}\n{request}\n{choice}\n{ts}".encode()


# ---------------------------------------------------------------- contract checks (in process)
ROOM = {"room_id": "room-a", "name": "Crew", "authority_gateway_id": "install:x", "authority_epoch": 1,
        "revision": 1, "created_at": 100.0, "updated_at": 200.0, "latest_seq": 6,
        "members": [{"member_id": "scout", "profile": "scout", "handle": "scout", "display_name": "Scout"},
                    {"member_id": "scribe", "profile": "scribe", "handle": "scribe"}]}
PENDING = [{"kind": "approval", "member_id": "scout", "task_id": "t-1", "execution_generation": 2,
            "request_id": "r-9", "approval": {"command": "rm -rf build", "description": "clean"}}]


def event(seq, kind, actor, at=None, room="room-a", **payload):
    return {"room_id": room, "seq": seq, "event_id": f"e{seq}", "kind": kind, "actor": actor,
            "authority_epoch": 1, "payload": payload, "created_at": 100.0 + seq if at is None else at}


LOG = [
    event(1, "message.user", {"kind": "user", "id": "desktop"}, text="plan the release", thread_id="t1"),
    event(2, "turn.started", {"kind": "gateway", "id": "install:x"}, member_id="scout"),
    event(3, "message.member", {"kind": "member", "id": "scout", "profile": "scout", "display_name": "Scout"},
          text="Draft is ready.", thread_id="t1", member_id="scout",
          attachments=[{"attachment_id": "att_" + "1" * 32, "kind": "file", "name": "notes.md", "size": 12,
                        "mime": "text/markdown"}]),
    event(4, "turn.failed", {"kind": "gateway", "id": "install:x"}, member_id="scribe", error="provider timeout"),
    event(5, "room.activity", {"kind": "gateway", "id": "install:x"}, status="bounded", reason_code="rounds"),
    event(6, "message.member", {"kind": "member", "id": "scribe", "profile": "scribe"}, text="Done.",
          thread_id="t1", member_id="scribe"),
]


class ScriptedConnection:
    """Answers the canonical group methods like ``AuthorityConnection.dispatch`` does."""

    def __init__(self):
        self.calls = []
        self.rooms = {"room-a": dict(ROOM)}
        self.logs = {"room-a": list(LOG)}
        self.refuse = {}  # method -> (code, reason), or (method, room_id) -> (code, reason)
        self.pending = list(PENDING)

    @property
    def room(self):
        return self.rooms["room-a"]

    @room.setter
    def room(self, value):
        self.rooms["room-a"] = value

    @property
    def log(self):
        return self.logs["room-a"]

    @log.setter
    def log(self, value):
        self.logs["room-a"] = value

    async def dispatch(self, request):
        method, params = request["method"], request["params"]
        self.calls.append((method, params))
        refusal = self.refuse.get((method, params.get("room_id"))) or self.refuse.get(method)
        if refusal:
            code, reason = refusal
            return {"jsonrpc": "2.0", "id": request["id"], "error": {"code": code, "message": reason,
                                                                       "data": {"reason": reason}}}
        rid = params.get("room_id", "room-a")
        result = {
            "groups.capabilities": lambda: {"driver": True, "seatable_profiles": ["scout", "scribe"]},
            "profiles.list": lambda: {"profiles": [{"name": "scout", "display_name": "Scout"}]},
            "groups.list": lambda: {"rooms": list(self.rooms.values()), "next_offset": None},
            "groups.state": lambda: {"room": self.rooms[rid], "driver_status": {
                "running": True, "working": True, "member_activity": [{"member_id": "scribe", "status": "running"}],
                "pending_actions": list(self.pending)}},
            "groups.log": lambda: {"events": [e for e in self.logs[rid] if params["since_seq"] < e["seq"]][:params["limit"]],
                                   "latest_seq": self.rooms[rid]["latest_seq"]},
            "groups.approve": lambda: {"approved": True, "result": {}},
            "groups.stop": lambda: {"cancelled": 1},
            "groups.attachment.upload": lambda: {
                "attachment_id": "att_" + hashlib.sha256(params["upload_id"].encode()).hexdigest()[:32],
                "kind": params["kind"], "name": params["name"], "mime": params["mime"],
                "size": len(base64.b64decode(params["data_base64"])), "upload_id": params["upload_id"]},
            "groups.send": lambda: {"accepted": True, "event": event(
                self.rooms[rid]["latest_seq"] + 1, "message.user", {"kind": "user", "id": "desktop"}, at=time.time(),
                text=params["payload"]["text"], thread_id=params["payload"]["thread_id"])},
        }[method]()
        return {"jsonrpc": "2.0", "id": request["id"], "result": result}


class RoomsContract(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.assertIsNotNone(RM, "this plugin tree has no rooms module")
        (S.data_dir() / "mobile.db").unlink(missing_ok=True)
        self.conn = ScriptedConnection()
        self.authority = object()
        self.ad = AD.MobileAdapter(PlatformConfig(enabled=True, extra={"bind": "127.0.0.1:0"}))
        self.ad._rooms = RM.Rooms(self.ad, authority=lambda: self.authority, connection_factory=lambda a: self.conn)
        self.app = self.ad._build_app()
        self.key, self.akey = ec.generate_private_key(ec.SECP256R1()), ec.generate_private_key(ec.SECP256R1())
        claim = self.ad.store.claim_pair_code(self.ad.store.create_pair_code(), "rooms-check",
                                              public(self.key), public(self.akey))
        self.token = claim["token"]
        self.client = TestClient(TestServer(self.app))
        await self.client.start_server()
        self.addAsyncCleanup(self.client.close)

    async def call(self, method, path, body=None, asig=None, ts=None, asig_value=None):
        raw = b"" if body is None else json.dumps(body).encode()
        ts = ts or str(int(time.time()))
        headers = {"Authorization": f"Bearer {self.token}", "X-HM-Ts": ts,
                   "X-HM-Sig": signature(self.key, canonical(method, path, ts, raw)), "Content-Type": "application/json"}
        if asig is not None:
            headers["X-HM-ASig"] = signature(self.akey, asig(ts))
        if asig_value is not None:
            headers["X-HM-ASig"] = asig_value
        resp = await self.client.request(method, path, data=raw or None, headers=headers)
        return resp.status, await resp.json()

    def dispatched(self, method):
        return [p for m, p in self.conn.calls if m == method]

    async def test_feature_flag_follows_core_support(self):
        status, me = await self.call("GET", "/v1/me")
        self.assertEqual(status, 200)
        self.assertIs(me["features"]["rooms"], True)
        self.authority = None  # the gateway has no session runtime
        _, me = await self.call("GET", "/v1/me")
        self.assertIs(me["features"]["rooms"], False)
        status, body = await self.call("GET", "/v1/rooms")  # the self-check probes it: an empty answer
        self.assertEqual((status, body["supported"], body["rooms"]), (200, False, []))
        status, body = await self.call("GET", "/v1/rooms/room-a")
        self.assertEqual((status, body.get("reason")), (404, "unsupported"))
        self.assertEqual(self.conn.calls, [])
        self.authority = object()
        real = RM.importlib.util.find_spec
        with patch.object(RM.importlib.util, "find_spec",
                          lambda name, *a: None if name == "gateway.session_group_controls" else real(name, *a)):
            _, me = await self.call("GET", "/v1/me")
        self.assertIs(me["features"]["rooms"], False)

    async def test_listing_counts_unread_and_never_moves_the_read_cursor(self):
        status, body = await self.call("GET", "/v1/rooms")
        self.assertEqual(status, 200, body)
        room, = body["rooms"]
        # History older than the plugin's first look is not news, and looking does not read anything.
        self.assertEqual((room["read_seq"], room["unread"]), (0, 0))
        self.assertEqual(room["preview"], {"seq": 6, "role": "member", "name": "scribe", "text": "Done.", "ts": 106.0})
        self.assertEqual([b["name"] for b in body["bots"]], ["Scout", "scribe"])
        now = time.time()
        fresh = [event(7 + 2 * i + j, kind, actor, at=now + i, text=f"reply {i}", thread_id="t2")
                 for i in range(60) for j, (kind, actor) in enumerate([
                     ("turn.started", {"kind": "gateway", "id": "install:x"}),
                     ("message.member", {"kind": "member", "id": "scout"})])]
        self.conn.log = LOG + fresh + [event(127, "message.user", {"kind": "user", "id": "desktop"}, at=now + 61,
                                             text="thanks", thread_id="t2")]
        self.conn.room = {**ROOM, "latest_seq": 127, "updated_at": now + 61}
        _, body = await self.call("GET", "/v1/rooms")
        # Every member reply since the cursor counts, past any preview window; the owner's own words do not.
        self.assertEqual((body["rooms"][0]["unread"], body["rooms"][0]["read_seq"]), (60, 0))
        result = await SC._call(self.app, "/v1/rooms", 10)  # the self-check probe 20 s after start
        self.assertNotIn("error", result)
        self.assertIsNone(self.ad.store.kv_get("room_read:room-a"))
        status, read = await self.call("POST", "/v1/rooms/room-a/read", {"seq": 999})
        self.assertEqual((status, read["read_seq"]), (200, 127))  # clamped to the log
        _, body = await self.call("GET", "/v1/rooms")
        self.assertEqual(body["rooms"][0]["unread"], 0)

    async def test_history_projects_messages_and_notices(self):
        status, page = await self.call("GET", "/v1/rooms/room-a/events?limit=10")
        self.assertEqual(status, 200, page)
        self.assertEqual([(e["seq"], e["kind"]) for e in page["events"]],
                         [(1, "message"), (3, "message"), (4, "notice"), (5, "notice"), (6, "message")])
        user, scout, failed, bounded, scribe = page["events"]
        self.assertEqual((user["role"], user["text"], user["thread"]), ("user", "plan the release", "t1"))
        self.assertEqual((scout["role"], scout["member"], scout["name"]), ("member", "scout", "Scout"))
        self.assertEqual(scout["attachments"][0]["name"], "notes.md")
        self.assertEqual((failed["notice"], failed["name"], failed["detail"]), ("turn.failed", "scribe", "provider timeout"))
        self.assertEqual(bounded["notice"], "room.bounded")
        status, older = await self.call("GET", "/v1/rooms/room-a/events?before=4&limit=2")
        self.assertEqual([e["seq"] for e in older["events"]], [3])
        self.assertEqual(older["before"], 2)

    async def test_detail_reports_driver_state(self):
        status, body = await self.call("GET", "/v1/rooms/room-a")
        self.assertEqual(status, 200, body)
        driver = body["driver"]
        self.assertEqual(driver["members"], [{"id": "scribe", "status": "running"}])
        self.assertEqual(driver["pending"], [{"kind": "approval", "member": "scout", "task_id": "t-1", "generation": 2,
                                              "request_id": "r-9", "command": "rm -rf build", "description": "clean"}])

    async def test_pending_actions_reach_the_summary_and_the_live_stream(self):
        self.conn.room = {**ROOM, "updated_at": time.time()}
        sink: asyncio.Queue = asyncio.Queue()
        self.ad._subs.add(sink)
        self.addCleanup(self.ad._subs.discard, sink)
        await self.ad._rooms.tick()  # the poll a listening phone gets
        emitted = [e for e in [sink.get_nowait() for _ in range(sink.qsize())] if e.get("type") == "room"]
        self.assertEqual([p["request_id"] for p in emitted[-1]["room"]["pending"]], ["r-9"])
        _, body = await self.call("GET", "/v1/rooms")
        pending, = body["rooms"][0]["pending"]
        self.assertEqual((pending["kind"], pending["member"], pending["command"]), ("approval", "scout", "rm -rf build"))
        self.conn.pending = []
        await self.ad._rooms.tick()
        emitted = [e for e in [sink.get_nowait() for _ in range(sink.qsize())] if e.get("type") == "room"]
        self.assertEqual(emitted[-1]["room"]["pending"], [])  # an answered approval leaves every phone

    async def test_room_stop_reaches_core(self):
        status, body = await self.call("POST", "/v1/rooms/room-a/stop")
        self.assertEqual((status, body), (200, {"ok": True, "cancelled": 1}))
        stop, = self.dispatched("groups.stop")
        self.assertEqual(stop["room_id"], "room-a")

    async def test_room_refusals_map_to_http(self):
        self.conn.refuse["groups.state"] = (4001, "permission_denied")
        status, body = await self.call("GET", "/v1/rooms/room-z")
        self.assertEqual((status, body["error"]), (404, "Group chat not found"))
        self.conn.refuse["groups.state"] = (-32601, "unknown method: groups.state")
        status, body = await self.call("GET", "/v1/rooms/room-z")
        self.assertEqual(status, 501)

    async def test_one_broken_room_keeps_the_list_and_the_poll(self):
        self.conn.rooms["room-b"] = {**ROOM, "room_id": "room-b", "name": "Broken", "updated_at": 100.0}
        self.conn.logs["room-b"] = []
        self.conn.refuse[("groups.log", "room-b")] = (5001, "storage_unavailable")
        status, body = await self.call("GET", "/v1/rooms")
        self.assertEqual(status, 200, body)
        rooms = {r["id"]: r for r in body["rooms"]}
        self.assertEqual(set(rooms), {"room-a", "room-b"})
        self.assertIn("preview", rooms["room-a"])
        self.assertNotIn("preview", rooms["room-b"])
        sink: asyncio.Queue = asyncio.Queue()
        self.ad._subs.add(sink)
        self.addCleanup(self.ad._subs.discard, sink)
        await self.ad._rooms.tick()
        emitted = {e["room"]["id"] for e in [sink.get_nowait() for _ in range(sink.qsize())] if e.get("type") == "room"}
        self.assertIn("room-a", emitted)

    async def test_rooms_nobody_watched_are_inspected_for_pending_actions(self):
        # An approval that waits in a room quiet for hours: the first list and the first poll still find it.
        status, body = await self.call("GET", "/v1/rooms")
        self.assertEqual(status, 200, body)
        self.assertEqual([p["request_id"] for p in body["rooms"][0]["pending"]], ["r-9"])
        sink: asyncio.Queue = asyncio.Queue()
        self.ad._subs.add(sink)
        self.addCleanup(self.ad._subs.discard, sink)
        self.conn.pending = []  # answered elsewhere while no phone listened
        start = time.time()
        with patch.object(RM.time, "time", lambda: start + RM.COLD_SECONDS + 1):
            await self.ad._rooms.tick()  # a cold room is read again after a while
        emitted = [e for e in [sink.get_nowait() for _ in range(sink.qsize())] if e.get("type") == "room"]
        self.assertEqual(emitted[-1]["room"]["pending"], [], emitted)

    async def test_detail_carries_the_whole_command_and_the_summary_says_it_is_cut(self):
        long = "printf %s " + "x" * 5000
        self.conn.pending = [{**PENDING[0], "approval": {"command": long, "description": "long"}}]
        _, body = await self.call("GET", "/v1/rooms/room-a")
        self.assertEqual(body["driver"]["pending"][0]["command"], long)  # what is approved is what is shown
        _, body = await self.call("GET", "/v1/rooms")
        cut, = body["rooms"][0]["pending"]
        self.assertEqual((len(cut["command"]), cut["command_truncated"]), (300, True))

    async def test_upload_ids_follow_the_file_not_its_position(self):
        media = S.data_dir() / "media"
        for name in ("u-first.txt", "u-second.txt"):
            (media / name).write_text(name * 10)
            self.addCleanup((media / name).unlink, missing_ok=True)
        att = lambda name: {"id": name, "kind": "file", "file_name": name, "mime": "text/plain"}  # noqa: E731
        status, out = await self.call("POST", "/v1/rooms/room-a/messages", {
            "text": "two files", "client_id": "files-1", "attachments": [att("u-first.txt"), att("u-second.txt")]})
        self.assertEqual(status, 200, out)
        first = {p["name"]: p["upload_id"] for p in self.dispatched("groups.attachment.upload")}
        # A retry that dropped the first file keeps the second file's own upload id, never the first's.
        await self.call("POST", "/v1/rooms/room-a/messages", {
            "text": "two files", "client_id": "files-1", "attachments": [att("u-second.txt")]})
        again = self.dispatched("groups.attachment.upload")[-1]
        self.assertEqual(again["upload_id"], first["u-second.txt"])
        self.assertNotEqual(first["u-first.txt"], first["u-second.txt"])

    async def test_oversized_attachment_is_refused_before_it_is_read(self):
        from gateway.hosted_room_attachments import MAX_ATTACHMENT_BYTES
        big = S.data_dir() / "media" / "u-oversized.bin"
        with open(big, "wb") as f:
            f.truncate(MAX_ATTACHMENT_BYTES + 1)  # sparse: the size is what matters
        self.addCleanup(big.unlink, missing_ok=True)
        status, body = await self.call("POST", "/v1/rooms/room-a/messages", {
            "text": "", "client_id": "big-1",
            "attachments": [{"id": big.name, "kind": "file", "file_name": "big.bin", "mime": "application/octet-stream"}]})
        self.assertEqual(status, 413, body)
        self.assertEqual(self.dispatched("groups.attachment.upload"), [])
        self.assertEqual(self.dispatched("groups.send"), [])

    async def test_room_approval_is_its_own_signed_domain(self):
        body = {"kind": "approval", "member": "scout", "task_id": "t-1", "generation": 2, "request_id": "r-9",
                "choice": "once"}
        status, out = await self.call("POST", "/v1/rooms/room-a/actions", body)
        self.assertEqual((status, out.get("need_sig")), (403, True))
        rejected = {
            "the session-prompt form": lambda ts: f"approve\nroom-a:scout:r-9\nonce\n{ts}".encode(),
            "another request": lambda ts: room_approval("room-a", "scout", "r-OTHER", "once", ts),
            "another room": lambda ts: room_approval("room-b", "scout", "r-9", "once", ts),
        }
        for what, proof in rejected.items():
            status, _ = await self.call("POST", "/v1/rooms/room-a/actions", body, asig=proof)
            self.assertEqual(status, 403, what)
        # Values are checked before the proof: a padded member is refused, never trimmed after verifying.
        padded = {**body, "member": " scout"}
        status, _ = await self.call("POST", "/v1/rooms/room-a/actions", padded,
                                    asig=lambda ts: room_approval("room-a", " scout", "r-9", "once", ts))
        self.assertEqual(status, 400)
        self.assertEqual(self.dispatched("groups.approve"), [])
        ts = str(int(time.time()))
        proof = signature(self.akey, room_approval("room-a", "scout", "r-9", "once", ts))
        status, out = await self.call("POST", "/v1/rooms/room-a/actions", body, ts=ts, asig_value=proof)
        self.assertEqual(status, 200, out)
        self.assertEqual(self.dispatched("groups.approve"), [{
            "room_id": "room-a", "member_id": "scout", "task_id": "t-1", "execution_generation": 2,
            "choice": "once", "request_id": "r-9"}])
        status, _ = await self.call("POST", "/v1/rooms/room-a/actions", body, ts=ts, asig_value=proof)
        self.assertEqual(status, 403)  # the same proof never approves twice
        self.assertEqual(len(self.dispatched("groups.approve")), 1)


# ---------------------------------------------------------------- gateway checks (subprocess)
class StubModel(BaseHTTPRequestHandler):
    """OpenAI-compatible stub. ``hold`` blocks the turn whose prompt carries it until ``release``;
    ``tool`` makes the turn whose prompt carries it run ``command`` in the terminal first."""

    def log_message(self, *args):
        pass

    def reply(self, payload: bytes, kind="application/json"):
        self.send_response(200)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        try:
            self.wfile.write(payload)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        self.reply(json.dumps({"object": "list", "data": [{"id": "stub-room", "object": "model"}]}).encode())

    def answer(self, body, message, finish):
        if body.get("stream"):
            delta = dict(message)
            if "tool_calls" in delta:
                delta["tool_calls"] = [{"index": 0, **c} for c in delta["tool_calls"]]
            chunk = {"id": "stub", "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}
            self.reply(f"data: {json.dumps(chunk)}\n\ndata: [DONE]\n\n".encode(), "text/event-stream")
        else:
            self.reply(json.dumps({"id": "stub", "choices": [{"index": 0, "message": message, "finish_reason": finish}],
                                   "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}}).encode())

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        msgs = body.get("messages") or []
        if msgs:
            self.server.chats.append(body)
        last = max((i for i, m in enumerate(msgs) if m.get("role") == "user"), default=-1)
        prompt = json.dumps(msgs[last].get("content")) if last >= 0 else ""
        hold = self.server.hold
        if hold and hold in prompt:
            self.server.blocked.set()
            self.server.release.wait(120)
        tool = self.server.tool
        if tool and tool in prompt:
            if not any(m.get("role") == "tool" for m in msgs[last + 1:]):
                call = {"id": "call_guarded", "type": "function",
                        "function": {"name": "terminal", "arguments": json.dumps({"command": self.server.command})}}
                return self.answer(body, {"role": "assistant", "content": None, "tool_calls": [call]}, "tool_calls")
            return self.answer(body, {"role": "assistant", "content": "GUARDED_DONE"}, "stop")
        self.answer(body, {"role": "assistant", "content": "ROOM_ACK from the stub model"}, "stop")


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def wait_until(probe, timeout, what):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = probe()
        if value:
            return value
        time.sleep(0.25)
    raise AssertionError(f"timed out after {timeout}s waiting for {what}")


class Phone:
    """A paired device that signs every request like the app (P-256, canonical method/path/ts/body)."""

    def __init__(self, base, token=None):
        self.base, self.token = base, token
        self.key, self.akey = ec.generate_private_key(ec.SECP256R1()), ec.generate_private_key(ec.SECP256R1())

    def pair(self, code, name):
        out = self.request("POST", "/v1/pair", {"code": code, "device_name": name, "pub": public(self.key),
                                                 "apub": public(self.akey)}, sign=False)
        self.token, self.device = out[1]["token"], out[1]["device_id"]
        return out

    def request(self, method, path, body=None, *, sign=True, token=True, raw=None, ctype="application/json",
                sign_path=None, asig=None):
        data = raw if raw is not None else (b"" if body is None else json.dumps(body).encode())
        req = urllib.request.Request(self.base + path, data=data if method != "GET" else None, method=method)
        req.add_header("Content-Type", ctype)
        if token and self.token:
            req.add_header("Authorization", f"Bearer {self.token}")
        if sign:
            ts = str(int(time.time()))
            req.add_header("X-HM-Ts", ts)
            req.add_header("X-HM-Sig", signature(self.key, canonical(method, sign_path or path, ts, data)))
            if asig is not None:
                req.add_header("X-HM-ASig", signature(self.akey, asig(ts)))
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                payload = r.read()
                return r.status, (json.loads(payload) if r.headers.get_content_type() == "application/json" else payload)
        except urllib.error.HTTPError as e:
            with e:
                payload = e.read()
            try:
                return e.code, json.loads(payload)
            except ValueError:
                return e.code, {"raw": payload.decode(errors="replace")}

    def stream(self, sink: "queue.Queue"):
        ts = str(int(time.time()))
        req = urllib.request.Request(self.base + "/v1/events", headers={
            "Authorization": f"Bearer {self.token}", "X-HM-Ts": ts, "Accept": "text/event-stream",
            "X-HM-Sig": signature(self.key, canonical("GET", "/v1/events", ts, b""))})

        def read():
            try:
                with urllib.request.urlopen(req, timeout=300) as r:
                    for line in r:
                        if line.startswith(b"data: "):
                            sink.put(json.loads(line[6:]))
            except Exception as e:  # the stream ends with the gateway
                sink.put({"type": "closed", "error": str(e)})
        threading.Thread(target=read, daemon=True).start()


class RoomsGateway(unittest.TestCase):
    """One disposable gateway for the ordered checks below (test_11 kills and restarts it)."""

    @classmethod
    def setUpClass(cls):
        assert RM is not None, "this plugin tree has no rooms module"
        venv = owner_environment()
        assert venv is not None, "no committed dependency environment"
        cls.base = ROOT / "gateway"
        home, user, locks = cls.base / "home", cls.base / "user", cls.base / "locks"
        for d in (home, user, locks):
            d.mkdir(parents=True)
        home.chmod(0o700)
        assert not home.resolve().is_relative_to(LIVE) and not user.resolve().is_relative_to(LIVE)
        cls.model = ThreadingHTTPServer(("127.0.0.1", 0), StubModel)
        cls.model.chats, cls.model.hold, cls.model.tool, cls.model.command = [], None, None, ""
        cls.model.blocked, cls.model.release = threading.Event(), threading.Event()
        threading.Thread(target=cls.model.serve_forever, daemon=True).start()
        url = f"http://127.0.0.1:{cls.model.server_port}/v1"
        cfg = {"model": {"provider": "custom", "default": "stub-room", "base_url": url},
               "auxiliary": {"title_generation": {"enabled": False}},
               # Room members get the terminal so one can ask for a real command approval.
               "platform_toolsets": {"gui": [], "bot_room": ["terminal"], "mobile": []},
               "approvals": {"mode": "manual"},
               "terminal": {"cwd": str(home)}, "plugins": {"enabled": ["mobile"]},
               "display": {"platforms": {"mobile": {"tool_progress": "off"}}}}
        (home / "config.yaml").write_text(json.dumps(cfg))
        helper = home / "profiles" / "helper"
        helper.mkdir(parents=True)
        (helper / "config.yaml").write_text(json.dumps({k: v for k, v in cfg.items() if k != "plugins"}))
        port = free_port()
        (home / ".env").write_text(f"MOBILE_ENABLED=1\nMOBILE_BIND=127.0.0.1:{port}\nMOBILE_ALLOWED_USERS=owner\n"
                                   "OPENAI_API_KEY=loopback-only\n")
        shutil.copytree(SERVER / "mobile", home / "plugins" / "mobile", ignore=shutil.ignore_patterns("__pycache__"))
        # A home outside the user's ~/.hermes is its own Hermes root: no live profile, lock or record is reachable.
        cls.env = {"HOME": str(user), "HERMES_HOME": str(home), "HERMES_GATEWAY_LOCK_DIR": str(locks),
                   "PATH": "/usr/bin:/bin", "LANG": "C.UTF-8", "TZ": "UTC", "PYTHONUNBUFFERED": "1",
                   "HERMES_DISABLE_LAZY_INSTALLS": "1", "HERMES_ENABLE_PROJECT_PLUGINS": "false",
                   "OPENAI_API_KEY": "loopback-only", "OPENAI_BASE_URL": url, "CUDA_VISIBLE_DEVICES": ""}
        cls.python = str(venv / "bin/python")
        cls.url = f"http://127.0.0.1:{port}"
        cls.start()
        cls.phone = Phone(cls.url)
        cls.phone.pair(cls.pair_code(), "rooms-a")
        cls.wait_ready()

    @classmethod
    def start(cls):
        if getattr(cls, "log", None) is not None:
            cls.log.close()
        cls.log = open(cls.base / "gateway.out", "a+")
        cls.proc = subprocess.Popen([cls.python, "-m", "hermes_cli.main", "gateway", "run"], cwd=str(CHECKOUT),
                                    env=cls.env, stdin=subprocess.DEVNULL, stdout=cls.log, stderr=subprocess.STDOUT,
                                    start_new_session=True)
        _CHILDREN.append(cls.proc)

        def healthy():
            if cls.proc.poll() is not None:
                cls.log.seek(0)
                raise AssertionError("gateway exited:\n" + cls.log.read()[-4000:])
            try:
                with urllib.request.urlopen(cls.url + "/v1/health", timeout=2) as r:
                    return r.status == 200
            except OSError:
                return False
        wait_until(healthy, 120, "the gateway's mobile API")

    @classmethod
    def wait_ready(cls):
        wait_until(lambda: cls.phone.request("GET", "/v1/rooms")[1].get("driver"), 90, "the hosted room driver")

    @classmethod
    def pair_code(cls, ttl=300):
        """A one-use code written by the plugin's own Store into the gateway home's mobile.db."""
        code = subprocess.run([cls.python, "-c", "import sys; sys.path.insert(0, sys.argv[1]);"
                               " from mobile import store; print(store.Store().create_pair_code(int(sys.argv[2])))",
                               str(cls.base / "home" / "plugins"), str(ttl)], env=cls.env, cwd=str(CHECKOUT),
                              capture_output=True, text=True, check=True, timeout=60)
        return code.stdout.strip().splitlines()[-1]

    @classmethod
    def tearDownClass(cls):
        model = getattr(cls, "model", None)
        if model is not None:
            model.release.set()
        _stop_children()
        if model is not None:
            model.shutdown()
            model.server_close()
        if getattr(cls, "log", None) is not None:
            cls.log.close()

    def history(self, rid):
        status, page = self.phone.request("GET", f"/v1/rooms/{rid}/events?limit=200")
        self.assertEqual(status, 200, page)
        return page

    def detail(self, rid):
        status, body = self.phone.request("GET", f"/v1/rooms/{rid}")
        self.assertEqual(status, 200, body)
        return body

    def hold(self, marker):
        self.model.hold = marker
        self.model.blocked.clear()
        self.model.release.clear()

    def let_go(self):
        self.model.hold = None
        self.model.release.set()

    def test_01_requests_need_a_paired_signature(self):
        stranger = Phone(self.url)
        self.assertEqual(stranger.request("GET", "/v1/rooms", sign=False, token=False)[0], 401)
        unsigned = Phone(self.url, token=self.phone.token)
        self.assertEqual(unsigned.request("GET", "/v1/rooms", sign=False)[0], 401)
        self.assertEqual(self.phone.request("GET", "/v1/rooms", sign_path="/v1/chats")[0], 401)
        self.assertEqual(self.phone.request("GET", "/v1/rooms/anything/events")[0], 404)

    def test_02_feature_flag_and_seatable_bots(self):
        status, me = self.phone.request("GET", "/v1/me")
        self.assertEqual(status, 200)
        self.assertIs(me["features"]["rooms"], True)
        status, body = self.phone.request("GET", "/v1/rooms")
        self.assertEqual(status, 200, body)
        self.assertIs(body["driver"], True)
        self.assertEqual(sorted(b["profile"] for b in body["bots"]), ["default", "helper"])
        self.assertEqual(body["rooms"], [])

    def test_03_create_refuses_invalid_rosters(self):
        status, body = self.phone.request("POST", "/v1/rooms", {"name": "Solo", "members": ["default"]})
        self.assertEqual(status, 400, body)
        status, body = self.phone.request("POST", "/v1/rooms", {"name": "Ghost", "members": ["default", "ghost"]})
        self.assertEqual(status, 400, body)
        self.assertIn("ghost", body.get("detail", ""), body)

    def test_04_create_send_and_both_bots_answer(self):
        events: queue.Queue = queue.Queue()
        self.phone.stream(events)
        status, body = self.phone.request("POST", "/v1/rooms", {"name": "Crew", "members": ["default", "helper"],
                                                                "room_id": "crew-room"})
        self.assertEqual(status, 200, body)
        self.assertEqual([m["id"] for m in body["room"]["members"]], ["default", "helper"])
        status, sent = self.phone.request("POST", "/v1/rooms/crew-room/messages",
                                          {"text": "Plan the release, team.", "client_id": "phone-send-1"})
        self.assertEqual(status, 200, sent)
        self.assertEqual((sent["event"]["role"], sent["event"]["text"]), ("user", "Plan the release, team."))
        retry = self.phone.request("POST", "/v1/rooms/crew-room/messages",
                                   {"text": "Plan the release, team.", "client_id": "phone-send-1"})[1]
        self.assertEqual(retry["event"]["seq"], sent["event"]["seq"])  # an app retry never posts twice

        def answered():
            members = [e for e in self.history("crew-room")["events"] if e["kind"] == "message" and e["role"] == "member"]
            return members if {e["member"] for e in members} == {"default", "helper"} else None
        replies = wait_until(answered, 120, "both Bots to answer through the room driver")
        self.assertTrue(all(e["text"] == "ROOM_ACK from the stub model" for e in replies))
        self.assertGreaterEqual(len(self.model.chats), 2)
        page = self.history("crew-room")
        user = [e for e in page["events"] if e["role"] == "user"]
        self.assertEqual(len(user), 1)
        older = self.phone.request("GET", f"/v1/rooms/crew-room/events?before={replies[-1]['seq']}&limit=200")[1]
        self.assertNotIn(replies[-1]["seq"], [e["seq"] for e in older["events"]])
        self.assertIn(sent["event"]["seq"], [e["seq"] for e in older["events"]])
        delta = self.phone.request("GET", f"/v1/rooms/crew-room/events?after={sent['event']['seq']}")[1]
        self.assertEqual({e["seq"] for e in delta["events"] if e["kind"] == "message"}, {e["seq"] for e in replies})

        def room_event():
            while True:
                try:
                    e = events.get(timeout=1)
                except queue.Empty:
                    return None
                if e.get("type") == "room" and e["room"]["id"] == "crew-room" and e["room"]["latest_seq"] >= replies[-1]["seq"]:
                    return e
        live = wait_until(room_event, 30, "an SSE room event after the replies")
        self.assertEqual(live["room"]["unread"], 2, live)
        listed = self.phone.request("GET", "/v1/rooms")[1]["rooms"]
        self.assertEqual([(r["id"], r["unread"]) for r in listed], [("crew-room", 2)])
        self.assertEqual(listed[0]["preview"]["role"], "member")
        type(self).events, type(self).latest = events, page["latest_seq"]

    def test_05_read_cursor_clears_unread_everywhere(self):
        status, body = self.phone.request("POST", "/v1/rooms/crew-room/read", {"seq": self.latest})
        self.assertEqual(status, 200, body)
        self.assertEqual(self.phone.request("GET", "/v1/rooms")[1]["rooms"][0]["unread"], 0)

        def cleared():
            while True:
                try:
                    e = self.events.get(timeout=1)
                except queue.Empty:
                    return None
                if e.get("type") == "room" and e["room"]["id"] == "crew-room" and e["room"]["unread"] == 0:
                    return e
        wait_until(cleared, 15, "the SSE room event with the read cursor")

    def test_06_attachments_round_trip(self):
        data = b"release checklist\n" * 40
        status, up = self.phone.request("POST", "/v1/upload?name=checklist.txt&mime=text%2Fplain", raw=data,
                                        ctype="application/octet-stream")
        self.assertEqual(status, 200, up)
        status, sent = self.phone.request("POST", "/v1/rooms/crew-room/messages", {
            "text": "", "client_id": "phone-send-file",
            "attachments": [{"id": up["id"], "kind": "file", "file_name": "checklist.txt", "mime": "text/plain"}]})
        self.assertEqual(status, 200, sent)
        att, = sent["event"]["attachments"]
        self.assertEqual((att["name"], att["size"]), ("checklist.txt", len(data)))
        status, got = self.phone.request("GET", f"/v1/rooms/crew-room/files/{sent['event']['id']}/{att['attachment_id']}")
        self.assertEqual((status, got), (200, data))

    def test_07_room_approvals_need_the_biometric_key(self):
        body = {"kind": "approval", "member": "default", "task_id": "t", "generation": 1, "request_id": "r",
                "choice": "once"}
        status, out = self.phone.request("POST", "/v1/rooms/crew-room/actions", body)
        self.assertEqual((status, out.get("need_sig")), (403, True), out)
        status, out = self.phone.request("POST", "/v1/rooms/crew-room/actions", {**body, "choice": "deny"})
        self.assertEqual(status, 409, out)  # reaches Hermes, which has nothing pending

    def test_08_stop_cancels_a_running_turn(self):
        self.wait_settled("crew-room")
        self.hold("STOP_THIS_TURN")
        status, _ = self.phone.request("POST", "/v1/rooms/crew-room/messages",
                                       {"text": "@default STOP_THIS_TURN", "client_id": "phone-stop-1"})
        self.assertEqual(status, 200)
        self.assertTrue(self.model.blocked.wait(60), "the member turn never reached the model")
        self.assertIn("default", [m["id"] for m in self.detail("crew-room")["driver"]["members"]])
        try:
            status, body = self.phone.request("POST", "/v1/rooms/crew-room/stop")
            self.assertEqual(status, 200, body)
            self.assertGreaterEqual(body["cancelled"], 1, body)
        finally:
            self.let_go()

        def stopped():
            notices = {e.get("notice") for e in self.history("crew-room")["events"] if e["kind"] == "notice"}
            return {"room.stop_requested", "turn.cancelled"} <= notices and not self.detail("crew-room")["driver"]["working"]
        wait_until(stopped, 60, "the stop notice and the cancelled turn")

    def wait_settled(self, rid):
        wait_until(lambda: not self.detail(rid)["driver"]["working"], 60, f"{rid} to settle")

    def test_09_a_real_command_approval(self):
        target = self.base / "home" / "approved-delete-target"
        target.mkdir()
        self.model.tool, self.model.command = "RUN_GUARDED", f"rm -rf {target}"
        self.addCleanup(setattr, self.model, "tool", None)
        status, _ = self.phone.request("POST", "/v1/rooms/crew-room/messages",
                                       {"text": "@default RUN_GUARDED", "client_id": "phone-guarded-1"})
        self.assertEqual(status, 200)

        def asked():
            pending = [a for a in self.detail("crew-room")["driver"]["pending"] if a["kind"] == "approval"]
            return pending[0] if pending else None
        action = wait_until(asked, 90, "the member's command approval")
        self.assertIn(str(target), action["command"])
        listed = {r["id"]: r for r in self.phone.request("GET", "/v1/rooms")[1]["rooms"]}
        self.assertEqual([p["request_id"] for p in listed["crew-room"]["pending"]], [action["request_id"]])
        body = {"kind": "approval", "member": action["member"], "task_id": action["task_id"],
                "generation": action["generation"], "request_id": action["request_id"], "choice": "once"}
        session_form = lambda ts: f"approve\ncrew-room:{action['member']}:{action['request_id']}\nonce\n{ts}".encode()  # noqa: E731
        self.assertEqual(self.phone.request("POST", "/v1/rooms/crew-room/actions", body, asig=session_form)[0], 403)
        self.assertTrue(target.exists())
        proof = lambda ts: room_approval("crew-room", action["member"], action["request_id"], "once", ts)  # noqa: E731
        status, out = self.phone.request("POST", "/v1/rooms/crew-room/actions", body, asig=proof)
        self.assertEqual(status, 200, out)

        def ran():
            done = any(e.get("text") == "GUARDED_DONE" for e in self.history("crew-room")["events"])
            return done and not target.exists() and not self.detail("crew-room")["driver"]["pending"]
        wait_until(ran, 90, "the approved command to run and the member to answer")

    def test_10_rename_then_delete(self):
        status, body = self.phone.request("PATCH", "/v1/rooms/crew-room", {"name": "Release crew"})
        self.assertEqual((status, body["room"]["name"]), (200, "Release crew"))

        def deleted():
            status, body = self.phone.request("DELETE", "/v1/rooms/crew-room")
            return body if status == 200 else None  # 409 while a member turn is still stopping
        wait_until(deleted, 60, "the room to be deleted")
        self.assertEqual(self.phone.request("GET", "/v1/rooms")[1]["rooms"], [])
        self.assertEqual(self.phone.request("GET", "/v1/rooms/crew-room")[0], 404)

    def test_11_unknown_turn_after_a_crash_refuses_retry_and_discards(self):
        status, _ = self.phone.request("POST", "/v1/rooms", {"name": "Recovery", "members": ["default", "helper"],
                                                             "room_id": "recovery-room"})
        self.assertEqual(status, 200)
        self.hold("CRASH_DURING_THIS_TURN")
        status, _ = self.phone.request("POST", "/v1/rooms/recovery-room/messages",
                                       {"text": "@default CRASH_DURING_THIS_TURN", "client_id": "phone-crash-1"})
        self.assertEqual(status, 200)
        self.assertTrue(self.model.blocked.wait(60), "the member turn never reached the model")
        crashed = self.proc
        os.killpg(crashed.pid, signal.SIGKILL)  # the owner dies with the turn started
        crashed.wait(timeout=20)
        self.let_go()
        type(self).start()
        self.wait_ready()

        def offered():
            actions = [a for a in self.detail("recovery-room")["driver"]["pending"] if a["kind"] == "discard"]
            return actions[0] if actions else None
        action = wait_until(offered, 120, "Hermes to offer discarding the unknown turn")
        control = {"member": action["member"], "task_id": action["task_id"], "generation": action["generation"]}
        status, out = self.phone.request("POST", "/v1/rooms/recovery-room/actions", {"kind": "retry", **control})
        self.assertEqual((status, out.get("reason")), (409, "unknown_execution"), out)
        status, out = self.phone.request("POST", "/v1/rooms/recovery-room/actions", {"kind": "discard", **control})
        self.assertEqual(status, 200, out)
        wait_until(lambda: not self.detail("recovery-room")["driver"]["pending"], 60, "the discard to clear the action")

    def test_12_a_revoked_device_reads_nothing(self):
        other = Phone(self.url)
        other.pair(self.pair_code(), "rooms-b")
        self.assertEqual(other.request("GET", "/v1/rooms")[0], 200)
        status, _ = self.phone.request("DELETE", f"/v1/devices/{other.device}")
        self.assertEqual(status, 200)
        self.assertEqual(other.request("GET", "/v1/rooms")[0], 401)
        self.assertEqual(self.phone.request("GET", "/v1/rooms")[0], 200)

    def test_13_a_terminated_harness_stops_its_gateway(self):
        env = {k: v for k, v in os.environ.items() if k not in ("HERMES_HOME",)}
        child = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), "--server-source", str(SERVER),
                                  "--serve", "600"], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                 text=True, start_new_session=True)
        gateway_pid, root, lines = None, None, []
        try:
            deadline = time.monotonic() + 180
            while (gateway_pid is None or root is None) and time.monotonic() < deadline:
                line = child.stdout.readline()
                if not line:
                    break
                lines.append(line)
                if line.startswith("Gateway pid: "):
                    gateway_pid = int(line.split(": ", 1)[1])
                if line.startswith("Harness root: "):
                    root = Path(line.split(": ", 1)[1].strip())
            self.assertIsNotNone(gateway_pid, "".join(lines)[-3000:])
            self.assertTrue(root is not None and root.is_dir())
            child.send_signal(signal.SIGTERM)
            child.wait(timeout=90)
            wait_until(lambda: not _alive(gateway_pid), 30, "the child's gateway to stop")
            self.assertFalse(root.exists(), "the terminated harness left its home behind")
        finally:
            if child.poll() is None:
                os.killpg(child.pid, signal.SIGKILL)
                child.wait(timeout=10)
            child.stdout.close()
            if gateway_pid is not None and _alive(gateway_pid):
                os.killpg(gateway_pid, signal.SIGKILL)  # never leak a gateway, even when the check fails
            if root is not None and root.exists() and root.resolve().parent == ROOT.resolve().parent:
                shutil.rmtree(root, ignore_errors=True)


def _alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    try:  # a zombie of a process we do not reap still answers kill(0)
        return Path(f"/proc/{pid}/stat").read_text().split(")", 1)[1].split()[0] != "Z"
    except OSError:
        return False


def serve(seconds: float) -> None:
    """Keep the disposable gateway up for an emulator: a seeded room, a pairing link, then full cleanup."""
    try:
        RoomsGateway.setUpClass()
        g = RoomsGateway
        status, body = g.phone.request("POST", "/v1/rooms", {"name": "Release crew", "members": ["default", "helper"]})
        assert status == 200, body
        rid = body["room"]["id"]
        g.phone.request("POST", f"/v1/rooms/{rid}/messages", {"text": "Plan the release, team.", "client_id": "seed-1"})
        port = g.url.rsplit(":", 1)[1]
        link = f"hermes://pair?u=http%3A%2F%2F10.0.2.2%3A{port}&c={g.pair_code(900)}&n=rooms-test"
        print(f"\nDisposable gateway: {g.url} (emulator: http://10.0.2.2:{port})\nPair link (one use, 15 minutes):\n{link}\n"
              f"Gateway log: {g.base / 'home/logs/gateway.log'}\nGateway pid: {g.proc.pid}\nHarness root: {ROOT}\n"
              f"Up for {seconds:.0f} s; Ctrl-C or SIGTERM stops it and removes the home.", flush=True)
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline and g.proc.poll() is None:
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        RoomsGateway.tearDownClass()


if __name__ == "__main__":
    if "--serve" in sys.argv:
        at = sys.argv.index("--serve")
        tail = sys.argv[at + 1:at + 2]
        serve(float(tail[0]) if tail and tail[0].replace(".", "", 1).isdigit() else 1800)
    else:
        unittest.main(verbosity=2)
