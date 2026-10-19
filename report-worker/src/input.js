export const MAX_RAW_BYTES = 64 * 1024;
export const MAX_TEXT_BYTES = 8192;
const uuidV4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
export class InputError extends Error {
  constructor(status, code) { super(code); this.status = status; }
}
export async function readReport(request) {
  if (!/^application\/json(?:\s*;\s*charset\s*=\s*"?utf-8"?)?\s*$/i.test(request.headers.get('content-type') || '')) {
    throw new InputError(415, 'json_required');
  }
  if (request.headers.has('content-encoding') && request.headers.get('content-encoding') !== 'identity') {
    throw new InputError(415, 'encoding_not_supported');
  }
  const length = request.headers.get('content-length');
  if (length && (!/^\d+$/.test(length) || Number(length) > MAX_RAW_BYTES)) {
    await request.body?.cancel();
    throw new InputError(413, 'body_too_large');
  }
  const reader = request.body?.getReader();
  const chunks = [];
  let total = 0;
  if (reader) {
    try {
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        total += value.byteLength;
        if (total > MAX_RAW_BYTES) {
          await reader.cancel();
          throw new InputError(413, 'body_too_large');
        }
        chunks.push(value);
      }
    } finally { reader.releaseLock(); }
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  let report;
  try { report = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)); }
  catch { throw new InputError(400, 'invalid_json'); }
  if (!report || Array.isArray(report) || typeof report !== 'object' ||
      Object.keys(report).length !== 2 || !Object.hasOwn(report, 'id') || !Object.hasOwn(report, 'text') ||
      typeof report.id !== 'string' || !uuidV4.test(report.id) || typeof report.text !== 'string' ||
      !report.text.trim() || !report.text.isWellFormed()) {
    throw new InputError(400, 'invalid_report');
  }
  if (new TextEncoder().encode(report.text).length > MAX_TEXT_BYTES) throw new InputError(413, 'text_too_large');
  // No trimming, case folding, or normalization: preserve the exact reviewed text and ID.
  return report;
}
