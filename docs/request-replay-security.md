# Request and approval replay protection

## Security minimum

A secure bundled plugin is required for public installation. A patched server advertises `security.signature_replay: 2` in `/v1/health`,
`/v1/me` and the command metadata response. An absent field means the server
has not advertised this fix. The HTTP signing protocol remains API 1.
The marker is a capability indication, not an independent attestation; connect
only to the intended server over authenticated TLS.

Install or update the complete matching `server/mobile/` directory using the
[fresh/update instructions](../README.md#install-the-gateway-plugin), including
the backup and clean-destination steps. The replay change spans `sig.py`,
`store.py`, `adapter.py`, `control.py`, `fleet.py` and `projects.py`; this list is
not a partial-deployment recipe. Copy directory contents into `plugins/mobile/`,
never a nested `plugins/mobile/mobile/`. Activate the copied code using the
gateway's supported service control. The store creates an
additive `signature_replays` table and expiry index; no re-pairing or manual
schema migration is normally required. This source change and the isolated
tests do not deploy or verify a running gateway. See [SECURITY.md](../SECURITY.md)
for artifact verification and private reporting to **hello@tiyuvta.ai**.

An old server remains vulnerable even when used by an updated Android app.
The security minimum is this server patch, not a new APK. Rolling back the
server also rolls back the protection. Keep the database intact across restarts
and use the same database for every worker serving a pairing.

## Identity and verification

* Base64 decoding is strict, including canonical padding and zero pad bits.
* Verify the actual ECDSA signature with the paired P-256 public key first.
  Other curves are rejected, not normalized using a guessed curve order.
* Decode the verified DER signature as `(r, s)`, replace `s` with
  `min(s, n-s)` for the secp256r1 generator order, and encode canonical DER.
  This accepts either valid low-S or high-S client signatures while giving both
  the same replay identity. Strict Base64 alone does not prevent ECDSA malleability.
* Hash the proof domain, verified public-key SPKI and normalized DER. The
  `request` and `approval` domains are separate. Using the verified key instead
  of a caller-supplied device ID also blocks reuse across pairings sharing a key.
* Atomically claim the identity in SQLite before entering the authorized action.
  A unique primary key plus a write transaction permits exactly one concurrent
  claimant, including separate database connections. Claims survive restarts.
* Keep the claim through the signed timestamp's entire acceptance window:
  `timestamp + SKEW`, with `SKEW = 300` seconds. Timestamp validation and expiry
  are inclusive at the endpoint; only strictly expired entries are pruned. No
  size-based eviction of live claims is permitted. Database failure fails closed.

Biometric proofs are consumed independently of outer request proofs. Re-signing
an outer request cannot reuse a captured approval, configuration/phone-arm,
plugin/Hub, fleet or git proof. Invalid signatures do not reserve an identity.
A proof is consumed even if the later action fails; retrying a guarded action
requires a newly authorized biometric signature, not reuse of the old proof.

## Compatibility and limits

No nonce header, canonical-message change, Android edit or key rotation is
required for this fix. Existing Android `Base64.NO_WRAP` encodings and P-256
signatures already satisfy the strict format. Existing signed-client fixtures
keep the same canonical message:

```text
UPPERCASE_METHOD\nraw_path_and_query\ntimestamp_seconds\nsha256(body)
```

Independently generated signatures over identical requests in the same second
remain valid, including repeated polling and an outer retry after a biometric
challenge. Deduplication is by cryptographic proof identity, not request content.
A deterministic signer reproducing the identical signature must advance its
signed timestamp for a new identical operation. Noncanonical Base64, signatures
reused as retries and unsupported curves are deliberately rejected.

The P-256 low-S normalization closes the known public signature transformation
`(r,s) -> (r,n-s)`; creating another accepted signature for the same message still
requires the signing key. This is replay prevention, not application idempotency:
a holder of the private key can authorize a new identical operation. A signed
nonce would be useful for a future explicit idempotency protocol, but is not
needed to repair these encoding/malleability vulnerabilities.

Replay claims do not cover proofs accepted before this patch first created the
persistent table, or claims erased by restoring an older database backup. If
captured pre-upgrade requests must be invalidated, revoke/re-pair the affected
devices, or keep API ingress closed for a full `2*SKEW` interval plus the inclusive
clock boundary before reopening it. Accurate server time remains a prerequisite;
do not roll the clock or replay database backwards within the validity window.

## Offline verification

```sh
"$HERMES_PYTHON" scripts/replay_security_integration.py
```

Run from the repository root with `HERMES_PYTHON` set to the installed Hermes
managed-runtime executable and its source checkout at `~/.hermes/hermes-agent`.
Do not import bootstrap or discover plugins in a wrapper first. A stale legacy
checkout venv can differ from the managed runtime; see [QA](android-qa.md#public-phone-harness-and-hermes-baseline).

The harness force-sets an isolated `HERMES_HOME` before importing bootstrap or
Hermes modules. It creates only synthetic keys/pairings and uses real P-256,
SQLite and aiohttp auth middleware on temporary loopback test servers. It covers
exact replays, Base64 variants including pad bits, alternate ECDSA signatures,
concurrent requests and approvals, separate SQLite connections, restart
persistence, timestamp boundaries, pruning, key/curve validation, request-body
binding, independent same-second requests, and independent approval signatures.
It does not contact a running gateway or production endpoint.
