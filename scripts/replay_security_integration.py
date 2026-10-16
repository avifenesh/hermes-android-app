"""Offline replay regression tests with real P-256, aiohttp auth and SQLite.

Run: ~/.hermes/hermes-agent/venv/bin/python scripts/replay_security_integration.py
The harness force-isolates Hermes BEFORE bootstrap or any application import.
No gateway, real credentials, production requests, or live state are used.
"""
import os
from pathlib import Path
import sys
import tempfile

SCRATCH = Path.home() / ".hermes/cache/scratch"
SCRATCH.mkdir(parents=True, exist_ok=True)
_HOME = tempfile.TemporaryDirectory(prefix="mobile-replay-", dir=SCRATCH)
os.environ["HERMES_HOME"] = _HOME.name
assert Path(os.environ["HERMES_HOME"]).resolve() != (Path.home() / ".hermes").resolve()
sys.path.insert(0, str(Path.home() / ".hermes/hermes-agent"))
import hermes_bootstrap  # noqa: E402,F401
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))

import asyncio
import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
import time
import unittest
from unittest.mock import patch

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, utils
from mobile import adapter as AD, control as CT, sig as SG, store as S

ORDER = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551


def public(key):
    return base64.b64encode(key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()


def sign(key, message):
    return base64.b64encode(key.sign(message, ec.ECDSA(hashes.SHA256()))).decode()


def alternate(signature):
    r, s = utils.decode_dss_signature(base64.b64decode(signature))
    return base64.b64encode(utils.encode_dss_signature(r, ORDER - s)).decode()


def pad_variant(signature):
    alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    if not signature.endswith("="):
        return signature + "="
    index = len(signature.rstrip("=")) - 1
    return signature[:index] + alphabet[alphabet.index(signature[index]) ^ 1] + signature[index + 1:]


class ReplayRoutes(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir=_HOME.name)
        self.ad = object.__new__(AD.MobileAdapter)
        self.ad.store = S.Store(Path(self.tmp.name) / "mobile.db")
        self.ad._replay = SG.Replay()
        self.key = ec.generate_private_key(ec.SECP256R1())
        self.approval_key = ec.generate_private_key(ec.SECP256R1())
        self.device = self.ad.store.claim_pair_code(self.ad.store.create_pair_code(), "synthetic",
                                                     public(self.key), public(self.approval_key))
        self.executions = 0
        self.approvals = 0

        @web.middleware
        async def auth(request, handler):
            return await self.ad._auth_mw(request, handler)

        async def action(request):
            self.executions += 1
            await asyncio.sleep(0)
            return web.json_response({"ok": True})

        async def guarded(request):
            if not CT.guard_ok(request, "phone", "arm", True):
                return web.json_response({"need_sig": True}, status=403)
            self.approvals += 1
            return web.json_response({"ok": True})

        app = web.Application(middlewares=[auth])
        app.router.add_post("/v1/action", action)
        app.router.add_get("/v1/read", action)
        app.router.add_post("/v1/guarded", guarded)
        app.router.add_get("/v1/health", self.ad.h_health)
        self.ad._ensure_mirror = lambda: None
        from types import SimpleNamespace
        self.ad._selfcheck = SimpleNamespace(last=None)
        self.client = TestClient(TestServer(app))
        await self.client.start_server()
        self.ts = str(int(time.time()))

    async def asyncTearDown(self):
        await self.client.close()
        self.ad.store._db.close()
        self.tmp.cleanup()

    def headers(self, method="POST", path="/v1/action", body=b"{}", ts=None):
        ts = ts or self.ts
        return {"Authorization": "Bearer " + self.device["token"], "X-HM-Ts": ts,
                "X-HM-Sig": sign(self.key, SG.canonical(method, path, ts, body))}

    async def send(self, headers, path="/v1/action", method="POST", body=b"{}"):
        async with self.client.request(method, path, data=body, headers=headers) as response:
            await response.read()
            return response.status

    async def test_exact_replay(self):
        headers = self.headers()
        self.assertEqual(await self.send(headers), 200)
        self.assertEqual(await self.send(headers), 401)
        self.assertEqual(self.executions, 1)

    async def test_base64_variants_cannot_replay(self):
        for name, transform in (("junk", lambda s: s + "!"), ("padding", lambda s: s + "="),
                                ("space", lambda s: s[:8] + " " + s[8:]), ("padbits", pad_variant)):
            with self.subTest(encoding=name):
                headers = self.headers()
                self.assertEqual(await self.send(headers), 200)
                headers["X-HM-Sig"] = transform(headers["X-HM-Sig"])
                self.assertEqual(await self.send(headers), 401)

    async def test_ecdsa_alternate_cannot_replay(self):
        headers = self.headers()
        other = alternate(headers["X-HM-Sig"])
        self.assertTrue(SG.verify(public(self.key), SG.canonical("POST", "/v1/action", self.ts, b"{}"), other))
        self.assertEqual(await self.send(headers), 200)
        headers["X-HM-Sig"] = other
        self.assertEqual(await self.send(headers), 401)
        self.assertEqual(self.executions, 1)

    async def test_concurrent_malleated_replay(self):
        headers = self.headers()
        other = {**headers, "X-HM-Sig": alternate(headers["X-HM-Sig"])}
        statuses = await asyncio.gather(*(self.send(headers if i % 2 else other) for i in range(24)))
        self.assertEqual(statuses.count(200), 1)
        self.assertEqual(statuses.count(401), 23)
        self.assertEqual(self.executions, 1)

    async def test_independent_identical_same_second_calls_remain_valid(self):
        for _ in range(3):
            self.assertEqual(await self.send(self.headers()), 200)
        self.assertEqual(self.executions, 3)

    async def test_get_replay_and_independent_poll(self):
        headers = self.headers("GET", "/v1/read", b"")
        self.assertEqual(await self.send(headers, "/v1/read", "GET", b""), 200)
        self.assertEqual(await self.send(headers, "/v1/read", "GET", b""), 401)
        self.assertEqual(await self.send(self.headers("GET", "/v1/read", b""), "/v1/read", "GET", b""), 200)

    async def test_invalid_signature_does_not_poison_valid_request(self):
        headers = self.headers()
        bad = {**headers, "X-HM-Sig": headers["X-HM-Sig"] + "!"}
        self.assertEqual(await self.send(bad), 401)
        self.assertEqual(await self.send(headers), 200)

    async def test_replay_survives_store_reopen(self):
        headers = self.headers()
        self.assertEqual(await self.send(headers), 200)
        path = self.ad.store.path
        self.ad.store._db.close()
        self.ad.store = S.Store(path)
        self.ad._replay = SG.Replay()
        self.assertEqual(await self.send(headers), 401)

    async def test_approval_reuse_with_fresh_outer_signature_is_rejected(self):
        msg = f"phone\narm\n{hashlib.sha256(b'true').hexdigest()}\n{self.ts}".encode()
        approval = sign(self.approval_key, msg)
        for sig, expected in ((approval, 200), (approval, 403), (alternate(approval), 403)):
            headers = self.headers(path="/v1/guarded")
            headers["X-HM-ASig"] = sig
            self.assertEqual(await self.send(headers, "/v1/guarded"), expected)
        self.assertEqual(self.approvals, 1)

    async def test_approval_independent_same_second_signature(self):
        msg = f"phone\narm\n{hashlib.sha256(b'true').hexdigest()}\n{self.ts}".encode()
        for _ in range(2):
            headers = self.headers(path="/v1/guarded")
            headers["X-HM-ASig"] = sign(self.approval_key, msg)
            self.assertEqual(await self.send(headers, "/v1/guarded"), 200)
        self.assertEqual(self.approvals, 2)

    async def test_future_timestamp_replay_until_inclusive_deadline(self):
        start = int(time.time())
        headers = self.headers(ts=str(start + SG.SKEW))
        with patch.object(SG.time, "time", return_value=float(start)):
            self.assertEqual(await self.send(headers), 200)
        with patch.object(SG.time, "time", return_value=float(start + 2 * SG.SKEW)):
            self.assertEqual(await self.send(headers), 401)
        self.assertEqual(self.executions, 1)

    async def test_concurrent_approval_replay_with_distinct_outer_requests(self):
        msg = f"phone\narm\n{hashlib.sha256(b'true').hexdigest()}\n{self.ts}".encode()
        proof = sign(self.approval_key, msg)
        calls = []
        for i in range(12):
            headers = self.headers(path="/v1/guarded")
            headers["X-HM-ASig"] = proof if i % 2 else alternate(proof)
            calls.append(self.send(headers, "/v1/guarded"))
        statuses = await asyncio.gather(*calls)
        self.assertEqual(statuses.count(200), 1)
        self.assertEqual(statuses.count(403), 11)
        self.assertEqual(self.approvals, 1)

    async def test_approval_bad_encoding_cannot_poison_original(self):
        msg = f"phone\narm\n{hashlib.sha256(b'true').hexdigest()}\n{self.ts}".encode()
        proof = sign(self.approval_key, msg)
        for signature, expected in ((proof + "!", 403), (proof, 200), (pad_variant(proof), 403)):
            headers = self.headers(path="/v1/guarded")
            headers["X-HM-ASig"] = signature
            self.assertEqual(await self.send(headers, "/v1/guarded"), expected)

    async def test_request_content_binding(self):
        headers = self.headers()
        self.assertEqual(await self.send(headers, body=b'{"changed":true}'), 401)
        self.assertEqual(await self.send(headers, path="/v1/action?changed=true"), 401)
        self.assertEqual(await self.send({**headers, "X-HM-Ts": str(int(self.ts) + 1)}), 401)
        self.assertEqual(await self.send(headers), 200)

    async def test_stale_and_future_timestamps_rejected(self):
        for ts in (str(int(self.ts) - SG.SKEW - 1), str(int(self.ts) + SG.SKEW + 2), "invalid"):
            self.assertEqual(await self.send(self.headers(ts=ts)), 401)
        self.assertEqual(self.executions, 0)

    async def test_health_advertises_security_minimum_without_protocol_change(self):
        async with self.client.get("/v1/health") as response:
            self.assertEqual(response.status, 200)
            body = await response.json()
        self.assertEqual(body["api"], 1)
        self.assertEqual(body["security"]["signature_replay"], 2)

    async def test_store_failure_never_executes_handler(self):
        import sqlite3
        with patch.object(self.ad.store, "claim_signature", side_effect=sqlite3.OperationalError("synthetic failure")):
            self.assertEqual(await self.send(self.headers()), 500)
        self.assertEqual(self.executions, 0)

    async def test_same_key_in_another_pairing_cannot_replay(self):
        other = self.ad.store.claim_pair_code(self.ad.store.create_pair_code(), "synthetic-2",
                                             public(self.key), public(self.approval_key))
        headers = self.headers()
        self.assertEqual(await self.send(headers), 200)
        headers["Authorization"] = "Bearer " + other["token"]
        self.assertEqual(await self.send(headers), 401)


class ReplayPrimitives(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir=_HOME.name)
        self.path = Path(self.tmp.name) / "mobile.db"
        self.store = S.Store(self.path)
        self.key = ec.generate_private_key(ec.SECP256R1())
        self.pub = public(self.key)
        self.msg = b"synthetic message"
        self.proof = sign(self.key, self.msg)
        self.ts = str(int(time.time()))

    def tearDown(self):
        self.store._db.close()
        self.tmp.cleanup()

    def claim(self, signature=None, scope="request", store=None):
        return SG.Replay().fresh(signature or self.proof, pub=self.pub, msg=self.msg, ts=self.ts,
                                 scope=scope, store=store or self.store)

    def test_separate_database_connections_claim_atomically(self):
        import threading
        stores = [S.Store(self.path) for _ in range(8)]
        barrier = threading.Barrier(len(stores))

        def claim_one(pair):
            i, store = pair
            barrier.wait(timeout=10)
            return self.claim(self.proof if i % 2 else alternate(self.proof), store=store)

        try:
            with ThreadPoolExecutor(max_workers=len(stores)) as pool:
                self.assertEqual(sum(pool.map(claim_one, enumerate(stores))), 1)
        finally:
            for store in stores:
                store._db.close()

    def test_request_and_approval_scopes_are_separate(self):
        self.assertTrue(self.claim(scope="request"))
        self.assertTrue(self.claim(scope="approval"))
        self.assertFalse(self.claim(alternate(self.proof), scope="request"))
        self.assertFalse(self.claim(alternate(self.proof), scope="approval"))

    def test_supported_curve_only(self):
        for curve in (ec.SECP384R1(), ec.SECP256K1()):
            with self.subTest(curve=curve.name):
                key = ec.generate_private_key(curve)
                self.assertIsNone(SG.load_pub(public(key)))
                self.assertFalse(SG.verify(public(key), self.msg, sign(key, self.msg)))

    def test_both_s_orders_map_to_one_claim(self):
        r, s = utils.decode_dss_signature(base64.b64decode(self.proof))
        high = base64.b64encode(utils.encode_dss_signature(r, max(s, ORDER - s))).decode()
        low = base64.b64encode(utils.encode_dss_signature(r, min(s, ORDER - s))).decode()
        self.assertTrue(self.claim(high))
        self.assertFalse(self.claim(low))

    def test_noncanonical_pad_bits_rejected(self):
        # Ensure the actual pad-bit variant is exercised, not merely extra '='.
        for _ in range(100):
            proof = sign(self.key, self.msg)
            if proof.endswith("="):
                variant = pad_variant(proof)
                self.assertEqual(base64.b64decode(proof), base64.b64decode(variant))
                self.assertFalse(SG.verify(self.pub, self.msg, variant))
                self.assertTrue(SG.verify(self.pub, self.msg, proof))
                break
        else:
            self.fail("failed to generate a padded DER signature")

    def test_invalid_der_and_wrong_key_rejected_without_claim(self):
        for der in (b"not DER", utils.encode_dss_signature(0, 1),
                    utils.encode_dss_signature(1, ORDER), base64.b64decode(self.proof) + b"extra"):
            self.assertFalse(self.claim(base64.b64encode(der).decode()))
        key = ec.generate_private_key(ec.SECP256R1())
        self.assertFalse(SG.Replay().fresh(self.proof, pub=public(key), msg=self.msg,
                                         ts=self.ts, scope="request", store=self.store))
        self.assertTrue(self.claim())

    def test_expired_claims_pruned_but_live_claims_retained(self):
        now = int(time.time())
        self.assertTrue(self.store.claim_signature("old", now + 1))
        self.assertTrue(self.store.claim_signature("live", now + 100))
        with patch.object(SG.time, "time", return_value=float(now + 2)):
            self.assertTrue(self.store.claim_signature("next", now + 100))
            self.assertFalse(self.store.claim_signature("live", now + 100))
            self.assertFalse(self.store.claim_signature("expired", now + 1))
        rows = {row[0] for row in self.store._db.execute("SELECT identity FROM signature_replays")}
        self.assertEqual(rows, {"live", "next"})


if __name__ == "__main__":
    try:
        unittest.main(verbosity=2)
    finally:
        _HOME.cleanup()
