"""Real loopback HTTP, production P-256 signing and SQLite. No live Hermes access."""
import ast
import asyncio
import base64
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import shutil
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from urllib.parse import urlsplit, parse_qs, quote

from aiohttp import ClientSession, web
from aiohttp.test_utils import make_mocked_request
from yarl import URL
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, utils

spec = importlib.util.spec_from_file_location('review_backend', Path(__file__).with_name('server.py'))
server = importlib.util.module_from_spec(spec)
spec.loader.exec_module(server)
REPO = Path(__file__).resolve().parents[1]

def kotlin_strings(src):
    """Every string template in Kotlin source; ${...} and $name become {} ({base} for the session screen's base)."""
    found, n = [], len(src)

    def skip_code(i, until_brace):
        depth = 0
        while i < n:
            if src.startswith('//', i):
                j = src.find('\n', i)
                i = n if j < 0 else j
                continue
            if src.startswith('/*', i):
                d, i = 1, i + 2
                while i < n and d:
                    if src.startswith('/*', i):
                        d, i = d + 1, i + 2
                    elif src.startswith('*/', i):
                        d, i = d - 1, i + 2
                    else:
                        i += 1
                continue
            c = src[i]
            if c == "'":
                m = re.match(r"'(\\u[0-9a-fA-F]{4}|\\.|[^'\\])'", src[i:i + 8])
                i += len(m.group(0)) if m else 1
                continue
            if c == '"':
                raw = src.startswith('"""', i)
                text, i = read_string(i + (3 if raw else 1), raw)
                found.append(text)
                continue
            if until_brace:
                if c == '{':
                    depth += 1
                elif c == '}':
                    if depth == 0:
                        return i
                    depth -= 1
            i += 1
        if until_brace:
            raise ValueError('unbalanced string template')
        return i

    def read_string(i, raw):
        buf = []
        while i < n:
            if raw and src.startswith('"""', i):
                j = i
                while j < n and src[j] == '"':
                    j += 1
                buf.append('"' * (j - i - 3))
                return ''.join(buf), j
            c = src[i]
            if not raw and c == '\\':
                buf.append(src[i:i + 2])
                i += 2
                continue
            if not raw and c == '"':
                return ''.join(buf), i + 1
            if src.startswith('${', i):
                j = skip_code(i + 2, True)
                buf.append('{base}' if src[i + 2:j].strip() == 'base' else '{}')
                i = j + 1
                continue
            m = re.match(r'\$([A-Za-z_]\w*)', src[i:i + 80])
            if m:
                buf.append('{base}' if m.group(1) == 'base' else '{}')
                i += len(m.group(0))
                continue
            buf.append(c)
            i += 1
        raise ValueError('unterminated string')

    skip_code(0, False)
    return found


# Every string template in the Play build's Kotlin sources (src/main, src/play) that names an API path, mapped to
# the calls made with it: (HTTP method, a concrete sample path). Session-screen calls are built on a `base`
# variable; the lexer shows it as {base}. A new or changed template fails the guard until it is listed here.
SESSION = '/v1/sessions/s-cli-release'
CLIENT_CALLS = {
    '/v1/activity/{}': [('GET', '/v1/activity/m-x')],
    '/v1/approvals/{}': [('POST', '/v1/approvals/a-x')],
    '/v1/chats': [('POST', '/v1/chats')],
    '/v1/chats/{}': [('PATCH', '/v1/chats/c-x')],
    '/v1/chats/{}/confirm': [('POST', '/v1/chats/c-x/confirm')],
    '/v1/chats/{}/messages': [('POST', '/v1/chats/c-x/messages')],
    '/v1/chats/{}/messages?limit=150': [('GET', '/v1/chats/c-x/messages?limit=150')],
    '/v1/chats/{}/model': [('GET', '/v1/chats/_new/model'), ('POST', '/v1/chats/c-x/model')],
    '/v1/chats/{}/read': [('POST', '/v1/chats/c-x/read')],
    '/v1/chats/{}/run': [('GET', '/v1/chats/c-x/run')],
    '/v1/chats/{}/session': [('POST', '/v1/chats/c-x/session')],
    '/v1/chats/{}/stop': [('POST', '/v1/chats/c-x/stop')],
    '/v1/chats/{}/usage': [('GET', '/v1/chats/c-x/usage')],
    '/v1/commands': [('GET', '/v1/commands')],
    '/v1/cron': [('GET', '/v1/cron')],
    '/v1/cron-delivery': [('GET', '/v1/cron-delivery'), ('POST', '/v1/cron-delivery')],
    '/v1/cron/{}/output': [('GET', '/v1/cron/j/output'), ('GET', '/v1/cron/j/output?run=r')],
    '/v1/cron/{}/pause': [('POST', '/v1/cron/j/pause')],
    '/v1/cron/{}/run': [('POST', '/v1/cron/j/run')],
    '/v1/cron/{}/{}': [('POST', '/v1/cron/j/pause'), ('POST', '/v1/cron/j/resume')],
    '/v1/cronjob': [('POST', '/v1/cronjob')],
    '/v1/cronjob/': [('GET', '/v1/cronjob/j'), ('PUT', '/v1/cronjob/j'), ('DELETE', '/v1/cronjob/j')],
    '/v1/devices': [('GET', '/v1/devices')],
    '/v1/devices/': [('DELETE', '/v1/devices/d-x')],
    '/v1/devices/{}': [('DELETE', '/v1/devices/d-x')],
    '/v1/events': [('GET', '/v1/events')],
    '/v1/fcm': [('POST', '/v1/fcm')],
    '/v1/fleet': [('GET', '/v1/fleet'), ('GET', '/v1/fleet?recent=1')],
    '/v1/fleet/{}/input': [('POST', '/v1/fleet/kitty%3Adocs/input')],
    '/v1/fleet/{}/send': [('POST', '/v1/fleet/kitty%3Adocs/send')],
    '/v1/fleet/{}/stop': [('POST', '/v1/fleet/tmux%3Abuild/stop')],
    '/v1/fleet/{}/tail?n={}': [('GET', '/v1/fleet/kitty%3Adocs/tail?n=200')],
    '/v1/health': [('GET', '/v1/health')],
    '/v1/hub/jobs/': [('GET', '/v1/hub/jobs/hj-x')],
    '/v1/hub/mcp': [('GET', '/v1/hub/mcp')],
    '/v1/hub/mcp/install': [('POST', '/v1/hub/mcp/install')],
    '/v1/hub/mcp/uninstall': [('POST', '/v1/hub/mcp/uninstall')],
    '/v1/hub/plugins': [('GET', '/v1/hub/plugins')],
    '/v1/hub/plugins/{}': [('POST', '/v1/hub/plugins/install'), ('POST', '/v1/hub/plugins/update'), ('POST', '/v1/hub/plugins/remove')],
    '/v1/hub/skills/browse': [('GET', '/v1/hub/skills/browse'), ('GET', '/v1/hub/skills/browse?profile=research')],
    '/v1/hub/skills/install': [('POST', '/v1/hub/skills/install')],
    '/v1/hub/skills/installed': [('GET', '/v1/hub/skills/installed')],
    '/v1/hub/skills/preview?identifier={}': [('GET', '/v1/hub/skills/preview?identifier=x')],
    '/v1/hub/skills/scan?identifier={}': [('GET', '/v1/hub/skills/scan?identifier=x')],
    '/v1/hub/skills/search?q={}&source={}&limit=50': [('GET', '/v1/hub/skills/search?q=x&source=all&limit=50')],
    '/v1/hub/skills/uninstall': [('POST', '/v1/hub/skills/uninstall')],
    '/v1/hub/skills/update': [('POST', '/v1/hub/skills/update')],
    '/v1/hub/skills/updates': [('GET', '/v1/hub/skills/updates')],
    '/v1/mcp': [('GET', '/v1/mcp')],
    '/v1/me': [('GET', '/v1/me')],
    '/v1/media/': [('GET', '/v1/media/u-0')],
    '/v1/memory': [('GET', '/v1/memory'), ('POST', '/v1/memory')],
    '/v1/models': [('GET', '/v1/models'), ('GET', '/v1/models?refresh=1')],
    '/v1/pair': [('POST', '/v1/pair')],
    '/v1/plugins': [('GET', '/v1/plugins')],
    '/v1/plugins/toggle': [('POST', '/v1/plugins/toggle')],
    '/v1/profiles': [('GET', '/v1/profiles')],
    '/v1/projects': [('GET', '/v1/projects')],
    '/v1/projects/{}': [('GET', '/v1/projects/r')],
    '/v1/projects/{}/diff?file={}': [('GET', '/v1/projects/r/diff?file=f')],
    '/v1/projects/{}/{}': [('POST', f'/v1/projects/r/{a}') for a in ('stage', 'unstage', 'commit', 'push', 'create-pr')],
    '/v1/pulse': [('GET', '/v1/pulse')],
    '/v1/push-config': [('GET', '/v1/push-config')],
    '/v1/radar': [('GET', '/v1/radar')],
    '/v1/radar/findings/{}': [('GET', '/v1/radar/findings/f')],
    '/v1/radar/findings/{}/spawn': [('POST', '/v1/radar/findings/f/spawn')],
    '/v1/radar/findings/{}/{}': [('POST', f'/v1/radar/findings/f/{a}') for a in
                                 ('like', 'dislike', 'clear-vote', 'save', 'unsave', 'dismiss', 'restore', 'feedback', 'note')],
    '/v1/radar/momentum': [('GET', '/v1/radar/momentum'), ('GET', '/v1/radar/momentum?refresh=1')],
    '/v1/radar/notes/{}': [('GET', '/v1/radar/notes/n')],
    '/v1/radar/notes/{}/ack': [('POST', '/v1/radar/notes/n/ack')],
    '/v1/radar?view={}': [('GET', '/v1/radar?view=current')],
    '/v1/restart': [('POST', '/v1/restart')],
    '/v1/search?q=': [('GET', '/v1/search?q=x')],
    '/v1/sessions': [('GET', '/v1/sessions')],
    '/v1/sessions/{}': [],  # only the base of the {base}/... calls below
    '/v1/sessions/{}/prompts/{}': [('PUT', SESSION + '/prompts/p')],
    '/v1/settings': [('GET', '/v1/settings'), ('PUT', '/v1/settings')],
    '/v1/settings/all': [('GET', '/v1/settings/all')],
    '/v1/skill?name=': [('GET', '/v1/skill?name=x')],
    '/v1/skills': [('GET', '/v1/skills')],
    '/v1/skills/toggle': [('POST', '/v1/skills/toggle')],
    '/v1/sync?since={}': [('GET', '/v1/sync?since=0')],
    '/v1/tts': [('POST', '/v1/tts')],
    '/v1/upload?name={}&mime={}': [('POST', '/v1/upload?name=a&mime=text%2Fplain')],
    '/v1/usage?days={}': [('GET', '/v1/usage?days=30')],
    '/v1/wake/cancel': [('POST', '/v1/wake/cancel')],
    '/v1/wake/config': [('PUT', '/v1/wake/config')],
    '/v1/wake/fired': [('POST', '/v1/wake/fired')],
    '/v1/wake/override': [('POST', '/v1/wake/override')],
    '/v1/wake/password': [('POST', '/v1/wake/password')],
    '/v1/wake/proof': [('POST', '/v1/wake/proof')],
    '/v1/wake/session': [('GET', '/v1/wake/session')],
    '/v1/wake/test': [('POST', '/v1/wake/test')],
    '/v1/wake/whatsapp-test': [('POST', '/v1/wake/whatsapp-test')],
    '/v1/wake?tz=': [('GET', '/v1/wake?tz=UTC')],
    '{base}/messages': [('PUT', SESSION + '/messages')],
    '{base}/messages?before={}&limit=100': [('GET', SESSION + '/messages?before=1&limit=100')],
    '{base}/messages{}': [('GET', SESSION + '/messages?limit=40&refresh=1')],
    '{base}/prompts/{}': [('PUT', SESSION + '/prompts/p')],
    '{base}/stop': [('POST', SESSION + '/stop')],
    '{base}/viewer': [('DELETE', SESSION + '/viewer')],
}


