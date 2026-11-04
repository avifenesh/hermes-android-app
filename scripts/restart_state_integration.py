"""Server-owned "restart to apply" state; run with Hermes' managed Python.

  ~/.hermes/tools/python-*/bin/python3 scripts/restart_state_integration.py

A restart-flagged change made through the plugin (a curated setting, a plugin
toggle, a Hub plugin/MCP job) records restart_needed for this gateway process.
/v1/me reports it with the process boot id; a new gateway process (a restart
from any surface) has a new boot id and reports nothing pending. /v1/restart
says whether it started a restart, is draining, or one was already under way.

Real handlers, real `hermes config set` and core plugin writers run against a
disposable HERMES_HOME. The gateway runner is a stub: no gateway is contacted.
"""
import atexit
import asyncio
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
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
_HOME = tempfile.TemporaryDirectory(prefix="restart-state-", dir=SCRATCH)
atexit.register(shutil.rmtree, _HOME.name, ignore_errors=True)
os.environ["HERMES_HOME"] = _HOME.name
os.environ["HERMES_ENABLE_PROJECT_PLUGINS"] = "false"
os.environ["HERMES_DISABLE_LAZY_INSTALLS"] = "1"
assert Path(_HOME.name).resolve() != (Path.home() / ".hermes").resolve()
sys.path.insert(0, str(Path.home() / ".hermes/hermes-agent"))
import hermes_bootstrap  # noqa: E402,F401
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))

from aiohttp import web  # noqa: E402
from aiohttp.test_utils import TestClient, TestServer  # noqa: E402
from cryptography.hazmat.primitives import hashes, serialization  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import ec  # noqa: E402
from mobile import adapter as AD, control as C, market as MK, sig as SG, store as S  # noqa: E402

assert S.data_dir().resolve().is_relative_to(Path(_HOME.name).resolve())


def adapter(db: Path):
    ad = object.__new__(AD.MobileAdapter)
    ad.store = S.Store(db)
    ad._control = C.Control(ad)
    ad.gateway_runner = None
    ad._public_url = ""
    ad._selfcheck = SimpleNamespace(last=None)
    return ad


def child(db: str) -> None:
    """A new gateway process opening the same mobile.db: prints what its /v1/me would report."""
    ad = adapter(Path(db))
    ad.start_boot()
    print(json.dumps(ad.restart_state()))


