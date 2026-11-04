import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readReport, rateKey, MAX_RAW_BYTES } from '../src/input.js';
const request = (body, headers = {}) => new Request('https://example.test/v1/reports', {
  method: 'POST', body, headers: { 'content-type': 'application/json', ...headers }, duplex: 'half',
});
test('exact text and ID are preserved', async () => {
  const report = { id: randomUUID(), text: '  שלום\n e\u0301 👾 \u0000 end ' };
  assert.deepEqual(await readReport(request(JSON.stringify(report))), report);
});
test('bounded stream cancels immediately on overflow without reading the rest', async () => {
  let cancelled = false;
  let pulls = 0;
  const stream = new ReadableStream({
    pull(c) { pulls++; c.enqueue(new Uint8Array(MAX_RAW_BYTES + 1)); },
    cancel() { cancelled = true; },
  }, { highWaterMark: 0 });
  await assert.rejects(readReport(request(stream)), e => e.status === 413);
  assert.equal(cancelled, true);
  assert.equal(pulls, 1);
});
test('invalid UTF-8 and unpaired surrogate rejected rather than altered', async () => {
  await assert.rejects(readReport(request(new Uint8Array([0xff]))), e => e.status === 400);
  await assert.rejects(readReport(request(JSON.stringify({ id: randomUUID(), text: '\ud800' }))), e => e.status === 400);
});
test('raw cap allows worst-case escaping of an 8192 byte body', async () => {
  const body = `{"id":"${randomUUID()}","text":"${'\\u0061'.repeat(8192)}"}`;
  assert.equal((await readReport(request(body))).text.length, 8192);
});
test('only the canonical lowercase UUIDv4 form is accepted, so deletion by ID is exact', async () => {
  const id = randomUUID();
  await assert.rejects(readReport(request(JSON.stringify({ id: id.toUpperCase(), text: 'x' }))), e => e.status === 400);
  assert.equal((await readReport(request(JSON.stringify({ id, text: 'x' })))).id, id);
});
test('rate limit key: IPv4 per address, IPv6 per /64 in every spelling', () => {
  assert.equal(rateKey('198.51.100.7'), 'v4:198.51.100.7');
  const k = rateKey('2001:db8:1:2::1');
  assert.equal(k, 'v6:2001:db8:1:2::/64');
  for (const ip of ['2001:db8:1:2:ffff:ffff:ffff:ffff', '2001:0DB8:0001:0002:0:0:0:9', '2001:db8:1:2:a:b:c:d']) assert.equal(rateKey(ip), k, ip);
  assert.notEqual(rateKey('2001:db8:1:3::1'), k);
  assert.equal(rateKey('::1'), 'v6:0:0:0:0::/64');
  assert.equal(rateKey('::ffff:198.51.100.7'), 'v4:198.51.100.7');
  assert.equal(rateKey('fe80::1%eth0'), 'v6:fe80:0:0:0::/64');
  for (const bad of ['', 'x', '1:2:3', '1::2::3', '300.1.1.1', '1.2.3']) assert.equal(rateKey(bad), null, bad);
});
