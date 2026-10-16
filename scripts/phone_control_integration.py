"""Narrow phone transport integration checks; no gateway, deployment or real FCM.

Run: "$HERMES_PYTHON" scripts/phone_control_integration.py
Set HERMES_PYTHON to the installed Hermes managed-runtime Python, not a stale venv.
The harness force-isolates Hermes BEFORE bootstrap or any application import.
Uses actual aiohttp routes, SQLite Store, request auth, replay checks and biometric guard.
Only FCM credentials/network are substituted; the production target selection is exercised.
No plugin discovery, running gateway, real credentials or live state are used.
"""
import atexit
import os
from pathlib import Path
import shutil
import sys
import tempfile

# Run bare, not through a caller that has already cached a production Hermes home.
if "hermes_bootstrap" in sys.modules or "hermes_constants" in sys.modules:
    raise RuntimeError("Run this harness directly in a fresh managed-Python process")
SCRATCH = Path.home() / ".hermes/cache/scratch"
SCRATCH.mkdir(parents=True, exist_ok=True)
_HOME = tempfile.TemporaryDirectory(prefix="mobile-phone-", dir=SCRATCH)
# Register before Hermes imports so this runs AFTER their shutdown hooks, which
# can recreate data-root directories after the explicit finally cleanup.
atexit.register(shutil.rmtree, _HOME.name, ignore_errors=True)
os.environ["HERMES_HOME"] = _HOME.name
os.environ["HERMES_ENABLE_PROJECT_PLUGINS"] = "false"
# Use the installed dependency graph; never auto-update the checkout or re-exec
# bootstrap into another interpreter (which would abandon this temporary home).
os.environ["HERMES_DISABLE_LAZY_INSTALLS"] = "1"
assert Path(os.environ["HERMES_HOME"]).resolve() != (Path.home() / ".hermes").resolve()
sys.path.insert(0, str(Path.home() / ".hermes/hermes-agent"))
import hermes_bootstrap  # noqa: E402,F401
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))

import asyncio
import base64
import hashlib
import json
import time
import unittest
from unittest.mock import patch

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from mobile import adapter as AD, phone as PH, sig as SG, store as S

assert S.data_dir().resolve().is_relative_to(Path(_HOME.name).resolve())


