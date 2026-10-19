# Local synthetic reviewer backend (not submission-ready)

This fixture runs independently of Hermes and never imports the gateway/plugin
entry point, discovers plugins, reads a real Hermes home, calls a provider, executes
commands or invokes tools. It imports **only** the checked-in `store.py` and
`sig.py` by file path. Always deploy those exact reviewed files with this fixture;
it is not a standalone single-file distribution.

## Run / stop / test

From the repository root, with the installed runtime (already has `aiohttp` and
`cryptography`; no installs or Gradle needed):

```sh
cd hermes-android-app
PYTHONDONTWRITEBYTECODE=1 uv run --with aiohttp --with cryptography python review-backend/test_backend.py
PYTHONDONTWRITEBYTECODE=1 uv run --with aiohttp --with cryptography python review-backend/server.py \
  --state "$HOME/.cache/hermes-play-prep/review-backend" \
  --public-url http://127.0.0.1:47941 --port 47941
```

Stop the foreground server with Ctrl-C. Tests bind ephemeral loopback ports and
close every server/client/SQLite connection and remove their own state directories.
State paths outside a dedicated child of `~/.cache/hermes-play-prep` are rejected,
including the live Hermes data tree. `HERMES_HOME` is neither consulted nor changed;
all production Store constructors receive explicit isolated database paths. Run with
`PYTHONDONTWRITEBYTECODE=1` to keep generated caches out of the source checkout.

For an independently operated HTTPS review deployment, use the **same command**
with `--public-url https://YOUR-DEDICATED-REVIEW-HOST` (no path, query or credentials).
The listener still binds only `127.0.0.1`. A dedicated TLS reverse proxy and an
isolated deployment account/container are required; this task does not provision
or expose either. Never mount a real Hermes home, source history, credentials or
execution host's directories in that deployment. Loopback HTTP is for local
protocol tests, not evidence that a production Android build can connect over
cleartext or reach the host. The signed local Play candidate and its bundle-generated APK were freshly paired against this isolated fixture over HTTPS. No Play submission or stable public reviewer deployment exists.

## Repeatable enrollment without a security bypass

