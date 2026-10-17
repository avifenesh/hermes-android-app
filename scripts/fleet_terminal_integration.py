"""Self-isolated Fleet terminal integration. Real aiohttp/P-256/SQLite + Xvfb Kitty.
Run: ~/.hermes/hermes-agent/venv/bin/python scripts/fleet_terminal_integration.py
No production sessions, gateways, credentials or desktop input are touched.
"""
import os
from pathlib import Path
import sys
import tempfile

SCRATCH = Path.home() / '.hermes/cache/scratch'
SCRATCH.mkdir(parents=True, exist_ok=True)
HOME = tempfile.TemporaryDirectory(prefix='fleet-terminal-', dir=SCRATCH)
os.environ['HERMES_HOME'] = HOME.name
assert Path(HOME.name).resolve() != (Path.home() / '.hermes').resolve()
sys.path.insert(0, str(Path.home() / '.hermes/hermes-agent'))
import hermes_bootstrap  # noqa: E402,F401
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))

import asyncio
import base64
import hashlib
import json
import shutil
import subprocess
import time
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from mobile import adapter as AD, fleet as F, sig as SG, store as S
from mobile.terminal import ANSI, validate_input, process_identity


def public(k):
    return base64.b64encode(k.public_key().public_bytes(serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo)).decode()


def sign(k, msg):
    return base64.b64encode(k.sign(msg, ec.ECDSA(hashes.SHA256()))).decode()


