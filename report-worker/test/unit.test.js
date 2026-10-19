import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readReport, MAX_RAW_BYTES } from '../src/input.js';
const request = (body, headers = {}) => new Request('https://example.test/v1/reports', {
  method: 'POST', body, headers: { 'content-type': 'application/json', ...headers }, duplex: 'half',
});
test('exact text and ID are preserved', async () => {
  const report = { id: randomUUID().toUpperCase(), text: '  שלום\n e\u0301 👾 \u0000 end ' };
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
