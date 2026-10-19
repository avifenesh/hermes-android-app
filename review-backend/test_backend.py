"""Real loopback HTTP, production P-256 signing and SQLite. No live Hermes access."""
import asyncio
import base64
import importlib.util
import json
import os
from pathlib import Path
import shutil
import tempfile
import time
import unittest
from urllib.parse import urlsplit, parse_qs

from aiohttp import ClientSession, web
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, utils

spec = importlib.util.spec_from_file_location('review_backend', Path(__file__).with_name('server.py'))
server = importlib.util.module_from_spec(spec)
spec.loader.exec_module(server)


def pub(key):
    return base64.b64encode(key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()


def sign(key, msg):
    return base64.b64encode(key.sign(msg, ec.ECDSA(hashes.SHA256()))).decode()


class ReviewTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        os.umask(0o077)
        server.BASE.mkdir(parents=True, exist_ok=True)
        self.home = Path(tempfile.mkdtemp(prefix='review-test-', dir=server.BASE))
        self.key, self.apr = ec.generate_private_key(ec.SECP256R1()), ec.generate_private_key(ec.SECP256R1())
        self.backend = server.Backend(self.home, 'http://127.0.0.1:47941')
        self.runner = web.AppRunner(self.backend.app(), access_log=None, shutdown_timeout=1)
        await self.runner.setup()
        site = web.TCPSite(self.runner, '127.0.0.1', 0)
        await site.start()
        self.url = 'http://127.0.0.1:' + str(site._server.sockets[0].getsockname()[1])
        self.http = ClientSession()
        self.dev = await self.enroll()

    async def asyncTearDown(self):
        await self.http.close()
        await self.runner.cleanup()
        shutil.rmtree(self.home)

    async def raw(self, method, path, body=b'', headers=None):
        async with self.http.request(method, self.url + path, data=body, headers=headers or {}) as r:
            data = await r.read()
            try:
                data = json.loads(data)
            except (ValueError, UnicodeDecodeError):
                pass
            return r.status, data

    async def enroll(self, key=None, apr=None, code=None):
        if code is None:
            status, d = await self.raw('POST', '/review/enroll', headers={'Authorization': 'Bearer ' + self.backend.invitation})
            self.assertEqual(status, 200)
            code = parse_qs(urlsplit(d['link']).query)['c'][0]
        status, d = await self.raw('POST', '/v1/pair', json.dumps({'code': code, 'pub': pub(key or self.key), 'apub': pub(apr or self.apr), 'lock': True, 'device_name': 'Synthetic reviewer'}).encode(), {'Content-Type': 'application/json'})
        self.assertEqual(status, 200)
        return d

    def headers(self, method, path, body=b'', dev=None, key=None, approval=None, ts=None):
        ts = str(ts if ts is not None else int(time.time()))
        h = {'Authorization': 'Bearer ' + (dev or self.dev)['token'], 'X-HM-Ts': ts,
             'X-HM-Sig': sign(key or self.key, server.SG.canonical(method, path, ts, body)), 'Content-Type': 'application/json'}
        if approval:
            aid, choice = approval
            h['X-HM-ASig'] = sign(self.apr, f'approve\n{aid}\n{choice}\n{ts}'.encode())
        return h

    async def call(self, method, path, body=None, dev=None, key=None, approval=None):
        data = json.dumps(body).encode() if body is not None else b''
        return await self.raw(method, path, data, self.headers(method, path, data, dev, key, approval))

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

    async def test_chat_sync_mutations_and_script_inertness(self):
        _, d = await self.call('GET', '/v1/sync?since=0')
        self.assertEqual(len(d['chats']), 3)
        self.assertGreaterEqual(len(d['messages']), 7)
        seq = d['seq']
        status, d = await self.call('POST', '/v1/chats', {'title': 'New example'})
        self.assertEqual(status, 200)
        cid = d['chat']['id']
        for key, value in [('title', 'Renamed'), ('pinned', 1), ('archived', 1)]:
            status, _ = await self.call('PATCH', '/v1/chats/' + cid, {key: value})
            self.assertEqual(status, 200)
            _, d = await self.call('GET', '/v1/chats?archived=1')
            self.assertEqual(next(c for c in d['chats'] if c['id'] == cid)[key], value)
        marker = self.home / 'SHOULD_NOT_EXIST'
        text = '/terminal touch ' + str(marker)
        _, sent = await self.call('POST', f'/v1/chats/{cid}/messages', {'text': text})
        _, d = await self.call('GET', f'/v1/chats/{cid}/messages')
        self.assertEqual(d['messages'][0]['content'], text)
        self.assertIn('Synthetic demo reply', d['messages'][1]['content'])
        self.assertFalse(marker.exists())
        _, d = await self.call('GET', f'/v1/sync?since={seq}')
        self.assertIn(sent['message']['id'], [m['id'] for m in d['messages']])
        self.assertEqual((await self.call('POST', f'/v1/chats/{cid}/read'))[0], 200)
        self.assertEqual((await self.call('GET', f'/v1/chats/{cid}/run'))[1]['working'], False)
        self.assertEqual((await self.call('POST', f'/v1/chats/{cid}/stop'))[0], 200)

    async def test_memory_cron_and_read_surfaces(self):
        for action, content, old in [('add', 'Synthetic note', ''), ('replace', 'Updated note', 'Synthetic note'), ('remove', '', 'Updated note')]:
            status, _ = await self.call('POST', '/v1/memory', {'target': 'memory', 'action': action, 'content': content, 'old_text': old})
            self.assertEqual(status, 200)
            _, d = await self.call('GET', '/v1/memory')
            self.assertEqual(content in d['memory']['entries'], action != 'remove')
        for action, enabled in [('pause', False), ('resume', True)]:
            self.assertEqual((await self.call('POST', '/v1/cron/demo-digest/' + action))[0], 200)
            self.assertEqual((await self.call('GET', '/v1/cron'))[1]['jobs'][0]['enabled'], enabled)
        _, before = await self.call('GET', '/v1/chats/c-digest/messages')
        await self.call('POST', '/v1/cron/demo-digest/run')
        _, after = await self.call('GET', '/v1/chats/c-digest/messages')
        self.assertEqual(len(after['messages']), len(before['messages']) + 1)
        for path, key in [('/v1/me', 'security'), ('/v1/commands', 'commands'), ('/v1/profiles', 'profiles'), ('/v1/sessions', 'sessions'), ('/v1/sessions/c-welcome/messages', 'messages'), ('/v1/search?q=synthetic', 'results'), ('/v1/pulse', 'spend_7d'), ('/v1/cron/demo-digest/output', 'text'), ('/v1/chats/c-welcome/usage', 'session')]:
            status, d = await self.call('GET', path)
            self.assertEqual(status, 200, path)
            self.assertIn(key, d, path)
        self.assertEqual((await self.call('GET', '/v1/push-config'))[1]['enabled'], False)
        self.assertEqual((await self.call('POST', '/v1/fcm', {'token': 'synthetic-only'}))[1]['push'], False)

    async def test_biometric_approval_proof(self):
        _, d = await self.call('GET', '/v1/sync')
        aid = d['approvals'][0]['id']
        p = '/v1/approvals/' + aid
        self.assertEqual((await self.call('POST', p, {'choice': 'once'}))[0], 403)
        self.assertEqual((await self.call('POST', p, {'choice': 'once'}, approval=(aid, 'deny')))[0], 403)
        status, d = await self.call('POST', p, {'choice': 'once'}, approval=(aid, 'once'))
        self.assertEqual(status, 200)
        self.assertEqual(d['approval']['state'], 'approved')
        _, d = await self.call('GET', '/v1/sync')
        self.assertEqual(d['approvals'][0]['state'], 'approved')
        self.assertEqual((await self.call('POST', p, {'choice': 'once'}, approval=(aid, 'once')))[0], 409)

    async def test_upload_download_tenant_and_path_boundaries(self):
        p, data = '/v1/upload?name=example.txt&mime=text/plain', b'Synthetic upload only'
        status, d = await self.raw('POST', p, data, self.headers('POST', p, data))
        self.assertEqual(status, 200)
        name = d['id']
        self.assertEqual((await self.call('GET', '/v1/media/' + name))[1], data)
        key = ec.generate_private_key(ec.SECP256R1())
        other = await self.enroll(key)
        self.assertEqual((await self.call('GET', '/v1/media/' + name, dev=other, key=key))[0], 404)
        self.assertEqual((await self.call('GET', '/v1/media/auth.db'))[0], 404)
        self.assertEqual((await self.call('GET', '/v1/media/checklist.txt'))[1], self.backend.sample_file())
        data = b'x' * (1024 * 1024 + 1)
        self.assertEqual((await self.raw('POST', p, data, self.headers('POST', p, data)))[0], 413)

    async def test_sse_and_revocation(self):
        h = self.headers('GET', '/v1/events')
        async with self.http.get(self.url + '/v1/events', headers=h) as r:
            self.assertEqual(r.status, 200)
            line = await asyncio.wait_for(r.content.readline(), 2)
            self.assertIn(b'"type": "hello"', line)
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
        with patch.object(self.backend.auth, 'claim_signature', side_effect=server.sqlite3.OperationalError('synthetic failure')):
            self.assertEqual((await self.call('POST', '/v1/chats', {'title': 'Must never create'}))[0], 503)
        _, d = await self.call('GET', '/v1/chats')
        self.assertNotIn('Must never create', [c['title'] for c in d['chats']])
        await self.runner.cleanup()
        self.backend = server.Backend(self.home, 'http://127.0.0.1:47941')
        self.runner = web.AppRunner(self.backend.app(), access_log=None, shutdown_timeout=1)
        await self.runner.setup()
        site = web.TCPSite(self.runner, '127.0.0.1', 0)
        await site.start()
        self.url = 'http://127.0.0.1:' + str(site._server.sockets[0].getsockname()[1])
        self.assertEqual((await self.raw('GET', p, headers=headers))[0], 401)
        _, d = await self.call('GET', '/v1/memory')
        self.assertIn('Persists across restart', d['memory']['entries'])
        self.assertEqual(len((await self.call('GET', '/v1/chats'))[1]['chats']), 3)

    async def test_explicit_limits_and_no_auth_bypass(self):
        self.assertEqual((await self.raw('GET', '/v1/sync'))[0], 401)
        for p in ['/v1/tts', '/v1/restart', '/v1/settings', '/v1/wake', '/v1/hub/skills/install', '/v1/fleet/x/input', '/v1/cronjob']:
            status, d = await self.call('POST', p, {'command': 'id'})
            self.assertEqual(status, 501, p)
            self.assertIn('Unsupported', d['error'])
        _, d = await self.call('GET', '/v1/review/capabilities')
        self.assertFalse(d['submission_ready'])
        self.assertGreater(len(d['unsupported']), 5)
        for _ in range(41):
            status, _ = await self.raw('POST', '/review/enroll')
        self.assertEqual(status, 429)
        with self.assertRaises(ValueError):
            server.state_root(Path.home() / '.hermes/mobile')


if __name__ == '__main__':
    unittest.main(verbosity=2)
