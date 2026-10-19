import privacy from '../privacy.html';
import deletion from '../delete.html';
import stylesheet from '../style.css';
import { InputError, readReport } from './input.js';

const headers = {
  'content-type': 'application/json; charset=utf-8',
  'cache-control': 'no-store',
  'x-content-type-options': 'nosniff',
  'referrer-policy': 'no-referrer',
};
const json = (status, body, extra = {}) => new Response(JSON.stringify(body), { status, headers: { ...headers, ...extra } });

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === '/health' && request.method === 'GET') return json(200, { ok: true });
    if (url.pathname === '/style.css' && request.method === 'GET') return new Response(stylesheet, {
      headers: { ...headers, 'content-type': 'text/css; charset=utf-8' },
    });
    if (['/privacy', '/delete'].includes(url.pathname) && request.method === 'GET') return new Response(url.pathname === '/privacy' ? privacy : deletion, {
      headers: { ...headers, 'content-type': 'text/html; charset=utf-8',
        'content-security-policy': "default-src 'none'; style-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'" },
    });
    if (url.pathname !== '/v1/reports') return json(404, { error: 'not_found' });
    if (request.method !== 'POST') return json(405, { error: 'method_not_allowed' }, { allow: 'POST' });
    // Native-only surface, not browser CORS. Origin checks are NOT authentication.
    if (request.headers.has('origin')) return json(403, { error: 'web_origin_not_allowed' });
    if (url.search) return json(400, { error: 'query_not_allowed' });
    try {
      // Cloudflare supplies this header in production; never trust client-forwarded alternatives.
      const ip = request.headers.get('cf-connecting-ip');
      if (!ip || !env.REPORT_RATE_LIMITER) return json(503, { error: 'temporarily_unavailable' });
      const { success } = await env.REPORT_RATE_LIMITER.limit({ key: ip });
      if (!success) return json(429, { error: 'rate_limited' }, { 'retry-after': '60' });
      const { id, text } = await readReport(request);
      // A transactional batch pins both statements to the primary, even with D1 read replication.
      // The capacity check + insert is ONE SQLite statement, not a read-then-write race.
      const [insert, lookup] = await env.DB.batch([
        env.DB.prepare(`INSERT INTO reports (id, text)
          SELECT ?, ? WHERE (SELECT count(*) FROM reports) < 1000
          ON CONFLICT(id) DO NOTHING`).bind(id, text),
        env.DB.prepare('SELECT text FROM reports WHERE id = ?').bind(id),
      ]);
      if (!insert.success || !lookup.success) return json(503, { error: 'temporarily_unavailable' });
      const stored = lookup.results?.[0];
      if (!stored) return json(503, { error: 'queue_full' });
      if (stored.text !== text) return json(409, { error: 'id_conflict' });
      return json(insert.meta.changes === 1 ? 202 : 200, { id, received: true });
    } catch (error) {
      if (error instanceof InputError) return json(error.status, { error: error.message });
      // Never log errors: D1 exceptions can contain SQL parameter values/report contents.
      return json(503, { error: 'temporarily_unavailable' });
    }
  },
};