def pub(key):
    return base64.b64encode(key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()


def sign(key, msg):
    return base64.b64encode(key.sign(msg, ec.ECDSA(hashes.SHA256()))).decode()


def guard(kind, key, value):
    norm = ('true' if value else 'false') if isinstance(value, bool) else ('null' if value is None else str(value))
    return lambda ts: f'{kind}\n{key}\n{hashlib.sha256(norm.encode()).hexdigest()}\n{ts}'.encode()


class ReviewTest(unittest.IsolatedAsyncioTestCase):
    maxDiff = None  # coverage failures must list every unanswered path

    async def asyncSetUp(self):
        os.umask(0o077)
        server.BASE.mkdir(parents=True, exist_ok=True)
        self.home = Path(tempfile.mkdtemp(prefix='review-test-', dir=server.BASE))
        self.key, self.apr = ec.generate_private_key(ec.SECP256R1()), ec.generate_private_key(ec.SECP256R1())
        await self.start()
        self.http = ClientSession()
        self.dev = await self.enroll()

    async def start(self, **kw):
        self.backend = server.Backend(self.home, 'http://127.0.0.1:47941', **kw)
        # Fast synthetic timing for tests; the defaults pace the demo for people.
        self.backend.REPLY_STEP, self.backend.JOB_SECONDS = 0.01, 0
        self.runner = web.AppRunner(self.backend.app(), access_log=None, shutdown_timeout=1)
        await self.runner.setup()
        site = web.TCPSite(self.runner, '127.0.0.1', 0)
        await site.start()
        self.url = 'http://127.0.0.1:' + str(site._server.sockets[0].getsockname()[1])

    async def asyncTearDown(self):
        await self.http.close()
        await self.runner.cleanup()
        shutil.rmtree(self.home)

    async def raw(self, method, path, body=b'', headers=None):
        # encoded=True sends the path byte-for-byte, as OkHttp does, so the signed raw path is what arrives.
        async with self.http.request(method, URL(self.url + path, encoded=True), data=body, headers=headers or {}) as r:
            data = await r.read()
            try:
                data = json.loads(data)
            except (ValueError, UnicodeDecodeError):
                pass
            return r.status, data

    async def enroll(self, key=None, apr=None, code=None, lock=True):
        if code is None:
            status, d = await self.raw('POST', '/review/enroll', headers={'Authorization': 'Bearer ' + self.backend.invitation})
            self.assertEqual(status, 200)
            code = parse_qs(urlsplit(d['link']).query)['c'][0]
        status, d = await self.raw('POST', '/v1/pair', json.dumps({'code': code, 'pub': pub(key or self.key), 'apub': pub(apr or self.apr), 'lock': lock, 'device_name': 'Synthetic reviewer'}).encode(), {'Content-Type': 'application/json'})
        self.assertEqual(status, 200)
        return d

    def headers(self, method, path, body=b'', dev=None, key=None, approval=None, ts=None, amsg=None, apr=None):
        ts = str(ts if ts is not None else int(time.time()))
        h = {'Authorization': 'Bearer ' + (dev or self.dev)['token'], 'X-HM-Ts': ts,
             'X-HM-Sig': sign(key or self.key, server.SG.canonical(method, path, ts, body)), 'Content-Type': 'application/json'}
        if approval:
            aid, choice = approval
            amsg = lambda t: f'approve\n{aid}\n{choice}\n{t}'.encode()
        if amsg:
            h['X-HM-ASig'] = sign(apr or self.apr, amsg(ts))
        return h

    async def call(self, method, path, body=None, dev=None, key=None, approval=None, amsg=None, apr=None):
        data = json.dumps(body).encode() if body is not None else b''
        return await self.raw(method, path, data, self.headers(method, path, data, dev, key, approval, amsg=amsg, apr=apr))

    async def idle(self, dev=None):
        await self.backend.idle((dev or self.dev)['device_id'])

    def tenant(self, dev=None):
        return self.backend.tenant((dev or self.dev)['device_id'])

    async def other(self):
        key, apr = ec.generate_private_key(ec.SECP256R1()), ec.generate_private_key(ec.SECP256R1())
        return await self.enroll(key, apr), key, apr

    # ---------------------------------------------------------------- security model (unchanged contract)
    async def test_enrollment_guards_and_isolation(self):
        self.assertEqual((await self.raw('POST', '/review/enroll'))[0], 401)
        expired = self.backend.auth.create_pair_code(-1)
        valid = self.backend.auth.create_pair_code()
        for code, expected in [(expired, 403), (valid, 200), (valid, 403)]:
            status, _ = await self.raw('POST', '/v1/pair', json.dumps({'code': code, 'pub': pub(self.key), 'apub': pub(self.apr)}).encode(), {'Content-Type': 'application/json'})
            self.assertEqual(status, expected)
        status, _ = await self.raw('POST', '/v1/pair', json.dumps({'code': valid, 'pub': 'invalid', 'apub': pub(self.apr)}).encode(), {'Content-Type': 'application/json'})
        self.assertEqual(status, 400)
        same_key_device = await self.enroll()
        headers = self.headers('GET', '/v1/me')
        self.assertEqual((await self.raw('GET', '/v1/me', headers=headers))[0], 200)
        headers['Authorization'] = 'Bearer ' + same_key_device['token']
        self.assertEqual((await self.raw('GET', '/v1/me', headers=headers))[0], 401)
        otherkey = ec.generate_private_key(ec.SECP256R1())
        other = await self.enroll(otherkey)
        status, d = await self.call('POST', '/v1/chats', {'title': 'Tenant A only'})
        cid = d['chat']['id']
        self.assertEqual(status, 200)
        self.assertEqual((await self.call('GET', f'/v1/chats/{cid}/messages', dev=other, key=otherkey))[0], 404)
        _, d = await self.call('GET', '/v1/devices', dev=other, key=otherkey)
        self.assertEqual([x['id'] for x in d['devices']], [other['device_id']])
        self.assertEqual((await self.call('DELETE', '/v1/devices/' + self.dev['device_id'], dev=other, key=otherkey))[0], 404)
        _, d = await self.call('GET', '/v1/chats', dev=other, key=otherkey)
        self.assertNotIn(cid, [c['id'] for c in d['chats']])

    async def test_request_signature_replays_tamper_restart(self):
        p = '/v1/sync?since=0'
        h = self.headers('GET', p)
        self.assertEqual((await self.raw('GET', p, headers=h))[0], 200)
        self.assertEqual((await self.raw('GET', p, headers=h))[0], 401)
        r, s = utils.decode_dss_signature(base64.b64decode(h['X-HM-Sig']))
        h['X-HM-Sig'] = base64.b64encode(utils.encode_dss_signature(r, server.SG.P256_ORDER-s)).decode()
        self.assertEqual((await self.raw('GET', p, headers=h))[0], 401)
        self.backend.auth._db.close()
        self.backend.auth = server.S.Store(self.home / 'auth.db')
        self.assertEqual((await self.raw('GET', p, headers=h))[0], 401)
        for headers in [self.headers('GET', p, ts=int(time.time())-1000), self.headers('GET', p, key=self.apr)]:
            self.assertEqual((await self.raw('GET', p, headers=headers))[0], 401)
        self.assertEqual((await self.raw('GET', p + '&limit=1', headers=self.headers('GET', p)))[0], 401)
        h = self.headers('POST', '/v1/chats', b'{"title":"A"}')
        self.assertEqual((await self.raw('POST', '/v1/chats', b'{"title":"B"}', h))[0], 401)
        h = self.headers('GET', '/v1/me')
        h['X-HM-Sig'] += '='
        self.assertEqual((await self.raw('GET', '/v1/me', headers=h))[0], 401)
        results = await asyncio.gather(*(self.raw('GET', '/v1/me', headers=self.headers('GET', '/v1/me')) for _ in range(2)))
        self.assertEqual([x[0] for x in results], [200, 200])
        h = self.headers('GET', '/v1/me')
        results = await asyncio.gather(*(self.raw('GET', '/v1/me', headers=h) for _ in range(2)))
        self.assertEqual(sorted(x[0] for x in results), [200, 401])

    async def test_sse_working_flag_and_revocation(self):
        h = self.headers('GET', '/v1/events')
        async with self.http.get(self.url + '/v1/events', headers=h) as r:
            self.assertEqual(r.status, 200)
            line = await asyncio.wait_for(r.content.readline(), 2)
            self.assertIn(b'"type": "hello"', line)
            self.backend.REPLY_STEP = 0.3
            await self.call('POST', '/v1/chats/c-planning/messages', {'text': 'Stream please', 'mode': 'send'})
            seen = False
            for _ in range(20):
                line = await asyncio.wait_for(r.content.readline(), 2)
                if b'c-planning' in line and b'"working"' in line:
                    seen = True
                    break
            self.assertTrue(seen, 'SSE must announce the working chat like the real adapter')
            self.backend.REPLY_STEP = 0.01
            await self.idle()
            status, _ = await self.call('DELETE', '/v1/devices/' + self.dev['device_id'])
            self.assertEqual(status, 200)
            await asyncio.wait_for(r.read(), 2)
        self.assertEqual((await self.call('GET', '/v1/me'))[0], 401)

    async def test_storage_failure_closed_and_full_restart(self):

        p = '/v1/me'
        headers = self.headers('GET', p)
        self.assertEqual((await self.raw('GET', p, headers=headers))[0], 200)
        await self.call('POST', '/v1/memory', {'target': 'memory', 'action': 'add', 'content': 'Persists across restart'})
        await self.call('PUT', '/v1/settings', {'key': 'agent.max_turns', 'value': '42'})
        with patch.object(self.backend.auth, 'claim_signature', side_effect=server.sqlite3.OperationalError('synthetic failure')):
            self.assertEqual((await self.call('POST', '/v1/chats', {'title': 'Must never create'}))[0], 503)
        _, d = await self.call('GET', '/v1/chats')
        self.assertNotIn('Must never create', [c['title'] for c in d['chats']])
        await self.runner.cleanup()
        await self.start()
        self.assertEqual((await self.raw('GET', p, headers=headers))[0], 401)
        _, d = await self.call('GET', '/v1/memory')
        self.assertIn('Persists across restart', d['memory']['entries'])
        _, d = await self.call('GET', '/v1/settings')
        self.assertEqual(next(f for g in d['groups'] for f in g['fields'] if f['key'] == 'agent.max_turns')['value'], 42)
        self.assertEqual(len((await self.call('GET', '/v1/chats'))[1]['chats']), 3)

    async def test_explicit_limits_and_no_auth_bypass(self):
        self.assertEqual((await self.raw('GET', '/v1/sync'))[0], 401)
        # Not part of the Play build: phone control, self-check and unknown paths stay explicit 501s.
        for p in ['/v1/phone/arm', '/v1/phone/result', '/v1/selfcheck', '/v1/cronjobs', '/v1/hub/skills/force-install']:
            status, d = await self.call('POST', p, {'command': 'id'})
            self.assertEqual(status, 501, p)
            self.assertIn('Unsupported', d['error'])
        _, d = await self.call('GET', '/v1/review/capabilities')
        self.assertNotIn('submission_ready', d)  # this server cannot know whether a submission is ready
        self.assertTrue(d['surfaces_complete'])
        self.assertEqual(d['unsupported'], [])
        self.assertIn('POST /v1/wake/proof', d['surfaces'])
        for _ in range(41):
            status, _ = await self.raw('POST', '/review/enroll')
        self.assertEqual(status, 429)
        with self.assertRaises(ValueError):
            server.state_root(Path.home() / '.hermes/mobile')

    async def test_one_client_cannot_lock_out_other_reviewers(self):
        """A flood from one proxied client hits its own limit; another reviewer still reaches enrollment."""
        await self.runner.cleanup()
        await self.start(client_header='X-Forwarded-For')
        flood = {'X-Forwarded-For': '198.51.100.9'}
        statuses = [(await self.raw('POST', '/review/enroll', headers=flood))[0] for _ in range(60)]
        self.assertIn(429, statuses)
        self.assertEqual((await self.raw('POST', '/review/enroll', headers={'X-Forwarded-For': '2001:db8:5:6::1'}))[0], 401)
        # Addresses inside one IPv6 /64 share a budget, so rotating them does not escape the limit.
        v6 = [(await self.raw('POST', '/review/enroll', headers={'X-Forwarded-For': f'2001:db8:9:9::{i:x}'}))[0] for i in range(1, 60)]
        self.assertIn(429, v6)
        # The proxy's own appended hop is the one trusted: a client-forged first entry is ignored.
        forged = {'X-Forwarded-For': '203.0.113.50, 198.51.100.9'}
        self.assertEqual((await self.raw('POST', '/review/enroll', headers=forged))[0], 429)

    # ---------------------------------------------------------------- chat
    async def test_chat_streaming_reply_steer_stop_and_inertness(self):
        _, d = await self.call('GET', '/v1/sync?since=0')
        self.assertEqual(len(d['chats']), 3)
        self.assertGreaterEqual(len(d['messages']), 8)
        seq = d['seq']
        status, d = await self.call('POST', '/v1/chats', {'title': 'New example', 'profile': 'default'})
        self.assertEqual(status, 200)
        cid = d['chat']['id']
        self.assertEqual((await self.call('POST', '/v1/chats', {'title': 'x', 'profile': 'nope'}))[0], 400)
        for key, value in [('title', 'Renamed'), ('pinned', 1), ('archived', 1)]:
            self.assertEqual((await self.call('PATCH', '/v1/chats/' + cid, {key: value}))[0], 200)
            _, d = await self.call('GET', '/v1/chats?archived=1')
            self.assertEqual(next(c for c in d['chats'] if c['id'] == cid)[key], value)
        marker = self.home / 'SHOULD_NOT_EXIST'
        text = '/terminal touch ' + str(marker)
        self.backend.REPLY_STEP = 0.2
        _, sent = await self.call('POST', f'/v1/chats/{cid}/messages', {'text': text, 'mode': 'send'})
        _, run = await self.call('GET', f'/v1/chats/{cid}/run')
        self.assertTrue(run['working'])
        _, d = await self.call('GET', '/v1/sync?since=0')
        self.assertIn(cid, [w['chat_id'] for w in d['working']])
        self.backend.REPLY_STEP = 0.01
        await self.idle()
        _, d = await self.call('GET', f'/v1/chats/{cid}/messages?limit=150')
        self.assertEqual(d['messages'][0]['content'], text)
        self.assertIn('no `/terminal` command', d['messages'][1]['content'])
        self.assertFalse(marker.exists())
        _, d = await self.call('GET', f'/v1/sync?since={seq}')
        self.assertIn(sent['message']['id'], [m['id'] for m in d['messages']])
        # A plain message gets a scripted, streamed reply with usage the footer and Run screen read.
        await self.call('POST', f'/v1/chats/{cid}/messages', {'text': 'Make a table please', 'mode': 'send'})
        await self.idle()
        _, d = await self.call('GET', f'/v1/chats/{cid}/messages?limit=150')
        reply = d['messages'][-1]
        self.assertIn('Synthetic demo reply', reply['content'])
        self.assertIn('| Item |', reply['content'])
        self.assertEqual(reply['meta']['usage']['model'], 'synthetic-demo')
        _, run = await self.call('GET', f'/v1/chats/{cid}/run')
        self.assertFalse(run['working'])
        self.assertGreaterEqual(len(run['steps']), 2)
        self.assertEqual({'tool', 'what', 'ts'} - set(run['steps'][0]), set())
        _, use = await self.call('GET', f'/v1/chats/{cid}/usage')
        self.assertGreater(use['session']['output_tokens'], 0)
        self.assertEqual(use['turn']['model'], 'synthetic-demo')
        # Steer while running folds into the reply; stop cancels with a status line and clears working.
        self.backend.REPLY_STEP = 0.2
        await self.call('POST', f'/v1/chats/{cid}/messages', {'text': 'Long plan', 'mode': 'send'})
        _, st = await self.call('POST', f'/v1/chats/{cid}/messages', {'text': 'focus on tests', 'mode': 'steer'})
        self.assertEqual(st['message']['kind'], 'steer')
        await self.idle()
        _, d = await self.call('GET', f'/v1/chats/{cid}/messages?limit=150')
        self.assertIn('Steer received', d['messages'][-1]['content'])
        await self.call('POST', f'/v1/chats/{cid}/messages', {'text': 'Another long one', 'mode': 'send'})
        _, q = await self.call('POST', f'/v1/chats/{cid}/messages', {'text': 'and then this', 'mode': 'queue'})
        self.assertEqual(q['message']['kind'], 'queued')
        self.assertEqual((await self.call('POST', f'/v1/chats/{cid}/stop'))[0], 200)
        await self.idle()
        _, d = await self.call('GET', f'/v1/chats/{cid}/messages?limit=150')
        self.assertIn('Stopped', d['messages'][-1]['content'])
        self.assertNotIn('and then this', d['messages'][-1]['content'])  # stop also drops the queued reply
        # A flood queues at most three scripted replies per chat.
        for i in range(6):
            await self.call('POST', f'/v1/chats/{cid}/messages', {'text': f'flood {i}', 'mode': 'queue'})
        self.assertLessEqual(len(self.backend.runtime(self.dev['device_id'])['all'].get(cid, ())), 3)
        await self.idle()
        self.assertEqual(self.backend.runtime(self.dev['device_id'])['depth'][cid], 0)  # no leaked queue slots
        self.assertFalse((await self.call('GET', f'/v1/chats/{cid}/run'))[1]['working'])
        self.backend.REPLY_STEP = 0.01
        self.assertEqual((await self.call('POST', f'/v1/chats/{cid}/read'))[0], 200)
        # Attachments and slash commands from the catalogue get explained, scripted answers.
        await self.call('POST', f'/v1/chats/{cid}/messages', {'text': '', 'attachments': [{'id': 'u-1', 'kind': 'voice', 'size': 2048}]})
        await self.call('POST', f'/v1/chats/{cid}/messages', {'text': '/help'})
        await self.idle()
        _, d = await self.call('GET', f'/v1/chats/{cid}/messages?limit=150')
        texts = [m['content'] for m in d['messages'] if m['role'] == 'agent']
        self.assertTrue(any('does not transcribe audio' in t for t in texts))
        self.assertTrue(any('/retry' in t for t in texts))
        _, d = await self.call('GET', '/v1/commands')
        self.assertIn('help', [c['name'] for c in d['commands']])
        self.assertEqual(d['features'], server.FEATURES)

    async def test_models_confirm_and_session_actions(self):
        _, m = await self.call('GET', '/v1/models')
        self.assertTrue(all({'slug', 'name', 'models', 'total_models', 'capabilities'} <= set(p) for p in m['providers']))
        self.assertEqual(m['default'], {'model': 'synthetic-demo', 'provider': 'sandbox'})
        _, st = await self.call('GET', '/v1/chats/_new/model')
        self.assertEqual((st['model'], st['scope']), ('synthetic-demo', 'default'))
        _, o = await self.call('POST', '/v1/chats/c-planning/model', {'model': 'example-mini', 'provider': 'example'})
        self.assertTrue(o['ok'])
        self.assertEqual((o['state']['model'], o['state']['scope']), ('example-mini', 'chat'))
        self.assertEqual((await self.call('GET', '/v1/chats/c-planning/model'))[1]['model'], 'example-mini')
        self.assertEqual((await self.call('GET', '/v1/chats/c-welcome/model'))[1]['model'], 'synthetic-demo')
        _, o = await self.call('POST', '/v1/chats/c-planning/model', {'reasoning': 'high'})
        self.assertEqual(o['state']['reasoning'], 'high')
        _, o = await self.call('POST', '/v1/chats/c-planning/model', {'fast': True, 'scope': 'chat'})
        self.assertFalse(o['ok'])  # example-mini has no fast mode: an explained refusal, not a fake switch.
        _, o = await self.call('POST', '/v1/chats/c-welcome/model', {'model': 'synthetic-large', 'provider': 'sandbox'})
        self.assertIsNotNone(o['confirm'])
        _, c = await self.call('POST', '/v1/chats/c-welcome/confirm', {'id': o['confirm']['id'], 'choice': 'once'})
        self.assertTrue(c['ok'])
        self.assertEqual(c['state']['model'], 'synthetic-large')
        self.assertEqual((await self.call('POST', '/v1/chats/c-welcome/confirm', {'id': o['confirm']['id'], 'choice': 'once'}))[0], 410)
        _, o = await self.call('POST', '/v1/chats/c-welcome/model', {'model': 'synthetic-fast', 'provider': 'sandbox', 'scope': 'global'})
        self.assertTrue(o['ok'])
        self.assertEqual((await self.call('GET', '/v1/me'))[1]['model'], 'synthetic-fast')
        for action in ('status', 'context'):
            _, o = await self.call('POST', '/v1/chats/c-welcome/session', {'action': action, 'arg': '', 'confirmed': False})
            self.assertIn('synthetic', o['reply'].lower())
        _, o = await self.call('POST', '/v1/chats/c-welcome/session', {'action': 'title', 'arg': 'Retitled', 'confirmed': False})
        self.assertEqual((await self.call('GET', '/v1/chats'))[1]['chats'][0]['title'], 'Retitled')
        _, o = await self.call('POST', '/v1/chats/c-welcome/session', {'action': 'save', 'arg': '', 'confirmed': False})
        self.assertTrue(o['ok'])
        _, d = await self.call('GET', '/v1/chats/c-welcome/messages?limit=150')
        export = [m for m in d['messages'] if m['kind'] == 'file'][-1]
        status, body = await self.call('GET', '/v1/media/' + export['meta']['media'])
        self.assertEqual(status, 200)
        self.assertIn(b'Retitled', body if isinstance(body, bytes) else json.dumps(body).encode())
        _, o = await self.call('POST', '/v1/chats/c-planning/session', {'action': 'undo', 'arg': '', 'confirmed': True})
        self.assertEqual(o['text'], 'Draft a release checklist.')
        _, d = await self.call('GET', '/v1/chats/c-planning/messages?limit=150')
        self.assertTrue(d['messages'][0]['meta'].get('undone'))
        for action in ('new', 'compress'):
            _, o = await self.call('POST', '/v1/chats/c-planning/session', {'action': action, 'arg': '', 'confirmed': True})
            self.assertTrue(o['ok'])
        _, d = await self.call('GET', '/v1/chats/c-planning/messages?limit=150')
        self.assertEqual([m['meta'].get('session_event') for m in d['messages'] if m['kind'] == 'status'], ['undo', 'new', 'compress'])
        self.backend.REPLY_STEP = 0.3
        await self.call('POST', '/v1/chats/c-planning/messages', {'text': 'busy'})
        self.assertEqual((await self.call('POST', '/v1/chats/c-planning/session', {'action': 'new', 'confirmed': True}))[0], 409)
        _, o = await self.call('POST', '/v1/chats/c-planning/model', {'model': 'example-pro', 'provider': 'example'})
        self.assertIn('running', o['error'])
        self.backend.REPLY_STEP = 0.01
        await self.idle()
        other, okey, _ = await self.other()
        self.assertEqual((await self.call('GET', '/v1/chats/c-planning/model', dev=other, key=okey))[1]['model'], 'synthetic-demo')

    async def test_biometric_approval_proof(self):
        _, d = await self.call('GET', '/v1/sync')
        aid = d['approvals'][0]['id']
        self.assertIn('approval', [m['kind'] for m in d['messages'] if m['chat_id'] == 'c-welcome'])
        p = '/v1/approvals/' + aid
        self.assertEqual((await self.call('POST', p, {'choice': 'once'}))[0], 403)
        self.assertEqual((await self.call('POST', p, {'choice': 'once'}, approval=(aid, 'deny')))[0], 403)
        status, d = await self.call('POST', p, {'choice': 'once'}, approval=(aid, 'once'))
        self.assertEqual(status, 200)
        self.assertEqual(d['approval']['state'], 'approved')
        _, d = await self.call('GET', '/v1/sync')
        self.assertEqual(next(a for a in d['approvals'] if a['id'] == aid)['state'], 'approved')
        status, d = await self.call('POST', p, {'choice': 'once'}, approval=(aid, 'once'))
        self.assertEqual((status, d['resolved'], d['approval']['state']), (200, 0, 'approved'))
        # The app denies without a fingerprint, as production allows; a new card comes from a message.
        await self.call('POST', '/v1/chats/c-welcome/messages', {'text': 'I need an approval'})
        await self.idle()
        _, d = await self.call('GET', '/v1/sync')
        new = next(a for a in d['approvals'] if a['state'] == 'pending')
        status, d = await self.call('POST', '/v1/approvals/' + new['id'], {'choice': 'deny'})
        self.assertEqual((status, d['approval']['state']), (200, 'denied'))
        # A phone without a screen lock can approve once only.
        key, apr = ec.generate_private_key(ec.SECP256R1()), ec.generate_private_key(ec.SECP256R1())
        nolock = await self.enroll(key, apr, lock=False)
        _, d = await self.call('GET', '/v1/sync', dev=nolock, key=key)
        aid2 = d['approvals'][0]['id']
        status, _ = await self.call('POST', '/v1/approvals/' + aid2, {'choice': 'always'}, dev=nolock, key=key, approval=(aid2, 'always'), apr=apr)
        self.assertEqual(status, 403)

    async def test_activity_card_detail(self):
        _, d = await self.call('GET', '/v1/chats/c-welcome/messages?limit=150')
        card = next(m for m in d['messages'] if m['kind'] == 'activity')
        _, a = await self.call('GET', '/v1/activity/' + card['id'])
        self.assertEqual([c['tool'] for c in a['calls']], ['read_file', 'patch'])
        self.assertEqual(a['files'][0]['path'], 'notes/checklist.md')
        self.assertIn('@@', a['files'][0]['diff'])
        self.assertEqual((await self.call('GET', '/v1/activity/' + d['messages'][0]['id']))[0], 404)

    # ---------------------------------------------------------------- files and voice
    async def test_upload_download_tenant_quota_and_path_boundaries(self):
        p, data = '/v1/upload?name=example.txt&mime=text/plain', b'Synthetic upload only'
        status, d = await self.raw('POST', p, data, self.headers('POST', p, data))
        self.assertEqual((status, d['mime'], d['file_name']), (200, 'text/plain', 'example.txt'))
        name = d['id']
        self.assertEqual((await self.call('GET', '/v1/media/' + name))[1], data)
        other, key, _ = await self.other()
        self.assertEqual((await self.call('GET', '/v1/media/' + name, dev=other, key=key))[0], 404)
        self.assertEqual((await self.call('GET', '/v1/media/auth.db'))[0], 404)
        self.assertEqual((await self.call('GET', '/v1/media/checklist.txt'))[1], self.backend.sample_file())
        # A camera photo (a few MB) uploads; the request cap and an aggregate per-enrollment quota still hold.
        photo = os.urandom(3 * 1024 * 1024)
        self.assertEqual((await self.raw('POST', p, photo, self.headers('POST', p, photo)))[0], 200)
        big = b'x' * (server.MAX_UPLOAD + 1)
        self.assertEqual((await self.raw('POST', p, big, self.headers('POST', p, big)))[0], 413)
        js = json.dumps({'title': 'x' * (server.MAX_BODY + 10)}).encode()
        self.assertEqual((await self.raw('POST', '/v1/chats', js, self.headers('POST', '/v1/chats', js)))[0], 413)
        chunk = b'y' * (11 * 1024 * 1024)
        statuses = [(await self.raw('POST', p, chunk, self.headers('POST', p, chunk)))[0] for _ in range(6)]
        self.assertEqual(statuses[:5], [200] * 5)
        self.assertEqual(statuses[5], 413)

    async def test_server_voice_returns_synthetic_clip(self):
        self.assertEqual((await self.call('POST', '/v1/tts', {'text': ''}))[0], 400)
        _, d = await self.call('POST', '/v1/tts', {'text': 'Good morning'})
        self.assertEqual(len(d['ids']), 1)
        status, clip = await self.call('GET', '/v1/media/' + d['ids'][0])
        self.assertEqual(status, 200)
        self.assertEqual((clip[:4], clip[8:12]), (b'RIFF', b'WAVE'))
        self.assertGreater(len(clip), 10000)
        other, key, _ = await self.other()
        self.assertEqual((await self.call('GET', '/v1/media/' + d['ids'][0], dev=other, key=key))[0], 404)

    # ---------------------------------------------------------------- other sessions, pulse, usage, search
    async def test_elsewhere_sessions_send_prompts_and_stop(self):
        _, d = await self.call('GET', '/v1/sessions')
        self.assertEqual({x['source'] for x in d['sessions']}, {'cli', 'telegram'})
        sid = 's-cli-release'
        status, d = await self.call('GET', f'/v1/sessions/{sid}/messages?limit=40&refresh=1')
        self.assertEqual(status, 200)
        rows = d['messages']
        self.assertEqual([m['position'] for m in rows], list(range(len(rows))))
        self.assertEqual(len({m['id'] for m in rows}), len(rows))
        self.assertFalse(d['readOnly'])
        prompt = d['prompts'][0]
        self.assertEqual(prompt['state'], 'pending')
        _, page = await self.call('GET', f'/v1/sessions/{sid}/messages?limit=2')
        self.assertEqual(page['nextBefore'], 1)
        _, older = await self.call('GET', f"/v1/sessions/{sid}/messages?before={page['nextBefore']}&limit=100")
        self.assertEqual([m['position'] for m in older['messages']], [0])
        _, r = await self.call('PUT', f'/v1/sessions/{sid}/messages', {'text': 'reviewer draft', 'submission_id': 'sub-1'})
        self.assertTrue(r['ok'])
        self.assertTrue((await self.call('PUT', f'/v1/sessions/{sid}/messages', {'text': 'reviewer draft', 'submission_id': 'sub-1'}))[1]['duplicate'])
        _, d = await self.call('GET', f'/v1/sessions/{sid}/messages?limit=40&refresh=1')
        self.assertTrue(d['running'])
        await self.idle()
        _, d = await self.call('GET', f'/v1/sessions/{sid}/messages?limit=40&refresh=1')
        self.assertEqual([m['content'] for m in d['messages']].count('reviewer draft'), 1)
        self.assertIn('Synthetic cli session reply', d['messages'][-1]['content'])
        body = {'kind': 'clarify', 'generation': prompt['generation'], 'answer': 'Both', 'question_id': ''}
        self.assertEqual((await self.call('PUT', f"/v1/sessions/{sid}/prompts/{prompt['prompt_id']}", body))[1], {'ok': True})
        _, d = await self.call('GET', f'/v1/sessions/{sid}/messages?limit=40')
        self.assertEqual(d['prompts'][0]['state'], 'answered')
        sid2 = 's-telegram-cleanup'
        _, d = await self.call('GET', f'/v1/sessions/{sid2}/messages?limit=40')
        ap = d['prompts'][0]
        self.assertIn('command', ap)
        path = f"/v1/sessions/{sid2}/prompts/{ap['prompt_id']}"
        body = {'kind': 'approval', 'choice': 'once', 'generation': ap['generation']}
        self.assertEqual((await self.call('PUT', path, body))[0], 403)
        amsg = lambda ts: f"approve\n{sid2}:{ap['prompt_id']}\nonce\n{ts}".encode()
        self.assertEqual((await self.call('PUT', path, body, amsg=amsg))[0], 200)
        self.assertEqual((await self.call('POST', f'/v1/sessions/{sid}/stop', {'generation': 1}))[0], 200)
        self.assertEqual((await self.call('DELETE', f'/v1/sessions/{sid}/viewer'))[0], 200)
        self.assertEqual((await self.call('PUT', '/v1/sessions/s-cli-old/messages', {'text': 'x'}))[0], 409)
        self.assertTrue((await self.call('GET', '/v1/sessions/s-cli-old/messages?limit=40'))[1]['readOnly'])
        self.assertEqual((await self.call('GET', '/v1/sessions/nope/messages'))[0], 404)
        other, key, _ = await self.other()
        _, d = await self.call('GET', f'/v1/sessions/{sid}/messages?limit=40', dev=other, key=key)
        self.assertNotIn('reviewer draft', [m['content'] for m in d['messages']])

    async def test_pulse_usage_and_search(self):
        _, p = await self.call('GET', '/v1/pulse')
        self.assertEqual(len(p['spend_7d']), 7)
        self.assertEqual([x['source'] for x in p['live_sessions']], ['cli'])
        self.assertEqual(len(p['approvals']), 1)
        for days in (7, 30, 90):
            _, u = await self.call('GET', f'/v1/usage?days={days}')
            self.assertEqual(len(u['series']), days)
            self.assertAlmostEqual(u['total'], round(sum(server.C.SPEND_7D), 4))
            self.assertTrue(u['models'] and u['sources'])
        _, s = await self.call('GET', '/v1/search?q=synthetic')
        self.assertTrue(any(r['chat_id'] for r in s['results']) and any(r['chat_id'] is None for r in s['results']))
        self.assertTrue(all('>>>' in r['snippet'] for r in s['results']))
        _, s = await self.call('GET', '/v1/search?q=' + quote('release NOT checklist'))
        self.assertTrue(s['results'])
        self.assertTrue(all('checklist' not in r['snippet'].lower() for r in s['results']))
        _, s = await self.call('GET', '/v1/search?q=' + quote('"release notes" OR archive'))
        self.assertTrue(any('archive' in r['snippet'].lower() for r in s['results']))
        self.assertEqual((await self.call('GET', '/v1/search?q=x'))[1]['results'], [])

    # ---------------------------------------------------------------- memory and cron
    async def test_memory_edits_and_limits(self):
        for action, content, old in [('add', 'Synthetic note', ''), ('replace', 'Updated note', 'Synthetic note'), ('remove', '', 'Updated note')]:
            status, d = await self.call('POST', '/v1/memory', {'target': 'memory', 'action': action, 'content': content, 'old_text': old})
            self.assertEqual(status, 200)
            self.assertTrue(d['message'])
            _, d = await self.call('GET', '/v1/memory')
            self.assertEqual(content in d['memory']['entries'], action != 'remove')
        self.assertEqual((await self.call('POST', '/v1/memory', {'target': 'memory', 'action': 'replace', 'content': 'x', 'old_text': 'missing'}))[0], 409)
        status, d = await self.call('POST', '/v1/memory', {'target': 'user', 'action': 'add', 'content': 'y' * 4000})
        self.assertEqual(status, 200)
        status, d = await self.call('POST', '/v1/memory', {'target': 'user', 'action': 'add', 'content': 'z' * 4000})
        self.assertEqual((status, d['success']), (400, False))
        self.assertIn('full', d['error'])
        _, d = await self.call('GET', '/v1/memory')
        await self.call('PUT', '/v1/settings', {'key': 'memory.user_char_limit', 'value': '10'})
        status, d2 = await self.call('POST', '/v1/memory', {'target': 'user', 'action': 'remove', 'content': '', 'old_text': 'y' * 4000})
        self.assertEqual((status, d2['success']), (200, True))
        status, d2 = await self.call('POST', '/v1/memory', {'target': 'user', 'action': 'add', 'content': 'z' * 200})
        self.assertEqual(status, 400)  # growth past the lowered limit is still refused
        await self.call('PUT', '/v1/settings', {'key': 'memory.memory_enabled', 'value': False})
        _, d = await self.call('GET', '/v1/memory')
        self.assertFalse(d['memory']['enabled'])

    async def test_cron_jobs_lifecycle_and_delivery(self):
        _, d = await self.call('GET', '/v1/cron')
        job = next(j for j in d['jobs'] if j['id'] == 'demo-digest')
        self.assertTrue(job['next_run_at'] > time.time())
        self.assertEqual(job['schedule_display'], 'Every day at 09:00')
        for action, enabled in [('pause', False), ('resume', True)]:
            self.assertEqual((await self.call('POST', '/v1/cron/demo-digest/' + action))[0], 200)
            self.assertEqual(next(j for j in (await self.call('GET', '/v1/cron'))[1]['jobs'] if j['id'] == 'demo-digest')['enabled'], enabled)
        _, before = await self.call('GET', '/v1/chats/cron-job-demo-digest/messages')
        _, r = await self.call('POST', '/v1/cron/demo-digest/run')
        self.assertEqual(r['job']['last_status'], 'ok')
        _, after = await self.call('GET', '/v1/chats/cron-job-demo-digest/messages')
        self.assertEqual(len(after['messages']), len(before['messages']) + 1)
        _, d = await self.call('GET', '/v1/cron')
        runs = next(j for j in d['jobs'] if j['id'] == 'demo-digest')['runs']
        self.assertGreaterEqual(len(runs), 2)
        _, out = await self.call('GET', '/v1/cron/demo-digest/output?run=' + runs[-1])
        self.assertEqual(out['run'], runs[-1])
        self.assertIn('Synthetic', out['text'])
        status, d = await self.call('POST', '/v1/cronjob', {'name': 'Bad', 'schedule': 'whenever', 'prompt': 'x', 'deliver': 'mobile', 'skills': []})
        self.assertEqual((status, d['success']), (400, False))
        self.assertIn("Couldn't read", d['error'])
        self.assertFalse((await self.call('POST', '/v1/cronjob', {'schedule': 'every 1h', 'prompt': '', 'skills': []}))[1]['success'])
        body = {'name': 'Synthetic soon', 'schedule': 'in 1m', 'prompt': 'Say hi', 'deliver': 'mobile', 'skills': ['meeting-notes'],
                'model': '', 'provider': '', 'reasoning_effort': 'low'}
        _, d = await self.call('POST', '/v1/cronjob', body)
        self.assertTrue(d['success'])
        jid = d['job_id']
        _, j = await self.call('GET', '/v1/cronjob/' + jid)
        self.assertEqual((j['name'], j['schedule_expr'], j['skills'], j['reasoning_effort']), ('Synthetic soon', 'in 1m', ['meeting-notes'], 'low'))
        _, d = await self.call('PUT', '/v1/cronjob/' + jid, {**body, 'name': 'Renamed', 'schedule': 'weekdays at 7:15am'})
        self.assertTrue(d['success'])
        _, j = await self.call('GET', '/v1/cronjob/' + jid)
        self.assertEqual((j['name'], j['schedule_display']), ('Renamed', 'Weekdays at 07:15'))
        # A one-shot job that came due produces its synthetic output the next time the list loads.
        s = self.tenant()
        jobs = s.kv_get('jobs')
        for x in jobs:
            if x['id'] == jid:
                x.update(schedule_expr='in 1m', created_at=time.time() - 120)
        s.kv_put('jobs', jobs)
        _, d = await self.call('GET', '/v1/cron')
        done = next(x for x in d['jobs'] if x['id'] == jid)
        self.assertEqual((done['enabled'], len(done['runs'])), (False, 1))
        self.assertEqual((await self.call('GET', f'/v1/chats/cron-job-{jid}/messages'))[0], 200)
        _, summary = await self.call('GET', '/v1/cron-delivery')
        self.assertEqual(summary['counts'].get('telegram,mobile'), 1)
        _, moved = await self.call('POST', '/v1/cron-delivery', {'deliver': 'mobile'})
        self.assertEqual(moved['moved'], ['Synthetic weekly review'])
        self.assertEqual(set(moved['counts']), {'mobile'})
        self.assertTrue((await self.call('DELETE', '/v1/cronjob/' + jid))[1]['success'])
        self.assertEqual((await self.call('GET', '/v1/cronjob/' + jid))[0], 404)
        other, key, _ = await self.other()
        self.assertEqual([j['id'] for j in (await self.call('GET', '/v1/cron', dev=other, key=key))[1]['jobs']], ['demo-digest', 'demo-weekly'])
        self.assertEqual(next(j for j in (await self.call('GET', '/v1/cron', dev=other, key=key))[1]['jobs'] if j['id'] == 'demo-weekly')['deliver'], 'telegram,mobile')

    # ---------------------------------------------------------------- settings, skills, plugins, MCP, restart
    async def test_settings_reads_writes_and_guard(self):
        _, d = await self.call('GET', '/v1/settings')
        tabs = {g['tab'] for g in d['groups']}
        self.assertEqual(tabs, {'Model', 'Safety', 'Memory', 'Tools', 'Chat'})
        fields = {f['key']: f for g in d['groups'] for f in g['fields']}
        self.assertEqual(fields['model.default']['value'], 'synthetic-demo')
        self.assertTrue(all(f.get('options') for f in fields.values() if f['type'] == 'select'))
        _, d = await self.call('PUT', '/v1/settings', {'key': 'agent.max_turns', 'value': '42'})
        self.assertEqual((d['ok'], d['value']), (True, 42))
        self.assertEqual((await self.call('PUT', '/v1/settings', {'key': 'agent.max_turns', 'value': '5000'}))[0], 400)
        self.assertEqual((await self.call('PUT', '/v1/settings', {'key': 'display.personality', 'value': 'pirate'}))[0], 400)
        self.assertEqual((await self.call('PUT', '/v1/settings', {'key': 'secret.api_key', 'value': 'x'}))[0], 400)
        _, d = await self.call('PUT', '/v1/settings', {'key': 'display.platforms.mobile.show_reasoning', 'value': True})
        self.assertTrue(d['ok'])
        status, d = await self.call('PUT', '/v1/settings', {'key': 'approvals.mode', 'value': 'off'})
        self.assertEqual((status, d.get('need_sig')), (403, True))
        status, d = await self.call('PUT', '/v1/settings', {'key': 'approvals.mode', 'value': 'off'}, amsg=guard('config', 'approvals.mode', 'smart'))
        self.assertEqual(status, 403)  # A proof for a different value cannot loosen this one.
        status, d = await self.call('PUT', '/v1/settings', {'key': 'approvals.mode', 'value': 'off'}, amsg=guard('config', 'approvals.mode', 'off'))
        self.assertEqual((status, d['value']), (200, 'off'))
        _, d = await self.call('PUT', '/v1/settings', {'key': 'model.default', 'value': 'example-pro', 'provider': 'example'})
        self.assertEqual((d['model'], d['provider']), ('example-pro', 'example'))
        _, d = await self.call('GET', '/v1/settings')
        fields = {f['key']: f for g in d['groups'] for f in g['fields']}
        self.assertEqual((fields['agent.max_turns']['value'], fields['approvals.mode']['value'], fields['model.default']['value']), (42, 'off', 'example-pro'))
        _, d = await self.call('GET', '/v1/settings/all')
        keys = {f['key']: f for f in d['fields']}
        self.assertIn('display.skin', keys)
        self.assertEqual(keys['agent.max_turns']['value'], 42)
        _, d = await self.call('PUT', '/v1/settings', {'key': 'display.skin', 'value': 'mono'})
        self.assertTrue(d['ok'])
        # Reasoning shown in the app now prepends the thinking block the client folds.
        await self.call('POST', '/v1/chats/c-welcome/messages', {'text': 'hello'})
        await self.idle()
        _, m = await self.call('GET', '/v1/chats/c-welcome/messages?limit=150')
        self.assertTrue(m['messages'][-1]['content'].startswith('💭 **Reasoning:**'))
        other, key, _ = await self.other()
        _, d = await self.call('GET', '/v1/settings', dev=other, key=key)
        self.assertEqual({f['key']: f['value'] for g in d['groups'] for f in g['fields']}['approvals.mode'], 'manual')

    async def test_skills_plugins_mcp_and_restart(self):
        _, d = await self.call('GET', '/v1/skills')
        names = {s['name']: s for s in d['skills']}
        self.assertEqual(names['changelog-writer']['provenance'], 'hub')
        _, t = await self.call('POST', '/v1/skills/toggle', {'name': 'trip-planner', 'enabled': False})
        self.assertFalse(t['enabled'])
        self.assertFalse({s['name']: s for s in (await self.call('GET', '/v1/skills'))[1]['skills']}['trip-planner']['enabled'])
        _, t = await self.call('POST', '/v1/skills/toggle', {'name': 'memory', 'enabled': False})
        self.assertTrue(t['enabled'])  # locked: the app says it can't be turned off
        _, sk = await self.call('GET', '/v1/skill?name=trip-planner')
        self.assertTrue(sk['content'].startswith('---'))
        self.assertEqual(sk['missing_env'], ['MAPS_API_KEY'])
        self.assertEqual((await self.call('GET', '/v1/skill?name=nope'))[0], 404)
        _, d = await self.call('GET', '/v1/plugins')
        plugins = {p['name']: p for p in d['plugins']}
        self.assertTrue(plugins['mobile']['locked'])
        self.assertTrue(plugins['sandbox-memory']['auto'])
        self.assertEqual((await self.call('POST', '/v1/plugins/toggle', {'name': 'calendar-peek', 'enabled': True}))[0], 403)
        status, _ = await self.call('POST', '/v1/plugins/toggle', {'name': 'calendar-peek', 'enabled': True}, amsg=guard('plugin', 'calendar-peek', True))
        self.assertEqual(status, 200)
        p = {x['name']: x for x in (await self.call('GET', '/v1/plugins'))[1]['plugins']}['calendar-peek']
        self.assertEqual((p['configured'], p['enabled'], p['pending']), (True, False, True))
        _, mcp = await self.call('GET', '/v1/mcp')
        self.assertEqual([s['status'] for s in mcp['servers']], ['connected', 'disabled'])
        _, rs = await self.call('POST', '/v1/restart')
        self.assertTrue(rs['ok'])
        p = {x['name']: x for x in (await self.call('GET', '/v1/plugins'))[1]['plugins']}['calendar-peek']
        self.assertEqual((p['enabled'], p['pending']), (True, False))
        _, d = await self.call('GET', '/v1/chats/c-hermes/messages')
        self.assertIn('Restart acknowledged', d['messages'][-1]['content'])

    # ---------------------------------------------------------------- Hub
    async def test_hub_browse_install_update_remove(self):
        _, inst = await self.call('GET', '/v1/hub/skills/installed')
        self.assertEqual((inst['profiles'], inst['gateway_profile']), (['default', 'research'], 'default'))
        _, b = await self.call('GET', '/v1/hub/skills/browse')
        idents = [x['identifier'] for x in b['featured'] + b['official']]
        self.assertIn('official/productivity/daily-brief', idents)
        for group in (b['featured'], b['official'], b['local']):
            ids = [x.get('identifier') or x.get('inventory_id') for x in group]
            self.assertEqual(len(ids), len(set(ids)))  # LazyColumn keys must be unique
        self.assertIn('official/software-development/changelog-writer', b['installed'])
        self.assertTrue(any(not r['managed'] for r in b['local']))
        _, sr = await self.call('GET', '/v1/hub/skills/search?q=cards&source=all&limit=50')
        self.assertEqual([x['name'] for x in sr['results']], ['flashcards'])
        ident = 'skills-sh/flashcards'
        _, pv = await self.call('GET', '/v1/hub/skills/preview?identifier=' + quote(ident, safe=''))
        self.assertIsNone(pv['installed'])
        _, sc = await self.call('GET', '/v1/hub/skills/scan?identifier=' + quote(ident, safe=''))
        self.assertEqual(sc['policy'], 'allow')
        _, blocked = await self.call('GET', '/v1/hub/skills/scan?identifier=' + quote('github/example/shell-helper', safe=''))
        self.assertEqual((blocked['policy'], blocked['severity_counts']['high']), ('block', 1))
        status, d = await self.call('POST', '/v1/hub/skills/install', {'profile': '', 'identifier': ident})
        self.assertEqual((status, d.get('need_sig')), (403, True))
        status, d = await self.call('POST', '/v1/hub/skills/install', {'profile': '', 'identifier': ident}, amsg=guard('hub', f'skill:install:{ident}', True))
        self.assertEqual((status, d['job']['state']), (200, 'running'))
        _, j = await self.call('GET', '/v1/hub/jobs/' + d['job']['id'])
        self.assertEqual(j['job']['state'], 'ok')
        _, pv = await self.call('GET', '/v1/hub/skills/preview?identifier=' + quote(ident, safe=''))
        self.assertEqual(pv['installed']['name'], 'flashcards')
        self.assertIn('flashcards', [s['name'] for s in (await self.call('GET', '/v1/skills'))[1]['skills']])
        status, d = await self.call('POST', '/v1/hub/skills/install', {'profile': '', 'identifier': 'github/example/shell-helper'},
                                    amsg=guard('hub', 'skill:install:github/example/shell-helper', True))
        self.assertEqual(status, 409)
        _, up = await self.call('GET', '/v1/hub/skills/updates')
        self.assertEqual({u['name']: u['status'] for u in up['updates']}, {'changelog-writer': 'update_available', 'flashcards': 'up_to_date'})
        status, d = await self.call('POST', '/v1/hub/skills/update', {'profile': '', 'name': 'changelog-writer'}, amsg=guard('hub', 'skill:update:changelog-writer', True))
        self.assertEqual(status, 200)
        await self.call('GET', '/v1/hub/jobs/' + d['job']['id'])
        self.assertEqual({u['name']: u['status'] for u in (await self.call('GET', '/v1/hub/skills/updates'))[1]['updates']}['changelog-writer'], 'up_to_date')
        status, d = await self.call('POST', '/v1/hub/skills/uninstall', {'profile': '', 'name': 'flashcards'})  # unsigned, as the app sends it
        self.assertEqual(status, 200)
        await self.call('GET', '/v1/hub/jobs/' + d['job']['id'])
        self.assertIsNone((await self.call('GET', '/v1/hub/skills/preview?identifier=' + quote(ident, safe='')))[1]['installed'])
        # Profiles keep separate installs.
        status, d = await self.call('POST', '/v1/hub/skills/install', {'profile': 'research', 'identifier': ident}, amsg=guard('hub', f'skill:install:{ident}', True))
        self.assertEqual(status, 200)
        await self.call('GET', '/v1/hub/jobs/' + d['job']['id'])
        self.assertIn(ident, (await self.call('GET', '/v1/hub/skills/browse?profile=research'))[1]['installed'])
        self.assertNotIn(ident, (await self.call('GET', '/v1/hub/skills/browse'))[1]['installed'])
        self.assertEqual((await self.call('GET', '/v1/hub/skills/browse?profile=nope'))[0], 404)

    async def test_hub_plugins_consent_and_mcp_credentials(self):
        _, d = await self.call('GET', '/v1/hub/plugins')
        self.assertTrue(d['categories'] and d['removed'])
        # The seeded install is one release behind, so Update and its capability consent are reachable from a phone.
        e = {x['name']: x for x in d['entries']}['voice-notes']
        self.assertEqual((e['installed'], e['update_available'], e['installed_name']), (True, True, 'voice-notes'))
        status, d = await self.call('POST', '/v1/hub/plugins/update', {'name': 'voice-notes'}, amsg=guard('hub', 'plugin:update:voice-notes', True))
        self.assertEqual(status, 200)
        _, j = await self.call('GET', '/v1/hub/jobs/' + d['job']['id'])
        self.assertEqual((j['job']['state'], j['job']['result']['delta_lines']), ('consent', ['tools: memo_delete']))
        entries = {x['name']: x for x in (await self.call('GET', '/v1/hub/plugins'))[1]['entries']}
        self.assertTrue(entries['voice-notes']['update_available'])  # asking for consent changed nothing
        status, d = await self.call('POST', '/v1/hub/plugins/update', {'name': 'voice-notes', 'accept_capabilities': True},
                                    amsg=guard('hub', 'plugin:update:voice-notes:accept', True))
        _, j = await self.call('GET', '/v1/hub/jobs/' + d['job']['id'])
        self.assertEqual((j['job']['state'], j['job']['result']['restart_required']), ('ok', True))
        e = {x['name']: x for x in (await self.call('GET', '/v1/hub/plugins'))[1]['entries']}['voice-notes']
        self.assertFalse(e['update_available'])
        status, d = await self.call('POST', '/v1/hub/plugins/install', {'name': 'focus-timer'}, amsg=guard('hub', 'plugin:install:focus-timer', True))
        self.assertEqual(status, 200)
        _, j = await self.call('GET', '/v1/hub/jobs/' + d['job']['id'])
        self.assertTrue(j['job']['result']['restart_required'])
        p = {x['name']: x for x in (await self.call('GET', '/v1/plugins'))[1]['plugins']}['focus-timer']
        self.assertTrue(p['pending'])
        await self.call('POST', '/v1/restart')
        e = {x['name']: x for x in (await self.call('GET', '/v1/hub/plugins'))[1]['entries']}['focus-timer']
        self.assertEqual((e['installed'], e['runtime_status'], e['update_available']), (True, 'enabled', False))
        status, d = await self.call('POST', '/v1/hub/plugins/remove', {'name': 'focus-timer'}, amsg=guard('hub', 'plugin:remove:focus-timer', True))
        await self.call('GET', '/v1/hub/jobs/' + d['job']['id'])
        self.assertNotIn('focus-timer', [x['name'] for x in (await self.call('GET', '/v1/plugins'))[1]['plugins']])
        secret = 'sk-synthetic-must-not-persist-123'
        self.assertEqual((await self.call('POST', '/v1/hub/mcp/install', {'name': 'weather-api', 'env': {}}))[0], 400)
        self.assertEqual((await self.call('POST', '/v1/hub/mcp/install', {'name': 'weather-api', 'env': {'OTHER': 'x'}}))[0], 400)
        status, d = await self.call('POST', '/v1/hub/mcp/install', {'name': 'weather-api', 'env': {'WEATHER_API_KEY': secret}},
                                    amsg=guard('hub', 'mcp:install:weather-api', True))
        self.assertEqual(status, 200)
        await self.call('GET', '/v1/hub/jobs/' + d['job']['id'])
        self.assertTrue({x['name']: x for x in (await self.call('GET', '/v1/hub/mcp'))[1]['entries']}['weather-api']['installed'])
        self.assertIn('weather-api', [x['name'] for x in (await self.call('GET', '/v1/mcp'))[1]['servers']])
        tenant_dir = self.home / self.dev['device_id']
        self.assertFalse(any(secret.encode() in p.read_bytes() for p in tenant_dir.iterdir() if p.is_file()))
        status, d = await self.call('POST', '/v1/hub/mcp/uninstall', {'name': 'weather-api'}, amsg=guard('hub', 'mcp:remove:weather-api', True))
        self.assertEqual(status, 200)
        await self.call('GET', '/v1/hub/jobs/' + d['job']['id'])
        self.assertFalse({x['name']: x for x in (await self.call('GET', '/v1/hub/mcp'))[1]['entries']}['weather-api']['installed'])

    # ---------------------------------------------------------------- Wake
    async def test_wake_alarm_full_flow(self):
        _, ov = await self.call('GET', '/v1/wake?tz=Europe%2FBerlin')
        self.assertEqual(ov['config']['tz'], 'Europe/Berlin')
        self.assertIsNotNone(ov['next']['ts'])
        cfg = ov['config']
        alarms = cfg['alarms'] + [{'time': '06:05', 'on': True, 'days': []}]
        _, ov = await self.call('PUT', '/v1/wake/config', {'alarms': alarms, 'contacts': cfg['contacts'], 'owner_phone': cfg['owner_phone']})
        self.assertEqual(len(ov['config']['alarms']), 2)
        self.assertTrue(all(a['id'].startswith('a-') for a in ov['config']['alarms']))
        self.assertEqual((await self.call('PUT', '/v1/wake/config', {'alarms': [{'time': '25:00'}]}))[0], 400)
        _, ov = await self.call('PUT', '/v1/wake/config', {'alarms': ov['config']['alarms'], 'contacts': [], 'owner_phone': '', 'level_base': 3})
        self.assertEqual(ov['level'], 3)
        _, t = await self.call('POST', '/v1/wake/test')
        sess = t['session']
        self.assertEqual((sess['phase'], sess['test']), ('ring', True))
        self.assertEqual(len(sess['content']['questions']), 5)
        self.assertTrue(sess['content']['reading'])
        _, r = await self.call('POST', '/v1/wake/proof', {'kind': 'logic', 'sid': sess['id'], 'answer': '1'})
        self.assertFalse(r['ok'])
        _, r = await self.call('POST', '/v1/wake/proof', {'kind': 'read', 'sid': sess['id'], 'answers': ['', '', '', 'x', '']})
        self.assertFalse(r['ok'])
        good = {'Why bread rises': ['sugar', 'carbon dioxide', 'gluten', 'kneading', 'it cooks off'],
                'How bees find flowers': ['dances', 'the sun', 'farther', 'the scent', 'fly out']}[sess['content']['title']]
        _, r = await self.call('POST', '/v1/wake/proof', {'kind': 'read', 'sid': sess['id'], 'answers': good})
        self.assertTrue(r['ok'])
        self.assertEqual(len(r['feedback']), 5)
        self.assertEqual(r['session']['phase'], 'wait')
        self.assertGreater(r['session']['checkin_due'], time.time())
        s = self.tenant()
        w = s.kv_get('wake')
        w['session']['checkin']['due'] = time.time() - 1
        s.kv_put('wake', w)
        _, ss = await self.call('GET', '/v1/wake/session')
        self.assertEqual(ss['session']['phase'], 'checkin')
        self.assertTrue(ss['session']['logic'])
        _, r = await self.call('POST', '/v1/wake/proof', {'kind': 'logic', 'sid': sess['id'], 'answer': '8:15'})
        self.assertTrue(r['ok'])
        self.assertEqual(r['session']['phase'], 'done')
        # The result screen says the summary is in the Wake chat: it is there at once, not after a later poll.
        _, d = await self.call('GET', '/v1/chats/wake/messages')
        self.assertIn('awake', d['messages'][-1]['content'])
        summaries = len(d['messages'])
        w = s.kv_get('wake')
        w['session']['done'] = time.time() - 60
        s.kv_put('wake', w)
        _, ss = await self.call('GET', '/v1/wake/session')
        self.assertIsNone(ss['session'])
        self.assertEqual(len((await self.call('GET', '/v1/chats/wake/messages'))[1]['messages']), summaries)  # closing adds no second copy
        # Photo and steps proofs, the emergency stop and the password.
        _, t = await self.call('POST', '/v1/wake/test')
        img = base64.b64encode(os.urandom(6000)).decode()
        self.assertFalse((await self.call('POST', '/v1/wake/proof', {'kind': 'photo', 'sid': t['session']['id'], 'image': base64.b64encode(b'x' * 10).decode()}))[1]['ok'])
        self.assertTrue((await self.call('POST', '/v1/wake/proof', {'kind': 'photo', 'sid': t['session']['id'], 'image': img}))[1]['ok'])
        _, t = await self.call('POST', '/v1/wake/test')
        self.assertFalse((await self.call('POST', '/v1/wake/proof', {'kind': 'photo', 'sid': t['session']['id'], 'image': img}))[1]['ok'])
        need = t['session']['steps_needed']
        now = time.time()
        self.assertFalse((await self.call('POST', '/v1/wake/proof', {'kind': 'steps', 'sid': t['session']['id'], 'steps': [now + i * 0.01 for i in range(need)]}))[1]['ok'])
        self.assertTrue((await self.call('POST', '/v1/wake/proof', {'kind': 'steps', 'sid': t['session']['id'], 'steps': [now + i * 0.6 for i in range(need)]}))[1]['ok'])
        self.assertEqual((await self.call('POST', '/v1/wake/override', {'password': 'wrong'}))[1]['reason'], 'Wrong password.')
        self.assertTrue((await self.call('POST', '/v1/wake/override', {'password': server.REVIEW_PASSWORD}))[1]['ok'])
        self.assertEqual((await self.call('POST', '/v1/wake/override', {'password': server.REVIEW_PASSWORD}))[1]['reason'], 'No alarm is running.')
        self.assertFalse((await self.call('POST', '/v1/wake/password', {'old': 'nope', 'new': 'longenough'}))[1]['ok'])
        self.assertFalse((await self.call('POST', '/v1/wake/password', {'old': server.REVIEW_PASSWORD, 'new': 'short'}))[1]['ok'])
        self.assertFalse((await self.call('POST', '/v1/wake/password', {'old': server.REVIEW_PASSWORD, 'new': 'x' * 300}))[1]['ok'])
        self.assertTrue((await self.call('POST', '/v1/wake/password', {'old': server.REVIEW_PASSWORD, 'new': 'longenough'}))[1]['ok'])
        await self.call('POST', '/v1/wake/test')
        self.assertTrue((await self.call('POST', '/v1/wake/cancel'))[1]['ok'])
        self.assertFalse((await self.call('POST', '/v1/wake/cancel'))[1]['ok'])
        _, f = await self.call('POST', '/v1/wake/fired', {'alarm_id': ''})
        self.assertEqual(f['session']['phase'], 'ring')
        status, wa = await self.call('POST', '/v1/wake/whatsapp-test')
        self.assertEqual((status, wa['ok']), (400, False))  # owner number cleared above
        _, ov = await self.call('PUT', '/v1/wake/config', {'alarms': ov['config']['alarms'], 'contacts': [], 'owner_phone': '+15555550123'})
        status, wa = await self.call('POST', '/v1/wake/whatsapp-test')
        self.assertEqual(status, 200)
        self.assertIn('nothing was sent', wa['error'])
        other, key, _ = await self.other()
        self.assertIsNone((await self.call('GET', '/v1/wake/session', dev=other, key=key))[1]['session'])

    # ---------------------------------------------------------------- Radar, Fleet, Projects
    async def test_radar_findings_spawn_notes_and_momentum(self):
        _, d = await self.call('GET', '/v1/radar?view=current')
        self.assertEqual(len(d['findings']), 3)
        self.assertTrue(all(isinstance(f[k], int) for f in d['findings'] for k in ('vote', 'spawned')))
        self.assertTrue(d['scout'] is True and d['batch']['ts'] > 0)
        self.assertEqual([n['acked'] for n in d['notes']], [0])
        fid = d['findings'][0]['id']
        for action, field, value in [('like', 'vote', 1), ('clear-vote', 'vote', 0), ('save', 'state', 'saved')]:
            _, r = await self.call('POST', f'/v1/radar/findings/{fid}/{action}', {})
            self.assertEqual(r['finding'][field], value)
        self.assertIn(fid, [f['id'] for f in (await self.call('GET', '/v1/radar?view=saved'))[1]['findings']])
        self.assertEqual((await self.call('POST', f'/v1/radar/findings/{fid}/note', {'text': ''}))[0], 400)
        _, r = await self.call('POST', f'/v1/radar/findings/{fid}/feedback', {'text': 'Useful'})
        self.assertEqual(r['finding']['feedback'][-1]['kind'], 'feedback')
        _, sp = await self.call('POST', f'/v1/radar/findings/{fid}/spawn', {'mode': 'chat', 'prompt': 'Look into it'})
        await self.idle()
        _, m = await self.call('GET', f"/v1/chats/{sp['chat_id']}/messages?limit=150")
        self.assertEqual([x['role'] for x in m['messages']], ['user', 'agent'])
        _, sp = await self.call('POST', f'/v1/radar/findings/{fid}/spawn', {'mode': 'task', 'prompt': 'Do it'})
        self.assertTrue(sp['task_id'].startswith('t_'))
        _, f = await self.call('GET', f'/v1/radar/findings/{fid}')
        self.assertEqual(f['finding']['spawned'], 2)
        self.assertEqual(len((await self.call('GET', '/v1/radar?view=history'))[1]['findings']), 5)
        nid = d['notes'][0]['id']
        _, n = await self.call('GET', f'/v1/radar/notes/{nid}')
        self.assertIn('@@', n['note']['diff'])
        self.assertTrue((await self.call('POST', f'/v1/radar/notes/{nid}/ack', {}))[1]['ok'])
        self.assertEqual((await self.call('GET', '/v1/radar'))[1]['notes'][0]['acked'], 1)
        _, mo = await self.call('GET', '/v1/radar/momentum')
        _, mo2 = await self.call('GET', '/v1/radar/momentum?refresh=1')
        self.assertTrue(mo['available'] and mo['report']['counts']['needs_you'] >= 2)
        self.assertEqual(mo['report']['needs_you'], mo2['report']['needs_you'])  # stable ids and times keep read state
        other, key, _ = await self.other()
        self.assertEqual(next(f for f in (await self.call('GET', '/v1/radar', dev=other, key=key))[1]['findings'] if f['id'] == fid)['state'], 'new')

    async def test_fleet_agents_terminal_and_signed_input(self):
        _, d = await self.call('GET', '/v1/fleet')
        ids = [a['id'] for a in d['agents']]
        self.assertNotIn('tmux:old-task', ids)
        self.assertIn('hs:s-cli-release', ids)
        self.assertEqual(d['counts']['waiting'], 1)
        _, d = await self.call('GET', '/v1/fleet?recent=1')
        self.assertIn('tmux:old-task', [a['id'] for a in d['agents']])
        remote = quote('remote:demo-box:review-bot', safe='')
        _, t = await self.call('GET', f'/v1/fleet/{remote}/tail?n=200')
        self.assertFalse(t['terminal']['available'])
        self.assertTrue(t['lines'])
        status, d = await self.call('POST', f'/v1/fleet/{remote}/send', {'text': 'yes'})
        self.assertEqual((status, d.get('need_sig')), (403, True))
        sig = lambda ts: f"fleet\nremote:demo-box:review-bot\n{hashlib.sha256(b'yes').hexdigest()}\n{ts}".encode()
        status, d = await self.call('POST', f'/v1/fleet/{remote}/send', {'text': 'yes'}, amsg=sig)
        self.assertEqual((status, d['ok']), (200, True))
        self.assertIn('> yes', (await self.call('GET', f'/v1/fleet/{remote}/tail?n=200'))[1]['lines'])
        kitty = quote('kitty:docs', safe='')
        _, t = await self.call('GET', f'/v1/fleet/{kitty}/tail?n=200')
        term = t['terminal']
        self.assertTrue(term['available'] and term['mouse'])
        self.assertTrue(re.fullmatch('[0-9a-f]{64}', term['frame']))
        self.assertEqual(len(term['lines']), term['rows'])
        self.assertEqual(term['frame'], (await self.call('GET', f'/v1/fleet/{kitty}/tail?n=200'))[1]['terminal']['frame'])
        self.assertEqual((await self.call('POST', f'/v1/fleet/{kitty}/send', {'text': 'next section'}))[0], 200)

        async def inp(body, signed=True):
            raw = json.dumps(body).encode()
            amsg = (lambda ts: f"fleet-input\nkitty:docs\n{hashlib.sha256(raw).hexdigest()}\n{ts}".encode()) if signed else None
            path = f'/v1/fleet/{kitty}/input'
            return await self.raw('POST', path, raw, self.headers('POST', path, raw, amsg=amsg))
        self.assertEqual((await inp({'action': 'key', 'key': 'down', 'shift': False, 'ctrl': False, 'alt': False}, signed=False))[0], 403)
        self.assertEqual((await inp({'action': 'key', 'key': 'f13', 'shift': False, 'ctrl': False, 'alt': False}))[0], 400)
        for k in ('down', '1', 'enter'):
            self.assertEqual((await inp({'action': 'key', 'key': k, 'shift': False, 'ctrl': False, 'alt': False}))[1], {'ok': True})
        _, t = await self.call('GET', f'/v1/fleet/{kitty}/tail?n=200')
        self.assertTrue(any('Picked' in x for x in t['terminal']['lines']))
        frame = t['terminal']['frame']
        row = next(i for i, x in enumerate(t['terminal']['lines']) if '3. Troubleshooting' in x)
        self.assertEqual((await inp({'action': 'click', 'row': row, 'col': 4, 'frame': frame}))[1], {'ok': True})
        _, t = await self.call('GET', f'/v1/fleet/{kitty}/tail?n=200')
        self.assertTrue(t['terminal']['lines'][row].startswith('›'))
        self.assertEqual((await inp({'action': 'click', 'row': 0, 'col': 0, 'frame': frame}))[0], 409)  # stale frame
        self.assertEqual((await inp({'action': 'scroll', 'direction': 'up', 'amount': 2, 'frame': t['terminal']['frame']}))[1], {'ok': True})
        self.assertEqual((await self.call('POST', f'/v1/fleet/{remote}/stop'))[0], 409)
        _, st = await self.call('POST', '/v1/fleet/' + quote('tmux:build', safe='') + '/stop')
        self.assertTrue(st['ok'])
        other, key, _ = await self.other()
        self.assertNotIn('> yes', (await self.call('GET', f'/v1/fleet/{remote}/tail?n=200', dev=other, key=key))[1]['lines'])

    async def test_projects_status_diff_commit_push_pr(self):
        _, d = await self.call('GET', '/v1/projects')
        self.assertEqual([r['id'] for r in d['repos']], ['example-notes', 'example-site'])
        _, st = await self.call('GET', '/v1/projects/example-notes')
        self.assertEqual(st['status']['changed'], 2)
        self.assertIsNone(st['ship']['pr'])
        _, df = await self.call('GET', '/v1/projects/example-notes/diff?file=' + quote('notes/checklist.md', safe=''))
        self.assertIn('@@', df['diff'])
        self.assertEqual((await self.call('GET', '/v1/projects/example-notes/diff?file=..%2Fetc'))[0], 400)
        await self.call('POST', '/v1/projects/example-notes/stage', {'file': 'notes/checklist.md'})
        self.assertTrue(next(f for f in (await self.call('GET', '/v1/projects/example-notes'))[1]['status']['files'] if f['path'] == 'notes/checklist.md')['staged'])
        await self.call('POST', '/v1/projects/example-notes/unstage', {'file': 'notes/checklist.md'})
        self.assertEqual((await self.call('POST', '/v1/projects/example-notes/commit', {'message': ''}))[0], 400)
        _, c = await self.call('POST', '/v1/projects/example-notes/commit', {'message': 'Update notes'})
        self.assertTrue(c['ok'])
        _, st = await self.call('GET', '/v1/projects/example-notes')
        self.assertEqual((st['status']['changed'], st['status']['ahead']), (0, 1))
        self.assertEqual((await self.call('POST', '/v1/projects/example-notes/push', {}))[0], 403)
        push = lambda ts: f'git\nexample-notes\npush\n{ts}'.encode()
        self.assertEqual((await self.call('POST', '/v1/projects/example-notes/push', {}, amsg=push))[0], 200)
        self.assertEqual((await self.call('GET', '/v1/projects/example-notes'))[1]['status']['ahead'], 0)
        pr = lambda ts: f'git\nexample-site\ncreate-pr\n{ts}'.encode()
        self.assertEqual((await self.call('POST', '/v1/projects/example-site/create-pr', {}))[0], 403)
        _, r = await self.call('POST', '/v1/projects/example-site/create-pr', {}, amsg=pr)
        self.assertTrue(r['result']['url'].endswith('/review/pr'))
        _, st = await self.call('GET', '/v1/projects/example-site')
        self.assertEqual((st['ship']['pr']['state'], bool(st['ship']['pr']['title'])), ('OPEN', True))
        self.assertEqual((await self.raw('GET', '/review/pr'))[0], 200)
        other, key, _ = await self.other()
        self.assertEqual((await self.call('GET', '/v1/projects/example-notes', dev=other, key=key))[1]['status']['changed'], 2)

    # ---------------------------------------------------------------- hosting hardening
    async def test_alarm_password_never_runs_a_kdf_on_the_event_loop(self):
        """Override and password-change spam must not stall other reviewers: no slow KDF on the loop thread."""
        real, on_loop = hashlib.pbkdf2_hmac, []

        def spy(*a, **kw):
            on_loop.append(threading.current_thread() is threading.main_thread())
            return real(*a, **kw)
        await self.call('POST', '/v1/wake/test')
        with patch.object(server.hashlib, 'pbkdf2_hmac', spy):
            results = await asyncio.gather(*(self.call('POST', '/v1/wake/override', {'password': f'guess-{i}'}) for i in range(30)))
            results += [await self.call('POST', '/v1/wake/password', {'old': 'wrong-old', 'new': 'whatever-new'})]
        self.assertEqual({(st, d.get('reason')) for st, d in results[:30]}, {(200, 'Wrong password.')})
        self.assertNotIn(True, on_loop)
        # A sandbox created before the change still holds a PBKDF2 hash: checked off the loop, rate-limited, rehashed.
        s = self.tenant()
        w = s.kv_get('wake')
        salt = os.urandom(16).hex()
        w['pw'] = {'salt': salt, 'hash': real('sha256', server.REVIEW_PASSWORD.encode(), bytes.fromhex(salt), 200_000).hex()}
        s.kv_put('wake', w)
        with patch.object(server.hashlib, 'pbkdf2_hmac', spy):
            status, d = await self.call('POST', '/v1/wake/override', {'password': server.REVIEW_PASSWORD})
        self.assertEqual((status, d['ok']), (200, True))
        self.assertEqual(s.kv_get('wake')['pw'].get('alg'), 'sha256')
        self.assertIn(False, on_loop)
        self.assertNotIn(True, on_loop)
        w = s.kv_get('wake')
        w['pw'] = {'salt': salt, 'hash': '00'}
        s.kv_put('wake', w)
        await self.call('POST', '/v1/wake/test')
        statuses = [(await self.call('POST', '/v1/wake/override', {'password': 'x'}))[0] for _ in range(self.backend.MAX_PW_TRIES + 1)]
        self.assertEqual((statuses[0], statuses[-1]), (200, 429))

    async def test_wrong_guesses_from_many_clients_cannot_lock_out_invited_reviewers(self):
        await self.runner.cleanup()
        await self.start(client_header='X-Forwarded-For')
        bogus = json.dumps({'code': 'not-a-code', 'pub': pub(self.key), 'apub': pub(self.apr)}).encode()
        for i in range(20):  # 20 addresses x 21 wrong invitations and 21 wrong pairing codes: 840 failures
            ip = {'X-Forwarded-For': f'198.51.100.{i}', 'Content-Type': 'application/json'}
            enroll = [(await self.raw('POST', '/review/enroll', headers=ip))[0] for _ in range(21)]
            pair = [(await self.raw('POST', '/v1/pair', bogus, ip))[0] for _ in range(21)]
            self.assertEqual((enroll[0], enroll[-1], pair[0], pair[-1]), (401, 429, 403, 429))
        invited = {'X-Forwarded-For': '203.0.113.7', 'Authorization': 'Bearer ' + self.backend.invitation}
        status, d = await self.raw('POST', '/review/enroll', headers=invited)
        self.assertEqual(status, 200)
        key, apr = ec.generate_private_key(ec.SECP256R1()), ec.generate_private_key(ec.SECP256R1())
        code = parse_qs(urlsplit(d['link']).query)['c'][0]
        body = json.dumps({'code': code, 'pub': pub(key), 'apub': pub(apr)}).encode()
        status, _ = await self.raw('POST', '/v1/pair', body, {'X-Forwarded-For': '203.0.113.7', 'Content-Type': 'application/json'})
        self.assertEqual(status, 200)
        # Invited enrollments still have a per-client budget.
        same = {'X-Forwarded-For': '203.0.113.8', 'Authorization': 'Bearer ' + self.backend.invitation}
        self.assertEqual([(await self.raw('POST', '/review/enroll', headers=same))[0] for _ in range(21)][-1], 429)

    async def test_tenant_stores_are_bounded_and_closed_on_revoke(self):
        fds = lambda: len(os.listdir('/proc/self/fd'))
        self.backend.MAX_OPEN_TENANTS = 2
        start = fds()
        devs = [await self.other() for _ in range(6)]
        for _ in range(2):
            for dev, key, _ in devs:
                self.assertEqual((await self.call('GET', '/v1/sync?since=0', dev=dev, key=key))[0], 200)
        self.assertLessEqual(len(self.backend.stores), 2)
        self.assertLessEqual(fds(), start + 8)  # six open sandboxes would hold about 18 more descriptors
        dev, key, _ = devs[-1]
        self.assertEqual((await self.call('DELETE', '/v1/devices/' + dev['device_id'], dev=dev, key=key))[0], 200)
        await asyncio.gather(*list(self.backend.bg))
        self.assertNotIn(dev['device_id'], self.backend.stores)
        self.assertFalse((self.home / dev['device_id']).exists())
        self.backend.IDLE_CLOSE = 0
        self.backend.evict()
        self.assertEqual(len(self.backend.stores), 0)
        self.assertEqual((await self.call('GET', '/v1/me'))[0], 200)  # a closed store reopens on the next request

    async def test_enrollment_disk_caps_and_retention(self):
        active = self.backend.auth._db.execute('SELECT COUNT(*) FROM devices WHERE revoked=0').fetchone()[0]
        self.backend.MAX_ENROLLMENTS = active + 1
        await self.other()
        status, d = await self.raw('POST', '/review/enroll', headers={'Authorization': 'Bearer ' + self.backend.invitation})
        self.assertEqual(status, 503)
        self.assertIn('full', d['error'])
        code = self.backend.auth.create_pair_code()
        body = json.dumps({'code': code, 'pub': pub(self.key), 'apub': pub(self.apr)}).encode()
        self.assertEqual((await self.raw('POST', '/v1/pair', body, {'Content-Type': 'application/json'}))[0], 503)
        self.backend.MAX_ENROLLMENTS = 200
        self.backend.disk = (0, -math.inf)
        self.backend.DISK_CAP = self.backend.disk_used() + 512 * 1024
        p, data = '/v1/upload?name=a.bin&mime=application%2Foctet-stream', os.urandom(1024 * 1024)
        status, d = await self.raw('POST', p, data, self.headers('POST', p, data))
        self.assertEqual(status, 507)
        self.assertIn('out of storage', d['error'])
        self.backend.DISK_CAP = self.backend.disk_used()  # completely full: no new sandboxes either
        status, d = await self.raw('POST', '/review/enroll', headers={'Authorization': 'Bearer ' + self.backend.invitation})
        self.assertEqual((status, 'storage' in d.get('error', '')), (503, True))
        self.backend.DISK_CAP = 2 ** 40
        dev, key, _ = await self.other()
        with self.backend.auth._lock:
            self.backend.auth._db.execute('UPDATE devices SET last_seen=? WHERE id=?', (time.time() - self.backend.TENANT_TTL - 10, dev['device_id']))
        await self.backend.sweep()
        self.assertFalse((self.home / dev['device_id']).exists())
        self.assertEqual((await self.call('GET', '/v1/me', dev=dev, key=key))[0], 401)
        self.assertEqual((await self.call('GET', '/v1/me'))[0], 200)

    async def test_request_size_limits(self):
        big = b'{' + b' ' * (2 * 1024 * 1024) + b'}'
        self.assertEqual((await self.raw('POST', '/v1/pair', big, {'Content-Type': 'application/json'}))[0], 413)
        await self.call('POST', '/v1/wake/test')
        proof = json.dumps({'kind': 'photo', 'sid': '', 'image': 'A' * (4 * 1024 * 1024)}).encode()
        self.assertEqual((await self.raw('POST', '/v1/wake/proof', proof, self.headers('POST', '/v1/wake/proof', proof)))[0], 413)
        photo = json.dumps({'kind': 'photo', 'sid': '', 'image': base64.b64encode(os.urandom(1500 * 1024)).decode()}).encode()
        status, d = await self.raw('POST', '/v1/wake/proof', photo, self.headers('POST', '/v1/wake/proof', photo))
        self.assertEqual((status, d['ok']), (200, True))

    async def test_per_tenant_caps_and_bounded_search(self):
        n = self.tenant()._db.execute('SELECT COUNT(*) FROM chats').fetchone()[0]
        self.backend.MAX_CHATS = n + 1
        self.assertEqual((await self.call('POST', '/v1/chats', {'title': 'last one'}))[0], 200)
        status, d = await self.call('POST', '/v1/chats', {'title': 'one too many'})
        self.assertEqual(status, 409)
        self.assertIn('at most', d['error'])
        self.assertEqual((await self.call('POST', '/v1/radar/findings/f-sandbox-01/spawn', {'mode': 'chat', 'prompt': 'x'}))[0], 409)
        self.backend.MAX_SPAWNS = 1
        self.assertEqual((await self.call('POST', '/v1/radar/findings/f-sandbox-01/spawn', {'mode': 'task', 'prompt': 'x'}))[0], 200)
        self.assertEqual((await self.call('POST', '/v1/radar/findings/f-sandbox-02/spawn', {'mode': 'task', 'prompt': 'x'}))[0], 409)
        self.assertEqual((await self.call('GET', '/v1/search?q=' + 'a' * 300))[0], 400)
        self.assertEqual((await self.call('GET', '/v1/search?q=' + quote(' '.join(['word'] * 9))))[0], 400)
        self.assertEqual((await self.call('GET', '/v1/search?q=synthetic'))[0], 200)

    async def test_sse_streams_are_limited_quiet_and_event_driven(self):
        self.backend.KEEPALIVE = 1.0
        streams = []
        try:
            for _ in range(3):
                r = await self.http.get(self.url + '/v1/events', headers=self.headers('GET', '/v1/events'))
                self.assertEqual(r.status, 200)
                self.assertIn(b'"hello"', await asyncio.wait_for(r.content.readline(), 2))
                await asyncio.wait_for(r.content.readline(), 2)
                streams.append(r)
            async with self.http.get(self.url + '/v1/events', headers=self.headers('GET', '/v1/events')) as fourth:
                self.assertEqual(fourth.status, 429)
            with self.assertRaises(asyncio.TimeoutError):  # nothing changed: no polling chatter
                await asyncio.wait_for(streams[0].content.readline(), 0.5)
            self.assertEqual(await asyncio.wait_for(streams[1].content.readline(), 3), b': keepalive\n')
            t0 = time.monotonic()
            await self.call('POST', '/v1/chats/c-planning/read', {})
            line = b''
            while b'"type": "seq"' not in line:  # skip a keepalive that may already be buffered
                line = await asyncio.wait_for(streams[2].content.readline(), 2)
            self.assertLess(time.monotonic() - t0, 0.9)  # a change wakes the stream at once, not on a poll tick
        finally:
            for r in streams:
                r.close()

    async def test_numeric_and_schedule_bounds_never_break_lists(self):
        status, d = await self.call('POST', '/v1/cronjob', {'name': 'Huge', 'schedule': 'every ' + '9' * 310 + 'm', 'prompt': 'x'})
        self.assertEqual((status, d['success']), (400, False))
        self.assertEqual((await self.call('POST', '/v1/cronjob', {'schedule': 'every 400d', 'prompt': 'x'}))[0], 400)
        for p in ('/v1/cron', '/v1/pulse', '/v1/cron-delivery'):
            self.assertEqual((await self.call('GET', p))[0], 200, p)
        s = self.tenant()
        jobs = s.kv_get('jobs')
        jobs[0]['schedule_expr'] = 'every ' + '9' * 310 + 'm'  # a schedule stored before the bound existed
        s.kv_put('jobs', jobs)
        for p in ('/v1/cron', '/v1/pulse', '/v1/cron-delivery', '/v1/cronjob/' + jobs[0]['id']):
            self.assertEqual((await self.call('GET', p))[0], 200, p)
        self.assertTrue((await self.call('GET', '/v1/cron'))[1]['jobs'][0]['schedule_display'].startswith('Invalid'))
        for raw in (b'{"name": "Inf", "schedule": "every 1h", "prompt": "x", "repeat": Infinity}',
                    b'{"name": "Big", "schedule": "every 1h", "prompt": "x", "repeat": 1e400}'):
            status, d = await self.raw('POST', '/v1/cronjob', raw, self.headers('POST', '/v1/cronjob', raw))
            self.assertEqual(status, 400, raw)
        await self.call('POST', '/v1/wake/test')
        raw = b'{"kind": "steps", "sid": "", "steps": [Infinity, 1, 2]}'
        self.assertEqual((await self.raw('POST', '/v1/wake/proof', raw, self.headers('POST', '/v1/wake/proof', raw)))[0], 400)
        raw = b'{"kind": "steps", "sid": "", "steps": [1e400, -1e400, 3]}'
        status, d = await self.raw('POST', '/v1/wake/proof', raw, self.headers('POST', '/v1/wake/proof', raw))
        self.assertEqual((status, d['ok']), (200, False))

    async def test_hub_jobs_cannot_overtake_each_other(self):
        status, d = await self.call('POST', '/v1/hub/plugins/install', {'name': 'focus-timer'}, amsg=guard('hub', 'plugin:install:focus-timer', True))
        self.assertEqual((await self.call('GET', '/v1/hub/jobs/' + d['job']['id']))[1]['job']['state'], 'ok')
        self.backend.JOB_SECONDS = 60
        status, rm = await self.call('POST', '/v1/hub/plugins/remove', {'name': 'focus-timer'}, amsg=guard('hub', 'plugin:remove:focus-timer', True))
        self.assertEqual(status, 200)
        status, d = await self.call('POST', '/v1/hub/plugins/update', {'name': 'focus-timer'}, amsg=guard('hub', 'plugin:update:focus-timer', True))
        self.assertEqual(status, 409)
        self.assertIn('still running', d['error'])
        status, again = await self.call('POST', '/v1/hub/plugins/remove', {'name': 'focus-timer'}, amsg=guard('hub', 'plugin:remove:focus-timer', True))
        self.assertEqual((status, again['job']['id']), (200, rm['job']['id']))
        self.backend.JOB_SECONDS = 0
        status, d = await self.call('GET', '/v1/hub/plugins')
        self.assertEqual(status, 200)
        self.assertFalse({x['name']: x for x in d['entries']}['focus-timer']['installed'])
        # A job whose target is gone fails on its own; the Hub and its neighbours keep answering.
        s = self.tenant()
        hub = s.kv_get('hub')
        hub['jobs']['hj-orphan'] = {'id': 'hj-orphan', 'kind': 'plugin', 'action': 'update', 'target': 'focus-timer', 'profile': 'default',
                                    'state': 'running', 'started': time.time() - 5, 'ended': None, 'log': '', 'error': '', 'result': None,
                                    'effect': {'op': 'plugin_update', 'name': 'focus-timer', 'accept': True}}
        s.kv_put('hub', hub)
        for p in ('/v1/hub/plugins', '/v1/plugins', '/v1/skills', '/v1/mcp', '/v1/hub/skills/browse'):
            self.assertEqual((await self.call('GET', p))[0], 200, p)
        _, j = await self.call('GET', '/v1/hub/jobs/hj-orphan')
        self.assertEqual(j['job']['state'], 'failed')
        self.assertIn('removed', j['job']['error'])
        self.assertEqual((await self.call('POST', '/v1/restart', {}))[0], 200)

    async def test_a_failing_reply_never_leaves_a_chat_working(self):
        status, _ = await self.call('POST', '/v1/chats/c-planning/messages',
                                    {'text': 'hi', 'attachments': [{'kind': ['x']}, {'kind': 'voice', 'size': 'big'}, 'junk']})
        self.assertEqual(status, 200)
        await self.idle()
        self.assertFalse((await self.call('GET', '/v1/chats/c-planning/run'))[1]['working'])
        _, d = await self.call('GET', '/v1/chats/c-planning/messages?limit=150')
        self.assertIn('Synthetic demo reply', d['messages'][-1]['content'])
        with patch.object(self.backend, 'compose', side_effect=RuntimeError('synthetic failure')):
            await self.call('POST', '/v1/chats/c-planning/messages', {'text': 'again'})
            await self.idle()
        self.assertFalse((await self.call('GET', '/v1/chats/c-planning/run'))[1]['working'])
        _, d = await self.call('GET', '/v1/chats/c-planning/messages?limit=150')
        self.assertEqual(d['messages'][-1]['kind'], 'status')

    async def test_session_replies_are_all_tracked(self):
        self.backend.REPLY_STEP = 0.3
        await self.call('PUT', '/v1/sessions/s-cli-release/messages', {'text': 'first', 'submission_id': 'a'})
        self.backend.REPLY_STEP = 0.01
        await self.call('PUT', '/v1/sessions/s-cli-release/messages', {'text': 'second', 'submission_id': 'b'})
        await self.idle()
        pending = [t for t in asyncio.all_tasks() if t.get_coro().__name__ == 'session_reply' and not t.done()]
        self.assertEqual(pending, [])  # idle() saw the first reply too, so shutdown and revoke can wait for it

    async def test_shutdown_closes_event_streams_promptly(self):
        await self.runner.cleanup()
        self.backend = server.Backend(self.home, 'http://127.0.0.1:47941')
        self.runner = web.AppRunner(self.backend.app(), access_log=None, shutdown_timeout=10)
        await self.runner.setup()
        site = web.TCPSite(self.runner, '127.0.0.1', 0)
        await site.start()
        self.url = 'http://127.0.0.1:' + str(site._server.sockets[0].getsockname()[1])
        r = await self.http.get(self.url + '/v1/events', headers=self.headers('GET', '/v1/events'))
        self.assertIn(b'"hello"', await asyncio.wait_for(r.content.readline(), 2))
        t0 = time.monotonic()
        await self.runner.cleanup()
        elapsed = time.monotonic() - t0
        r.close()
        self.assertLess(elapsed, 3)  # an open event stream must not hold Ctrl-C/SIGTERM for the 10 s timeout
        await self.start()

    # ---------------------------------------------------------------- coverage and drift guards
    async def test_every_play_reachable_api_answers(self):
        """Every client call answers with its expected status and the fields the app reads; any other status fails."""
        problems, hit = [], set()
        app = self.backend.app()

        async def check(method, path, body=None, amsg=None, status=200, keys=(), raw=None):
            match = await app.router.resolve(make_mocked_request(method, path, app=app))
            hit.add((method, match.route.resource.canonical if match.route.resource else path))
            if raw is not None:
                got, d = await self.raw(method, path, raw, self.headers(method, path, raw, amsg=amsg))
            else:
                got, d = await self.call(method, path, body, amsg=amsg)
            missing = [k for k in keys if not isinstance(d, dict) or k not in d] if keys != 'bytes' else ([] if isinstance(d, bytes) else ['bytes'])
            if got != status or missing:
                problems.append((method, path, got, missing or (d.get('error') if isinstance(d, dict) else None)))
            return d if isinstance(d, dict) else {}

        hub = lambda key: guard('hub', key, True)
        sync = await check('GET', '/v1/sync?since=0', keys=('seq', 'chats', 'messages', 'approvals', 'working'))
        aid = next((a['id'] for a in sync.get('approvals', []) if a['state'] == 'pending'), 'a-none')
        card = next((m['id'] for m in sync.get('messages', []) if m['kind'] == 'activity'), 'm-none')
        await check('GET', '/v1/health', keys=('ok', 'features'))
        await check('GET', '/v1/me', keys=('features', 'device', 'model'))
        await check('GET', '/v1/commands', keys=('commands',))
        await check('GET', '/v1/profiles', keys=('profiles',))
        await check('POST', '/v1/chats', {'title': 'Coverage', 'profile': 'research'}, keys=('chat',))
        await check('PATCH', '/v1/chats/c-planning', {'pinned': 1}, keys=('chat',))
        await check('GET', '/v1/chats/c-planning/messages?limit=150', keys=('messages',))
        await check('POST', '/v1/chats/c-planning/messages', {'text': 'hi', 'mode': 'send'}, keys=('message',))
        await check('POST', '/v1/chats/c-planning/read', {}, keys=('ok',))
        await check('POST', '/v1/chats/c-planning/stop', {}, keys=('ok',))
        await self.idle()
        await check('GET', '/v1/chats/c-planning/run', keys=('working', 'steps', 'turn', 'approvals', 'session'))
        await check('GET', '/v1/chats/c-planning/usage', keys=('turn', 'session'))
        await check('GET', '/v1/chats/_new/model', keys=('model', 'provider', 'caps', 'reasoning'))
        await check('POST', '/v1/chats/c-planning/model', {'reasoning': 'low'}, keys=('ok', 'state'))
        big = await check('POST', '/v1/chats/c-planning/model', {'model': 'synthetic-large', 'provider': 'sandbox'}, keys=('confirm', 'state'))
        await check('POST', '/v1/chats/c-planning/confirm', {'id': (big.get('confirm') or {}).get('id', ''), 'choice': 'cancel'}, keys=('reply', 'state'))
        await check('POST', '/v1/chats/c-planning/session', {'action': 'status', 'arg': '', 'confirmed': False}, keys=('ok', 'reply'))
        await check('GET', f'/v1/activity/{card}', keys=('message', 'calls', 'files'))
        await check('POST', f'/v1/approvals/{aid}', {'choice': 'deny'}, keys=('approval', 'resolved'))
        up = await check('POST', '/v1/upload?name=a.txt&mime=text%2Fplain', raw=b'synthetic', keys=('id', 'size', 'mime', 'file_name'))
        await check('GET', '/v1/media/' + up.get('id', 'u-0'), keys='bytes')
        tts = await check('POST', '/v1/tts', {'text': 'hello'}, keys=('ids',))
        await check('GET', '/v1/media/' + (tts.get('ids') or ['x'])[0], keys='bytes')
        await check('GET', '/v1/models', keys=('providers', 'recent', 'default'))
        await check('GET', '/v1/models?refresh=1', keys=('providers',))
        await check('GET', '/v1/pulse', keys=('spend_7d', 'live_sessions', 'approvals', 'working'))
        await check('GET', '/v1/usage?days=30', keys=('series', 'models', 'sources', 'total'))
        await check('GET', '/v1/search?q=synthetic', keys=('results',))
        await check('GET', '/v1/sessions', keys=('sessions',))
        await check('GET', SESSION + '/messages?limit=40&refresh=1', keys=('messages', 'nextBefore', 'running', 'generation', 'prompts', 'readOnly'))
        await check('GET', SESSION + '/messages?before=1&limit=100', keys=('messages',))
        await check('PUT', SESSION + '/messages', {'text': 'hello', 'submission_id': 'c-1'}, keys=('ok',))
        await check('PUT', SESSION + '/prompts/p-clarify', {'kind': 'clarify', 'generation': 1, 'answer': 'Both', 'question_id': ''}, keys=('ok',))
        await check('POST', SESSION + '/stop', {'generation': 2}, keys=('ok',))
        await check('DELETE', SESSION + '/viewer', keys=('ok',))
        await self.idle()
        await check('GET', '/v1/cron', keys=('jobs', 'now'))
        for action in ('pause', 'resume', 'run'):
            await check('POST', f'/v1/cron/demo-digest/{action}', {}, keys=('job',))
        out = await check('GET', '/v1/cron/demo-digest/output', keys=('run', 'text'))
        await check('GET', '/v1/cron/demo-digest/output?run=' + out.get('run', 'x'), keys=('run', 'text'))
        await check('GET', '/v1/cronjob/demo-digest', keys=('id', 'schedule_expr', 'prompt', 'deliver', 'skills'))
        job = {'name': 'C', 'schedule': 'every 2h', 'prompt': 'p', 'deliver': 'mobile', 'skills': [], 'model': '', 'provider': '', 'reasoning_effort': ''}
        await check('POST', '/v1/cronjob', job, keys=('success', 'job_id', 'job'))
        await check('PUT', '/v1/cronjob/demo-weekly', {**job, 'schedule': 'every monday 9am'}, keys=('success', 'job'))
        await check('GET', '/v1/cron-delivery', keys=('counts', 'total'))
        await check('POST', '/v1/cron-delivery', {'deliver': 'mobile'}, keys=('success', 'moved', 'counts'))
        await check('DELETE', '/v1/cronjob/demo-weekly', keys=('success',))
        await check('GET', '/v1/memory', keys=('memory', 'user'))
        await check('POST', '/v1/memory', {'target': 'user', 'action': 'add', 'content': 'c'}, keys=('success', 'message'))
        await check('GET', '/v1/settings', keys=('groups',))
        await check('GET', '/v1/settings/all', keys=('fields',))
        await check('PUT', '/v1/settings', {'key': 'agent.max_turns', 'value': '12'}, keys=('ok', 'key', 'value'))
        await check('PUT', '/v1/settings', {'key': 'terminal.timeout', 'value': '60'}, guard('config', 'terminal.timeout', '60'), keys=('ok', 'value'))
        await check('GET', '/v1/skills', keys=('skills',))
        await check('GET', '/v1/skill?name=meeting-notes', keys=('name', 'content', 'files', 'missing_env'))
        await check('POST', '/v1/skills/toggle', {'name': 'meeting-notes', 'enabled': False}, keys=('ok', 'enabled'))
        await check('GET', '/v1/plugins', keys=('plugins',))
        await check('POST', '/v1/plugins/toggle', {'name': 'notes-export', 'enabled': False}, guard('plugin', 'notes-export', False), keys=('ok',))
        await check('GET', '/v1/mcp', keys=('servers',))
        await check('POST', '/v1/restart', {}, keys=('ok', 'reply'))
        await check('GET', '/v1/hub/skills/installed', keys=('profiles', 'gateway_profile', 'installed', 'local'))
        await check('GET', '/v1/hub/skills/browse', keys=('sources', 'featured', 'official', 'installed', 'local'))
        await check('GET', '/v1/hub/skills/browse?profile=research', keys=('installed',))
        await check('GET', '/v1/hub/skills/search?q=brief&source=all&limit=50', keys=('results', 'timed_out'))
        await check('GET', '/v1/hub/skills/updates', keys=('updates',))
        await check('GET', '/v1/hub/skills/preview?identifier=official%2Fproductivity%2Fdaily-brief', keys=('name', 'installed', 'skill_md', 'files'))
        await check('GET', '/v1/hub/skills/scan?identifier=official%2Fproductivity%2Fdaily-brief', keys=('policy', 'verdict', 'severity_counts'))
        inst = await check('POST', '/v1/hub/skills/install', {'profile': '', 'identifier': 'official/productivity/daily-brief'},
                           hub('skill:install:official/productivity/daily-brief'), keys=('ok', 'job'))
        done = await check('GET', '/v1/hub/jobs/' + (inst.get('job') or {}).get('id', 'hj-none'), keys=('job',))
        if (done.get('job') or {}).get('state') != 'ok':
            problems.append(('GET', '/v1/hub/jobs/{jid}', done.get('job')))
        await check('POST', '/v1/hub/skills/update', {'profile': '', 'name': 'changelog-writer'}, hub('skill:update:changelog-writer'), keys=('ok', 'job', 'scan'))
        await check('POST', '/v1/hub/skills/uninstall', {'profile': '', 'name': 'changelog-writer'}, keys=('ok', 'job'))
        await check('GET', '/v1/hub/plugins', keys=('entries', 'categories', 'removed'))
        await check('POST', '/v1/hub/plugins/install', {'name': 'focus-timer'}, hub('plugin:install:focus-timer'), keys=('ok', 'job'))
        await check('POST', '/v1/hub/plugins/update', {'name': 'voice-notes'}, hub('plugin:update:voice-notes'), keys=('ok', 'job'))
        await check('POST', '/v1/hub/plugins/remove', {'name': 'focus-timer'}, hub('plugin:remove:focus-timer'), keys=('ok', 'job'))
        await check('GET', '/v1/hub/mcp', keys=('entries',))
        await check('POST', '/v1/hub/mcp/install', {'name': 'notes-search', 'env': {}}, hub('mcp:install:notes-search'), keys=('ok', 'job'))
        await check('POST', '/v1/hub/mcp/uninstall', {'name': 'notes-search'}, hub('mcp:remove:notes-search'), keys=('ok', 'job'))
        await check('GET', '/v1/wake?tz=UTC', keys=('config', 'next', 'session', 'level', 'level_max'))
        await check('PUT', '/v1/wake/config', {'alarms': [{'time': '07:00', 'on': True, 'days': []}], 'contacts': [], 'owner_phone': '+15555550100'},
                    keys=('config', 'next', 'session'))
        await check('GET', '/v1/wake/session', keys=('session', 'server_now'))
        await check('POST', '/v1/wake/fired', {'alarm_id': ''}, keys=('session',))
        await check('POST', '/v1/wake/test', {}, keys=('session',))
        await check('POST', '/v1/wake/proof', {'kind': 'steps', 'sid': '', 'steps': []}, keys=('ok', 'reason', 'session'))
        await check('POST', '/v1/wake/cancel', {}, keys=('ok',))
        await check('POST', '/v1/wake/override', {'password': 'x'}, keys=('ok', 'reason'))
        await check('POST', '/v1/wake/password', {'old': 'x', 'new': 'yyyyyyyy'}, keys=('ok', 'reason'))
        await check('POST', '/v1/wake/whatsapp-test', {}, keys=('ok', 'error'))
        for view in ('current', 'saved', 'history'):
            await check('GET', '/v1/radar?view=' + view, keys=('findings', 'notes', 'batch', 'scout'))
        await check('GET', '/v1/radar', keys=('findings',))
        await check('GET', '/v1/radar/findings/f-sandbox-01', keys=('finding',))
        await check('POST', '/v1/radar/findings/f-sandbox-01/dismiss', {}, keys=('finding',))
        await check('POST', '/v1/radar/findings/f-sandbox-01/feedback', {'text': 'ok'}, keys=('finding',))
        await check('POST', '/v1/radar/findings/f-sandbox-01/spawn', {'mode': 'task', 'prompt': 'x'}, keys=('ok', 'task_id'))
        await check('GET', '/v1/radar/notes/n-sandbox-01', keys=('note',))
        await check('POST', '/v1/radar/notes/n-sandbox-01/ack', {}, keys=('ok',))
        await check('GET', '/v1/radar/momentum', keys=('available', 'report', 'fetched_at', 'stale'))
        await check('GET', '/v1/radar/momentum?refresh=1', keys=('report',))
        await check('GET', '/v1/fleet', keys=('agents', 'counts', 'events'))
        await check('GET', '/v1/fleet?recent=1', keys=('agents',))
        await check('GET', '/v1/fleet/kitty%3Adocs/tail?n=200', keys=('lines', 'terminal'))
        await check('POST', '/v1/fleet/kitty%3Adocs/send', {'text': 'go ahead'}, keys=('ok', 'line'))
        await check('POST', '/v1/fleet/tmux%3Abuild/stop', {}, keys=('ok', 'line'))
        key_in = json.dumps({'action': 'key', 'key': 'enter', 'shift': False, 'ctrl': False, 'alt': False}).encode()
        await check('POST', '/v1/fleet/kitty%3Adocs/input', raw=key_in, keys=('ok',),
                    amsg=lambda ts: f"fleet-input\nkitty:docs\n{hashlib.sha256(key_in).hexdigest()}\n{ts}".encode())
        await check('GET', '/v1/projects', keys=('repos',))
        await check('GET', '/v1/projects/example-site', keys=('status', 'ship'))
        await check('GET', '/v1/projects/example-site/diff?file=src%2Ftheme.css', keys=('diff',))
        await check('POST', '/v1/projects/example-site/stage', {}, keys=('ok', 'result'))
        await check('POST', '/v1/projects/example-site/unstage', {'file': 'src/theme.css'}, keys=('ok', 'result'))
        await check('POST', '/v1/projects/example-site/commit', {'message': 'm'}, keys=('ok', 'result'))
        await check('POST', '/v1/projects/example-site/push', {}, lambda ts: f'git\nexample-site\npush\n{ts}'.encode(), keys=('ok', 'result'))
        await check('POST', '/v1/projects/example-site/create-pr', {}, lambda ts: f'git\nexample-site\ncreate-pr\n{ts}'.encode(), keys=('ok', 'result'))
        await check('GET', '/v1/push-config', keys=('enabled', 'config'))
        await check('POST', '/v1/fcm', {'token': 'discarded'}, keys=('ok', 'push'))
        await check('GET', '/v1/devices', keys=('devices', 'this'))
        await check('DELETE', '/v1/devices/' + self.dev['device_id'], keys=('ok',))
        self.assertEqual(problems, [])
        # Every client call (except pairing and the event stream, which have their own tests) was exercised above.
        wanted = set()
        for calls in CLIENT_CALLS.values():
            for method, path in calls:
                match = await app.router.resolve(make_mocked_request(method, path, app=app))
                wanted.add((method, match.route.resource.canonical))
        self.assertEqual(sorted(wanted - hit - {('POST', '/v1/pair'), ('GET', '/v1/events')}), [])

    async def test_client_calls_are_all_served_with_their_methods(self):
        """Lexes the Play Kotlin sources and pins every API string template; each listed call resolves to a handler."""
        found = {}
        for src in ('android/app/src/main/java', 'android/app/src/play/java'):
            for f in sorted((REPO / src).rglob('*.kt')):
                for s in kotlin_strings(f.read_text()):
                    if s.startswith('/v1') or s.startswith('{base}'):
                        found.setdefault(s, set()).add(f.name)
        self.assertEqual(sorted(set(found) - set(CLIENT_CALLS)), [], 'new client API templates: list their calls in CLIENT_CALLS')
        self.assertEqual(sorted(set(CLIENT_CALLS) - set(found)), [], 'templates no longer in the client: drop them from CLIENT_CALLS')
        app = self.backend.app()
        unserved = []
        for template, calls in CLIENT_CALLS.items():
            for method, path in calls:
                match = await app.router.resolve(make_mocked_request(method, path, app=app))
                if match.http_exception is not None or match.handler == self.backend.unsupported:
                    unserved.append((template, method, path))
        self.assertEqual(unserved, [])
        # The guard itself must catch what a prefix heuristic missed: nested quotes and the session screen's base.
        self.assertEqual(kotlin_strings('Api.get("/v1/radar/notes/${enc(n.str("id"))}/ack") // "/v1/no"'), ['id', '/v1/radar/notes/{}/ack'])
        self.assertIn('{base}/viewer', found)

    def test_sandbox_copies_match_production(self):
        """The sandbox mirrors production rules it cannot import: guard regex, curated settings, terminal keys."""
        tree = ast.parse((REPO / 'server/mobile/control.py').read_text())
        guarded = next(n for n in tree.body if isinstance(n, ast.Assign) and getattr(n.targets[0], 'id', '') == '_GUARDED')
        self.assertEqual(ast.literal_eval(guarded.value.args[0]), server.C.GUARDED.pattern)
        groups = next(n for n in tree.body if isinstance(n, ast.AnnAssign) and getattr(n.target, 'id', '') == 'GROUPS')
        prod_keys = [c.args[0].value for c in ast.walk(groups.value) if isinstance(c, ast.Call) and getattr(c.func, 'id', '') == '_f']
        self.assertEqual(prod_keys, [f['key'] for g in server.C.GROUPS for f in g['fields']])
        prod_tabs = [ast.literal_eval(k) for d in groups.value.elts for k, v in zip(d.keys, d.values) if ast.literal_eval(k) == 'tab']
        self.assertEqual(len(prod_tabs), len(server.C.GROUPS))
        term = ast.parse((REPO / 'server/mobile/terminal.py').read_text())
        keys = next(n for n in term.body if isinstance(n, ast.Assign) and getattr(n.targets[0], 'id', '') == 'KEYS')
        scope = {'__builtins__': {}, 'frozenset': frozenset, 'str': str, 'range': range}
        self.assertEqual(eval(compile(ast.Expression(keys.value), 'terminal.py', 'eval'), scope), server.C.TERMINAL_KEYS)


if __name__ == '__main__':
    unittest.main(verbosity=2)
