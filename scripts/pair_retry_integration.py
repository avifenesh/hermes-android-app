"""Pairing retry regressions with real P-256 keys, aiohttp routes and SQLite.

Run: ~/.hermes/hermes-agent/venv/bin/python scripts/pair_retry_integration.py
The harness force-isolates Hermes BEFORE bootstrap or any application import.
No gateway, real credentials, production requests, or live state are used.

A phone whose first /v1/pair response was lost (activity recreated, network cut)
retries the same one-time code. The same device keys, proven by a fresh request
signature, get the same device back with a new token; anything else still fails.
"""
import atexit
import os
from pathlib import Path
import shutil
import sys
import tempfile
from types import SimpleNamespace

SCRATCH = Path.home() / ".hermes/cache/scratch"
SCRATCH.mkdir(parents=True, exist_ok=True)
# hermes_bootstrap may re-exec this script under the managed runtime (os.execv), which skips exit handlers. The
# re-run adopts the isolated home its first run made instead of orphaning it; either run removes it at exit.
_prior = Path(os.environ.get("HERMES_HOME") or SCRATCH)
_home = (str(_prior) if _prior.parent == SCRATCH and _prior.name.startswith('mobile-pair-retry-') and _prior.is_dir()
         else tempfile.mkdtemp(prefix='mobile-pair-retry-', dir=SCRATCH))
atexit.register(shutil.rmtree, _home, ignore_errors=True)
_HOME = SimpleNamespace(name=_home, cleanup=lambda: shutil.rmtree(_home, ignore_errors=True))
os.environ["HERMES_HOME"] = _HOME.name
assert Path(os.environ["HERMES_HOME"]).resolve() != (Path.home() / ".hermes").resolve()
sys.path.insert(0, str(Path.home() / ".hermes/hermes-agent"))
import hermes_bootstrap  # noqa: E402,F401
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))

import base64  # noqa: E402
import json  # noqa: E402
import time  # noqa: E402
import unittest  # noqa: E402

from aiohttp import web  # noqa: E402
from aiohttp.test_utils import TestClient, TestServer  # noqa: E402
from cryptography.hazmat.primitives import hashes, serialization  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import ec  # noqa: E402
from mobile import adapter as AD, sig as SG, store as S  # noqa: E402

assert S.data_dir().resolve().is_relative_to(Path(_HOME.name).resolve())