def public(key):
    return base64.b64encode(key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()


def sign(key, message):
    return base64.b64encode(key.sign(message, ec.ECDSA(hashes.SHA256()))).decode()


class PhoneIntegration(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="routes-", dir=_HOME.name)
        self.ad = object.__new__(AD.MobileAdapter)
        self.ad.store = S.Store(Path(self.tmp.name) / "mobile.db")
        self.ad._subs = set()
        self.ad._working = {}
        self.ad._replay = SG.Replay()
        self.ad._ensure_mirror = lambda: None
        self.ad._phone = PH.Phone(self.ad)
        self.phone = self.ad._phone
        self.devices = []
        for name in ("A", "B", "C"):
            key, arm_key = ec.generate_private_key(ec.SECP256R1()), ec.generate_private_key(ec.SECP256R1())
            claim = self.ad.store.claim_pair_code(self.ad.store.create_pair_code(), name, public(key), public(arm_key))
            self.devices.append({**claim, "key": key, "arm_key": arm_key})
        self.a, self.b, self.c = self.devices
        self.posts = []
        posts = self.posts

        class FCMClient:
            def __init__(self, **kwargs):
                pass

            async def __aenter__(self):
                return self

            async def __aexit__(self, *args):
                pass

            async def post(self, url, json, headers):
                posts.append(json)
                return type("Response", (), {"status_code": 200})()

        self.patches = [patch.object(AD, "_fcm_config", return_value={"project_id": "test"}),
                        patch.object(AD, "_fcm_token", return_value="test-only"),
                        patch("httpx.AsyncClient", FCMClient)]
        for p in self.patches:
            p.start()
        for i, d in enumerate(self.devices):
            self.ad.store.set_fcm(d["device_id"], f"test-device-{i}")

        @web.middleware
        async def auth(request, handler):
            return await self.ad._auth_mw(request, handler)

        app = web.Application(middlewares=[auth])
        PH.routes(app.router, self.ad, self.ad._json)
        app.router.add_get("/v1/events", self.ad.h_events)
        app.router.add_delete("/v1/devices/{did}", self.ad.h_revoke)
        self.client = TestClient(TestServer(app))
        await self.client.start_server()
        self.tasks = []

    async def asyncTearDown(self):
        for t in self.tasks:
            t.cancel()
        await asyncio.gather(*self.tasks, return_exceptions=True)
        await self.client.close()
        for p in reversed(self.patches):
            p.stop()
        self.ad.store._db.close()
        self.tmp.cleanup()

    def headers(self, device, method, path, data=b"", biometric=False):
        ts = str(int(time.time()))
        h = {"Authorization": "Bearer " + device["token"], "X-HM-Ts": ts,
             "X-HM-Sig": sign(device["key"], SG.canonical(method, path, ts, data))}
        if biometric:
            digest = hashlib.sha256(b"true").hexdigest()
            h["X-HM-ASig"] = sign(device["arm_key"], f"phone\narm\n{digest}\n{ts}".encode())
        return h

    async def request(self, device, method, path, body=None, biometric=False):
        data = json.dumps(body).encode() if body is not None else b""
        r = await self.client.request(method, path, data=data,
                                      headers=self.headers(device, method, path, data, biometric))
        return r.status, await r.json()

    async def arm(self, device):
        status, _ = await self.request(device, "POST", "/v1/phone/arm", {"on": True}, True)
        self.assertEqual(status, 200)

    def run_command(self, action="state", timeout=2, **args):
        task = asyncio.create_task(self.phone.run({"action": action, **args}, timeout))
        self.tasks.append(task)
        return task

    async def pending(self, device):
        status, body = await self.request(device, "GET", "/v1/phone/pending")
        self.assertEqual(status, 200)
        return body["cmds"]

    async def command(self, device):
        async with asyncio.timeout(2):
            while True:
                commands = await self.pending(device)
                if commands:
                    return commands[0]
                await asyncio.sleep(0.01)

    async def answer(self, device, cid):
        return await self.request(device, "POST", "/v1/phone/result", {"id": cid, "ok": True, "result": {"test": True}})

    async def test_auth_and_biometric_arm(self):
        r = await self.client.get("/v1/phone/pending")
        self.assertEqual(r.status, 401)
        status, body = await self.request(self.a, "POST", "/v1/phone/arm", {"on": True})
        self.assertEqual(status, 403)
        self.assertTrue(body["need_sig"])
        await self.arm(self.a)
        status, body = await self.request(self.a, "GET", "/v1/phone")
        self.assertEqual(status, 200)
        self.assertTrue(body["this"])
        h = self.headers(self.a, "GET", "/v1/phone")
        r = await self.client.get("/v1/phone", headers=h)
        self.assertEqual(r.status, 200)
        r = await self.client.get("/v1/phone", headers=h)
        self.assertEqual(r.status, 401)
        # Arm signature for the wrong normalized value must not widen power.
        data = b'{"on":true}'
        h = self.headers(self.b, "POST", "/v1/phone/arm", data)
        h["X-HM-ASig"] = sign(self.b["arm_key"], f'phone\narm\n{hashlib.sha256(b"false").hexdigest()}\n{h["X-HM-Ts"]}'.encode())
        r = await self.client.post("/v1/phone/arm", data=data, headers=h)
        self.assertEqual(r.status, 403)

    async def test_targeted_sse_fcm_and_result(self):
        await self.arm(self.a)
        streams = []
        for d in (self.a, self.b):
            stream = await self.client.get("/v1/events", headers=self.headers(d, "GET", "/v1/events"))
            self.assertIn(b'"hello"', await stream.content.readline())
            await stream.content.readline()
            streams.append(stream)
        task = self.run_command("type", text="private-command-text")
        cmd = await self.command(self.a)
        self.assertEqual(cmd["device"], self.a["device_id"])
        self.assertGreater(cmd["expires"], cmd["ts"])
        self.assertEqual(await self.pending(self.b), [])
        hint = await asyncio.wait_for(streams[0].content.readline(), 1)
        self.assertIn(b'"phone"', hint)
        self.assertNotIn(b"private-command-text", hint)
        with self.assertRaises(TimeoutError):
            await asyncio.wait_for(streams[1].content.readline(), 0.05)
        async with asyncio.timeout(2):
            while not self.posts:
                await asyncio.sleep(0.01)
        self.assertEqual([p["message"]["token"] for p in self.posts], ["test-device-0"])
        self.assertEqual(self.posts[0]["message"]["data"]["t"], "phone")
        self.assertEqual((await self.answer(self.b, cmd["id"]))[0], 409)
        self.assertEqual((await self.answer(self.a, cmd["id"]))[0], 200)
        self.assertTrue((await task)["ok"])
        self.assertEqual((await self.answer(self.a, cmd["id"]))[0], 409)
        self.assertEqual(await self.pending(self.a), [])
        for stream in streams:
            stream.close()

    async def test_offline_target_still_gets_fcm_with_other_sse(self):
        await self.arm(self.a)
        stream = await self.client.get("/v1/events", headers=self.headers(self.b, "GET", "/v1/events"))
        await stream.content.readline()
        await stream.content.readline()
        task = self.run_command()
        cmd = await self.command(self.a)
        async with asyncio.timeout(2):
            while not self.posts:
                await asyncio.sleep(0.01)
        self.assertEqual([p["message"]["token"] for p in self.posts], ["test-device-0"])
        with self.assertRaises(TimeoutError):
            await asyncio.wait_for(stream.content.readline(), 0.05)
        await self.answer(self.a, cmd["id"])
        self.assertTrue((await task)["ok"])
        stream.close()

    async def test_real_tool_thread_dispatch(self):
        from mobile import activity as A
        await self.arm(self.a)
        self.ad._mobile_loop = asyncio.get_running_loop()
        with patch.object(A, "_LIVE", self.ad):
            tool = asyncio.create_task(asyncio.to_thread(PH.handle, {"action": "list_apps", "query": "test"}))
            self.tasks.append(tool)
            cmd = await self.command(self.a)
            self.assertEqual(cmd["action"], "list_apps")
            self.assertEqual(cmd["query"], "test")
            await self.answer(self.a, cmd["id"])
            self.assertEqual(json.loads(await tool), {"test": True})
            self.assertIn("error", json.loads(PH.handle({"action": "invalid"})))

    async def test_computer_surface_aliases_and_empty_results(self):
        from mobile import activity as A
        await self.arm(self.a)
        self.ad._mobile_loop = asyncio.get_running_loop()
        with patch.object(A, "_LIVE", self.ad):
            inputs = [(alias, target, {}) for alias, target in PH.ALIASES.items()]
            inputs += [("perform_action", "tap", {"operation": "click"}),
                       ("list_windows", "list_windows", {}),
                       ("activate_window", "activate_window", {"window_id": 42})]
            for name, target, extra in inputs:
                tool = asyncio.create_task(asyncio.to_thread(PH.handle, {"action": name, **extra}))
                self.tasks.append(tool)
                cmd = await self.command(self.a)
                self.assertEqual(cmd["action"], target)
                await self.answer(self.a, cmd["id"])
                self.assertEqual(json.loads(await tool), {"test": True})
            self.assertEqual(json.loads(PH._fmt({"ok": True, "result": []}, "list_apps")), [])
            self.assertIn("error", json.loads(PH.handle({"action": "perform_action", "operation": "perform_action"})))

    async def test_screen_filters_and_explicit_click_method(self):
        from mobile import activity as A
        properties = PH.SCHEMA["parameters"]["properties"]
        self.assertEqual(properties["scope"]["enum"], ["focused", "all"])
        self.assertEqual(properties["method"]["enum"], ["auto", "gesture", "accessibility"])
        self.assertEqual(properties["include_keyboard"]["type"], "boolean")
        await self.arm(self.a)
        self.ad._mobile_loop = asyncio.get_running_loop()
        cases = [{"action": "get_app_state", "scope": "all", "include_keyboard": True, "screenshot": False},
                 {"action": "click", "index": 3, "method": "gesture"},
                 {"action": "tap", "index": 3, "method": "accessibility"}]
        with patch.object(A, "_LIVE", self.ad):
            for args in cases:
                tool = asyncio.create_task(asyncio.to_thread(PH.handle, args))
                self.tasks.append(tool)
                cmd = await self.command(self.a)
                for key, value in args.items():
                    self.assertEqual(cmd[key], PH.ALIASES.get(value, value) if key == "action" else value)
                result = {"dispatch_method": args.get("method"), "effect_verified": False}
                status, _ = await self.request(self.a, "POST", "/v1/phone/result", {"id": cmd["id"], "ok": True, "result": result})
                self.assertEqual(status, 200)
                self.assertEqual(json.loads(await tool), result)

    async def test_invalid_screen_filter_and_click_method_never_queue(self):
        from mobile import activity as A
        self.ad._mobile_loop = asyncio.get_running_loop()
        with patch.object(A, "_LIVE", self.ad):
            for args in [{"action": "state", "scope": "unknown"},
                         {"action": "state", "include_keyboard": "false"},
                         {"action": "tap", "index": 3, "method": "retry"}]:
                result = json.loads(await asyncio.to_thread(PH.handle, args))
                self.assertIn("invalid", result["error"])
                self.assertEqual(self.phone.cmds, {})

    async def test_activation_rollback_is_generation_bound(self):
        await self.arm(self.a)
        _, status = await self.request(self.a, "GET", "/v1/phone")
        old_session = status["session"]
        self.assertTrue(old_session)
        await self.arm(self.a)
        _, newer = await self.request(self.a, "GET", "/v1/phone")
        self.assertNotEqual(old_session, newer["session"])
        _, hidden = await self.request(self.b, "GET", "/v1/phone")
        self.assertIsNone(hidden["session"])
        await self.request(self.a, "POST", "/v1/phone/arm", {"on": False, "session": old_session})
        self.assertEqual(self.phone.armed()["session"], newer["session"])
        await self.request(self.a, "POST", "/v1/phone/arm", {"on": False, "session": newer["session"]})
        self.assertIsNone(self.phone.armed())

    async def test_serial_commands(self):
        await self.arm(self.a)
        first = self.run_command("tap", x=1, y=2)
        cmd = await self.command(self.a)
        second = self.run_command("key", key="back")
        await asyncio.sleep(0.03)
        self.assertEqual([c["id"] for c in await self.pending(self.a)], [cmd["id"]])
        await self.answer(self.a, cmd["id"])
        self.assertTrue((await first)["ok"])
        next_cmd = await self.command(self.a)
        self.assertNotEqual(next_cmd["id"], cmd["id"])
        await self.answer(self.a, next_cmd["id"])
        self.assertTrue((await second)["ok"])

    async def test_rearm_cancels_active_and_queued_not_retargeted(self):
        await self.arm(self.a)
        first = self.run_command()
        old = await self.command(self.a)
        queued = self.run_command("tap", x=1, y=2)
        await asyncio.sleep(0)
        await self.arm(self.b)
        self.assertFalse((await first)["ok"])
        self.assertFalse((await queued)["ok"])
        self.assertEqual(await self.pending(self.b), [])
        self.assertEqual((await self.answer(self.b, old["id"]))[0], 409)
        await self.arm(self.a)
        self.assertEqual((await self.answer(self.a, old["id"]))[0], 409)

    async def test_same_device_rearm_and_disarm_cancel(self):
        await self.arm(self.a)
        first = self.run_command()
        old = await self.command(self.a)
        await self.arm(self.a)
        self.assertFalse((await first)["ok"])
        self.assertEqual((await self.answer(self.a, old["id"]))[0], 409)
        second = self.run_command()
        await self.command(self.a)
        # An unrelated phone's local disarm cannot disarm the selected phone.
        await self.request(self.b, "POST", "/v1/phone/arm", {"on": False})
        self.assertFalse(second.done())
        await self.request(self.a, "POST", "/v1/phone/arm", {"on": False})
        self.assertFalse((await second)["ok"])
        self.assertEqual(await self.pending(self.a), [])

    async def test_expiry_queue_timeout_and_cancellation(self):
        await self.arm(self.a)
        first = self.run_command(timeout=0.3)
        old = await self.command(self.a)
        queued = self.run_command("tap", timeout=0.05)
        self.assertFalse((await queued)["ok"])
        self.assertEqual([c["id"] for c in await self.pending(self.a)], [old["id"]])
        self.assertFalse((await first)["ok"])
        self.assertEqual(await self.pending(self.a), [])
        self.assertEqual((await self.answer(self.a, old["id"]))[0], 409)
        cancelled = self.run_command()
        old = await self.command(self.a)
        cancelled.cancel()
        await asyncio.gather(cancelled, return_exceptions=True)
        self.assertEqual(await self.pending(self.a), [])
        self.assertEqual((await self.answer(self.a, old["id"]))[0], 409)
        self.assertEqual(self.phone.pending, {})
        self.assertEqual(self.phone._arms, {})

    async def test_revocation_route_and_external_store_revocation(self):
        await self.arm(self.a)
        task = self.run_command()
        await self.command(self.a)
        status, _ = await self.request(self.b, "DELETE", "/v1/devices/" + self.a["device_id"])
        self.assertEqual(status, 200)
        self.assertFalse((await task)["ok"])
        self.assertIsNone(self.phone.armed())
        self.assertEqual((await self.request(self.a, "GET", "/v1/phone/pending"))[0], 401)
        await self.arm(self.b)
        task = self.run_command()
        await self.command(self.b)
        self.ad.store.revoke(self.b["device_id"])
        self.assertFalse((await asyncio.wait_for(task, 0.5))["ok"])
        self.assertEqual(self.phone.cmds, {})

    async def test_external_same_device_arm_generation_change(self):
        await self.arm(self.a)
        task = self.run_command()
        cmd = await self.command(self.a)
        self.ad.store.kv_put("phone:armed", {"device": self.a["device_id"], "since": time.time(), "session": "changed"})
        self.assertEqual(await self.pending(self.a), [])
        self.assertEqual((await self.answer(self.a, cmd["id"]))[0], 409)
        self.assertFalse((await asyncio.wait_for(task, 0.5))["ok"])

    async def test_action_protocol_preserved(self):
        await self.arm(self.a)
        for action in PH.ACTIONS:
            task = self.run_command(action)
            cmd = await self.command(self.a)
            self.assertEqual(cmd["action"], action)
            await self.answer(self.a, cmd["id"])
            self.assertTrue((await task)["ok"])


if __name__ == "__main__":
    try:
        unittest.main(verbosity=2)
    finally:
        _HOME.cleanup()
