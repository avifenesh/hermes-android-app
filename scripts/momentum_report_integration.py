"""Radar momentum route checks against the real mobile app router. No gateway, live data or network.

Run directly with Hermes' Python: "$HERMES_PYTHON" scripts/momentum_report_integration.py
A disposable HERMES_HOME holds the store; a stand-in `momentum` prints a synthetic v1 report, fails, hangs or
prints the wrong version on demand. Requests are signed like the app's and pass the real auth middleware.
This covers the server half only: not the Android rendering, a running gateway or the real momentum binary.
"""
import atexit
import os
from pathlib import Path
import shutil
import sys
import tempfile

if "hermes_bootstrap" in sys.modules or "hermes_constants" in sys.modules:
    raise RuntimeError("Run this harness directly in a fresh Hermes Python process")
# Under the Hermes home, where the managed runtime finds its dependency environment.
SCRATCH = Path.home() / ".hermes/cache/scratch"
SCRATCH.mkdir(parents=True, exist_ok=True)
_HOME = tempfile.TemporaryDirectory(prefix="momentum-", dir=SCRATCH)
atexit.register(shutil.rmtree, _HOME.name, ignore_errors=True)
os.environ["HERMES_HOME"] = _HOME.name  # FORCE, never inherit a gateway home
os.environ["RADAR_HOME"] = str(Path(_HOME.name) / "radar")
os.environ["HERMES_ENABLE_PROJECT_PLUGINS"] = "false"
os.environ["HERMES_DISABLE_LAZY_INSTALLS"] = "1"
assert Path(_HOME.name).resolve() != (Path.home() / ".hermes").resolve()
sys.path.insert(0, str(Path.home() / ".hermes/hermes-agent"))
import hermes_bootstrap  # noqa: E402,F401
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))

import base64  # noqa: E402
import json  # noqa: E402
import stat  # noqa: E402
import time  # noqa: E402
import unittest  # noqa: E402
from datetime import datetime, timedelta, timezone  # noqa: E402
from unittest.mock import patch  # noqa: E402

from aiohttp.test_utils import TestClient, TestServer  # noqa: E402
from cryptography.hazmat.primitives import hashes, serialization  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import ec  # noqa: E402
from gateway.config import PlatformConfig  # noqa: E402
from gateway.platform_registry import PlatformEntry, platform_registry  # noqa: E402
from mobile import adapter as AD, momentum as MO, sig as SG, store as S  # noqa: E402

assert S.data_dir().resolve().is_relative_to(Path(_HOME.name).resolve())
platform_registry.register(PlatformEntry(name="mobile", label="Hermes Mobile", adapter_factory=AD.MobileAdapter,
                                         check_fn=AD.check_requirements, source="plugin", plugin_name="mobile"))

STAND_IN = """#!/usr/bin/env python3
import json, os, sys, time
here = os.path.dirname(os.path.abspath(__file__))
with open(os.path.join(here, "calls.log"), "a") as f:
    f.write(json.dumps(sys.argv[1:]) + "\\n")
mode = open(os.path.join(here, "mode")).read().strip()
if mode == "slow":
    time.sleep(10)
if mode == "fail":
    print("momentum: the store is locked by another writer", file=sys.stderr)
    sys.exit(3)
report = json.load(open(os.path.join(here, "report.json")))
if mode == "v2":
    report["version"] = 2
print(json.dumps(report))
"""


def at(minutes_ago):
    return (datetime.now(timezone(timedelta(hours=2))) - timedelta(minutes=minutes_ago)).isoformat(timespec="seconds")


def item(i, title, **kw):
    return {"id": i, "title": title, "at": at(kw.pop("ago", 30)), "links": kw.pop("links", []), "source": "momentum", **kw}


def synthetic_report():
    """A v1 report exactly as the contract shapes it: five arrays of at most 20 items, plus counts of everything."""
    shipped = [item(f"s{i}", f"Improvement {i} landed", effect=f"metric {50 - i}% -> {40 - i}% (7 d)", ago=60 * i)
               for i in range(1, 21)]
    return {
        "version": 1,
        "generated_at": at(0),
        "needs_you": [
            item("n1", "Keep the nightly eval on the small model?", detail="The large one costs 3x and scored 0.4 higher.",
                 links=[{"label": "PR #18", "url": "https://github.com/example/evals/pull/18"},
                        {"label": "run", "url": "javascript:alert(1)"}]),
            item("n2", "Approve the release notes\nas drafted?", detail="Two numbers still cite a single run.", ago=90),
        ],
        "decided": [item("d1", "Paused the duplicate scan at night", detail="Override: re-enable it in Cron.")],
        "shipped": shipped,
        "running": [item("r1", "Refactor the ingestion pipeline", detail="agent session, working", source="sessions")],
        "problems": [item("p1", "Nightly backup skipped twice", detail="Disk is 94% full; supervise gave up.")],
        "counts": {"needs_you": 2, "decided": 1, "shipped": 23, "running": 1, "problems": 1},
    }


