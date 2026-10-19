"""Isolated synthetic review API. Never imports the gateway or runs agent tools."""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import hmac
import importlib.util
import json
import os
from pathlib import Path
import secrets
import sqlite3
import time
from urllib.parse import urlencode, urlsplit

from aiohttp import web

# Load ONLY these two dependency-free production modules, not mobile/__init__.
ROOT = Path(__file__).resolve().parents[1]

def module(name):
    spec = importlib.util.spec_from_file_location('review_' + name, ROOT / 'server/mobile' / (name + '.py'))
    assert spec is not None and spec.loader is not None
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value

S, SG = module('store'), module('sig')
BASE = Path.home() / '.cache/hermes-play-prep'
SECURITY = {'signature_replay': SG.REPLAY_VERSION}
FEATURES = {'fleet': False, 'radar': False, 'projects': False, 'wake_wa': False}
UNSUPPORTED = ['Wake server sessions/proofs/configuration', 'server TTS/STT and real Talk voice',
               'FCM delivery', 'fleet/terminal', 'projects/git', 'Radar', 'Hub marketplace installs',
               'plugin/MCP installation and toggles', 'gateway restart', 'real model/provider execution',
               'session destructive commands/model switching', 'arbitrary settings writes']

def state_root(path):
    path = Path(path).expanduser().resolve()
    if path == BASE.resolve() or BASE.resolve() not in path.parents:
        raise ValueError('state must be a dedicated child of ~/.cache/hermes-play-prep')
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(path, 0o700)
    return path


def metadata():
    return dict(name='Hermes Review Sandbox', profile='default', model='synthetic-demo',
                agent_name='Hermes Demo', user_name='Reviewer', profiles=['default'],
                models=['synthetic-demo'], features=FEATURES, security=SECURITY, demo=True)