def public(key):
    return base64.b64encode(key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()


class RestartState(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir=_HOME.name)
        self.db = Path(self.tmp.name) / "mobile.db"
        self.ad = adapter(self.db)
        self.ad.start_boot()
        self.approval = ec.generate_private_key(ec.SECP256R1())
        device = {"id": "d-synthetic", "name": "synthetic", "created": time.time(), "apub": public(self.approval)}
        replay = SG.Replay()

        @web.middleware
        async def as_device(request, handler):
            request["device"] = device
            request["signature_replay"] = replay
            request["signature_store"] = self.ad.store
            return await handler(request)

        app = web.Application(middlewares=[as_device])
        app.router.add_get("/v1/me", self.ad.h_me)
        C.routes(app.router, self.ad._control, self.ad._json)
        self.jobs = MK.routes(app.router, self.ad._json, on_restart=self.ad.mark_restart_needed)
        self.client = TestClient(TestServer(app))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()
        self.ad.store._db.close()
        self.tmp.cleanup()

    async def me(self):
        async with self.client.get("/v1/me") as r:
            self.assertEqual(r.status, 200)
            return (await r.json())["restart"]

    async def put(self, key, value):
        async with self.client.put("/v1/settings", json={"key": key, "value": value}) as r:
            return r.status, await r.json()

    def new_process(self):
        out = subprocess.run([sys.executable, __file__, "--child", str(self.db)], capture_output=True, text=True,
                             timeout=120, env=dict(os.environ))
        self.assertEqual(out.returncode, 0, out.stderr[-2000:])
        return json.loads(out.stdout.strip().splitlines()[-1])

    async def test_restart_flagged_setting_sets_needed_until_a_new_boot(self):
        first = await self.me()
        self.assertEqual((first["needed"], first["pending"]), (False, False))
        self.assertTrue(first["boot"])
        status, body = await self.put("agent.max_turns", 77)
        self.assertEqual(status, 200, body)
        self.assertFalse((await self.me())["needed"], "a live setting must not ask for a restart")
        before = time.time()
        status, body = await self.put("logging.level", "DEBUG")
        self.assertEqual(status, 200, body)
        after = await self.me()
        self.assertTrue(after["needed"])
        self.assertEqual(after["boot"], first["boot"])
        self.assertGreaterEqual(after["since"], before - 1)
        # Persisted: the same process reopening its store still reports it.
        self.assertTrue(adapter(self.db).restart_state()["needed"])
        # A restart from any surface is a new gateway process with a new boot id.
        fresh = self.new_process()
        self.assertNotEqual(fresh["boot"], first["boot"])
        self.assertEqual((fresh["needed"], fresh["since"]), (False, None))

    async def test_plugin_disable_through_the_guarded_route(self):
        def no_gateway(*a, **kw):
            raise AssertionError("the test must never contact a gateway")

        name = "disk-cleanup"
        ts = str(int(time.time()))
        msg = f"plugin\n{name}\n{hashlib.sha256(b'false').hexdigest()}\n{ts}".encode()
        sig = base64.b64encode(self.approval.sign(msg, ec.ECDSA(hashes.SHA256()))).decode()
        with patch("hermes_cli.plugins_activation.reload_running_gateway", no_gateway):
            async with self.client.post("/v1/plugins/toggle", json={"name": name, "enabled": False}) as r:
                self.assertEqual(r.status, 403, "unsigned plugin toggles stay refused")
            self.assertFalse((await self.me())["needed"])
            async with self.client.post("/v1/plugins/toggle", json={"name": name, "enabled": False},
                                        headers={"X-HM-Ts": ts, "X-HM-ASig": sig}) as r:
                body = await r.json()
                self.assertEqual(r.status, 200, body)
        self.assertTrue(body.get("restart_required"), body)
        self.assertTrue((await self.me())["needed"])

    async def test_hub_jobs_mark_only_restart_requiring_successes(self):
        async def run(kind, action, result):
            job = self.jobs.run_call(kind, action, "synthetic", lambda: result)
            await job["_task"]
            return job["state"]

        self.assertEqual(await run("plugin", "install", {"ok": True, "restart_required": False}), "ok")
        self.assertEqual(await run("plugin", "remove", {"ok": False, "error": "nope"}), "failed")
        self.assertFalse((await self.me())["needed"])
        self.assertEqual(await run("plugin", "install", {"ok": True, "restart_required": True}), "ok")
        self.assertTrue((await self.me())["needed"])

        for kind, action in (("plugin", "remove"), ("plugin", "update"), ("mcp", "install"), ("mcp", "uninstall")):
            with self.subTest(kind=kind, action=action):
                self.ad.store.kv_put("restart_needed", None)
                self.assertFalse((await self.me())["needed"])
                self.assertEqual(await run(kind, action, {"ok": True}), "ok")
                self.assertTrue((await self.me())["needed"])

    async def test_restart_reports_draining_and_in_progress_honestly(self):
        runner = SimpleNamespace(_restart_requested=False, _draining=False, active=2)
        runner._running_agent_count = lambda: runner.active

        async def command(cid, text):
            # Same branches as core's /restart handler: a repeat while draining starts nothing new.
            self.assertEqual(text, "/restart")
            if runner._restart_requested or runner._draining:
                return f"Draining {runner.active} agents" if runner.active else "Restart already in progress"
            runner._restart_requested = runner._draining = True
            return f"Draining {runner.active} agents" if runner.active else "Restarting"

        self.ad.gateway_runner = runner
        self.ad._control.command = command
        self.ad.store.ensure_chat = lambda *a, **kw: None

        async def restart():
            async with self.client.post("/v1/restart") as r:
                return r.status, await r.json()

        status, body = await restart()
        self.assertEqual((status, body["ok"], body["state"], body["active"]), (200, True, "draining", 2), body)
        self.assertTrue((await self.me())["pending"])
        status, body = await restart()
        self.assertEqual((body["ok"], body["state"]), (True, "in_progress"), body)
        runner.active = 0
        status, body = await restart()
        self.assertEqual(body["state"], "in_progress", body)

        runner._restart_requested = runner._draining = False
        status, body = await restart()
        self.assertEqual((body["ok"], body["state"], body["active"]), (True, "restarting", 0), body)

        async def refused(cid, text):
            return "You are not allowed to run /restart"

        runner._restart_requested = runner._draining = False
        self.ad._control.command = refused
        status, body = await restart()
        self.assertEqual((status, body["ok"], body["state"]), (409, False, "refused"), body)
        self.assertIn("not allowed", body["error"])
        self.assertFalse((await self.me())["pending"])


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--child":
        child(sys.argv[2])
        sys.exit(0)
    try:
        unittest.main(verbosity=2)
    finally:
        _HOME.cleanup()
