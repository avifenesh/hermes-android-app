# Local synthetic reviewer backend

This fixture runs independently of Hermes and never imports the gateway/plugin
entry point, discovers plugins, reads a real Hermes home, calls a provider, executes
commands or invokes tools. It imports **only** the checked-in `store.py` and
`sig.py` by file path, plus `catalog.py` (static synthetic data) beside it. Always
deploy those exact reviewed files with this fixture; it is not a standalone
single-file distribution. Python 3.11 or newer.

Every API path the Play build can reach answers with synthetic, per-enrollment
behaviour (see [Surfaces](#surfaces)). Nothing is simulated by fabricating success
for an action that did not happen: replies, jobs and installs are labelled
synthetic and change only this enrollment's sandbox state. The server is local
preparation; hosting it is still a separate step (see
[Deployment limits](#deployment-limits)).

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
cleartext or reach the host. An earlier signed local Play candidate and its
bundle-generated APK were paired against this fixture over HTTPS; the surfaces
added since have not been exercised on a device yet. No Play submission or stable
public reviewer deployment exists.

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

The `/review` page also shows the alarm clock's emergency-stop password
(`review-sandbox`). It protects nothing real: it only ends a synthetic alarm.

The reusable invitation authorizes *issuing new isolated enrollments*, not reading
any existing tenant. It is not an API bearer token and cannot sign device requests.
No shared pre-enrolled device key/token is shipped. Public enrollment/pairing allows
20 attempts per client (IPv4 address or IPv6 /64) and 400 in total per five minutes,
in one process. Behind the TLS proxy, pass `--client-ip-header X-Forwarded-For` (or the
header your proxy writes) so limits key on the real client; only the proxy's own last
hop is used, never a client-supplied value. Without it every request looks like the
proxy and shares one budget. Do not run multiple workers. The invitation page does not transmit it
in URLs and has no analytics/external assets; API responses and page use no-store.

## Security model

Both keys must be P-256 SPKI/canonical Base64. Request signing uses the production
method + raw path/query + timestamp + body SHA-256 contract and the production
normalized-signature durable SQLite replay claims. Invalid, stale, altered,
noncanonical and malleated/replayed proofs fail. Replay storage failures fail
closed. One central auth/replay database prevents cross-enrollment proof reuse
when a phone reuses its hardware key. Each enrollment has its own fixture database
and upload directory. Devices can list/revoke only themselves; revocation closes
SSE on its next 250 ms tick. Replay state persists over restarts; **do not restore
an old auth database or roll its clock back while ingress is open**.

Power-equivalent actions need the same exact-content approval-key proof as
production, even though nothing executes: approving a command (`once`, `session`,
`always`; a phone without a screen lock may approve `once` only), session prompt
approvals, guard-loosening settings (the production `_GUARDED` key pattern, copied
and kept equal by a test), plugin toggles, Hub skill install/update, plugin
install/update/remove, MCP install/remove, Fleet replies to agents that act without
asking, every raw terminal input, git push and pull-request creation. Denying,
uninstalling a Hub skill and clarify answers need no proof, as in production.

Limits: 1 MiB per ordinary request; 12 MiB for uploads and photo proofs (a camera
photo is several MB); 64 MiB of uploads per enrollment in total; at most 40 cron
jobs, three queued scripted replies per chat, 500 messages per other-app session.
MCP credential values typed into the Hub are discarded; only their names are kept.

To rotate invitations, stop the server and remove only `STATE/invitation.txt`;
restart generates a new invitation without invalidating enrolled devices. To reset
all synthetic state, stop the server and remove **only that dedicated STATE
child**. This invalidates every pairing and discards replay claims; close ingress
and re-enroll, never reuse old devices/tokens after a reset. No reset/delete HTTP
endpoint is exposed. Uploaded files and entered text remain on disk until an
operator removes that dedicated state; do not enter real personal data.

## Surfaces

`/v1/me` advertises `fleet`, `radar`, `projects` and `wake_wa` as available, so the
Play build shows every screen. Each row below is per enrollment and persists in
that enrollment's SQLite file.

| Surface | Sandbox behaviour |
|---|---|
| Identity, health, profiles, commands | Synthetic machine, profiles `default` and `research`, a small slash-command catalogue (`/help`, `/retry`, `/moa`, a skill command) |
| Chats, sync, SSE | Seeded chats with markdown, table, code, checklist, file, an activity card and an approval card; create (per profile), rename, pin, archive, read; incremental sync; SSE `hello`/`seq` with the working list |
| Send, steer, queue, stop | Inert user text; a scripted reply that streams like the adapter (working flag, a growing message, final usage footer); steer folds into the running reply; up to three queued replies; stop cancels the run and the queue with a status line. Slash commands, voice notes, photos and files get explained scripted answers; nothing is transcribed, viewed, opened or run |
| Run, usage, activity | Run screen steps of the scripted turn; per-turn and per-session token counts derived from text length, cost `$0.00`; activity detail with calls and a file diff |
| Approvals | Seeded card and a new one for any message containing "approval"; approve choices need the exact-content proof; deny does not; resolving records the choice only |
| Model and reasoning | Synthetic provider catalogue; per-chat and default model, reasoning and fast mode persist; `synthetic-large` raises the cost-confirmation dialog; switching while a reply runs is refused, as in production |
| Session actions | Status and context sheets, rename, export as a markdown file in the chat, undo (returns the text, greys the turn), new session and compress status lines |
| Elsewhere sessions | Synthetic CLI and Telegram sessions: paged history, send with a scripted reply, a clarify prompt, an approval prompt (signed), stop, a superseded read-only session |
| Search | Over chats and other sessions; words, `"phrases"`, `OR`, `NOT`, `prefix*`; FTS-style `>>>hit<<<` snippets |
| Pulse, usage history | Illustrative spend (the same seven values on both screens), live other-app session, pending approvals; usage by day, model and surface |
| Cron | Seeded jobs; create, edit, delete with schedule parsing (`every 2h`, `every day at 9am`, `weekdays at 9am`, `every monday 9am`, `in 45m`, five-field cron) and readable errors; pause, resume, run now; outputs per run; due runs materialise as synthetic output when the list loads; delivery to the app posts to the job's digest chat; delivery summary and "move to the app" |
| Alarm clock (Wake) | Alarms, days, reading level and contacts persist; test alarm, alarm fired, reading proof (five questions, fixed answer key), photo proof (any new photo, never analysed), steps proof, check-in puzzle about a minute after the proof, done summary in the Wake chat, cancel test, emergency stop with the password, password change. Escalation and the WhatsApp test are simulated and say nothing was sent |
| Memory | Add, edit, forget with character limits from settings; on/off from settings |
| Settings | Curated groups (same keys as production) and "All settings"; typed validation; guard-loosening keys need the proof; values feed back (reasoning shown in replies, memory on/off, timezone for cron) |
| Skills, plugins, MCP | Toggle skills (locked ones stay on); skill text; plugin toggles store the choice and show "restart to apply"; MCP server list |
| Restart | Acknowledged: pending plugin and MCP changes load; a status line in the Hermes chat says no process restarted |
| Hub | Synthetic catalogue across sources and profiles; search; preview; security scan with one skill the scan blocks; install, update (with "update available"), uninstall; plugin install with restart, update with a capability-consent step, remove; MCP install with a required credential (discarded) and remove. Jobs finish after about a second |
| Radar | Synthetic scout batch, saved and history views, vote, save, dismiss, feedback and notes, spawn a chat (with a scripted reply) or a task, a curator note with its diff, a Momentum report with stable ids |
| Fleet | Synthetic agents on a synthetic host: a remote agent (replies need the proof), a terminal agent with a live synthetic screen (keys, scroll and taps need the proof per input; stale frames are refused), a build watcher that accepts Esc, an ended task, plus rows for working chats and live other-app sessions |
| Projects | Two synthetic repos: status, per-file diff, stage, unstage, commit, push and open PR (both signed); the PR link opens a static `/review/pr` page that says nothing was pushed |
| Files, voice | Signed raw upload/download, isolated per tenant, random IDs, the requested MIME type returned; opaque bytes, never parsed/executed/fetched. Server voice returns a short generated two-note chime, not speech |
| Devices, push | Only the current enrollment is visible/revocable; push config disabled and FCM tokens discarded (the app explains it falls back to its own connection) |
| Review capabilities | `/v1/review/capabilities` lists every served route, `unsupported: []`, honest non-screen limits and `submission_ready: true` |

Paths outside the Play build (phone control, self-check, anything unknown) still
return an explicit HTTP **501** `Unsupported in synthetic review sandbox`.

Not simulated, because they are device or account behaviour rather than server
surfaces: FCM push delivery, the phone's speech recognizer and voices, exact-alarm
and notification permissions, background/lifecycle behaviour, biometrics, first
pairing and deep-link opening on the signed Play artifact. Check those on a device.

## Deployment limits

No hosting, DNS, public network writes, store submission, real gateway changes,
provider calls or APK/Gradle/emulator work are performed by these files/tests.
The server is single-process, not Internet-hardened: no distributed quotas,
retention sweeper, aggregate chat/message quota, production abuse controls or
monitoring. Before hosting, add bounded tenant retention and enrollment limits,
TLS/proxy configuration, invitation rotation, uptime/access procedures,
privacy/retention instructions and artifact-specific Android qualification. Do not
expose the local fixture on a real host with valuable mounts merely because auth
tests pass.

## Test scope

`test_backend.py` uses real aiohttp HTTP sockets (paths sent byte-for-byte, as
OkHttp does), SQLite databases and P-256 request and approval keys. Its 27 unittest
cases cover:

- enrollment, signatures, replay, tamper, restart persistence, storage failure
  closed, signed SSE with the working flag and revocation, throttling;
- one test per surface above, with the request shapes the app sends, the fields it
  parses, persistence across calls and tenant isolation;
- `test_every_play_reachable_api_answers`: every Play-reachable path from the
  client inventory returns a real answer (77 of them returned 501 before);
- `test_client_calls_are_all_in_the_play_inventory`: every `/v1` literal in the
  Play build's Kotlin sources (`src/main`, `src/play`) maps to an inventoried,
  served route, so a new client call fails here until the sandbox serves it;
- `test_sandbox_copies_match_production`: the guard pattern, curated settings keys
  and terminal keys equal the production source (parsed, never imported).

These are focused backend checks, **not** production security certification or
Android UI tests.