class Routes(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.ad = object.__new__(AD.MobileAdapter)
        self.ad.store = S.Store(Path(HOME.name) / (str(time.time_ns()) + '.db'))
        self.ad._replay = SG.Replay()
        self.key, self.ap = ec.generate_private_key(ec.SECP256R1()), ec.generate_private_key(ec.SECP256R1())
        self.device = self.ad.store.claim_pair_code(self.ad.store.create_pair_code(), 'synthetic', public(self.key), public(self.ap))
        self.fleet = F.Fleet(self.ad)
        self.agent = {'pid': os.getpid(), 'live': True, 'tool': 'claude', 'session': 'fixture'}
        self.fleet.find = AsyncMock(return_value=self.agent)
        self.fleet.terminal.input = AsyncMock(return_value={'ok': True})
        @web.middleware
        async def auth(req, handler):
            return await self.ad._auth_mw(req, handler)
        app = web.Application(middlewares=[auth])
        F.routes(app.router, self.fleet, lambda d, status=200: web.json_response(d, status=status))
        self.client = TestClient(TestServer(app))
        await self.client.start_server()
        self.path = '/v1/fleet/fixture/input'

    async def asyncTearDown(self):
        await self.client.close()
        self.ad.store._db.close()

    def headers(self, body, *, approval=True, guard_body=None, proof=None):
        ts = str(int(time.time()))
        h = {'Authorization': 'Bearer ' + self.device['token'], 'X-HM-Ts': ts,
             'X-HM-Sig': sign(self.key, SG.canonical('POST', self.path, ts, body))}
        if approval:
            msg = f'fleet-input\nfixture\n{hashlib.sha256(body if guard_body is None else guard_body).hexdigest()}\n{ts}'.encode()
            h['X-HM-ASig'] = proof or sign(self.ap, msg)
        return h

    async def post(self, body, **kw):
        h = kw.pop('headers', None) or self.headers(body, **kw)
        async with self.client.post(self.path, data=body, headers=h) as r:
            return r.status, await r.json()

    async def test_forward_all_named_keys_and_modifiers(self):
        from mobile.terminal import KEYS
        for key in sorted(KEYS):
            b = json.dumps({'action': 'key', 'key': key, 'shift': True, 'ctrl': False, 'alt': True}).encode()
            self.assertEqual((await self.post(b))[0], 200)
            self.assertEqual(self.fleet.terminal.input.call_args.args[-1], json.loads(b))

    async def test_unsigned_wrong_body_and_replay(self):
        b = b'{"action":"key","key":"enter"}'
        self.assertEqual((await self.post(b, approval=False))[0], 403)
        self.assertEqual((await self.post(b, guard_body=b'other'))[0], 403)
        h = self.headers(b)
        self.assertEqual((await self.post(b, headers=h))[0], 200)
        self.assertEqual((await self.post(b, headers=h))[0], 401)
        fresh = self.headers(b, proof=h['X-HM-ASig'])
        self.assertEqual((await self.post(b, headers=fresh))[0], 403)
        self.assertEqual(self.fleet.terminal.input.await_count, 1)
        # Replay persists when the Store and replay consumer are recreated.
        p = self.ad.store.path
        self.ad.store._db.close()
        self.ad.store = S.Store(p)
        self.ad._replay = SG.Replay()
        self.assertEqual((await self.post(b, headers=self.headers(b, proof=h['X-HM-ASig'])))[0], 403)

    async def test_malformed(self):
        for b in (b'[]', b'{', b'null', b'{"action":"key","key":"enter","key":"escape"}',
                  b'{"action":"key","key":"\\u001b"}', b'{"action":"key","key":"enter","shift":1}',
                  b'{"action":"scroll","direction":"up","amount":true,"frame":"' + b'a'*64 + b'"}',
                  b'{"action":"click","row":-1,"col":0,"frame":"' + b'a'*64 + b'"}',
                  b'{"action":"key","key":"enter","text":"exec"}'):
            with self.subTest(body=b):
                self.assertEqual((await self.post(b))[0], 400)
        self.fleet.terminal.input.assert_not_awaited()

    async def test_gone_and_readonly(self):
        self.fleet.terminal = F.KittyTerminal()
        b = b'{"action":"key","key":"enter"}'
        self.fleet.find.return_value = None
        self.assertEqual((await self.post(b))[0], 409)
        for a in ({'live': False}, {'live': True, 'pane': '%123'}, {'live': True, 'pid': 999999999}):
            self.fleet.find.return_value = a
            self.assertEqual((await self.post(b))[0], 409)

    async def test_stale_forwarding(self):
        body = {'action': 'click', 'row': 2, 'col': 3, 'frame': 'a'*64}
        self.fleet.terminal.input.return_value = {'ok': False, 'error': 'stale frame'}
        self.assertEqual((await self.post(json.dumps(body).encode()))[0], 409)
        self.assertEqual(self.fleet.terminal.input.call_args.args[-1], body)

    async def test_unicode_ansi(self):
        value = '  A e\u0301 中 🙂  '
        self.assertEqual(ANSI.sub('', '\x1b[38:2:2:3:4m' + value + '\x1b[0m\x1b]8;;url\x1b\\end'), value + 'end')


FIXTURE = r'''
import os,sys,tty,json,signal
from pathlib import Path
tty.setraw(0)
p=Path(sys.argv[1]); p.write_text(json.dumps({'pid':os.getpid()}))
os.write(1, ('\x1b[?1049h\x1b[?1000h\x1b[?1006h\x1b[2J\x1b[H  e\u0301 中 🙂  READY').encode())
while True:
 b=os.read(0,4096)
 with open(sys.argv[2],'a') as f:f.write(b.hex()+'\n')
 if b==b'9': os.write(1,b'\x1b[?1000l\x1b[?1006l\x1b[2J\x1b[HDISABLED')
 if b==b'8': os.write(1,b'\x1b[?1049l\x1b[2J\x1b[HMAIN\r\n'+b'history\r\n'*120)
 if b==b'6': os.write(1,b'\x1b[?1049l\x1b[2J\x1b[HMAIN MOUSE')
 if b==b'7': os.execl('/bin/sh','sh','-c','read ignored')
'''


class RealKitty(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        if not shutil.which('Xvfb') or not shutil.which('kitty'):
            self.skipTest('Kitty/Xvfb not installed')
        self.tmp = tempfile.TemporaryDirectory(dir=HOME.name)
        self.root = Path(self.tmp.name)
        rd, wr = os.pipe()
        self.xvfb = subprocess.Popen(['Xvfb', '-displayfd', str(wr), '-screen', '0', '1024x768x24', '-nolisten', 'tcp'], pass_fds=(wr,), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.addAsyncCleanup(self.cleanup_fixture)
        os.close(wr)
        import select
        if not select.select([rd], [], [], 10)[0]:
            raise RuntimeError('Xvfb startup timed out')
        display = ':' + os.read(rd, 30).decode().strip()
        os.close(rd)
        self.sock = 'unix:' + str(self.root / 'kitty.sock')
        self.pidfile, self.log = self.root / 'pid.json', self.root / 'input.log'
        fixture = self.root / 'claude-fixture.py'
        fixture.write_text(FIXTURE)
        env = {**os.environ, 'DISPLAY': display, 'LIBGL_ALWAYS_SOFTWARE': '1'}
        env.pop('WAYLAND_DISPLAY', None)
        self.err = open(self.root / 'kitty.err', 'w+')
        self.kitty = subprocess.Popen(['kitty', '--config', 'NONE', '--listen-on', self.sock,
             '-o', 'allow_remote_control=yes', '-o', 'linux_display_server=x11',
             '/usr/bin/python3', str(fixture), str(self.pidfile), str(self.log)], env=env,
             stdout=subprocess.DEVNULL, stderr=self.err)
        self.backend = F.KittyTerminal()
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            if self.pidfile.exists():
                self.agent = {'pid': json.loads(self.pidfile.read_text())['pid'], 'live': True}
                p = await asyncio.create_subprocess_exec('kitty', '@', '--to', self.sock, 'ls', stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
                out, _ = await p.communicate()
                if p.returncode == 0:
                    window = json.loads(out)[0]['tabs'][0]['windows'][0]
                    self.target = (self.sock, str(window['id']))
                    snap = await self.backend.snapshot(self.agent, self.target)
                    if snap.get('available') and 'READY' in '\n'.join(snap['lines']):
                        return
                    self.last = snap
            await asyncio.sleep(.1)
        self.err.seek(0)
        raise RuntimeError('disposable Kitty startup: ' + repr(getattr(self, 'last', None)) + self.err.read())

    async def cleanup_fixture(self):
        for name in ('kitty', 'xvfb'):
            p = getattr(self, name, None)
            if p and p.poll() is None:
                p.terminate()
                try: p.wait(timeout=5)
                except subprocess.TimeoutExpired: p.kill(); p.wait(timeout=5)
        if hasattr(self, 'err'): self.err.close()
        if hasattr(self, 'tmp'): self.tmp.cleanup()

    async def snapshot(self):
        s = await self.backend.snapshot(self.agent, self.target)
        self.assertTrue(s.get('available'), s)
        return s

    async def key(self, key, **mods):
        r = await self.backend.input(self.agent, self.target, {'action': 'key', 'key': key, **mods})
        self.assertTrue(r.get('ok'), r)
        await asyncio.sleep(.1)

    def data(self):
        return b''.join(bytes.fromhex(x) for x in self.log.read_text().splitlines()) if self.log.exists() else b''

    async def test_main_screen_tui_receives_mousewheel(self):
        await self.key('6')
        screen = await self.snapshot()
        before = self.data()
        result = await self.backend.input(self.agent, self.target, {
            'action': 'scroll', 'direction': 'down', 'amount': 1, 'frame': screen['frame']})
        self.assertTrue(result.get('ok'), result)
        await asyncio.sleep(.1)
        self.assertIn(b'\x1b[<65;1;1M', self.data()[len(before):],
                      'Main-buffer mouse TUI must receive a wheel event, not terminal scrollback')

    async def test_native_screen_input_and_fail_closed(self):
        s = await self.snapshot()
        self.assertEqual(len(s['lines']), s['rows'])
        self.assertIn('e\u0301 中 🙂', '\n'.join(s['lines']))
        self.assertNotIn('\x1b', '\n'.join(s['lines']))
        self.assertEqual(len(s['frame']), 64)
        # A real live agent-named process, but NOT this Kitty PTY's foreground.
        impostor = subprocess.Popen(['/usr/bin/python3', '-c', 'import time; time.sleep(20)', 'claude'])
        try:
            r = await self.backend.input({'pid': impostor.pid, 'live': True}, self.target,
                                         {'action': 'key', 'key': 'enter'})
            self.assertFalse(r.get('ok'), r)
            self.assertIn('foreground', r.get('error', ''))
        finally:
            impostor.terminate()
            impostor.wait(timeout=5)
        bad = {'action': 'click', 'row': 0, 'col': 0, 'frame': '0'*64}
        self.assertFalse((await self.backend.input(self.agent, self.target, bad)).get('ok'))
        bad['frame'], bad['row'] = s['frame'], s['rows']
        self.assertFalse((await self.backend.input(self.agent, self.target, bad)).get('ok'))
        self.assertEqual(self.data(), b'')
        click = {'action': 'click', 'row': 1, 'col': 2, 'frame': s['frame']}
        r = await self.backend.input(self.agent, self.target, click)
        self.assertTrue(r.get('ok'), r)
        await asyncio.sleep(.1)
        self.assertIn(b'\x1b[<0;3;2M', self.data())
        self.assertIn(b'\x1b[<0;3;2m', self.data())
        await self.key('enter')
        await self.key('escape')
        await self.key('tab', shift=True)
        await self.key('1')
        self.assertIn(b'\r\x1b\x1b[Z1', self.data())
        s = await self.snapshot()
        r = await self.backend.input(self.agent, self.target, {'action': 'scroll', 'direction': 'up', 'amount': 2, 'frame': s['frame']})
        self.assertTrue(r.get('ok'), r)
        await asyncio.sleep(.1)
        self.assertEqual(self.data().count(b'\x1b[<64;1;1M'), 2)
        await self.key('9')
        s = await self.snapshot()
        before = self.data()
        click['frame'] = s['frame']
        self.assertFalse((await self.backend.input(self.agent, self.target, click)).get('ok'))
        await asyncio.sleep(.1)
        self.assertEqual(self.data(), before, 'mouse-disabled prompt must receive NO bytes')
        r = await self.backend.input(self.agent, self.target, {'action': 'scroll', 'direction': 'down', 'amount': 1, 'frame': s['frame']})
        self.assertTrue(r.get('ok'), r)
        await asyncio.sleep(.1)
        self.assertTrue(self.data().endswith(b'\x1b[6~'))
        await self.key('8')
        s = await self.snapshot()
        before = self.data()
        r = await self.backend.input(self.agent, self.target, {'action': 'scroll', 'direction': 'up', 'amount': 3, 'frame': s['frame']})
        self.assertTrue(r.get('ok'), r)
        await asyncio.sleep(.1)
        self.assertEqual(self.data(), before, 'scrollback must not send TUI keys')
        self.assertNotEqual((await self.snapshot())['frame'], s['frame'])
        # Exercise the real HTTP auth + biometric proof + Fleet routing all
        # the way through the fixed kitten into this disposable child's PTY.
        ad = object.__new__(AD.MobileAdapter)
        ad.store = S.Store(self.root / 'http.db')
        ad._replay = SG.Replay()
        request_key, approval_key = ec.generate_private_key(ec.SECP256R1()), ec.generate_private_key(ec.SECP256R1())
        device = ad.store.claim_pair_code(ad.store.create_pair_code(), 'fixture', public(request_key), public(approval_key))
        fleet = F.Fleet(ad)
        fleet.find = AsyncMock(return_value=self.agent)
        @web.middleware
        async def auth(request, handler):
            return await ad._auth_mw(request, handler)
        app = web.Application(middlewares=[auth])
        F.routes(app.router, fleet, lambda d, status=200: web.json_response(d, status=status))
        client = TestClient(TestServer(app))
        await client.start_server()
        try:
            path = '/v1/fleet/fixture/input'
            body = b'{"action":"key","key":"down"}'
            ts = str(int(time.time()))
            headers = {'Authorization': 'Bearer ' + device['token'], 'X-HM-Ts': ts,
                       'X-HM-Sig': sign(request_key, SG.canonical('POST', path, ts, body)),
                       'X-HM-ASig': sign(approval_key, f'fleet-input\nfixture\n{hashlib.sha256(body).hexdigest()}\n{ts}'.encode())}
            async with client.post(path, data=body, headers=headers) as response:
                self.assertEqual(response.status, 200, await response.text())
            await asyncio.sleep(.1)
            self.assertTrue(self.data().endswith(b'\x1b[B'))
        finally:
            await client.close()
            ad.store._db.close()
        await self.key('7')
        r = await self.backend.input(self.agent, self.target, {'action': 'key', 'key': 'enter'})
        self.assertFalse(r.get('ok'), r)


if __name__ == '__main__':
    try:
        suite = unittest.defaultTestLoader.loadTestsFromModule(sys.modules[__name__])
        result = unittest.TextTestRunner(verbosity=2).run(suite)
        print(json.dumps({'suite': 'fleet_terminal_integration', 'tests': result.testsRun,
                          'failures': len(result.failures), 'errors': len(result.errors),
                          'skipped': len(result.skipped), 'ok': result.wasSuccessful()}))
        sys.exit(0 if result.wasSuccessful() else 1)
    finally:
        HOME.cleanup()