1. Start the server. It creates a random reviewer invitation in
   `STATE/invitation.txt` with mode 0600 (the file's contents are not logged).
2. Give authorized reviewers the `/review` HTTPS page address and invitation via
   the submission's private access instructions. Keep the invitation secret.
3. The reviewer enters the invitation on that page and presses **Get fresh pairing
   link**. Copy/paste the generated link into the app if URI opening is ambiguous
   between full and Play packages. No QR generator dependency is required.
4. Each request produces an ordinary **one-use, ten-minute pairing code**. The
   unchanged app submits its separate request and approval P-256 public keys to
   `/v1/pair`, receives a random token and uses the normal signed API afterward.
5. To enroll another device or reset a demo, obtain another fresh link from the
   same page. Every enrollment gets a separately seeded tenant, even if the
   physical device reuses a key. Pairing codes are never made reusable.

The reusable invitation authorizes *issuing new isolated enrollments*, not reading
any existing tenant. It is not an API bearer token and cannot sign device requests.
No shared pre-enrolled device key/token is shipped. Public enrollment/pairing has a
single-process global limit of 40 attempts per five minutes (no trusted client IP
headers). Do not run multiple workers. The invitation page does not transmit it
in URLs and has no analytics/external assets; API responses and page use no-store.

Both keys must be P-256 SPKI/canonical Base64. Request signing uses the production
method + raw path/query + timestamp + body SHA-256 contract and the production
normalized-signature durable SQLite replay claims. Invalid, stale, altered,
noncanonical and malleated/replayed proofs fail. Replay storage failures fail
closed. One central auth/replay database prevents cross-enrollment proof reuse
when a phone reuses its hardware key. Each enrollment has its own fixture database
and upload directory. Devices can list/revoke only themselves; revocation closes
SSE on its next 250 ms tick. Replay state persists over restarts; **do not restore
an old auth database or roll its clock back while ingress is open**.

To rotate invitations, stop the server and remove only `STATE/invitation.txt`;
restart generates a new invitation without invalidating enrolled devices. To reset
all synthetic state, stop the server and remove **only that dedicated STATE
child**. This invalidates every pairing and discards replay claims; close ingress
and re-enroll, never reuse old devices/tokens after a reset. No reset/delete HTTP
endpoint is exposed. Uploaded files and entered text remain on disk until an
operator removes that dedicated state; do not enter real personal data.

## Implemented surfaces

| Surface | Behavior |
|---|---|
| Health, me, profiles, commands | Synthetic identity, default profile, security v2 marker; empty executable-command catalogue |
| Chats and sync | Three seeded conversations, seven messages including markdown/table/code/checklist/file, incremental seq sync; create, title/pin/archive/read mutations |
| Send, run, stop | Stores inert user text and a clearly scripted reply; no working agent; run snapshot, harmless stop acknowledgement |
| Events | Production SSE hello/seq envelope and keepalive; signed handshake and ongoing revocation checks |
| Approvals | Seeded inert card; once/deny requires separate exact-content production approval proof; durable replay checks; never resolves any real gateway operation |
| Pulse | Explicitly synthetic illustrative totals/chart and real fixture pending cards, not real billing |
| Cron | Seeded job/output; pause/resume persisted; run appends a labelled synthetic digest, **no scheduler executes** |
| Memory | Seeded memory/user entries; bounded add/replace/remove persisted only to tenant SQLite |
| Sessions/search | Synthetic conversation history; plain substring search, not production FTS operators |
| Files | Seeded text download; signed raw upload/download, isolated per tenant, random IDs; 1 MiB request cap; opaque bytes, never parsed/executed/fetched externally |
| Devices | Only current enrollment visible/revocable, not other reviewers |
| Push config/FCM | Disabled configuration; submitted FCM tokens deliberately discarded; no notification delivery |
| Review capabilities | `/v1/review/capabilities` enumerates unsupported categories and explicitly returns `submission_ready: false` |

Unknown/unimplemented authenticated surfaces return an explicit HTTP **501**
`Unsupported in synthetic review sandbox`, never a fake success. Feature flags
hide optional fleet, projects, Radar and WhatsApp escalation; they do **not** hide
all retained app controls. This is useful local preparation, not complete Play
review access.

## Blocking gaps and deployment limitations

Before submission, implement harmless faithful simulations of every retained
surface or deliberately remove it from the submitted artifact with honest UX:

- Wake configuration, sessions/proofs/password/override/test/cancel/fired flows,
  cron create/edit/delete and delivery configuration.
- Server TTS/STT, voice attachments/transcription and real Talk behavior. Text
  chat does not prove voice, microphone or local Android wake/alarm behavior.
- Model catalogue/switch/session actions, usage details/history, general settings,
  settings schema, skills/plugin/MCP inventory/toggles, marketplace browse/install,
  activity cards/details and self-check.
- Fleet/terminal, projects/git, Radar are disabled rather than simulated. They
  remain incomplete if the final artifact retains them for reviewers.
- FCM push, Android background/lifecycle behavior, permissions/biometrics, first
  pairing and deep-link opening on the actual signed Play artifact remain untested.

No hosting, DNS, public network writes, store submission, real gateway changes,
provider calls or APK/Gradle/emulator work are performed by these files/tests.
The server is single-process, not Internet-hardened: no distributed quotas,
retention sweeper, aggregate chat/device/disk quota, production abuse controls or
monitoring. A per-request size cap is not an aggregate storage bound. Before
hosting, add bounded tenant retention/storage and enrollment limits, TLS/proxy
configuration, invitation rotation, uptime/access procedures, privacy/retention
instructions and artifact-specific Android qualification. Do not expose the local
fixture on a real host with valuable mounts merely because auth tests pass.

## Test scope

`test_backend.py` uses real aiohttp HTTP sockets, SQLite databases, P-256 request
and approval keys. Nine unittest cases cover repeated enrollment/one-use/expiry/
invalid keys, cross-tenant isolation, chat/sync mutations and inert slash-like text,
cron/memory readback, signed upload/download and size/path limits, exact and
malleated replay, noncanonical Base64, query/body tampering, wrong keys/stale
requests, concurrent duplicate claims and independent calls, restart persistence,
storage failure closed, approval-content binding, signed SSE/revocation, disabled
push, explicit unsupported responses and enrollment throttling. These are focused
backend checks, **not** production security certification or Android UI tests.
