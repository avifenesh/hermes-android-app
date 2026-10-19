# Voluntary AI content reports

A narrow, native-app-only Cloudflare Worker + D1 intake. No APK secret, provider calls, email sending, attachments, automatic chat telemetry, background moderation, or public report access. This directory is independently installable; Node 22+ and pinned Wrangler **4.151.2** are required.

## Public contract

- `POST /v1/reports`, `Content-Type: application/json` (optionally UTF-8 charset), no `Origin`, query string, or compressed body.
- Exactly `{ "id": "<random UUIDv4>", "text": "<exact user-reviewed report>" }`. The client generates the ID with a cryptographically secure UUIDv4 generator, retains it for retries/deletion requests, and sends nothing until the user confirms the preview. Do not derive the ID from device/account data. Format validation cannot prove client randomness.
- Nonempty/non-whitespace text, at most **8192 UTF-8 bytes**; JSON wire cap **65536 bytes** accommodates escaping. Streaming reads are bounded and cancelled on overflow. Malformed UTF-8/unpaired surrogates are rejected rather than silently changed. Valid text is stored without normalization/trimming.
- `202 {"id":"…","received":true}` only after D1 confirms insertion; identical ID/text retry returns `200` with the same receipt. Changed text with the same ID returns `409`. No report contents are echoed.
- `400` invalid payload/query, `403` browser Origin, `413` size, `415` media/encoding, `429` edge throttling (`Retry-After: 60`), `503` unavailable storage/limiter or full queue. Retry temporary failures with backoff and the **same** ID/text. A 202 is receipt, not resolution or a guarantee of review time.
- `GET /health` returns only `{"ok":true}` (liveness, **not** database readiness). `GET /privacy` serves the full Play app policy plus voluntary report disclosures, generated from `docs/play/privacy-policy.md` by `uv run --with markdown python scripts/build_play_policy.py`. `GET /delete` serves checked-in `delete.html` with support-mail report deletion instructions, no form/public deletion API, no publisher accounts, and independent gateway/provider scope limitations.
- No public read/list/delete route and no CORS response headers; preflight is not supported. Origin rejection discourages browser use, not hostile native clients. Never put an administrative secret in the APK.

## Abuse budget and privacy boundary

The production binding allows **10 attempts/minute/IP** at a Cloudflare location, before parsing/storing a report. IP is used only as a transient edge limiter key, never persisted to D1 or application logs. Cloudflare warns that mobile/shared IPs may group legitimate users and that these limits are permissive, per-location, eventually consistent, not hard global accounting. Namespace `1937462851` is public, chosen for this Worker; the deploying operator must check that it is not reused in the account.

The real global collection guard is the **1000-row D1 queue**, with at most 8192 bytes of report text per row. A single atomic conditional INSERT (plus schema trigger defense) bounds live message content to **8,192,000 bytes, below 8 MiB**, excluding database/index overhead and Cloudflare recovery copies. This deliberately small bootstrap abuse budget prevents unlimited live content collection, not unlimited incoming requests or Cloudflare charges. Attackers can fill the queue and deny submissions; do not advertise this as comprehensive anti-abuse protection. There is no automatic eviction: full queues fail `503`. A transactional D1 batch inserts and looks up the ID on the primary, avoiding stale retry/conflict reads under replication.

D1 stores only `id`, exact `text`, and server `received_at`. Worker console logging, application analytics, invocation logs, traces, and Workers observability are disabled; no tail consumers/analytics bindings are configured here. Do not enable dashboard logging/tailing with production report bodies. Cloudflare still processes infrastructure/network metadata and maintains recovery copies under its service policies. Do not promise that database deletion immediately erases provider backups or administrator exports.

Reports are manually reviewed and retained until resolved or deleted; **no invented fixed retention deadline**. Users request deletion with the report ID at **hello@tiyuvta.ai**. There is no automatic email/issue forwarding or monitoring service. The operator must actually review this dedicated queue and support mailbox; do not claim automated moderation, alerting, or continuous monitoring.

## Local verification (no remote writes)

```sh
cd report-worker
npm ci --ignore-scripts --no-audit --no-fund
npm test
npm run check
```