def public(key):
    return base64.b64encode(key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()


class MomentumRoute(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.case = Path(tempfile.mkdtemp(prefix="case-", dir=_HOME.name))
        self.bin = self.case / "bin"
        self.bin.mkdir()
        exe = self.bin / "momentum"
        exe.write_text(STAND_IN)
        exe.chmod(exe.stat().st_mode | stat.S_IXUSR)
        (self.bin / "report.json").write_text(json.dumps(synthetic_report()))
        self.mode("ok")
        self.env = patch.dict(os.environ, {"MOMENTUM_BIN": str(exe)})
        self.env.start()
        (S.data_dir() / MO.CACHE_FILE).unlink(missing_ok=True)  # each case starts with no saved copy
        self.client = await self.start()

    async def start(self):
        """The real app: every route mounted the way the gateway mounts them, behind the real auth middleware."""
        ad = AD.MobileAdapter(PlatformConfig(enabled=True, extra={"bind": "127.0.0.1:0"}))
        app = ad._build_app()
        self.key = ec.generate_private_key(ec.SECP256R1())
        claim = ad.store.claim_pair_code(ad.store.create_pair_code(), "momentum-check", public(self.key),
                                         public(ec.generate_private_key(ec.SECP256R1())))
        self.token = claim["token"]
        client = TestClient(TestServer(app))
        await client.start_server()
        self.addAsyncCleanup(client.close)
        self.ad = ad
        return client

    async def asyncTearDown(self):
        self.env.stop()

    def mode(self, m):
        (self.bin / "mode").write_text(m)

    def calls(self):
        log = self.bin / "calls.log"
        return [json.loads(x) for x in log.read_text().splitlines()] if log.exists() else []

    async def get(self, path="/v1/radar/momentum"):
        ts = str(int(time.time()))
        sig = base64.b64encode(self.key.sign(SG.canonical("GET", path, ts, b""), ec.ECDSA(hashes.SHA256()))).decode()
        r = await self.client.get(path, headers={"Authorization": "Bearer " + self.token, "X-HM-Ts": ts, "X-HM-Sig": sig})
        return r.status, await r.json()

    async def test_unsigned_request_is_refused(self):
        r = await self.client.get("/v1/radar/momentum")
        self.assertEqual(r.status, 401)
        self.assertEqual(self.calls(), [])

    async def test_report_arrives_as_plain_contract_data(self):
        status, body = await self.get()
        self.assertEqual(status, 200, body)
        self.assertTrue(body["available"])
        self.assertFalse(body["stale"])
        self.assertIsNone(body["error"])
        rep = body["report"]
        self.assertEqual(rep["version"], 1)
        for k in MO.SECTIONS:
            self.assertIsInstance(rep[k], list)
        self.assertEqual(rep["counts"]["shipped"], 23)
        self.assertEqual(len(rep["shipped"]), 20)
        n1, n2 = rep["needs_you"]
        self.assertEqual(n1["links"], [{"label": "PR #18", "url": "https://github.com/example/evals/pull/18"}])
        self.assertEqual(n2["title"], "Approve the release notes as drafted?")
        self.assertEqual(rep["shipped"][0]["effect"], "metric 49% -> 39% (7 d)")
        self.assertEqual(self.calls(), [["report", "--json"]])

    async def test_failure_keeps_the_last_good_copy_and_says_stale(self):
        _, first = await self.get()
        self.mode("fail")
        status, body = await self.get("/v1/radar/momentum?refresh=1")
        self.assertEqual(status, 200)
        self.assertTrue(body["stale"])
        self.assertIn("exited 3", body["error"])
        self.assertIn("store is locked", body["error"])
        self.assertEqual(body["report"], first["report"])
        self.assertEqual(body["fetched_at"], first["fetched_at"])
        self.mode("ok")
        _, again = await self.get("/v1/radar/momentum?refresh=1")
        self.assertFalse(again["stale"])
        self.assertIsNone(again["error"])

    async def test_a_hung_report_is_cut_off(self):
        _, first = await self.get()
        self.mode("slow")
        with patch.object(MO, "TIMEOUT_S", 0.5):
            t = time.monotonic()
            _, body = await self.get("/v1/radar/momentum?refresh=1")
            self.assertLess(time.monotonic() - t, 5)
        self.assertTrue(body["stale"])
        self.assertIn("timed out", body["error"])
        self.assertEqual(body["report"], first["report"])

    async def test_a_restart_during_an_outage_serves_the_saved_copy(self):
        _, first = await self.get()
        self.mode("fail")
        self.client = await self.start()
        _, body = await self.get()
        self.assertTrue(body["available"])
        self.assertTrue(body["stale"])
        self.assertEqual(body["report"], first["report"])
        self.assertAlmostEqual(body["fetched_at"], first["fetched_at"], places=3)
        saved = S.data_dir() / MO.CACHE_FILE
        self.assertEqual(stat.S_IMODE(saved.stat().st_mode), 0o600)

    async def test_one_run_answers_a_burst_and_refresh_forces_one(self):
        await self.get()
        await self.get()
        self.assertEqual(len(self.calls()), 1)
        await self.get("/v1/radar/momentum?refresh=1")
        self.assertEqual(len(self.calls()), 2)
        self.assertTrue(all(c == ["report", "--json"] for c in self.calls()))

    async def test_another_report_version_is_refused(self):
        self.mode("v2")
        _, body = await self.get()
        self.assertTrue(body["available"])
        self.assertIsNone(body["report"])
        self.assertFalse(body["stale"])
        self.assertIn("version 2", body["error"])

    async def test_no_momentum_hides_the_section(self):
        with patch.object(MO, "binary", return_value=None):
            _, body = await self.get()
            self.assertFalse(body["available"])
            self.assertIsNone(body["report"])
            self.assertFalse(self.ad._features()["radar"])
        self.assertTrue(self.ad._features()["radar"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