def public(key):
    return base64.b64encode(key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()


def sign(key, message):
    return base64.b64encode(key.sign(message, ec.ECDSA(hashes.SHA256()))).decode()


class Phone:
    """One phone: a request key and an approval key, like Keys.REQ / Keys.APR."""

    def __init__(self):
        self.key = ec.generate_private_key(ec.SECP256R1())
        self.approval = ec.generate_private_key(ec.SECP256R1())


class PairRetry(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir=_HOME.name)
        self.ad = object.__new__(AD.MobileAdapter)
        self.ad.store = S.Store(Path(self.tmp.name) / "mobile.db")
        self.ad._replay = SG.Replay()
        self.ad._pair_hits = {}

        @web.middleware
        async def auth(request, handler):
            return await self.ad._auth_mw(request, handler)

        async def ping(request):
            return web.json_response({"device": request["device"]["id"]})

        app = web.Application(middlewares=[auth])
        app.router.add_post("/v1/pair", self.ad.h_pair)
        app.router.add_get("/v1/ping", ping)
        self.client = TestClient(TestServer(app))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()
        self.ad.store._db.close()
        self.tmp.cleanup()

    async def pair(self, code, phone, *, signer=None, pub=None, apub=None, headers=None):
        """POST /v1/pair as the app does. ``signer`` adds the retry proof (canonical request signature)."""
        body = json.dumps({"code": code, "device_name": "synthetic", "pub": pub or public(phone.key),
                           "apub": apub or public(phone.approval), "lock": True}).encode()
        h = dict(headers or {})
        if signer is not None and not h:
            ts = str(int(time.time()))
            h = {"X-HM-Ts": ts, "X-HM-Sig": sign(signer, SG.canonical("POST", "/v1/pair", ts, body))}
        async with self.client.post("/v1/pair", data=body, headers={"Content-Type": "application/json", **h}) as r:
            return r.status, await r.json(), h

    async def ping(self, token, phone):
        ts = str(int(time.time()))
        h = {"Authorization": "Bearer " + token, "X-HM-Ts": ts,
             "X-HM-Sig": sign(phone.key, SG.canonical("GET", "/v1/ping", ts, b""))}
        async with self.client.get("/v1/ping", headers=h) as r:
            return r.status

    def devices(self):
        return self.ad.store.devices()

    async def test_same_keys_retry_returns_same_device_and_rotates_token(self):
        phone = Phone()
        code = self.ad.store.create_pair_code()
        status, first, _ = await self.pair(code, phone, signer=phone.key)
        self.assertEqual(status, 200, first)
        self.assertEqual(await self.ping(first["token"], phone), 200)
        # The first response was lost on the phone; it retries the same link with the same keys.
        status, second, _ = await self.pair(code, phone, signer=phone.key)
        self.assertEqual(status, 200, second)
        self.assertEqual(second["device_id"], first["device_id"])
        self.assertNotEqual(second["token"], first["token"])
        self.assertEqual(await self.ping(first["token"], phone), 401, "the superseded token must stop working")
        self.assertEqual(await self.ping(second["token"], phone), 200)
        self.assertEqual([d["id"] for d in self.devices()], [first["device_id"]], "no orphan device row")

    async def test_store_retry_requires_proof_and_same_keys(self):
        phone, other = Phone(), Phone()
        st = self.ad.store
        code = st.create_pair_code()
        first = st.claim_pair_code(code, "synthetic", public(phone.key), public(phone.approval))
        self.assertIsNotNone(first)
        self.assertIsNone(st.claim_pair_code(code, "synthetic", public(phone.key), public(phone.approval)),
                          "an unproven retry must not reclaim")
        again = st.claim_pair_code(code, "synthetic", public(phone.key), public(phone.approval), proven=True)
        self.assertEqual(again["device_id"], first["device_id"])
        self.assertIsNone(st.device_for_token(first["token"]))
        self.assertEqual(st.device_for_token(again["token"])["id"], first["device_id"])
        self.assertIsNone(st.claim_pair_code(code, "synthetic", public(other.key), public(other.approval), proven=True))
        self.assertIsNone(st.claim_pair_code(code, "synthetic", public(phone.key), public(other.approval), proven=True),
                          "both device keys must match")
        self.assertEqual(len(st.devices()), 1)

    async def test_retry_without_a_valid_proof_is_rejected(self):
        phone, attacker = Phone(), Phone()
        code = self.ad.store.create_pair_code()
        status, first, _ = await self.pair(code, phone)
        self.assertEqual(status, 200)
        # Knowing the code and the public key is not enough: no signature, or one by another key.
        status, _, _ = await self.pair(code, phone)
        self.assertEqual(status, 403)
        status, _, _ = await self.pair(code, phone, signer=attacker.key)
        self.assertEqual(status, 403)
        self.assertEqual(await self.ping(first["token"], phone), 200, "a rejected retry must not rotate the token")
        self.assertEqual(len(self.devices()), 1)

    async def test_different_keys_are_rejected(self):
        phone, other = Phone(), Phone()
        code = self.ad.store.create_pair_code()
        self.assertEqual((await self.pair(code, phone, signer=phone.key))[0], 200)
        status, body, _ = await self.pair(code, other, signer=other.key)
        self.assertEqual(status, 403, body)
        self.assertEqual(len(self.devices()), 1)

    async def test_expired_code_is_rejected(self):
        phone = Phone()
        st = self.ad.store
        unused = st.create_pair_code()
        used = st.create_pair_code()
        self.assertEqual((await self.pair(used, phone, signer=phone.key))[0], 200)
        with st._lock:
            st._db.execute("UPDATE pair_codes SET expires=?", (time.time() - 1,))
        self.assertEqual((await self.pair(unused, phone, signer=phone.key))[0], 403)
        self.assertEqual((await self.pair(used, phone, signer=phone.key))[0], 403)
        self.assertEqual(len(self.devices()), 1)

    async def test_revoked_device_cannot_reclaim(self):
        phone = Phone()
        code = self.ad.store.create_pair_code()
        status, first, _ = await self.pair(code, phone, signer=phone.key)
        self.assertEqual(status, 200)
        self.assertTrue(self.ad.store.revoke(first["device_id"]))
        self.assertEqual((await self.pair(code, phone, signer=phone.key))[0], 403)

    async def test_retry_proof_is_single_use(self):
        phone = Phone()
        code = self.ad.store.create_pair_code()
        self.assertEqual((await self.pair(code, phone))[0], 200)
        status, second, proof = await self.pair(code, phone, signer=phone.key)
        self.assertEqual(status, 200, second)
        # Replaying the captured retry (same body, same signature) must not mint another token.
        status, _, _ = await self.pair(code, phone, headers=proof)
        self.assertEqual(status, 403)
        self.assertEqual(await self.ping(second["token"], phone), 200)

    async def test_first_claim_still_needs_no_proof(self):
        # Older apps send no signature on /v1/pair: a fresh code keeps working for them.
        phone = Phone()
        status, body, _ = await self.pair(self.ad.store.create_pair_code(), phone)
        self.assertEqual(status, 200, body)


if __name__ == "__main__":
    try:
        unittest.main(verbosity=2)
    finally:
        _HOME.cleanup()