class Backend:
    def __init__(self, home, public_url):
        self.home = state_root(home)
        parsed = urlsplit(public_url)
        if parsed.scheme != 'https' and not (parsed.scheme == 'http' and parsed.hostname in ('127.0.0.1', 'localhost')):
            raise ValueError('public URL needs HTTPS (loopback HTTP allowed for tests only)')
        if parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ('', '/'):
            raise ValueError('public URL must be a clean origin')
        self.url = public_url.rstrip('/')
        self.auth = S.Store(self.home / 'auth.db')
        self.replay = SG.Replay()
        self.stores = {}
        self.hits = []
        invitation = self.home / 'invitation.txt'
        if not invitation.exists():
            fd = os.open(invitation, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, 'w') as f:
                f.write(secrets.token_urlsafe(32))
        self.invitation = invitation.read_text().strip()

    def limited(self):
        now = time.monotonic()
        self.hits = [t for t in self.hits if now - t < 300]
        if len(self.hits) >= 40:
            return True
        self.hits.append(now)
        return False

    def tenant(self, did):
        # did comes only from our authenticated production devices table.
        if did not in self.stores:
            d = self.home / did
            d.mkdir(mode=0o700, exist_ok=True)
            s = S.Store(d / 'fixture.db')
            if not s.kv_get('seeded'):
                self.seed(s)
            self.stores[did] = s
        return self.stores[did]

    def seed(self, s):
        for cid, title, question, answer in [
            ('c-welcome', 'Welcome · synthetic review sandbox', 'What can I try?',
             '# Welcome, Reviewer\nThis is **synthetic data**, not a live agent.\n\nTry chat, rename/pin/archive, approvals, search, memory, file download and cron pause/run. No commands execute.\n\n| Surface | Mode |\n|---|---|\n| Chat | scripted |\n| Cron | simulated |\n| Approvals | cryptographic, no execution |'),
            ('c-planning', 'Launch checklist · example project', 'Draft a release checklist.',
             '## Example checklist\n- [x] Review permissions\n- [x] Verify synthetic fixture\n- [ ] Qualify the submitted Play artifact\n\n```python\nprint("example only - not executed")\n```'),
            ('c-digest', 'Morning digest · synthetic', 'Summarize the demo.',
             'Three demo workflows are ready: chat, approval decisions and schedule controls. All usage figures are illustrative, not billing.')]:
            s.ensure_chat(cid, title, kind='digest' if cid == 'c-digest' else 'chat')
            s.add_message(cid, 'user', question)
            s.add_message(cid, 'agent', answer, meta={'demo': True})
        s.update_chat('c-welcome', pinned=1)
        s.add_message('c-planning', 'agent', 'Download the synthetic checklist.', kind='file',
                      meta={'id': 'checklist.txt', 'kind': 'file', 'file_name': 'checklist.txt', 'mime': 'text/plain', 'size': len(self.sample_file())})
        s.add_approval('c-welcome', 'demo', 'demo: confirm checklist',
                       'Synthetic approval. Nothing will execute.', ['once', 'deny'])
        s.kv_put('jobs', [{'id': 'demo-digest', 'name': 'Synthetic morning digest', 'schedule': '0 9 * * *',
                          'schedule_display': 'Every day at 09:00 (demo only)', 'enabled': True, 'deliver': 'mobile',
                          'prompt': 'Prepare a synthetic checklist', 'model': 'synthetic-demo',
                          'last_status': 'ok', 'runs': ['demo-run'], 'next_run_at': None}])
        s.kv_put('memory', {'memory': ['This sandbox never executes tools.'], 'user': ['Reviewer prefers synthetic examples.']})
        s.kv_put('seeded', True)

    @staticmethod
    def sample_file():
        return b'SYNTHETIC REVIEW CHECKLIST\nPair, chat, review approval, pause schedule.\n'

    @web.middleware
    async def auth_mw(self, r, handler):
        try:
            if r.path in ('/v1/health', '/v1/pair', '/review', '/review/enroll'):
                return await handler(r)
            token = r.headers.get('Authorization', '')
            dev = self.auth.device_for_token(token[7:] if token.startswith('Bearer ') else '')
            if not dev:
                return web.json_response({'error': 'unauthorized'}, status=401)
            body = await r.read()
            ts = r.headers.get('X-HM-Ts', '')
            if not self.replay.fresh(r.headers.get('X-HM-Sig', ''), pub=dev['pub'],
                                     msg=SG.canonical(r.method, r.raw_path, ts, body), ts=ts,
                                     scope='request', store=self.auth):
                return web.json_response({'error': 'bad signature'}, status=401)
            r['device'], r['signature_replay'], r['signature_store'] = dev, self.replay, self.auth
            r['store'] = self.tenant(dev['id'])
            return await handler(r)
        except (sqlite3.Error, OSError):
            return web.json_response({'error': 'sandbox storage unavailable'}, status=503)
        except (json.JSONDecodeError, UnicodeDecodeError, ValueError, TypeError, KeyError):
            return web.json_response({'error': 'invalid request'}, status=400)

    async def body(self, r):
        if not r.body_exists:
            return {}
        value = await r.json()
        if not isinstance(value, dict):
            raise ValueError('object required')
        return value

    async def public(self, r):
        if r.path == '/v1/health':
            return web.json_response({'ok': True, 'api': 1, **metadata()})
        if r.path == '/review':
            return web.Response(text='''<!doctype html><meta name="viewport" content="width=device-width"><title>Hermes synthetic review</title>
<h1>Hermes synthetic review sandbox</h1><p>No live agent, personal data or command execution. Each enrollment creates a separate sandbox. Do not enter personal data.</p>
<form id="f"><label>Reviewer invitation <input id="i" type="password" required autocomplete="off"></label><button>Get fresh pairing link</button></form><p id="out"></p>
<script>document.getElementById('f').onsubmit=async(e)=>{e.preventDefault();let o=document.getElementById('out');o.textContent='';let r=await fetch('/review/enroll',{method:'POST',headers:{Authorization:'Bearer '+document.getElementById('i').value}});let d=await r.json();if(d.link){let a=document.createElement('a');a.href=d.link;a.textContent='Open Hermes / copy this pairing link';o.appendChild(a)}else{o.textContent=d.error}};</script>''', content_type='text/html', headers={'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer', 'X-Content-Type-Options': 'nosniff'})
        if self.limited():
            return web.json_response({'error': 'too many attempts'}, status=429)
        if r.path == '/review/enroll':
            if not hmac.compare_digest(r.headers.get('Authorization', ''), 'Bearer ' + self.invitation):
                return web.json_response({'error': 'invitation required'}, status=401)
            code = self.auth.create_pair_code(600)
            link = 'hermes://pair?' + urlencode({'u': self.url, 'c': code, 'n': 'Hermes Review Sandbox'})
            return web.json_response({'link': link, 'expires_in': 600}, headers={'Cache-Control': 'no-store'})
        b = await self.body(r)
        pub, apub = b.get('pub', ''), b.get('apub', '')
        if not SG.load_pub(pub) or not SG.load_pub(apub):
            return web.json_response({'error': 'device keys required (P-256 SPKI, base64)'}, status=400)
        result = self.auth.claim_pair_code(str(b.get('code', '')), str(b.get('device_name', 'Reviewer')), pub, apub, nolock=b.get('lock') is False)
        if not result:
            return web.json_response({'error': 'invalid or expired code'}, status=403)
        self.tenant(result['device_id'])
        return web.json_response({**result, 'server': {'name': 'Hermes Review Sandbox', 'profile': 'default'}, 'cf': {}}, headers={'Cache-Control': 'no-store'})

    async def events(self, r):
        s = r['store']
        resp = web.StreamResponse(headers={'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache'})
        await resp.prepare(r)
        seq = -1
        try:
            while self.auth.device_active(r['device']['id']):
                if seq != s.seq():
                    await resp.write(('data: ' + json.dumps({'type': 'hello' if seq < 0 else 'seq', 'seq': s.seq(), 'working': []}) + '\n\n').encode())
                    seq = s.seq()
                else:
                    await resp.write(b': keepalive\n\n')
                await asyncio.sleep(0.25)
        except (ConnectionResetError, RuntimeError):
            pass
        return resp

    async def api(self, r):
        s, p, method = r['store'], r.path, r.method
        b = await self.body(r) if method in ('POST', 'PUT', 'PATCH') and p != '/v1/upload' else {}
        parts = p.strip('/').split('/')
        out = None
        if p == '/v1/me':
            out = {**metadata(), 'public_url': self.url, 'device': {k: r['device'][k] for k in ('id', 'name', 'created')}}
        elif p == '/v1/sync':
            out = {**s.changes(int(r.query.get('since', 0))), 'working': []}
        elif p == '/v1/commands':
            out = {**metadata(), 'commands': []}
        elif p == '/v1/profiles':
            out = {'profiles': ['default']}
        elif p == '/v1/chats':
            out = {'chat': s.ensure_chat(S.new_id('c'), str(b.get('title') or 'New chat')[:80])} if method == 'POST' else {'chats': s.list_chats(r.query.get('archived') == '1'), 'working': []}
        elif len(parts) >= 3 and parts[1] == 'chats':
            cid = parts[2]
            if not s.get_chat(cid):
                return web.json_response({'error': 'chat not found'}, status=404)
            action = parts[3] if len(parts) == 4 else ''
            if not action and method == 'PATCH':
                out = {'chat': s.update_chat(cid, **{k: v for k, v in b.items() if k in ('title', 'pinned', 'archived', 'unread')})}
            elif action == 'messages' and method == 'GET':
                out = {'messages': s.messages(cid, min(200, max(1, int(r.query.get('limit', 80)))), float(r.query['before']) if 'before' in r.query else None)}
            elif action == 'messages' and method == 'POST':
                text = str(b.get('text') or '').strip()[:8000]
                if not text and not b.get('attachments'):
                    raise ValueError('empty message')
                # Arbitrary input is inert text. No slash handlers, providers, tools or URLs.
                msg = s.add_message(cid, 'user', text, meta={'attachments': b.get('attachments', []), 'demo': True})
                s.add_message(cid, 'agent', 'Synthetic demo reply: message received. No tools, shell commands, providers or external requests were used.\n\nTry the schedule controls, approval card or memory editor.', meta={'demo': True})
                out = {'message': msg}
            elif action == 'read' and method == 'POST':
                s.update_chat(cid, unread=0)
                out = {'ok': True}
            elif action == 'stop' and method == 'POST':
                out = {'ok': True, 'demo': True}
            elif action == 'usage':
                out = {'chat_id': cid, 'turn': None, 'session': None, 'demo': True}
            elif action == 'run':
                out = {'chat_id': cid, 'working': False, 'steps': [], 'turn': s.messages(cid), 'approvals': [a for a in s.pending_approvals() if a['chat_id'] == cid], 'demo': True}
        elif p == '/v1/pulse':
            out = {'spend_today': 0.12, 'runs_today': 3, 'failures_today': 0, 'spend_7d': [0.04, 0.08, 0.06, 0.11, 0.09, 0.1, 0.12], 'live_sessions': [], 'working': [], 'approvals': s.pending_approvals(), 'demo': True}
        elif p == '/v1/cron':
            out = {'jobs': s.kv_get('jobs'), 'now': time.time(), 'demo': True}
        elif len(parts) == 4 and parts[1] == 'cron':
            jobs = s.kv_get('jobs')
            job = next((j for j in jobs if j['id'] == parts[2]), None)
            if not job:
                return web.json_response({'error': 'job not found'}, status=404)
            if parts[3] == 'output' and method == 'GET':
                out = {'run': 'demo-run', 'text': '# Synthetic digest\nDemo checklist complete. No job was executed.'}
            elif method == 'POST' and parts[3] in ('pause', 'resume', 'run'):
                if parts[3] == 'run':
                    s.add_message('c-digest', 'agent', 'Synthetic schedule run requested. No scheduler or tools were invoked.', meta={'demo': True})
                else:
                    job['enabled'] = parts[3] == 'resume'
                s.kv_put('jobs', jobs)
                out = {'job': job, 'demo': True}
        elif p == '/v1/memory':
            mem = s.kv_get('memory')
            if method == 'POST':
                target, action = b.get('target'), b.get('action')
                if target not in mem or action not in ('add', 'replace', 'remove'):
                    raise ValueError('invalid memory edit')
                content = str(b.get('content', ''))[:4000]
                if action == 'add':
                    mem[target].append(content)
                else:
                    if b.get('old_text') not in mem[target]:
                        return web.json_response({'success': False, 'error': 'entry not found'}, status=409)
                    i = mem[target].index(b['old_text'])
                    if action == 'remove':
                        mem[target].pop(i)
                    else:
                        mem[target][i] = content
                if sum(map(len, mem[target])) > 8000:
                    raise ValueError('memory full')
                s.kv_put('memory', mem)
                out = {'success': True, 'demo': True}
            else:
                out = {k: {'entries': v, 'chars': sum(map(len, v)), 'limit': 8000, 'enabled': True} for k, v in mem.items()}
        elif p == '/v1/search':
            q = r.query.get('q', '').lower()
            out = {'results': [{'session_id': c['id'], 'chat_id': c['id'], 'source': 'demo', 'title': c['title'], 'role': m['role'], 'snippet': m['content'], 'content': m['content'], 'timestamp': m['ts']} for c in s.list_chats(True) for m in s.messages(c['id']) if q and q in m['content'].lower()][:50]}
        elif p == '/v1/sessions':
            out = {'sessions': [{'id': c['id'], 'source': 'demo', 'title': c['title'], 'model': 'synthetic-demo', 'started_at': c['created'], 'ended_at': c['updated'], 'cost': 0} for c in s.list_chats(True)]}
        elif len(parts) == 4 and parts[1] == 'sessions' and parts[3] == 'messages':
            out = {'messages': [{'role': 'assistant' if m['role'] == 'agent' else m['role'], 'content': m['content'], 'timestamp': m['ts']} for m in s.messages(parts[2])]}
        elif len(parts) == 3 and parts[1] == 'approvals' and method == 'POST':
            aid, choice = parts[2], b.get('choice')
            a = s.get_approval(aid)
            if not a:
                return web.json_response({'error': 'not found'}, status=404)
            if a['state'] != 'pending':
                return web.json_response({'error': 'already resolved'}, status=409)
            if choice not in ('once', 'deny'):
                raise ValueError('demo allows once or deny only')
            msg = f"approve\n{aid}\n{choice}\n{r.headers.get('X-HM-Ts', '')}".encode()
            if not SG.approval_ok(r, msg):
                return web.json_response({'error': 'device confirmation required', 'need_sig': True}, status=403)
            out = {'approval': s.set_approval(aid, 'denied' if choice == 'deny' else 'approved', choice), 'resolved': 1, 'demo': True}
        elif p == '/v1/devices':
            out = {'devices': [d for d in self.auth.devices() if d['id'] == r['device']['id']], 'this': r['device']['id']}
        elif len(parts) == 3 and parts[1] == 'devices' and method == 'DELETE':
            if parts[2] != r['device']['id']:
                return web.json_response({'error': 'not found'}, status=404)
            out = {'ok': self.auth.revoke(parts[2])}
        elif p == '/v1/push-config':
            out = {'enabled': False, 'config': {}}
        elif p == '/v1/fcm' and method == 'POST':
            out = {'ok': True, 'push': False}  # Deliberately discard token.
        elif p == '/v1/upload' and method == 'POST':
            data = await r.read()
            if not data:
                raise ValueError('empty upload')
            name = S.new_id('u')
            d = self.home / r['device']['id']
            (d / name).write_bytes(data)
            out = {'id': name, 'size': len(data), 'mime': 'application/octet-stream', 'file_name': Path(r.query.get('name', 'demo.bin')).name[:120]}
        elif len(parts) == 3 and parts[1] == 'media' and method == 'GET':
            if parts[2] == 'checklist.txt':
                return web.Response(body=self.sample_file(), content_type='text/plain')
            name = parts[2]
            if not name.startswith('u-') or any(c not in 'u-0123456789abcdef' for c in name):
                return web.json_response({'error': 'not found'}, status=404)
            path = self.home / r['device']['id'] / name
            if not path.is_file():
                return web.json_response({'error': 'not found'}, status=404)
            return web.Response(body=path.read_bytes(), content_type='application/octet-stream')
        elif p == '/v1/review/capabilities':
            out = {'demo': True, 'unsupported': UNSUPPORTED, 'submission_ready': False}
        if out is None:
            return web.json_response({'error': 'Unsupported in synthetic review sandbox', 'demo': True, 'surface': p}, status=501)
        return web.json_response(out)

    def app(self):
        app = web.Application(middlewares=[self.auth_mw], client_max_size=1024 * 1024)
        app.router.add_get('/v1/health', self.public)
        app.router.add_get('/review', self.public)
        app.router.add_post('/review/enroll', self.public)
        app.router.add_post('/v1/pair', self.public)
        app.router.add_get('/v1/events', self.events)
        app.router.add_route('*', '/{path:.*}', self.api)
        async def close(app):
            for s in [self.auth, *self.stores.values()]:
                s._db.close()
        app.on_cleanup.append(close)
        async def headers(request, response):
            response.headers['Cache-Control'] = 'no-store'
            response.headers['X-Content-Type-Options'] = 'nosniff'
            response.headers['Referrer-Policy'] = 'no-referrer'
        app.on_response_prepare.append(headers)
        return app


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state', default=str(BASE / 'review-backend'))
    parser.add_argument('--port', type=int, default=47941)
    parser.add_argument('--public-url', default='http://127.0.0.1:47941')
    args = parser.parse_args()
    backend = Backend(args.state, args.public_url)
    print('Synthetic sandbox only; invitation file:', backend.home / 'invitation.txt', flush=True)
    web.run_app(backend.app(), host='127.0.0.1', port=args.port, access_log=None)


if __name__ == '__main__':
    main()
