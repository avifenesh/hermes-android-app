"""Group Chat room routes against Hermes' real room store and room driver. No live gateway, device or data.

Run directly with Hermes' managed Python (the interpreter `hermes` runs):
    env -u HERMES_HOME "$HERMES_PYTHON" scripts/rooms_integration.py [--server-source DIR]

Contract checks (in process): the plugin's rooms routes behind the real app and auth middleware, with a
scripted authority connection: the features.rooms flag, event projection, the unread cursor, error mapping
and the biometric gate on room approvals.

Gateway checks (subprocess): a real `gateway run` in a disposable HOME, HERMES_HOME and lock directory,
serving two Bot profiles, with the mobile plugin from this checkout and a loopback stub model. Requests
signed like the app's create a room, send, page history, receive the SSE room event and watch both Bots
answer through Hermes' own room driver. Requests without a token, without a signature or from a revoked
device are refused. The gateway runs in the committed dependency environment, so the disposable home
installs nothing. ``--server-source`` points both parts at another plugin tree (red runs against main).
"""
import atexit
import os
from pathlib import Path
import shutil
import sys
import tempfile

if "hermes_bootstrap" in sys.modules or "hermes_constants" in sys.modules:
    raise RuntimeError("Run this harness directly in a fresh Hermes Python process")
SCRATCH = Path.home() / ".hermes/cache/scratch"
SCRATCH.mkdir(parents=True, exist_ok=True)
# hermes_bootstrap may re-exec this script into the managed runtime. The child inherits this marker,
# reuses the same disposable root and owns its cleanup, so a relaunch never leaves a home behind.
_MARK = "HM_ROOMS_INTEGRATION_ROOT"
_inherited = Path(os.environ.get(_MARK) or "/nonexistent")
if _inherited.is_dir() and _inherited.resolve().parent == SCRATCH.resolve():
    ROOT = _inherited
else:
    ROOT = Path(tempfile.mkdtemp(prefix="mobile-rooms-", dir=SCRATCH))
    os.environ[_MARK] = str(ROOT)
atexit.register(shutil.rmtree, str(ROOT), ignore_errors=True)
os.environ["HERMES_HOME"] = str(ROOT / "phone")  # FORCE, never inherit a gateway home
os.environ["HERMES_ENABLE_PROJECT_PLUGINS"] = "false"
os.environ["HERMES_DISABLE_LAZY_INSTALLS"] = "1"
(ROOT / "phone").mkdir(exist_ok=True)
LIVE = (Path.home() / ".hermes").resolve()
assert Path(os.environ["HERMES_HOME"]).resolve() != LIVE
CHECKOUT = Path.home() / ".hermes/hermes-agent"
sys.path.insert(0, str(CHECKOUT))
import hermes_bootstrap  # noqa: E402,F401

SERVER = Path(__file__).resolve().parents[1] / "server"
if "--server-source" in sys.argv:
    SERVER = Path(sys.argv[sys.argv.index("--server-source") + 1]).resolve()
    del sys.argv[sys.argv.index("--server-source"):sys.argv.index("--server-source") + 2]
sys.path.insert(0, str(SERVER))

import asyncio  # noqa: E402
import base64  # noqa: E402
import hashlib  # noqa: E402
import json  # noqa: E402
import queue  # noqa: E402
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
from mobile import adapter as AD, store as S  # noqa: E402

try:
    from mobile import rooms as RM  # noqa: E402
except ImportError:  # a plugin tree from before rooms: every check below must fail, not skip
    RM = None

assert S.data_dir().resolve().is_relative_to(ROOT.resolve())
platform_registry.register(PlatformEntry(name="mobile", label="Hermes Mobile", adapter_factory=AD.MobileAdapter,
                                         check_fn=AD.check_requirements, source="plugin", plugin_name="mobile"))