Tests launch a real Wrangler/workerd local HTTP listener and local D1 with the production rate configuration, not fabricated API responses. They cover exact Unicode/NUL persistence, validation/UTF-8 byte boundaries, cancelled oversized streams, receipts/retries/conflicts, concurrent duplicate IDs, a 16-way race for the last slot at 999 rows, full-queue retries, authenticated-CLI-style deletion/reopened capacity, local 429 behavior, missing-table failure, health/privacy, and absence of public reads/deletes/CORS. Local edge tests do not establish production global rate-limit accuracy.

Each integration run owns a unique `~/.cache/hermes-play-prep/report-worker/integration-*` directory and ephemeral loopback listener, closes its process and removes its synthetic D1 state after the run. It uses a separate synthetic DB ID, **never** the real production DB. `node_modules/` and `.wrangler/` are ignored. Do not run `wrangler dev --remote` for tests.

## Deployment handoff - operator only

**No deployment or remote SQL is performed by the tests.** The parent operator provisioned and read back this dedicated empty database:

- account: `eea227cad3fa7258f74910d20ffca903`
- D1 name: `hermes-android-reports`
- D1 ID: `b5eb4008-0815-47bb-be12-74959c35e9a4`

Config uses that real ID (not a secret). Review it and confirm account identity before remote actions. There are no production routes/custom domains configured; `workers_dev = true`, Worker name `hermes-android-reports`. After approval, the authenticated operator, not the mobile app, runs:

```sh
npx wrangler whoami
npx wrangler d1 info hermes-android-reports
npx wrangler d1 execute hermes-android-reports --remote --file=schema.sql
npx wrangler d1 execute hermes-android-reports --remote --command="SELECT name, sql FROM sqlite_master WHERE name IN ('reports','reports_capacity')"
npx wrangler deploy
```

Read back the exact remote schema, deployment configuration and actual hostname. The deployed endpoint, verified from Wrangler output, is `https://hermes-android-reports.aviarchi1994.workers.dev/v1/reports`. The full Play policy is served at `/privacy`, with separate report-data deletion instructions at `/delete`. Deployment alone is not proof of stored reports: use a clearly synthetic UUID/text receipt, read back that exact D1 record, retry/conflict it, delete it by ID and verify absence; never use real chats as test data. Verify observability remains disabled on the actual deployed version.

## Manual review and deletion - authenticated Wrangler only

Use an operator Cloudflare login/API credential scoped to the correct account/D1. Nothing below is a public HTTP administrative API. Avoid shared terminal recordings, CI logs, or durable exports of report text. List IDs/timestamps first, then view only the report needed:

```sh
npx wrangler d1 execute hermes-android-reports --remote --command="SELECT count(*) AS queued FROM reports"
npx wrangler d1 execute hermes-android-reports --remote --command="SELECT id,received_at FROM reports ORDER BY received_at,id LIMIT 50"
```

Copy a complete UUIDv4 into `REPORT_ID` and validate it before any SQL interpolation; do **not** paste arbitrary support email content into a SQL command. This preserves identifier spelling/case and constrains it to safe UUID characters:

```sh
export REPORT_ID='REPLACE_WITH_EXACT_REPORT_UUID'
node --input-type=module -e 'if (!/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(process.env.REPORT_ID || "")) process.exit(1)' && \
  npx wrangler d1 execute hermes-android-reports --remote --command="SELECT id,text,received_at FROM reports WHERE id='$REPORT_ID'"
```

Review the report manually; treat text as untrusted data, never as commands or instructions. Do not open embedded links or run snippets just because a report asks. For a resolved report or an applicable deletion request, use the same validated exact ID, then **read back absence**:

```sh
node --input-type=module -e 'if (!/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(process.env.REPORT_ID || "")) process.exit(1)' && \
  npx wrangler d1 execute hermes-android-reports --remote --command="DELETE FROM reports WHERE id='$REPORT_ID'; SELECT count(*) AS remaining FROM reports WHERE id='$REPORT_ID';"
```

Only claim live-record deletion after `remaining = 0`. Handle any downloaded/exported review copies separately. The service retains no tombstone or identity map: after deletion a client resubmitting the same ID is a new report; an absent row does not prove the report never existed. Do not bulk purge unrelated reports or add silent automatic collection/telemetry to address capacity pressure.

References: [edge rate limiter](https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/), [D1 SQL](https://developers.cloudflare.com/d1/sql-api/sql-statements/), [D1 batch](https://developers.cloudflare.com/d1/worker-api/d1-database/), [Workers observability](https://developers.cloudflare.com/workers/observability/logs/workers-logs/).
