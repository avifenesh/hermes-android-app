export const MAX_RAW_BYTES = 64 * 1024;
export const MAX_TEXT_BYTES = 8192;
// Canonical lowercase only (what the app generates): the stored ID is the exact key operators delete by.
const uuidV4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
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
  // No trimming or normalization of text: preserve the exact reviewed text and canonical ID.
  return report;
}

const v4 = /^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$/;
/** Rate-limit key: one budget per IPv4 address and per IPv6 /64, the smallest prefix a single host usually controls. */
export function rateKey(ip) {
  if (typeof ip !== 'string') return null;
  if (v4.test(ip)) return `v4:${ip}`;
  const bare = ip.split('%')[0].toLowerCase();
  const mapped = /^::ffff:(\d+\.\d+\.\d+\.\d+)$/.exec(bare);
  if (mapped) return v4.test(mapped[1]) ? `v4:${mapped[1]}` : null;
  if (!/^[0-9a-f:]+$/.test(bare) || bare.split('::').length > 2) return null;
  const [head, tail] = bare.includes('::') ? bare.split('::') : [bare, null];
  const left = head ? head.split(':') : [];
  const right = tail ? tail.split(':') : [];
  const groups = tail === null ? left : [...left, ...Array(8 - left.length - right.length).fill('0'), ...right];
  if (groups.length !== 8 || groups.some(g => !/^[0-9a-f]{1,4}$/.test(g))) return null;
  return `v6:${groups.slice(0, 4).map(g => parseInt(g, 16).toString(16)).join(':')}::/64`;
}