def public(key):
    return base64.b64encode(key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()


def signature(key, message: bytes) -> str:
    return base64.b64encode(key.sign(message, ec.ECDSA(hashes.SHA256()))).decode()


def canonical(method, path, ts, body: bytes) -> bytes:
    return f"{method}\n{path}\n{ts}\n{hashlib.sha256(body).hexdigest()}".encode()


# ---------------------------------------------------------------- contract checks (in process)
ROOM = {"room_id": "room-a", "name": "Crew", "authority_gateway_id": "install:x", "authority_epoch": 1,
        "revision": 1, "created_at": 100.0, "updated_at": 200.0, "latest_seq": 6,
        "members": [{"member_id": "scout", "profile": "scout", "handle": "scout", "display_name": "Scout"},
                    {"member_id": "scribe", "profile": "scribe", "handle": "scribe"}]}


def event(seq, kind, actor, **payload):
    return {"room_id": "room-a", "seq": seq, "event_id": f"e{seq}", "kind": kind, "actor": actor,
            "authority_epoch": 1, "payload": payload, "created_at": 100.0 + seq}


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
        self.room = dict(ROOM)
        self.log = list(LOG)
        self.refuse = {}

    async def dispatch(self, request):
        method, params = request["method"], request["params"]
        self.calls.append((method, params))
        if method in self.refuse:
            code, reason = self.refuse[method]
            return {"jsonrpc": "2.0", "id": request["id"], "error": {"code": code, "message": reason,
                                                                       "data": {"reason": reason}}}
        result = {
            "groups.capabilities": lambda: {"driver": True, "seatable_profiles": ["scout", "scribe"]},
            "profiles.list": lambda: {"profiles": [{"name": "scout", "display_name": "Scout"}]},
            "groups.list": lambda: {"rooms": [self.room], "next_offset": None},
            "groups.state": lambda: {"room": self.room, "driver_status": {
                "running": True, "working": True, "member_activity": [{"member_id": "scribe", "status": "running"}],
                "pending_actions": [{"kind": "approval", "member_id": "scout", "task_id": "t-1",
                                     "execution_generation": 2, "request_id": "r-9",
                                     "approval": {"command": "rm -rf build", "description": "clean"}}]}},
            "groups.log": lambda: {"events": [e for e in self.log if params["since_seq"] < e["seq"]][:params["limit"]],
                                   "latest_seq": self.room["latest_seq"]},
            "groups.approve": lambda: {"approved": True, "result": {}},
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
        app = self.ad._build_app()
        self.key, self.akey = ec.generate_private_key(ec.SECP256R1()), ec.generate_private_key(ec.SECP256R1())
        claim = self.ad.store.claim_pair_code(self.ad.store.create_pair_code(), "rooms-check",
                                              public(self.key), public(self.akey))
        self.token = claim["token"]
        self.client = TestClient(TestServer(app))
        await self.client.start_server()
        self.addAsyncCleanup(self.client.close)

    async def call(self, method, path, body=None, asig=None):
        raw = b"" if body is None else json.dumps(body).encode()
        ts = str(int(time.time()))
        headers = {"Authorization": f"Bearer {self.token}", "X-HM-Ts": ts,
                   "X-HM-Sig": signature(self.key, canonical(method, path, ts, raw)), "Content-Type": "application/json"}
        if asig is not None:
            headers["X-HM-ASig"] = signature(self.akey, asig(ts))
        resp = await self.client.request(method, path, data=raw or None, headers=headers)
        return resp.status, await resp.json()

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

    async def test_listing_projects_rooms_and_unread_cursor(self):
        status, body = await self.call("GET", "/v1/rooms")
        self.assertEqual(status, 200, body)
        room, = body["rooms"]
        # First sight: earlier history is not news.
        self.assertEqual((room["read_seq"], room["unread"]), (6, 0))
        self.assertEqual(room["preview"], {"seq": 6, "role": "member", "name": "scribe", "text": "Done.", "ts": 106.0})
        self.assertEqual([b["name"] for b in body["bots"]], ["Scout", "scribe"])
        self.conn.room = {**ROOM, "latest_seq": 8, "updated_at": 300.0}
        self.conn.log += [event(7, "message.member", {"kind": "member", "id": "scout"}, text="One more.", thread_id="t1"),
                          event(8, "message.user", {"kind": "user", "id": "desktop"}, text="thanks", thread_id="t1")]
        _, body = await self.call("GET", "/v1/rooms")
        self.assertEqual(body["rooms"][0]["unread"], 1)  # the member's reply counts, the owner's own words do not
        status, read = await self.call("POST", "/v1/rooms/room-a/read", {"seq": 99})
        self.assertEqual((status, read["read_seq"]), (200, 8))  # clamped to the log
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

    async def test_room_refusals_map_to_http(self):
        self.conn.refuse["groups.state"] = (4001, "permission_denied")
        status, body = await self.call("GET", "/v1/rooms/room-z")
        self.assertEqual((status, body["error"]), (404, "Group chat not found"))
        self.conn.refuse["groups.state"] = (-32601, "unknown method: groups.state")
        status, body = await self.call("GET", "/v1/rooms/room-z")
        self.assertEqual(status, 501)

    async def test_room_approval_needs_the_biometric_key(self):
        body = {"kind": "approval", "member": "scout", "task_id": "t-1", "generation": 2, "request_id": "r-9",
                "choice": "once"}
        status, out = await self.call("POST", "/v1/rooms/room-a/actions", body)
        self.assertEqual((status, out.get("need_sig")), (403, True))
        swapped = lambda ts: f"approve\nroom-a:scout:r-OTHER\nonce\n{ts}".encode()  # noqa: E731
        status, _ = await self.call("POST", "/v1/rooms/room-a/actions", body, asig=swapped)
        self.assertEqual(status, 403)
        self.assertNotIn("groups.approve", [m for m, _ in self.conn.calls])
        exact = lambda ts: f"approve\nroom-a:scout:r-9\nonce\n{ts}".encode()  # noqa: E731
        status, out = await self.call("POST", "/v1/rooms/room-a/actions", body, asig=exact)
        self.assertEqual(status, 200, out)
        self.assertEqual(self.conn.calls[-1], ("groups.approve", {
            "room_id": "room-a", "member_id": "scout", "task_id": "t-1", "execution_generation": 2,
            "choice": "once", "request_id": "r-9"}))


# ---------------------------------------------------------------- gateway checks (subprocess)
class StubModel(BaseHTTPRequestHandler):
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

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if body.get("messages"):
            self.server.chats.append(body)
        message = {"role": "assistant", "content": "ROOM_ACK from the stub model"}
        if body.get("stream"):
            chunk = {"id": "stub", "choices": [{"index": 0, "delta": message, "finish_reason": "stop"}]}
            self.reply(f"data: {json.dumps(chunk)}\n\ndata: [DONE]\n\n".encode(), "text/event-stream")
        else:
            self.reply(json.dumps({"id": "stub", "choices": [{"index": 0, "message": message, "finish_reason": "stop"}],
                                   "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}}).encode())


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
                sign_path=None):
        data = raw if raw is not None else (b"" if body is None else json.dumps(body).encode())
        req = urllib.request.Request(self.base + path, data=data if method != "GET" else None, method=method)
        req.add_header("Content-Type", ctype)
        if token and self.token:
            req.add_header("Authorization", f"Bearer {self.token}")
        if sign:
            ts = str(int(time.time()))
            req.add_header("X-HM-Ts", ts)
            req.add_header("X-HM-Sig", signature(self.key, canonical(method, sign_path or path, ts, data)))
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
    """One disposable gateway for the ordered checks below."""

    @classmethod
    def setUpClass(cls):
        assert RM is not None, "this plugin tree has no rooms module"
        from pm.environments import committed_venv
        venv = committed_venv(CHECKOUT)
        assert venv is not None and (Path(venv) / "bin/python").is_file(), "no committed dependency environment"
        cls.base = ROOT / "gateway"
        home, user, locks = cls.base / "home", cls.base / "user", cls.base / "locks"
        for d in (home, user, locks):
            d.mkdir(parents=True)
        home.chmod(0o700)
        assert LIVE not in (home.resolve(), user.resolve()) and not str(home.resolve()).startswith(str(LIVE) + "/profiles")
        cls.model = ThreadingHTTPServer(("127.0.0.1", 0), StubModel)
        cls.model.chats = []
        threading.Thread(target=cls.model.serve_forever, daemon=True).start()
        url = f"http://127.0.0.1:{cls.model.server_port}/v1"
        cfg = {"model": {"provider": "custom", "default": "stub-room", "base_url": url},
               "auxiliary": {"title_generation": {"enabled": False}},
               "platform_toolsets": {"gui": [], "bot_room": [], "mobile": []},
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
        cls.python = str(Path(venv) / "bin/python")
        cls.log = open(cls.base / "gateway.out", "w+")
        cls.proc = subprocess.Popen([cls.python, "-m", "hermes_cli.main", "gateway", "run"], cwd=str(CHECKOUT),
                                    env=cls.env, stdin=subprocess.DEVNULL, stdout=cls.log, stderr=subprocess.STDOUT,
                                    start_new_session=True)
        cls.url = f"http://127.0.0.1:{port}"

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
        cls.phone = Phone(cls.url)
        cls.phone.pair(cls.pair_code(), "rooms-a")
        wait_until(lambda: cls.phone.request("GET", "/v1/rooms")[1].get("driver"), 90, "the hosted room driver")

    @classmethod
    def pair_code(cls):
        """A one-use code written by the plugin's own Store into the gateway home's mobile.db."""
        code = subprocess.run([cls.python, "-c", "import sys; sys.path.insert(0, sys.argv[1]);"
                               " from mobile import store; print(store.Store().create_pair_code(300))",
                               str(cls.base / "home" / "plugins")], env=cls.env, cwd=str(CHECKOUT),
                              capture_output=True, text=True, check=True, timeout=60)
        return code.stdout.strip().splitlines()[-1]

    @classmethod
    def tearDownClass(cls):
        proc = getattr(cls, "proc", None)
        if proc is not None and proc.poll() is None:
            os.killpg(proc.pid, signal.SIGTERM)
            try:
                proc.wait(timeout=30)
            except subprocess.TimeoutExpired:
                os.killpg(proc.pid, signal.SIGKILL)
                proc.wait(timeout=10)
        if getattr(cls, "model", None) is not None:
            cls.model.shutdown()
            cls.model.server_close()
        if getattr(cls, "log", None) is not None:
            cls.log.close()

    def history(self, rid):
        status, page = self.phone.request("GET", f"/v1/rooms/{rid}/events?limit=200")
        self.assertEqual(status, 200, page)
        return page

    def test_01_requests_need_a_paired_signature(self):
        stranger = Phone(self.url)
        self.assertEqual(stranger.request("GET", "/v1/rooms", sign=False, token=False)[0], 401)
        unsigned = Phone(self.url, token=self.phone.token)
        self.assertEqual(unsigned.request("GET", "/v1/rooms", sign=False)[0], 401)
        self.assertEqual(self.phone.request("GET", "/v1/rooms", sign_path="/v1/chats")[0], 401)
        self.assertEqual(self.phone.request("GET", "/v1/rooms/anything/events")[0] // 100, 4)

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
        self.assertEqual(status // 100, 4, out)  # reaches Hermes, which has nothing pending

    def test_08_rename_then_delete(self):
        status, body = self.phone.request("PATCH", "/v1/rooms/crew-room", {"name": "Release crew"})
        self.assertEqual((status, body["room"]["name"]), (200, "Release crew"))

        def deleted():
            status, body = self.phone.request("DELETE", "/v1/rooms/crew-room")
            return body if status == 200 else None  # 409 while a member turn is still stopping
        wait_until(deleted, 60, "the room to be deleted")
        self.assertEqual(self.phone.request("GET", "/v1/rooms")[1]["rooms"], [])
        self.assertEqual(self.phone.request("GET", "/v1/rooms/crew-room")[0] // 100, 4)

    def test_09_a_revoked_device_reads_nothing(self):
        other = Phone(self.url)
        other.pair(self.pair_code(), "rooms-b")
        self.assertEqual(other.request("GET", "/v1/rooms")[0], 200)
        status, _ = self.phone.request("DELETE", f"/v1/devices/{other.device}")
        self.assertEqual(status, 200)
        self.assertEqual(other.request("GET", "/v1/rooms")[0], 401)
        self.assertEqual(self.phone.request("GET", "/v1/rooms")[0], 200)


if __name__ == "__main__":
    unittest.main(verbosity=2)
