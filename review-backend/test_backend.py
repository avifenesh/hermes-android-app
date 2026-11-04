"""Real loopback HTTP, production P-256 signing and SQLite. No live Hermes access."""
import ast
import asyncio
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import time
import unittest
from urllib.parse import urlsplit, parse_qs, quote

from aiohttp import ClientSession, web
from yarl import URL
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, utils

spec = importlib.util.spec_from_file_location('review_backend', Path(__file__).with_name('server.py'))
server = importlib.util.module_from_spec(spec)
spec.loader.exec_module(server)
REPO = Path(__file__).resolve().parents[1]

# Every API path the Play build can reach (android/app/src/main + src/play), from the client inventory.
PLAY_API = [
    ('GET', '/v1/health'), ('POST', '/v1/pair'), ('GET', '/v1/events'),
    ('GET', '/v1/me'), ('GET', '/v1/sync'), ('GET', '/v1/commands'), ('GET', '/v1/profiles'),
    ('GET', '/v1/chats'), ('POST', '/v1/chats'), ('PATCH', '/v1/chats/{cid}'), ('GET', '/v1/chats/{cid}/messages'),
    ('POST', '/v1/chats/{cid}/messages'), ('POST', '/v1/chats/{cid}/read'), ('POST', '/v1/chats/{cid}/stop'),
    ('GET', '/v1/chats/{cid}/run'), ('GET', '/v1/chats/{cid}/usage'), ('GET', '/v1/chats/{cid}/model'),
    ('POST', '/v1/chats/{cid}/model'), ('POST', '/v1/chats/{cid}/confirm'), ('POST', '/v1/chats/{cid}/session'),
    ('GET', '/v1/activity/{mid}'), ('POST', '/v1/approvals/{aid}'), ('POST', '/v1/upload'), ('GET', '/v1/media/{name}'),
    ('POST', '/v1/tts'), ('GET', '/v1/models'), ('GET', '/v1/pulse'), ('GET', '/v1/usage'), ('GET', '/v1/search'),
    ('GET', '/v1/sessions'), ('GET', '/v1/sessions/{sid}/messages'), ('PUT', '/v1/sessions/{sid}/messages'),
    ('PUT', '/v1/sessions/{sid}/prompts/{pid}'), ('POST', '/v1/sessions/{sid}/stop'), ('DELETE', '/v1/sessions/{sid}/viewer'),
    ('GET', '/v1/cron'), ('POST', '/v1/cron/{jid}/{action}'), ('GET', '/v1/cron/{jid}/output'), ('GET', '/v1/cronjob/{jid}'),
    ('POST', '/v1/cronjob'), ('PUT', '/v1/cronjob/{jid}'), ('DELETE', '/v1/cronjob/{jid}'), ('GET', '/v1/cron-delivery'),
    ('POST', '/v1/cron-delivery'), ('GET', '/v1/memory'), ('POST', '/v1/memory'),
    ('GET', '/v1/settings'), ('GET', '/v1/settings/all'), ('PUT', '/v1/settings'), ('GET', '/v1/skills'), ('GET', '/v1/skill'),
    ('POST', '/v1/skills/toggle'), ('GET', '/v1/plugins'), ('POST', '/v1/plugins/toggle'), ('GET', '/v1/mcp'), ('POST', '/v1/restart'),
    ('GET', '/v1/hub/skills/installed'), ('GET', '/v1/hub/skills/browse'), ('GET', '/v1/hub/skills/search'),
    ('GET', '/v1/hub/skills/updates'), ('GET', '/v1/hub/skills/preview'), ('GET', '/v1/hub/skills/scan'),
    ('POST', '/v1/hub/skills/{action:install|update|uninstall}'), ('GET', '/v1/hub/plugins'),
    ('POST', '/v1/hub/plugins/{action:install|update|remove}'), ('GET', '/v1/hub/mcp'),
    ('POST', '/v1/hub/mcp/{action:install|uninstall}'), ('GET', '/v1/hub/jobs/{jid}'),
    ('GET', '/v1/wake'), ('PUT', '/v1/wake/config'), ('GET', '/v1/wake/session'), ('POST', '/v1/wake/fired'),
    ('POST', '/v1/wake/proof'), ('POST', '/v1/wake/test'), ('POST', '/v1/wake/cancel'), ('POST', '/v1/wake/override'),
    ('POST', '/v1/wake/password'), ('POST', '/v1/wake/whatsapp-test'),
    ('GET', '/v1/radar'), ('GET', '/v1/radar/findings/{fid}'), ('POST', '/v1/radar/findings/{fid}/{action}'),
    ('GET', '/v1/radar/notes/{nid}'), ('POST', '/v1/radar/notes/{nid}/ack'), ('GET', '/v1/radar/momentum'),
    ('GET', '/v1/fleet'), ('GET', '/v1/fleet/{aid}/tail'), ('POST', '/v1/fleet/{aid}/send'), ('POST', '/v1/fleet/{aid}/stop'),
    ('POST', '/v1/fleet/{aid}/input'), ('GET', '/v1/projects'), ('GET', '/v1/projects/{rid}'), ('GET', '/v1/projects/{rid}/diff'),
    ('POST', '/v1/projects/{rid}/{action}'), ('GET', '/v1/devices'), ('DELETE', '/v1/devices/{did}'),
    ('GET', '/v1/push-config'), ('POST', '/v1/fcm'),
]


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
        results = await asyncio.gather(*(self.raw('GET', '/v1/me', headers=(h2 := self.headers('GET', '/v1/me'))) for _ in range(2)))
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
        from unittest.mock import patch
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
        self.assertTrue(d['submission_ready'])
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
        status, d = await self.call('POST', '/v1/hub/plugins/install', {'name': 'voice-notes'}, amsg=guard('hub', 'plugin:install:voice-notes', True))
        self.assertEqual(status, 200)
        _, j = await self.call('GET', '/v1/hub/jobs/' + d['job']['id'])
        self.assertTrue(j['job']['result']['restart_required'])
        p = {x['name']: x for x in (await self.call('GET', '/v1/plugins'))[1]['plugins']}['voice-notes']
        self.assertTrue(p['pending'])
        await self.call('POST', '/v1/restart')
        e = {x['name']: x for x in (await self.call('GET', '/v1/hub/plugins'))[1]['entries']}['voice-notes']
        self.assertEqual((e['installed'], e['runtime_status']), (True, 'enabled'))
        # Simulate a newer catalogue sha so the update path (and its capability consent) is reachable.
        s = self.tenant()
        hub = s.kv_get('hub')
        hub['plugins']['voice-notes']['sha'] = '0' * 40
        s.kv_put('hub', hub)
        e = {x['name']: x for x in (await self.call('GET', '/v1/hub/plugins'))[1]['entries']}['voice-notes']
        self.assertTrue(e['update_available'])
        status, d = await self.call('POST', '/v1/hub/plugins/update', {'name': 'voice-notes'}, amsg=guard('hub', 'plugin:update:voice-notes', True))
        _, j = await self.call('GET', '/v1/hub/jobs/' + d['job']['id'])
        self.assertEqual((j['job']['state'], j['job']['result']['delta_lines']), ('consent', ['tools: memo_delete']))
        status, d = await self.call('POST', '/v1/hub/plugins/update', {'name': 'voice-notes', 'accept_capabilities': True},
                                    amsg=guard('hub', 'plugin:update:voice-notes:accept', True))
        self.assertEqual((await self.call('GET', '/v1/hub/jobs/' + d['job']['id']))[1]['job']['state'], 'ok')
        status, d = await self.call('POST', '/v1/hub/plugins/remove', {'name': 'voice-notes'}, amsg=guard('hub', 'plugin:remove:voice-notes', True))
        await self.call('GET', '/v1/hub/jobs/' + d['job']['id'])
        self.assertNotIn('voice-notes', [x['name'] for x in (await self.call('GET', '/v1/plugins'))[1]['plugins']])
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
        w = s.kv_get('wake')
        w['session']['done'] = time.time() - 60
        s.kv_put('wake', w)
        _, ss = await self.call('GET', '/v1/wake/session')
        self.assertIsNone(ss['session'])
        _, d = await self.call('GET', '/v1/chats/wake/messages')
        self.assertIn('awake', d['messages'][-1]['content'])
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

    # ---------------------------------------------------------------- coverage and drift guards
    async def test_every_play_reachable_api_answers(self):
        """Red before this change: most of these returned 501. Every Play-reachable path must answer for real."""
        _, sync = await self.call('GET', '/v1/sync?since=0')
        aid = sync['approvals'][0]['id']
        card = next((m['id'] for m in sync['messages'] if m['kind'] == 'activity'), 'm-none')
        p, data = '/v1/upload?name=a.txt&mime=text%2Fplain', b'synthetic'
        _, up = await self.raw('POST', p, data, self.headers('POST', p, data))
        hub = lambda key: guard('hub', key, True)
        steps = [
            ('GET', '/v1/me', None, None), ('GET', '/v1/sync?since=0', None, None), ('GET', '/v1/commands', None, None),
            ('GET', '/v1/profiles', None, None), ('GET', '/v1/chats', None, None), ('GET', '/v1/chats?archived=1', None, None),
            ('POST', '/v1/chats', {'title': 'Coverage', 'profile': 'research'}, None), ('PATCH', '/v1/chats/c-planning', {'pinned': 1}, None),
            ('GET', '/v1/chats/c-planning/messages?limit=150', None, None), ('POST', '/v1/chats/c-planning/messages', {'text': 'hi', 'mode': 'send'}, None),
            ('POST', '/v1/chats/c-planning/read', {}, None), ('POST', '/v1/chats/c-planning/stop', {}, None),
            ('GET', '/v1/chats/c-planning/run', None, None), ('GET', '/v1/chats/c-planning/usage', None, None),
            ('GET', '/v1/chats/_new/model', None, None), ('GET', '/v1/chats/c-planning/model', None, None),
            ('POST', '/v1/chats/c-planning/model', {'reasoning': 'low'}, None),
            ('POST', '/v1/chats/c-planning/confirm', {'id': 'cf-none', 'choice': 'cancel'}, None),
            ('POST', '/v1/chats/c-planning/session', {'action': 'status', 'arg': '', 'confirmed': False}, None),
            ('GET', f'/v1/activity/{card}', None, None), ('POST', f'/v1/approvals/{aid}', {'choice': 'deny'}, None),
            ('GET', '/v1/media/' + up['id'], None, None), ('POST', '/v1/tts', {'text': 'hello'}, None),
            ('GET', '/v1/models', None, None), ('GET', '/v1/models?refresh=1', None, None), ('GET', '/v1/pulse', None, None),
            ('GET', '/v1/usage?days=30', None, None), ('GET', '/v1/search?q=synthetic', None, None), ('GET', '/v1/sessions', None, None),
            ('GET', '/v1/sessions/s-cli-release/messages?limit=40&refresh=1', None, None),
            ('PUT', '/v1/sessions/s-cli-release/messages', {'text': 'hello', 'submission_id': 'c-1'}, None),
            ('PUT', '/v1/sessions/s-cli-release/prompts/p-clarify', {'kind': 'clarify', 'generation': 1, 'answer': 'Both', 'question_id': ''}, None),
            ('POST', '/v1/sessions/s-cli-release/stop', {'generation': 2}, None), ('DELETE', '/v1/sessions/s-cli-release/viewer', None, None),
            ('GET', '/v1/cron', None, None), ('POST', '/v1/cron/demo-digest/pause', {}, None), ('POST', '/v1/cron/demo-digest/resume', {}, None),
            ('POST', '/v1/cron/demo-digest/run', {}, None), ('GET', '/v1/cron/demo-digest/output', None, None),
            ('GET', '/v1/cronjob/demo-digest', None, None),
            ('POST', '/v1/cronjob', {'name': 'C', 'schedule': 'every 2h', 'prompt': 'p', 'deliver': 'mobile', 'skills': [], 'model': '', 'provider': '', 'reasoning_effort': ''}, None),
            ('PUT', '/v1/cronjob/demo-weekly', {'name': 'W', 'schedule': 'every monday 9am', 'prompt': 'p', 'deliver': 'mobile', 'skills': [], 'model': '', 'provider': '', 'reasoning_effort': ''}, None),
            ('GET', '/v1/cron-delivery', None, None), ('POST', '/v1/cron-delivery', {'deliver': 'mobile'}, None),
            ('DELETE', '/v1/cronjob/demo-weekly', None, None),
            ('GET', '/v1/memory', None, None), ('POST', '/v1/memory', {'target': 'user', 'action': 'add', 'content': 'c'}, None),
            ('GET', '/v1/settings', None, None), ('GET', '/v1/settings/all', None, None),
            ('PUT', '/v1/settings', {'key': 'agent.max_turns', 'value': '12'}, None),
            ('PUT', '/v1/settings', {'key': 'terminal.timeout', 'value': '60'}, guard('config', 'terminal.timeout', '60')),
            ('GET', '/v1/skills', None, None), ('GET', '/v1/skill?name=meeting-notes', None, None),
            ('POST', '/v1/skills/toggle', {'name': 'meeting-notes', 'enabled': False}, None), ('GET', '/v1/plugins', None, None),
            ('POST', '/v1/plugins/toggle', {'name': 'notes-export', 'enabled': False}, guard('plugin', 'notes-export', False)),
            ('GET', '/v1/mcp', None, None), ('POST', '/v1/restart', {}, None),
            ('GET', '/v1/hub/skills/installed', None, None), ('GET', '/v1/hub/skills/browse', None, None),
            ('GET', '/v1/hub/skills/search?q=brief&source=all&limit=50', None, None), ('GET', '/v1/hub/skills/updates', None, None),
            ('GET', '/v1/hub/skills/preview?identifier=official%2Fproductivity%2Fdaily-brief', None, None),
            ('GET', '/v1/hub/skills/scan?identifier=official%2Fproductivity%2Fdaily-brief', None, None),
            ('POST', '/v1/hub/skills/install', {'profile': '', 'identifier': 'official/productivity/daily-brief'}, hub('skill:install:official/productivity/daily-brief')),
            ('POST', '/v1/hub/skills/update', {'profile': '', 'name': 'changelog-writer'}, hub('skill:update:changelog-writer')),
            ('POST', '/v1/hub/skills/uninstall', {'profile': '', 'name': 'changelog-writer'}, None),
            ('GET', '/v1/hub/plugins', None, None), ('POST', '/v1/hub/plugins/install', {'name': 'focus-timer'}, hub('plugin:install:focus-timer')),
            ('POST', '/v1/hub/plugins/update', {'name': 'focus-timer'}, hub('plugin:update:focus-timer')),
            ('POST', '/v1/hub/plugins/remove', {'name': 'focus-timer'}, hub('plugin:remove:focus-timer')),
            ('GET', '/v1/hub/mcp', None, None), ('POST', '/v1/hub/mcp/install', {'name': 'notes-search', 'env': {}}, hub('mcp:install:notes-search')),
            ('POST', '/v1/hub/mcp/uninstall', {'name': 'notes-search'}, hub('mcp:remove:notes-search')),
            ('GET', '/v1/wake?tz=UTC', None, None),
            ('PUT', '/v1/wake/config', {'alarms': [{'time': '07:00', 'on': True, 'days': []}], 'contacts': [], 'owner_phone': '+15555550100'}, None),
            ('GET', '/v1/wake/session', None, None), ('POST', '/v1/wake/fired', {'alarm_id': ''}, None), ('POST', '/v1/wake/test', {}, None),
            ('POST', '/v1/wake/proof', {'kind': 'steps', 'sid': '', 'steps': []}, None), ('POST', '/v1/wake/cancel', {}, None),
            ('POST', '/v1/wake/override', {'password': 'x'}, None), ('POST', '/v1/wake/password', {'old': 'x', 'new': 'yyyyyyyy'}, None),
            ('POST', '/v1/wake/whatsapp-test', {}, None),
            ('GET', '/v1/radar?view=current', None, None), ('GET', '/v1/radar?view=saved', None, None), ('GET', '/v1/radar?view=history', None, None),
            ('GET', '/v1/radar/findings/f-sandbox-01', None, None), ('POST', '/v1/radar/findings/f-sandbox-01/dismiss', {}, None),
            ('POST', '/v1/radar/findings/f-sandbox-01/spawn', {'mode': 'task', 'prompt': 'x'}, None),
            ('GET', '/v1/radar/notes/n-sandbox-01', None, None), ('POST', '/v1/radar/notes/n-sandbox-01/ack', {}, None),
            ('GET', '/v1/radar/momentum', None, None), ('GET', '/v1/radar/momentum?refresh=1', None, None),
            ('GET', '/v1/fleet', None, None), ('GET', '/v1/fleet?recent=1', None, None),
            ('GET', '/v1/fleet/kitty%3Adocs/tail?n=200', None, None), ('POST', '/v1/fleet/kitty%3Adocs/send', {'text': 'go ahead'}, None),
            ('POST', '/v1/fleet/tmux%3Abuild/stop', {}, None),
            ('GET', '/v1/projects', None, None), ('GET', '/v1/projects/example-site', None, None),
            ('GET', '/v1/projects/example-site/diff?file=src%2Ftheme.css', None, None),
            ('POST', '/v1/projects/example-site/stage', {}, None), ('POST', '/v1/projects/example-site/unstage', {'file': 'src/theme.css'}, None),
            ('POST', '/v1/projects/example-site/commit', {'message': 'm'}, None),
            ('POST', '/v1/projects/example-site/push', {}, lambda ts: f'git\nexample-site\npush\n{ts}'.encode()),
            ('POST', '/v1/projects/example-site/create-pr', {}, lambda ts: f'git\nexample-site\ncreate-pr\n{ts}'.encode()),
            ('GET', '/v1/push-config', None, None), ('POST', '/v1/fcm', {'token': 'discarded'}, None), ('GET', '/v1/devices', None, None),
        ]
        bad = []
        for method, path, body, amsg in steps:
            status, d = await self.call(method, path, body, amsg=amsg)
            if status == 501 or status >= 500 or status in (401, 403):
                bad.append((method, path, status))
        raw = json.dumps({'action': 'key', 'key': 'enter', 'shift': False, 'ctrl': False, 'alt': False}).encode()
        path = '/v1/fleet/kitty%3Adocs/input'
        status, _ = await self.raw('POST', path, raw, self.headers('POST', path, raw, amsg=lambda ts: f"fleet-input\nkitty:docs\n{hashlib.sha256(raw).hexdigest()}\n{ts}".encode()))
        if status != 200:
            bad.append(('POST', path, status))
        status, _ = await self.call('DELETE', '/v1/devices/' + self.dev['device_id'])
        if status != 200:
            bad.append(('DELETE', '/v1/devices/{did}', status))
        self.assertEqual(bad, [])

    def test_client_calls_are_all_in_the_play_inventory(self):
        """Every /v1 literal in the Play build's Kotlin sources maps to an inventoried, served path."""
        def norm(p):
            if '${' in p and '}' not in p[p.index('${'):]:
                p = p[:p.index('${')] + '{}'
            p = re.sub(r'\$\{[^}]*\}', '{}', p)
            p = re.sub(r'\$[A-Za-z_]\w*', '{}', p)
            return p.split('?')[0]
        templates = {re.sub(r'\{[^}]*\}', '{}', t) for _, t in PLAY_API}
        literals = set()
        for src in ('android/app/src/main/java', 'android/app/src/play/java'):
            for f in (REPO / src).rglob('*.kt'):
                literals.update(norm(m) for m in re.findall(r'"(/v1/[^"]*)', f.read_text()))
        self.assertGreater(len(literals), 60)
        def covered(lit):
            ls = lit.rstrip('/').split('/')
            return any(len(ls) <= len(ts) and all(a == b or b == '{}' for a, b in zip(ls, ts)) and (len(ls) == len(ts) or lit.endswith('/') or ls[-1] == '{}')
                       for ts in (t.split('/') for t in templates))
        missing = sorted(l for l in literals if not covered(l))
        self.assertEqual(missing, [])
        backend = server.Backend(self.home, 'http://127.0.0.1:47941')
        served = {(m, t) for m, t, _ in getattr(backend, 'routes', list)()} | {('GET', '/v1/health'), ('POST', '/v1/pair'), ('GET', '/v1/events')}
        self.assertEqual(sorted(set(PLAY_API) - served), [])
        backend.auth._db.close()

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
