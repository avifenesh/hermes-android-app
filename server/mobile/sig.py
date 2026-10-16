"""P-256 request signatures and durable, encoding/malleability-safe replay claims."""
from __future__ import annotations

import base64
import hashlib
import time
from typing import Optional

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, utils
from cryptography.hazmat.primitives.serialization import load_der_public_key

SKEW = 300
REPLAY_VERSION = 2
# Order of secp256r1's generator, NOT the field prime. Only this curve is accepted.
P256_ORDER = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551


def _b64(value: str) -> bytes:
    decoded = base64.b64decode(value, validate=True)
    # validate=True alone permits nonzero pad bits and some excess padding.
    if base64.b64encode(decoded).decode("ascii") != value:
        raise ValueError("noncanonical base64")
    return decoded


def load_pub(b64: str):
    try:
        k = load_der_public_key(_b64(b64))
        if isinstance(k, ec.EllipticCurvePublicKey) and isinstance(k.curve, ec.SECP256R1):
            return k
    except Exception:
        pass
    return None


def canonical(method: str, raw_path: str, ts: str, body: bytes) -> bytes:
    return f"{method.upper()}\n{raw_path}\n{ts}\n{hashlib.sha256(body or b'').hexdigest()}".encode()


def _verified(pub_b64: Optional[str], msg: bytes, sig_b64: str):
    k = load_pub(pub_b64 or "")
    if not k or not sig_b64:
        return None
    try:
        der = _b64(sig_b64)
        k.verify(der, msg, ec.ECDSA(hashes.SHA256()))
        r, s = utils.decode_dss_signature(der)
        # Both (r,s) and (r,n-s) verify. Never use raw signature bytes or
        # the header spelling as the replay identity, even with strict Base64.
        if not (0 < r < P256_ORDER and 0 < s < P256_ORDER):
            return None
        normalized = utils.encode_dss_signature(r, min(s, P256_ORDER - s))
        principal = k.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        return principal, normalized
    except Exception:
        return None


def verify(pub_b64: Optional[str], msg: bytes, sig_b64: str) -> bool:
    return _verified(pub_b64, msg, sig_b64) is not None


class Replay:
    """Claims live in Store, not process memory; a restart must not enable replay."""

    def fresh(self, sig_b64: str, *, pub: Optional[str], msg: bytes,
              ts: str, scope: str, store) -> bool:
        if not ts_ok(ts):
            return False
        verified = _verified(pub, msg, sig_b64)
        if verified is None:
            return False
        principal, normalized = verified
        # Bind to the VERIFIED public key, not a supplied key/device label. This
        # also prevents reuse across two pairings of the same hardware key.
        # Separate request and approval domains; never dedupe by action content:
        # independently signed identical calls in one second are legitimate.
        identity = hashlib.sha256(scope.encode("ascii") + b"\0" + principal + normalized).hexdigest()
        # Keep through the full signed timestamp window, including future skew
        # and the inclusive endpoint. Store checks time again and claims atomically.
        return store.claim_signature(identity, int(ts) + SKEW)


def approval_ok(request, msg: bytes) -> bool:
    """Consume a biometric proof once, independently of its outer request proof."""
    replay, store = request.get("signature_replay"), request.get("signature_store")
    if replay is None or store is None:
        return False
    return replay.fresh(request.headers.get("X-HM-ASig", ""),
                        pub=request["device"].get("apub"), msg=msg,
                        ts=request.headers.get("X-HM-Ts", ""), scope="approval", store=store)


def ts_ok(ts: str) -> bool:
    try:
        return abs(time.time() - int(ts)) <= SKEW
    except Exception:
        return False
