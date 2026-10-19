import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { spawn, execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync, rmSync, mkdirSync } from 'node:fs';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import net from 'node:net';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const base = '/home/avifenesh/.cache/hermes-play-prep/report-worker';
const cli = join(root, 'node_modules/wrangler/bin/wrangler.js');
const env = { ...process.env, WRANGLER_SEND_METRICS: 'false', CI: 'true' };
const delay = ms => new Promise(r => setTimeout(r, ms));

test('real Wrangler HTTP + D1 integration (no remote calls)', { timeout: 180000 }, async t => {
  mkdirSync(base, { recursive: true });
  const temp = mkdtempSync(join(base, 'integration-'));
  t.after(() => rmSync(temp, { recursive: true, force: true }));
  const config = join(temp, 'wrangler.json');
  const state = join(temp, 'state');
  // Only the local test database ID differs; production limiter is exercised unchanged.
  writeFileSync(config, JSON.stringify({
    name: 'hermes-android-reports-local-test', main: join(root, 'src/index.js'),
    compatibility_date: '2026-10-01', observability: { enabled: false },
    rules: [{ type: 'Text', globs: ['**/*.html'], fallthrough: true }],
    d1_databases: [{ binding: 'DB', database_name: 'hermes-android-reports', database_id: '00000000-0000-4000-8000-000000000001' }],
    ratelimits: [{ name: 'REPORT_RATE_LIMITER', namespace_id: '1937462851', simple: { limit: 10, period: 60 } }],
  }));
  const sql = command => JSON.parse(execFileSync(process.execPath, [cli, 'd1', 'execute', 'DB', '--local', '--persist-to', state, '--config', config, `--command=${command}`, '--json'], { cwd: root, env, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }));
  sql(readFileSync(join(root, 'schema.sql'), 'utf8'));
  const listener = net.createServer();
  await new Promise(r => listener.listen(0, '127.0.0.1', r));
  const port = listener.address().port;
  await new Promise(r => listener.close(r));
  const server = spawn(process.execPath, [cli, 'dev', '--local', '--ip', '127.0.0.1', '--port', String(port), '--persist-to', state, '--config', config, '--inspector-port', '0'], { cwd: root, env, stdio: ['ignore', 'pipe', 'pipe'] });
  let output = '';
  server.stdout.on('data', b => { output += b; });
  server.stderr.on('data', b => { output += b; });
  t.after(async () => {
    server.kill('SIGTERM');
    await Promise.race([new Promise(r => server.once('exit', r)), delay(5000)]);
    if (server.exitCode === null) { server.kill('SIGKILL'); await new Promise(r => server.once('exit', r)); }
    rmSync(temp, { recursive: true, force: true });
  });
  const origin = `http://127.0.0.1:${port}`;
  let ready = false;
  for (let i = 0; i < 150; i++) {
    try { if ((await fetch(`${origin}/health`)).ok) { ready = true; break; } } catch {}
    if (server.exitCode !== null) break;
    await delay(100);
  }
  assert.ok(ready, output);
  let ipCounter = 0;
  const post = (report, options = {}) => fetch(`${origin}/v1/reports`, {
    method: 'POST', headers: { 'content-type': 'application/json', 'cf-connecting-ip': `198.18.${Math.floor(++ipCounter / 250)}.${ipCounter % 250 + 1}`, ...options.headers },
    body: typeof report === 'string' ? report : JSON.stringify(report),
  });
  const id = randomUUID();
  const text = '  Reviewed only: שלום 👾 e\u0301\n\u0000 end  ';
  await t.test('confirmed receipt, exact persistence, idempotency and conflict', async () => {
    const first = await post({ id, text });
    assert.equal(first.status, 202); assert.deepEqual(await first.json(), { id, received: true });
    assert.equal((await post({ id, text })).status, 200);
    assert.equal((await post({ id, text: 'different' })).status, 409);
    const row = sql(`SELECT * FROM reports WHERE id = '${id}'`)[0].results[0];
    assert.deepEqual(Object.keys(row).sort(), ['id', 'received_at', 'text']);
    assert.equal(row.text, text); assert.match(row.received_at, /^\d{4}-.*Z$/);
  });
  await t.test('strict input, byte limits, media type and browser rejection', async () => {
    const invalid = [null, [], {}, { id, text: '' }, { id, text: '  ' }, { id, text: 1 }, { id: 'not-uuid', text: 'x' }, { id: '00000000-0000-1000-8000-000000000001', text: 'x' }, { id, text: 'x', email: 'hidden' }];
    for (const body of invalid) assert.equal((await post(body)).status, 400, JSON.stringify(body));
    assert.equal((await post('{bad')).status, 400);
    assert.equal((await post({ id, text: 'x' }, { headers: { 'content-type': 'text/plain' } })).status, 415);
    assert.equal((await post({ id, text: 'x' }, { headers: { origin: 'https://example.org' } })).status, 403);
    assert.equal((await post({ id: randomUUID(), text: '😀'.repeat(2049) })).status, 413);
    assert.equal((await post(' '.repeat(65537))).status, 413);
    assert.equal((await post({ id: randomUUID(), text: '😀'.repeat(2048) })).status, 202);
  });
  await t.test('no public reads, deletes, preflight or CORS; static privacy and health', async () => {
    for (const method of ['GET', 'DELETE', 'PUT', 'OPTIONS']) {
      const r = await fetch(`${origin}/v1/reports`, { method });
      assert.equal(r.status, 405); assert.equal(r.headers.get('access-control-allow-origin'), null);
    }
    assert.equal((await fetch(`${origin}/v1/reports/${id}`)).status, 404);
    assert.deepEqual(await (await fetch(`${origin}/health`)).json(), { ok: true });
    assert.match(await (await fetch(`${origin}/privacy`)).text(), /hello@tiyuvta.ai/);
    const deletion = await fetch(`${origin}/delete`);
    assert.equal(deletion.status, 200);
    assert.match(await deletion.text(), /random report ID/);
    assert.equal((await fetch(`${origin}/delete`, { method: 'POST' })).status, 404);
  });
  await t.test('production 10 per minute local edge binding returns 429', async () => {
    const statuses = [];
    for (let i = 0; i < 12; i++) statuses.push((await post({ id: randomUUID(), text: 'rate-test' }, { headers: { 'cf-connecting-ip': '198.19.1.1' } })).status);
    assert.equal(statuses.filter(s => s === 202).length, 10, JSON.stringify(statuses));
    assert.deepEqual(statuses.slice(10), [429, 429]);
  });
  await t.test('concurrent duplicate writes create exactly one row', async () => {
    const duplicate = { id: randomUUID(), text: 'same concurrent submission' };
    const responses = await Promise.all(Array.from({ length: 8 }, () => post(duplicate)));
    assert.equal(responses.filter(r => r.status === 202).length, 1);
    assert.equal(responses.filter(r => r.status === 200).length, 7);
  });
  await t.test('atomic capacity under concurrency and retry at full queue; admin deletion reopens slot', async () => {
    sql('DELETE FROM reports');
    sql(`WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<999) INSERT INTO reports(id,text) SELECT printf('00000000-0000-4000-8000-%012d',x),'synthetic seed' FROM n`);
    const candidates = Array.from({ length: 16 }, () => ({ id: randomUUID(), text: 'capacity race' }));
    const responses = await Promise.all(candidates.map(r => post(r)));
    assert.equal(responses.filter(r => r.status === 202).length, 1);
    assert.equal(responses.filter(r => r.status === 503).length, 15);
    assert.equal(sql('SELECT count(*) AS n FROM reports')[0].results[0].n, 1000);
    const winner = candidates[responses.findIndex(r => r.status === 202)];
    assert.equal((await post(winner)).status, 200);
    assert.equal((await post({ ...winner, text: 'changed' })).status, 409);
    sql(`DELETE FROM reports WHERE id='${winner.id}'`);
    assert.equal(sql(`SELECT count(*) AS n FROM reports WHERE id='${winner.id}'`)[0].results[0].n, 0);
    assert.equal((await post({ id: randomUUID(), text: 'slot reopened' })).status, 202);
  });
  await t.test('D1 error fails closed without content disclosure', async () => {
    sql('DROP TABLE reports');
    const response = await post({ id: randomUUID(), text: 'never-echo-sensitive-text' });
    assert.equal(response.status, 503);
    assert.deepEqual(await response.json(), { error: 'temporarily_unavailable' });
    assert.ok(!output.includes('never-echo-sensitive-text'));
  });
});
