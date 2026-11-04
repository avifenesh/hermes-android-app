"""Isolated synthetic review API. Never imports the gateway or runs agent tools."""
from __future__ import annotations

import argparse
import asyncio
import base64
import binascii
import hashlib
import hmac
import importlib.util
import ipaddress
import json
import math
import os
from pathlib import Path
import re
import secrets
import sqlite3
import struct
import time
from datetime import datetime, timedelta, timezone
from urllib.parse import urlencode, urlsplit
from zoneinfo import ZoneInfo

from aiohttp import web

# Load ONLY these two dependency-free production modules, not mobile/__init__, plus the static catalogue beside this file.
ROOT = Path(__file__).resolve().parents[1]


def module(name, path=None):
    spec = importlib.util.spec_from_file_location('review_' + name, path or ROOT / 'server/mobile' / (name + '.py'))
    assert spec is not None and spec.loader is not None
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


S, SG = module('store'), module('sig')
C = module('catalog', Path(__file__).resolve().with_name('catalog.py'))
BASE = Path.home() / '.cache/hermes-play-prep'
SECURITY = {'signature_replay': SG.REPLAY_VERSION}
# Every optional surface is simulated, so every client gate is on (absent would also mean on).
FEATURES = {'fleet': True, 'radar': True, 'projects': True, 'wake_wa': True}
# Reachable surfaces with no reviewable behaviour. Empty: every Play-reachable API path has a synthetic answer.
UNSUPPORTED: list = []
# Honest limits that are not app surfaces: nothing here is reachable as a screen or action.
LIMITS = ['FCM push delivery (push-config reports disabled; the app falls back to its own connection)',
          'speech recognition and real voices (Talk uses the phone recognizer; server voice is a synthetic chime)',
          'Android background, alarm and permission behaviour depends on the device, not on this server']
REVIEW_PASSWORD = 'review-sandbox'  # Alarm emergency-stop password; printed on the /review page.
MAX_BODY, MAX_UPLOAD, TENANT_QUOTA = 1024 * 1024, 12 * 1024 * 1024, 64 * 1024 * 1024
BIG_BODY_PATHS = ('/v1/upload', '/v1/wake/proof')
NOTE = 'Synthetic review sandbox: nothing ran on any machine.'


def state_root(path):
    path = Path(path).expanduser().resolve()
    if path == BASE.resolve() or BASE.resolve() not in path.parents:
        raise ValueError('state must be a dedicated child of ~/.cache/hermes-play-prep')
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(path, 0o700)
    return path


def metadata(model='synthetic-demo'):
    return dict(name='Hermes Review Sandbox', profile='default', model=model,
                agent_name='Hermes Demo', user_name='Reviewer', profiles=['default', 'research'],
                models=['synthetic-demo', 'synthetic-fast'], features=FEATURES, security=SECURITY, demo=True)


def J(data, status=200):
    return web.json_response(data, status=status)


def E(msg, status=400, **extra):
    return web.json_response({'error': msg, **extra}, status=status)


def norm(value):
    # Same normalisation as production guard_ok and the app's Guard.norm.
    return ('true' if value else 'false') if isinstance(value, bool) else ('null' if value is None else str(value))


def guard_msg(kind, key, value, ts):
    return f'{kind}\n{key}\n{hashlib.sha256(norm(value).encode()).hexdigest()}\n{ts}'.encode()


def run_id(ts):
    return datetime.fromtimestamp(ts, timezone.utc).strftime('%Y-%m-%d_%H-%M-%S')


def chime():
    """A short two-note synthetic clip (16 kHz mono 16-bit WAV), generated, never recorded."""
    rate, out = 16000, bytearray()
    for freq, dur in ((660.0, 0.22), (880.0, 0.32)):
        n = int(rate * dur)
        for i in range(n):
            env = min(1.0, i / 400, (n - i) / 1600)
            out += struct.pack('<h', int(9000 * env * math.sin(2 * math.pi * freq * i / rate)))
    head = b'RIFF' + struct.pack('<I', 36 + len(out)) + b'WAVEfmt ' + struct.pack('<IHHIIHH', 16, 1, 1, rate, rate * 2, 2, 16)
    return head + b'data' + struct.pack('<I', len(out)) + bytes(out)


CHIME = chime()
WEEKDAYS = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday']


# ---------------------------------------------------------------- schedules (cron editor)
def _clock(text):
    m = re.fullmatch(r'(\d{1,2})(?::(\d{2}))?\s*(am|pm)?', text.strip())
    if not m:
        return None
    h, mi = int(m.group(1)), int(m.group(2) or 0)
    if m.group(3):
        if not 1 <= h <= 12:
            return None
        h = h % 12 + (12 if m.group(3) == 'pm' else 0)
    return (h, mi) if h < 24 and mi < 60 else None


def _cron_field(text, lo, hi):
    out = set()
    for part in text.split(','):
        step = 1
        if '/' in part:
            part, s = part.split('/', 1)
            if not s.isdigit() or int(s) < 1:
                return None
            step = int(s)
        if part == '*':
            a, b = lo, hi
        elif re.fullmatch(r'\d+-\d+', part):
            a, b = map(int, part.split('-'))
        elif part.isdigit():
            a = b = int(part)
        else:
            return None
        if a < lo or b > hi or a > b:
            return None
        out.update(range(a, b + 1, step))
    return out


def parse_schedule(expr):
    """The schedule forms the editor offers. Returns a dict or raises ValueError with a readable message."""
    t = ' '.join(expr.lower().split())
    m = re.fullmatch(r'(in\s+|every\s+)?(\d+)\s*(m|min|mins|minutes?|h|hr|hrs|hours?|d|days?)', t)
    if m:
        n = int(m.group(2))
        unit = {'m': 60, 'h': 3600, 'd': 86400}[m.group(3)[0]]
        word = {60: 'minute', 3600: 'hour', 86400: 'day'}[unit]
        if n < 1:
            raise ValueError('Use a positive interval.')
        once = (m.group(1) or '').startswith('in')
        label = f'{n} {word}' + ('s' if n != 1 else '')
        return {'kind': 'once' if once else 'interval', 'seconds': n * unit,
                'display': ('Once in ' if once else 'Every ') + label}
    m = re.fullmatch(r'(?:every day|daily|everyday)(?: at)? (.+)', t)
    if m and _clock(m.group(1)):
        h, mi = _clock(m.group(1))
        return {'kind': 'cron', 'expr': f'{mi} {h} * * *', 'display': f'Every day at {h:02d}:{mi:02d}'}
    m = re.fullmatch(r'(?:every )?weekdays?(?: at)? (.+)', t)
    if m and _clock(m.group(1)):
        h, mi = _clock(m.group(1))
        return {'kind': 'cron', 'expr': f'{mi} {h} * * 1-5', 'display': f'Weekdays at {h:02d}:{mi:02d}'}
    m = re.fullmatch(r'(?:every )?(' + '|'.join(WEEKDAYS) + r')s?(?: at)? (.+)', t)
    if m and _clock(m.group(2)):
        h, mi = _clock(m.group(2))
        dow = (WEEKDAYS.index(m.group(1)) + 1) % 7  # cron: 0 = Sunday
        return {'kind': 'cron', 'expr': f'{mi} {h} * * {dow}', 'display': f'Every {m.group(1).title()} at {h:02d}:{mi:02d}'}
    fields = t.split()
    if len(fields) == 5:
        sets = [_cron_field(f, lo, hi) for f, (lo, hi) in zip(fields, ((0, 59), (0, 23), (1, 31), (1, 12), (0, 7)))]
        if all(x is not None for x in sets):
            return {'kind': 'cron', 'expr': ' '.join(fields), 'display': 'Cron: ' + ' '.join(fields)}
    raise ValueError(f"Couldn't read the schedule '{expr}'. Try 'every 2h', 'every day at 9am', 'weekdays at 9am', 'in 45m' or '0 9 * * *'.")


def next_cron(expr, after, tz):
    mins, hours, doms, months, dows = [_cron_field(f, lo, hi) for f, (lo, hi) in
                                       zip(expr.split(), ((0, 59), (0, 23), (1, 31), (1, 12), (0, 7)))]
    dows = {d % 7 for d in dows}
    start = datetime.fromtimestamp(after, tz).replace(second=0, microsecond=0) + timedelta(minutes=1)
    day = start.replace(hour=0, minute=0)
    for _ in range(400):
        if day.month in months and day.day in doms and (day.isoweekday() % 7) in dows:
            for h in sorted(hours):
                for mi in sorted(mins):
                    cand = day.replace(hour=h, minute=mi)
                    if cand >= start:
                        return cand.timestamp()
        day = (day + timedelta(days=1)).replace(hour=0, minute=0)
    return None


class Backend:
    REPLY_STEP = 0.35   # seconds between streamed edits of a scripted reply
    JOB_SECONDS = 1.2   # synthetic Hub jobs finish after this long

    def __init__(self, home, public_url, client_header=None):
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
        self.rt = {}
        self.hits = []
        # Set only behind a TLS proxy that writes this header; never trust a client-supplied value.
        self.client_header = client_header
        invitation = self.home / 'invitation.txt'
        if not invitation.exists():
            fd = os.open(invitation, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, 'w') as f:
                f.write(secrets.token_urlsafe(32))
        self.invitation = invitation.read_text().strip()

    # Per-client budget so one script cannot lock reviewers out; the global ceiling bounds total work.
    CLIENT_LIMIT, GLOBAL_LIMIT, WINDOW = 20, 400, 300

    def client(self, r):
        """Client key: IPv4 address or IPv6 /64, from the proxy's own hop when a proxy header is configured."""
        if self.client_header:
            # The proxy appends the peer it saw; earlier entries are client-controlled.
            raw = r.headers.get(self.client_header, '').split(',')[-1].strip()
        else:
            raw = r.remote or ''
        try:
            ip = ipaddress.ip_address(raw.split('%')[0])
        except ValueError:
            return 'unknown'
        if ip.version == 6 and ip.ipv4_mapped:
            ip = ip.ipv4_mapped
        return str(ipaddress.ip_network(f'{ip}/64', strict=False)) if ip.version == 6 else str(ip)

    def limited(self, key):
        now = time.monotonic()
        self.hits = [(t, k) for t, k in self.hits if now - t < self.WINDOW]
        if len(self.hits) >= self.GLOBAL_LIMIT or sum(1 for _, k in self.hits if k == key) >= self.CLIENT_LIMIT:
            return True
        self.hits.append((now, key))
        return False

    # ------------------------------------------------------------ tenants
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

    def runtime(self, did):
        return self.rt.setdefault(did, {'working': {}, 'tasks': {}, 'steer': {}, 'tts': [], 'session_tasks': {}})

    def working_list(self, did):
        return [{'chat_id': c, 'since': t} for c, t in self.runtime(did)['working'].items()]

    def kv(self, s, key, default):
        """Lazily initialised tenant state, so older enrollments gain new surfaces without a reseed."""
        value = s.kv_get(key)
        if value is None:
            value = default() if callable(default) else default
            s.kv_put(key, value)
        return value

    def seed(self, s):
        now = time.time()
        welcome = ('# Welcome, Reviewer\nThis is **synthetic data**, not a live agent. Every reply is scripted and nothing runs on any machine.\n\n'
                   '| Try | Where |\n|---|---|\n| Chat, streaming replies, steer and stop | any chat |\n'
                   '| Approvals with your fingerprint | send a message with the word *approval* |\n'
                   '| Model, reasoning, session actions | composer chip and ⋯ menu |\n| Cron jobs and the alarm clock | Cron tab |\n'
                   '| Settings, skills, plugins, Hub, memory, usage | Me tab |\n| Radar, projects, fleet | Me and Pulse |\n\n'
                   f'Alarm emergency-stop password: `{REVIEW_PASSWORD}`')
        for cid, title, question, answer in [
            ('c-welcome', 'Welcome · synthetic review sandbox', 'What can I try?', welcome),
            ('c-planning', 'Launch checklist · example project', 'Draft a release checklist.',
             '## Example checklist\n- [x] Review permissions\n- [x] Verify synthetic fixture\n- [ ] Qualify the submitted Play artifact\n\n```python\nprint("example only - not executed")\n```')]:
            s.ensure_chat(cid, title)
            s.add_message(cid, 'user', question)
            s.add_message(cid, 'agent', answer, meta={'demo': True})
        s.update_chat('c-welcome', pinned=1)
        card = s.add_message('c-welcome', 'agent', 'Synthetic example · read 1 file, edited 1 file', kind='activity',
                             meta={'summary': 'Synthetic example', 'count': 2, 'files': 1, 'errors': 0, 'running': '',
                                   'started': now - 40, 'ended': now - 36, 'added': 3, 'removed': 1, 'demo': True})
        diff = '--- a/notes/checklist.md\n+++ b/notes/checklist.md\n@@ -1,3 +1,5 @@\n # Checklist\n-- [ ] review\n+- [x] review\n+- [ ] test on a phone\n+- [ ] write release notes\n'
        s.upsert_call({'id': S.new_id('call'), 'msg_id': card['id'], 'chat_id': 'c-welcome', 'tool': 'read_file',
                       'label': 'Read notes/checklist.md (synthetic)', 'detail': 'notes/checklist.md', 'status': 'ok',
                       'output': '# Checklist\n- [ ] review\n(example text, no file was read)', 'path': '', 'ms': 120,
                       'ts': now - 40, 'ended': now - 39.9})
        s.upsert_call({'id': S.new_id('call'), 'msg_id': card['id'], 'chat_id': 'c-welcome', 'tool': 'patch',
                       'label': 'Edited notes/checklist.md (synthetic, nothing written)', 'detail': 'notes/checklist.md',
                       'status': 'ok', 'path': 'notes/checklist.md', 'diff': diff, 'added': 3, 'removed': 1, 'ms': 340,
                       'ts': now - 38, 'ended': now - 37.7})
        self.approval_card(s, 'c-welcome', 'demo: confirm checklist', 'Synthetic approval. Nothing will execute.')
        s.add_message('c-planning', 'agent', 'Download the synthetic checklist.', kind='file',
                      meta={'id': 'checklist.txt', 'media': 'checklist.txt', 'kind': 'file', 'file_name': 'checklist.txt', 'mime': 'text/plain', 'size': len(self.sample_file())})
        s.kv_put('jobs', self.default_jobs(now))
        for job in s.kv_get('jobs'):
            if job['id'] == 'demo-digest':
                self.cron_deliver(s, job, '# Synthetic morning digest\nThree demo workflows are ready: chat, approval decisions and schedule controls. All usage figures are illustrative, not billing.', now - 3600)
        s.kv_put('memory', {'memory': ['This sandbox never executes tools.'], 'user': ['Reviewer prefers synthetic examples.']})
        s.kv_put('seeded', True)

    @staticmethod
    def sample_file():
        return b'SYNTHETIC REVIEW CHECKLIST\nPair, chat, review approval, pause schedule.\n'

    def approval_card(self, s, cid, command, description):
        actions = [['Approve once', 'once', 'primary'], ['This session', 'session', ''], ['Always', 'always', ''], ['Deny', 'deny', 'danger']]
        a = s.add_approval(cid, 'demo', command, description, actions)
        s.add_message(cid, 'agent', '', kind='approval', meta={'approval_id': a['id'], 'command': command, 'description': description,
                                                                'actions': actions, 'files': ['notes/checklist.md'], 'demo': True})
        return a

    # ------------------------------------------------------------ auth
    @web.middleware
    async def auth_mw(self, r, handler):
        try:
            if r.path in ('/v1/health', '/v1/pair', '/review', '/review/enroll', '/review/pr'):
                return await handler(r)
            token = r.headers.get('Authorization', '')
            dev = self.auth.device_for_token(token[7:] if token.startswith('Bearer ') else '')
            if not dev:
                return J({'error': 'unauthorized'}, 401)
            body = await r.read()
            if len(body) > MAX_BODY and r.path not in BIG_BODY_PATHS:
                return J({'error': 'Request too large for the sandbox'}, 413)
            ts = r.headers.get('X-HM-Ts', '')
            if not self.replay.fresh(r.headers.get('X-HM-Sig', ''), pub=dev['pub'],
                                     msg=SG.canonical(r.method, r.raw_path, ts, body), ts=ts,
                                     scope='request', store=self.auth):
                return J({'error': 'bad signature'}, 401)
            r['device'], r['signature_replay'], r['signature_store'] = dev, self.replay, self.auth
            r['store'] = self.tenant(dev['id'])
            return await handler(r)
        except (sqlite3.Error, OSError):
            return J({'error': 'sandbox storage unavailable'}, 503)
        except (json.JSONDecodeError, UnicodeDecodeError, ValueError, TypeError, KeyError):
            return J({'error': 'invalid request'}, 400)

    def signed(self, r, msg):
        return SG.approval_ok(r, msg)

    async def body(self, r):
        if not r.body_exists:
            return {}
        raw = await r.read()
        if not raw.strip():
            return {}
        value = json.loads(raw)
        if not isinstance(value, dict):
            raise ValueError('object required')
        return value

    # ------------------------------------------------------------ public
    async def public(self, r):
        if r.path == '/v1/health':
            return J({'ok': True, 'api': 1, 'degraded': [], **metadata()})
        if r.path == '/review':
            return web.Response(text='''<!doctype html><meta name="viewport" content="width=device-width"><title>Hermes synthetic review</title>
<h1>Hermes synthetic review sandbox</h1><p>No live agent, personal data or command execution. Each enrollment creates a separate sandbox. Do not enter personal data.</p>
<p>Alarm clock emergency-stop password: <code>''' + REVIEW_PASSWORD + '''</code></p>
<form id="f"><label>Reviewer invitation <input id="i" type="password" required autocomplete="off"></label><button>Get fresh pairing link</button></form><p id="out"></p>
<script>document.getElementById('f').onsubmit=async(e)=>{e.preventDefault();let o=document.getElementById('out');o.textContent='';let r=await fetch('/review/enroll',{method:'POST',headers:{Authorization:'Bearer '+document.getElementById('i').value}});let d=await r.json();if(d.link){let a=document.createElement('a');a.href=d.link;a.textContent='Open Hermes / copy this pairing link';o.appendChild(a)}else{o.textContent=d.error}};</script>''', content_type='text/html', headers={'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer', 'X-Content-Type-Options': 'nosniff'})
        if r.path == '/review/pr':
            # Where the sandbox's synthetic "Open PR" lands: static text, no tenant data.
            return web.Response(text='<!doctype html><meta name="viewport" content="width=device-width"><title>Synthetic pull request</title>'
                                     '<h1>Synthetic pull request</h1><p>This pull request exists only in the Hermes review sandbox. '
                                     'Nothing was pushed and no repository was changed.</p>', content_type='text/html',
                                headers={'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer', 'X-Content-Type-Options': 'nosniff'})
        if self.limited(self.client(r)):
            return J({'error': 'too many attempts'}, 429)
        if r.path == '/review/enroll':
            if not hmac.compare_digest(r.headers.get('Authorization', ''), 'Bearer ' + self.invitation):
                return J({'error': 'invitation required'}, 401)
            code = self.auth.create_pair_code(600)
            link = 'hermes://pair?' + urlencode({'u': self.url, 'c': code, 'n': 'Hermes Review Sandbox'})
            return web.json_response({'link': link, 'expires_in': 600}, headers={'Cache-Control': 'no-store'})
        b = await self.body(r)
        pub, apub = b.get('pub', ''), b.get('apub', '')
        if not SG.load_pub(pub) or not SG.load_pub(apub):
            return J({'error': 'device keys required (P-256 SPKI, base64)'}, 400)
        result = self.auth.claim_pair_code(str(b.get('code', '')), str(b.get('device_name', 'Reviewer')), pub, apub, nolock=b.get('lock') is False)
        if not result:
            return J({'error': 'invalid or expired code'}, 403)
        self.tenant(result['device_id'])
        return web.json_response({**result, 'server': {'name': 'Hermes Review Sandbox', 'profile': 'default'}, 'cf': {}}, headers={'Cache-Control': 'no-store'})

    async def events(self, r):
        s, did = r['store'], r['device']['id']
        resp = web.StreamResponse(headers={'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache'})
        await resp.prepare(r)
        last = None
        try:
            while self.auth.device_active(did):
                work = self.working_list(did)
                now = (s.seq(), tuple(sorted(w['chat_id'] for w in work)))
                if now != last:
                    await resp.write(('data: ' + json.dumps({'type': 'hello' if last is None else 'seq', 'seq': s.seq(), 'working': work}) + '\n\n').encode())
                    last = now
                else:
                    await resp.write(b': keepalive\n\n')
                await asyncio.sleep(0.25)
        except (ConnectionResetError, RuntimeError):
            pass
        return resp

    # ------------------------------------------------------------ identity, sync, chats
    def defaults(self, s):
        return self.kv(s, 'defaults', dict(C.DEFAULT_MODEL))

    async def h_me(self, r):
        return J({**metadata(self.defaults(r['store'])['model']), 'public_url': self.url,
                  'device': {k: r['device'][k] for k in ('id', 'name', 'created')}})

    async def h_sync(self, r):
        return J({**r['store'].changes(int(r.query.get('since', 0))), 'working': self.working_list(r['device']['id'])})

    async def h_commands(self, r):
        cmds = [{'name': n, 'description': d, 'args': a, 'category': c, 'aliases': [], 'subcommands': []} for n, d, a, c in C.COMMANDS]
        return J({**metadata(self.defaults(r['store'])['model']), 'commands': cmds})

    async def h_profiles(self, r):
        return J({'profiles': ['default', 'research'], 'current': 'default'})

    async def h_chats(self, r):
        s = r['store']
        if r.method == 'POST':
            b = await self.body(r)
            prof = str(b.get('profile') or 'default')
            if prof not in ('default', 'research'):
                return E(f'unknown profile {prof}')
            chat = s.ensure_chat(S.new_id('c'), str(b.get('title') or 'New chat')[:80])
            if prof != 'default':
                chat = s.update_chat(chat['id'], profile=prof)
            return J({'chat': chat})
        return J({'chats': s.list_chats(r.query.get('archived') == '1'), 'working': self.working_list(r['device']['id'])})

    def chat_or_404(self, r):
        cid = r.match_info['cid']
        return cid, r['store'].get_chat(cid)

    async def h_chat_patch(self, r):
        cid, chat = self.chat_or_404(r)
        if not chat:
            return E('chat not found', 404)
        b = await self.body(r)
        return J({'chat': r['store'].update_chat(cid, **{k: v for k, v in b.items() if k in ('title', 'pinned', 'archived', 'unread')})})

    async def h_messages(self, r):
        cid, chat = self.chat_or_404(r)
        if not chat:
            return E('chat not found', 404)
        before = float(r.query['before']) if 'before' in r.query else None
        return J({'messages': r['store'].messages(cid, min(200, max(1, int(r.query.get('limit', 80)))), before)})

    async def h_read(self, r):
        cid, chat = self.chat_or_404(r)
        if not chat:
            return E('chat not found', 404)
        r['store'].update_chat(cid, unread=0)
        return J({'ok': True})

    async def h_post_message(self, r):
        s, did = r['store'], r['device']['id']
        cid, chat = self.chat_or_404(r)
        if not chat:
            return E('chat not found', 404)
        b = await self.body(r)
        text = str(b.get('text') or '').strip()[:8000]
        atts = [a for a in (b.get('attachments') or []) if isinstance(a, dict)][:10]
        if not text and not atts:
            return E('empty')
        mode = str(b.get('mode') or 'send')
        rt = self.runtime(did)
        running = cid in rt['tasks'] and not rt['tasks'][cid].done()
        kind = {'steer': 'steer', 'queue': 'queued'}.get(mode, 'text') if running else 'text'
        if chat.get('title') in (None, '', 'New chat') and text and not text.startswith('/'):
            s.update_chat(cid, title=text[:48])
        # Arbitrary input is inert text. No slash handlers, providers, tools or URLs.
        msg = s.add_message(cid, 'user', text, kind=kind, meta={'mode': mode, 'attachments': atts, 'demo': True})
        depth = rt.setdefault('depth', {})
        if running and mode == 'steer':
            rt['steer'][cid] = text
        elif depth.get(cid, 0) < 3:
            depth[cid] = depth.get(cid, 0) + 1
            self.spawn_reply(did, s, cid, text, atts, rt['tasks'].get(cid) if running else None)
        return J({'message': msg})

    async def h_stop(self, r):
        did = r['device']['id']
        cid, chat = self.chat_or_404(r)
        if not chat:
            return E('chat not found', 404)
        self.stop_replies(did, cid)
        return J({'ok': True, 'demo': True})

    # ------------------------------------------------------------ scripted replies
    def spawn_reply(self, did, s, cid, text, atts, prev=None):
        rt = self.runtime(did)
        task = asyncio.get_running_loop().create_task(self.scripted(did, s, cid, text, atts, prev))
        rt['tasks'][cid] = task
        group = rt.setdefault('all', {}).setdefault(cid, set())
        group.add(task)
        task.add_done_callback(group.discard)
        return task

    def stop_replies(self, did, cid):
        for task in list(self.runtime(did).get('all', {}).get(cid, ())):
            task.cancel()

    def chat_state(self, s, cid):
        st = s.kv_get('model:' + cid) or {}
        d = self.defaults(s)
        settings = self.settings(s)
        model, provider = st.get('model') or d['model'], st.get('provider') or d['provider']
        caps = C.model_caps(provider, model)
        reasoning = st.get('reasoning') or settings.get('agent.reasoning_effort') or 'medium'
        tier = st.get('service_tier') or ('fast' if settings.get('agent.service_tier') == 'fast' else 'normal')
        return {'chat_id': cid, 'model': model, 'provider': provider, 'scope': 'chat' if st.get('model') else 'default',
                'default': dict(d), 'reasoning': reasoning, 'reasoning_scope': 'chat' if st.get('reasoning') else 'default',
                'fast': tier == 'fast' and caps.get('fast', False), 'service_tier': 'priority' if tier == 'fast' else 'normal', 'caps': caps}

    def compose(self, s, cid, text, atts):
        """Deterministic scripted answer and the synthetic steps shown on the Run screen."""
        chat = s.get_chat(cid) or {}
        steps = [{'tool': 'sandbox.read', 'what': 'Read your message (nothing executed)'}]
        lines = []
        for a in atts:
            kind, name, size = a.get('kind') or 'file', str(a.get('file_name') or a.get('id') or 'attachment')[:80], a.get('size')
            sz = f' ({int(size) // 1024 or 1} KB)' if isinstance(size, (int, float)) else ''
            lines.append({'voice': f'Voice note received{sz}. The sandbox does not transcribe audio, so this reply is scripted.',
                          'image': f'Photo received{sz}. The sandbox does not look at images.'}.get(kind, f'File received: **{name}**{sz}. Stored in this sandbox only; never opened.'))
        low = text.lower()
        if text.startswith('/'):
            cmd, _, arg = text[1:].partition(' ')
            steps.append({'tool': 'sandbox.command', 'what': f'/{cmd} (simulated)'})
            if cmd == 'help':
                lines.append('Commands in this sandbox:\n' + '\n'.join(f'- `/{n}` {d}' for n, d, _, _ in C.COMMANDS))
            elif cmd == 'retry':
                lines.append('Synthetic retry: here is the scripted answer again. Nothing re-ran.')
            elif cmd == 'moa':
                lines.append('**Mixture of agents (synthetic)**\n1. Planner: split the task into steps.\n2. Critic: check the risky step first.\n3. Writer: keep the answer short.\n\nNo models were called.')
            elif cmd in ('status', 'context', 'new', 'undo', 'compress', 'title', 'model', 'reasoning'):
                lines.append(f'`/{cmd}` is simulated here. Use the ⋯ menu or the model chip to see it in the app.')
            elif any(n == cmd for n, _, _, c in C.COMMANDS if c == 'Skill'):
                lines.append(f'Skill **{cmd}** (synthetic):\n- [x] Read the request\n- [ ] Draft the result\n- [ ] Ask before anything irreversible')
            else:
                lines.append(f'There is no `/{cmd}` command in the sandbox. Type `/` to see the list.')
        elif text:
            quoted = text if len(text) <= 160 else text[:157] + '…'
            lines.insert(0, f'Synthetic demo reply: message received.\n\n> {quoted}')
            if 'table' in low:
                lines.append('| Item | Status |\n|---|---|\n| Draft | done |\n| Review | next |')
            if 'code' in low:
                lines.append('```kotlin\nprintln("example only - not executed")\n```')
            if 'checklist' in low or 'plan' in low:
                lines.append('- [x] Understand the goal\n- [ ] Pick the first step\n- [ ] Check back')
            lines.append('No tools, shell commands, providers or external requests were used.')
        if chat.get('profile') and chat['profile'] != 'default':
            lines.append(f'_Profile **{chat["profile"]}**: its own synthetic model and memory._')
        if 'approval' in low:
            steps.append({'tool': 'sandbox.approval', 'what': 'Asked for an approval (nothing will run)'})
        steps.append({'tool': 'sandbox.reply', 'what': 'Wrote a scripted reply'})
        final = '\n\n'.join(lines) or 'Synthetic demo reply.'
        if self.settings(s).get('display.platforms.mobile.show_reasoning'):
            final = '💭 **Reasoning:**\n```\nScripted: the sandbox picks a fixed answer for this kind of message.\n```\n' + final
        return final, steps

    async def scripted(self, did, s, cid, text, atts, prev=None):
        """Stream a scripted reply the way the real adapter does: working flag, growing message, final usage."""
        rt = self.runtime(did)
        if prev is not None:
            try:
                await asyncio.shield(prev)
            except asyncio.CancelledError:
                if asyncio.current_task().cancelling():
                    # Stopped while still queued: release the queue slot, write nothing.
                    rt.setdefault('depth', {})[cid] = max(0, rt['depth'].get(cid, 1) - 1)
                    raise
            except Exception:
                pass
        since = time.time()
        rt['working'][cid] = since
        msg = None
        final, steps = self.compose(s, cid, text, atts)
        try:
            shown = []
            for i, st in enumerate(steps):
                shown.append({**st, 'ts': since + i * self.REPLY_STEP, 'status': 'ok'})
                s.kv_put('steps:' + cid, shown)
                await asyncio.sleep(self.REPLY_STEP)
            words = final.split(' ')
            for frac in (0.35, 0.7):
                part = ' '.join(words[:max(1, int(len(words) * frac))]) + ' …'
                msg = s.add_message(cid, 'agent', part, meta={'demo': True}) if msg is None else s.edit_message(msg['id'], content=part)
                await asyncio.sleep(self.REPLY_STEP)
            steer = rt['steer'].pop(cid, None)
            if steer:
                final += f'\n\n_Steer received: “{steer[:120]}”. The scripted answer took it into account._'
            usage = self.turn_usage(s, cid, text, final, since)
            meta = {'demo': True, 'usage': usage}
            msg = s.add_message(cid, 'agent', final, meta=meta) if msg is None else s.edit_message(msg['id'], content=final, meta=meta)
            s.put_turn(cid, usage)
            if 'approval' in text.lower():
                self.approval_card(s, cid, 'demo: archive example notes', 'Synthetic approval requested by your message. Approving records your choice; nothing runs.')
        except asyncio.CancelledError:
            if msg is not None:
                s.edit_message(msg['id'], content=(msg.get('content') or '').rstrip(' …') + ' …', meta={'demo': True, 'stopped': True})
            s.add_message(cid, 'agent', '⏹ Stopped. (synthetic run)', kind='status', meta={'quiet': True, 'demo': True})
        finally:
            rt['working'].pop(cid, None)
            rt.setdefault('depth', {})[cid] = max(0, rt['depth'].get(cid, 1) - 1)
            if rt['tasks'].get(cid) is asyncio.current_task():
                rt['tasks'].pop(cid, None)

    async def idle(self, did):
        """Test helper: wait until no scripted work is pending for this tenant."""
        rt = self.runtime(did)
        while True:
            pending = [t for t in [*rt['tasks'].values(), *rt['session_tasks'].values(), *(x for g in rt.get('all', {}).values() for x in g)] if not t.done()]
            if not pending:
                return
            await asyncio.gather(*pending, return_exceptions=True)

    def turn_usage(self, s, cid, text, final, since):
        st = self.chat_state(s, cid)
        prompt = 900 + len(text) // 4
        out = max(1, len(final) // 4)
        turn = {'model': st['model'], 'provider': st['provider'], 'input_tokens': prompt, 'output_tokens': out,
                'cache_read_tokens': 0, 'cache_write_tokens': 0, 'reasoning_tokens': 0, 'prompt_tokens': prompt,
                'cache_hit': 0.0, 'calls': 1, 'tok_s': 40.0, 'cost': 0.0, 'cost_status': 'estimated',
                'context_used': prompt + out, 'context_max': 128000, 'updated': time.time(), 'demo': True}
        sess = s.kv_get('usage:' + cid) or {k: 0 for k in ('input_tokens', 'output_tokens', 'cache_read_tokens', 'cache_write_tokens', 'reasoning_tokens', 'calls', 'messages')}
        for k in ('input_tokens', 'output_tokens', 'calls'):
            sess[k] = sess.get(k, 0) + turn[k]
        sess['messages'] = sess.get('messages', 0) + 2
        s.kv_put('usage:' + cid, sess)
        return turn

    def session_usage(self, s, cid):
        u = s.kv_get('usage:' + cid)
        if not u:
            return None
        st = self.chat_state(s, cid)
        prompt = u['input_tokens'] + u.get('cache_read_tokens', 0)
        return {**u, 'model': st['model'], 'session_id': 'sandbox-' + cid, 'tool_calls': 0, 'cost': 0.0, 'cost_status': 'estimated',
                'prompt_tokens': prompt, 'cache_hit': 0.0 if prompt else None}

    async def h_run(self, r):
        s, did = r['store'], r['device']['id']
        cid, chat = self.chat_or_404(r)
        if not chat:
            return E('chat not found', 404)
        msgs = s.messages(cid, limit=120)
        start = max([i for i, m in enumerate(msgs) if m['role'] == 'user' and m['kind'] == 'text'] or [0])
        since = self.runtime(did)['working'].get(cid)
        u = self.session_usage(s, cid) or {}
        return J({'chat_id': cid, 'working': since is not None, 'since': since or (msgs[start]['ts'] if msgs else None),
                  'steps': s.kv_get('steps:' + cid) or [], 'model': self.chat_state(s, cid)['model'],
                  'tokens': u.get('input_tokens', 0) + u.get('output_tokens', 0), 'cost': 0.0, 'usage': s.get_turn(cid),
                  'session': self.session_usage(s, cid), 'turn': msgs[start:],
                  'approvals': [a for a in s.pending_approvals() if a['chat_id'] == cid], 'demo': True})

    async def h_chat_usage(self, r):
        s = r['store']
        cid, chat = self.chat_or_404(r)
        if not chat:
            return E('chat not found', 404)
        return J({'chat_id': cid, 'turn': s.get_turn(cid), 'session': self.session_usage(s, cid), 'demo': True})

    # ------------------------------------------------------------ model, confirm, session actions
    async def h_models(self, r):
        s = r['store']
        d = self.defaults(s)
        models = json.loads(json.dumps(C.MODELS))
        for p in models['providers']:
            p['is_current'] = p['slug'] == d['provider']
        recent = s.kv_get('recent_models') or [{'model': 'synthetic-fast', 'provider': 'sandbox'}, {'model': 'example-mini', 'provider': 'example'}]
        return J({**models, 'recent': recent[:8], 'fetched': time.time(), 'default': dict(d), 'demo': True})

    async def h_chat_model(self, r):
        return J(self.chat_state(r['store'], r.match_info['cid']))

    async def h_set_chat_model(self, r):
        s, did = r['store'], r['device']['id']
        cid = r.match_info['cid']
        b = await self.body(r)
        replies, scope = [], 'global' if b.get('scope') == 'global' else 'chat'

        def result(error='', confirm=None):
            return J({'ok': not error, 'replies': replies, 'error': error, 'confirm': confirm, 'state': self.chat_state(s, cid)})

        model, prov = str(b.get('model') or '').strip(), str(b.get('provider') or '').strip()
        if model:
            if not re.fullmatch(r'[\w.:/@+\-]+', model) or (prov and not re.fullmatch(r'[\w.:\-]+', prov)):
                return E('bad model name')
            if cid in self.runtime(did)['working']:
                return result('Agent is running. Wait or stop the run before switching models.')
            prov = prov or next((p['slug'] for p in C.MODELS['providers'] if model in p['models']), '')
            if not C.known_model(prov, model):
                return result(f'❌ Unknown model {model} in the sandbox catalogue.')
            if model in C.CONFIRM_MODELS:
                cf = {'id': S.new_id('cf'), 'model': model, 'provider': prov, 'scope': scope}
                s.kv_put('confirm:' + cid, cf)
                return result(confirm={'id': cf['id'], 'message': f'⚠️ **{model}** is the sandbox\'s "expensive" model. Switching would raise the cost per turn (illustrative only; nothing is billed). Switch anyway?'})
            replies.append(self.switch_model(s, cid, model, prov, scope))
        eff = str(b.get('reasoning') or '').strip().lower()
        if eff:
            if eff not in C.EFFORTS + ['reset']:
                return E('bad reasoning level')
            if scope == 'global':
                self.settings_put(s, 'agent.reasoning_effort', eff if eff != 'reset' else 'medium')
            else:
                st = s.kv_get('model:' + cid) or {}
                st.pop('reasoning', None) if eff == 'reset' else st.update(reasoning=eff)
                s.kv_put('model:' + cid, st)
            replies.append(f'Reasoning: {eff} (synthetic)')
        if 'fast' in b:
            if not isinstance(b['fast'], bool):
                return E('fast must be a boolean')
            state = self.chat_state(s, cid)
            if b['fast'] and not state['caps'].get('fast'):
                return result(f'❌ Fast mode is not offered for {state["model"]}.')
            if scope == 'global':
                self.settings_put(s, 'agent.service_tier', 'fast' if b['fast'] else 'normal')
            else:
                st = s.kv_get('model:' + cid) or {}
                st['service_tier'] = 'fast' if b['fast'] else 'normal'
                s.kv_put('model:' + cid, st)
            replies.append('Fast mode ' + ('on' if b['fast'] else 'off') + ' (synthetic)')
        return result()

    def switch_model(self, s, cid, model, prov, scope):
        if scope == 'global':
            s.kv_put('defaults', {'model': model, 'provider': prov})
        else:
            st = s.kv_get('model:' + cid) or {}
            st.update(model=model, provider=prov)
            s.kv_put('model:' + cid, st)
        recent = [x for x in (s.kv_get('recent_models') or []) if x['model'] != model]
        s.kv_put('recent_models', [{'model': model, 'provider': prov}, *recent][:8])
        return f'Model switched to {model} (synthetic)'

    async def h_confirm(self, r):
        s = r['store']
        cid = r.match_info['cid']
        b = await self.body(r)
        choice = str(b.get('choice') or '')
        if choice not in ('once', 'always', 'cancel'):
            return E('bad choice')
        cf = s.kv_get('confirm:' + cid)
        if not cf or cf.get('id') != b.get('id'):
            return J({'ok': False, 'error': 'That confirmation expired'}, 410)
        s.kv_put('confirm:' + cid, {})
        if choice == 'cancel':
            return J({'ok': False, 'reply': '🟡 Kept the current model.', 'state': self.chat_state(s, cid)})
        return J({'ok': True, 'reply': self.switch_model(s, cid, cf['model'], cf['provider'], cf['scope']), 'state': self.chat_state(s, cid)})

    async def h_chat_session(self, r):
        s, did = r['store'], r['device']['id']
        cid, chat = self.chat_or_404(r)
        if not chat:
            return E('unknown chat', 404)
        b = await self.body(r)
        action, arg = str(b.get('action') or ''), str(b.get('arg') or '').strip().replace('\n', ' ')[:120]
        if action not in ('new', 'undo', 'compress', 'title', 'save', 'status', 'context'):
            return E('bad action')
        if action in ('new', 'undo', 'compress') and cid in self.runtime(did)['working']:
            return E('Hermes Demo is working in this chat. Stop the run first.', 409)
        msgs = s.messages(cid, limit=400)
        live = [m for m in msgs if not m['meta'].get('undone') and m['kind'] != 'deleted']
        st, u = self.chat_state(s, cid), self.session_usage(s, cid) or {}
        out = {'ok': True}
        if action == 'status':
            out['reply'] = (f'## Session status (synthetic)\n| | |\n|---|---|\n| Chat | {chat["title"]} |\n| Model | {st["model"]} · {st["provider"]} |\n'
                            f'| Reasoning | {st["reasoning"]} |\n| Messages | {len(live)} |\n| Tokens | {u.get("input_tokens", 0) + u.get("output_tokens", 0)} |\n| Cost | $0.00 (sandbox) |\n\n{NOTE}')
        elif action == 'context':
            used = sum(len(m['content']) for m in live) // 4 + 900
            out['reply'] = (f'## Context window (synthetic)\n`{used:,}` of `128,000` tokens ({used * 100 // 128000}%).\n\n'
                            f'- System prompt and tools: 900\n- Conversation: {used - 900}\n\n{NOTE}')
        elif action == 'title':
            if not arg:
                return E('title required')
            s.update_chat(cid, title=arg[:80])
            out['reply'] = f'✏️ Title set to “{arg[:80]}”.'
        elif action == 'save':
            body = f'# {chat["title"]}\n\n' + '\n\n'.join(f'**{m["role"]}:** {m["content"]}' for m in live if m['kind'] == 'text') + f'\n\n_{NOTE}_\n'
            name = self.store_upload(r, body.encode())
            fname = re.sub(r'[^\w.-]+', '-', chat['title'])[:40].strip('-') + '.md'
            s.add_message(cid, 'agent', 'Exported this chat as markdown.', kind='file',
                          meta={'id': name, 'media': name, 'kind': 'file', 'file_name': fname, 'mime': 'text/markdown', 'size': len(body.encode()), 'demo': True})
            out['reply'] = '💾 Exported. The file is in this chat.'
        elif action == 'undo':
            idx = next((i for i in range(len(msgs) - 1, -1, -1) if msgs[i]['role'] == 'user' and msgs[i]['kind'] == 'text'
                        and not msgs[i]['content'].startswith('/') and not msgs[i]['meta'].get('undone')), None)
            if idx is None:
                out.update(ok=False, reply='⚠️ Nothing to undo.')
            else:
                out['text'] = msgs[idx]['content']
                for m in msgs[idx:]:
                    if m['kind'] != 'deleted':
                        s.edit_message(m['id'], meta={'undone': True})
                out['reply'] = '↩ Undid the last turn. (synthetic)'
        elif action == 'new':
            s.kv_put('usage:' + cid, None)
            out['reply'] = '✨ New session started. Earlier messages stay visible but leave the context. (synthetic)'
        else:
            out['reply'] = f'🗜️ Compressed {max(0, len(live) - 2)} earlier messages into a short summary. (synthetic)'
        if action in ('new', 'undo', 'compress') and out['ok']:
            s.add_message(cid, 'agent', out['reply'], kind='status', meta={'session_event': action, 'quiet': True, 'demo': True})
        return J(out)

    # ------------------------------------------------------------ approvals
    async def h_approve(self, r):
        s = r['store']
        aid = r.match_info['aid']
        b = await self.body(r)
        choice = str(b.get('choice') or '')
        if choice not in ('once', 'session', 'always', 'deny'):
            return E('bad choice')
        if choice in ('session', 'always') and (r['device'].get('nolock') or '') == '1':
            return E('This phone has no screen lock, so it can only approve once', 403)
        a = s.get_approval(aid)
        if not a:
            return E('unknown approval', 404)
        # Denying is not new authority (as in production); every approving choice needs the exact-content proof.
        if choice != 'deny' and not self.signed(r, f"approve\n{aid}\n{choice}\n{r.headers.get('X-HM-Ts', '')}".encode()):
            return E('device confirmation required', 403, need_sig=True)
        if a['state'] != 'pending':
            return J({'approval': a, 'resolved': 0})
        a = s.set_approval(aid, 'denied' if choice == 'deny' else 'approved', choice)
        s.add_message(a['chat_id'], 'agent', ('🛑 Denied' if choice == 'deny' else f'✅ Approved ({choice})') + '. ' + NOTE,
                      kind='status', meta={'quiet': True, 'demo': True})
        return J({'approval': a, 'resolved': 1, 'demo': True})

    async def h_activity(self, r):
        s = r['store']
        msg = s.get_message(r.match_info['mid'])
        if not msg or msg['kind'] != 'activity':
            return E('not found', 404)
        calls = s.calls(msg['id'])
        files = {}
        for c in calls:
            if c['tool'] in ('write_file', 'patch') and c.get('path') and c['status'] != 'error':
                f = files.setdefault(c['path'], {'path': c['path'], 'added': 0, 'removed': 0, 'diff': ''})
                f['added'] += c.get('added') or 0
                f['removed'] += c.get('removed') or 0
                f['diff'] += c.get('diff') or ''
        return J({'message': msg, 'calls': calls, 'files': list(files.values())})

    # ------------------------------------------------------------ files and voice
    def tenant_bytes(self, did):
        d = self.home / did
        return sum(p.stat().st_size for p in d.iterdir() if p.name.startswith('u-') and p.is_file())

    def store_upload(self, r, data):
        did = r['device']['id']
        if self.tenant_bytes(did) + len(data) > TENANT_QUOTA:
            raise web.HTTPRequestEntityTooLarge(max_size=TENANT_QUOTA, actual_size=len(data),
                                                text=json.dumps({'error': 'Sandbox storage is full for this enrollment. Get a fresh pairing link for a clean sandbox.'}),
                                                content_type='application/json')
        name = S.new_id('u')
        (self.home / did / name).write_bytes(data)
        return name

    async def h_upload(self, r):
        data = await r.read()
        if not data:
            return E('empty upload')
        name = self.store_upload(r, data)
        mime = r.query.get('mime', '')
        mime = mime if re.fullmatch(r'[\w.+-]+/[\w.+-]+', mime or '') else 'application/octet-stream'
        return J({'id': name, 'size': len(data), 'mime': mime, 'file_name': Path(r.query.get('name', 'demo.bin')).name[:120]})

    async def h_media(self, r):
        name, did = r.match_info['name'], r['device']['id']
        if name == 'checklist.txt':
            return web.Response(body=self.sample_file(), content_type='text/plain')
        if name in self.runtime(did)['tts']:
            return web.Response(body=CHIME, content_type='audio/wav')
        if not name.startswith('u-') or any(c not in 'u-0123456789abcdef' for c in name):
            return E('not found', 404)
        path = self.home / did / name
        if not path.is_file():
            return E('not found', 404)
        return web.Response(body=path.read_bytes(), content_type='application/octet-stream')

    async def h_tts(self, r):
        b = await self.body(r)
        if not str(b.get('text') or '').strip():
            return E('text required')
        tts = self.runtime(r['device']['id'])['tts']
        name = S.new_id('tts') + '.wav'
        tts.append(name)
        del tts[:-50]
        return J({'ids': [name], 'provider': 'sandbox-chime', 'demo': True})

    # ------------------------------------------------------------ pulse, usage, search
    def sessions(self, s):
        def make():
            now = time.time()
            out = []
            for x in C.SESSIONS:
                start = now - x['age']
                msgs = [{'id': f"{x['id']}:{i}", 'role': role, 'content': text, 'timestamp': start + 60 * i} for i, (role, text) in enumerate(x['messages'])]
                prompts = [{**p, 'generation': 1, 'state': 'pending'} for p in x['prompts']]
                out.append({'id': x['id'], 'source': x['source'], 'title': x['title'], 'model': x['model'], 'started_at': start,
                            'ended_at': None if x['live'] else start + 600, 'live': x['live'], 'read_only': x.get('read_only', False),
                            'messages': msgs, 'next': len(msgs), 'prompts': prompts, 'running': False, 'generation': 1, 'submissions': []})
            return out
        return self.kv(s, 'sessions', make)

    async def h_pulse(self, r):
        s = r['store']
        now = time.time()
        live = [{'id': x['id'], 'source': x['source'], 'title': x['title'], 'model': x['model'], 'started_at': x['started_at'],
                 'last': now - 90} for x in self.sessions(s) if x['live']]
        failed = sum(1 for j in self.jobs(s) if j.get('last_status') not in (None, 'ok', 'success'))
        return J({'spend_today': C.SPEND_7D[-1], 'runs_today': 3, 'failures_today': failed, 'spend_7d': C.SPEND_7D,
                  'live_sessions': live, 'working': self.working_list(r['device']['id']), 'approvals': s.pending_approvals(), 'demo': True})

    async def h_usage(self, r):
        days = max(1, min(int(r.query.get('days', 30) or 30), 120))
        today = datetime.now().date()
        series = []
        for i in range(days):
            back = days - 1 - i
            cost = C.SPEND_7D[6 - back] if back < 7 else 0.0
            series.append({'day': (today - timedelta(days=back)).isoformat(), 'cost': cost, 'tokens': int(cost * 200000), 'sessions': 3 if cost else 0})
        total = round(sum(x['cost'] for x in series), 4)
        sessions = sum(x['sessions'] for x in series)
        tokens = sum(x['tokens'] for x in series)
        models = [{'model': 'synthetic-demo', 'provider': 'sandbox', 'cost': round(total * 0.7, 4), 'tokens': int(tokens * 0.7), 'sessions': sessions - 4,
                   'calls': (sessions - 4) * 3, 'output': int(tokens * 0.07), 'cache_read': int(tokens * 0.3), 'prompt': int(tokens * 0.63), 'unpriced': 0,
                   'included': False, 'cache_hit': 0.476},
                  {'model': 'example-mini', 'provider': 'example', 'cost': round(total * 0.3, 4), 'tokens': int(tokens * 0.3), 'sessions': 4,
                   'calls': 9, 'output': int(tokens * 0.03), 'cache_read': 0, 'prompt': int(tokens * 0.27), 'unpriced': 0, 'included': False, 'cache_hit': 0.0}]
        sources = [{'source': 'mobile', 'cost': round(total * 0.6, 4), 'sessions': sessions - 6},
                   {'source': 'cli', 'cost': round(total * 0.3, 4), 'sessions': 4}, {'source': 'telegram', 'cost': round(total * 0.1, 4), 'sessions': 2}]
        return J({'days': days, 'total': total, 'sessions': sessions, 'unpriced_sessions': 0, 'included_sessions': 0,
                  'suspect': {'sessions': 0, 'cost': 0.0}, 'series': series, 'models': models, 'sources': sources, 'demo': True})

    @staticmethod
    def matcher(q):
        """Small search language: words (AND), "phrases", OR, NOT word, prefix*."""
        tokens = re.findall(r'"[^"]+"|\S+', q.lower())
        groups, neg, cur = [], [], []
        i = 0
        while i < len(tokens):
            t = tokens[i]
            if t == 'or':
                groups.append(cur)
                cur = []
            elif t == 'not' and i + 1 < len(tokens):
                neg.append(tokens[i + 1].strip('"').rstrip('*'))
                i += 1
            elif t != 'and':
                cur.append(t.strip('"').rstrip('*'))
            i += 1
        groups.append(cur)
        groups = [g for g in groups if g]

        def hit(text):
            low = text.lower()
            if any(n and n in low for n in neg):
                return None
            for g in groups:
                if all(w in low for w in g):
                    return g[0]
            return None
        return hit

    @staticmethod
    def snippet(text, word):
        i = text.lower().find(word)
        a = max(0, i - 40)
        return ('…' if a else '') + text[a:i] + '>>>' + text[i:i + len(word)] + '<<<' + text[i + len(word):i + len(word) + 80]

    async def h_search(self, r):
        s = r['store']
        q = (r.query.get('q') or '').strip()
        if len(q) < 2:
            return J({'results': [], 'query': q})
        hit, out = self.matcher(q), []
        for c in s.list_chats(True):
            for m in s.messages(c['id']):
                w = hit(m['content']) if m['kind'] == 'text' else None
                if w:
                    out.append({'id': m['id'], 'session_id': 'sandbox-' + c['id'], 'chat_id': c['id'], 'source': 'mobile', 'title': c['title'],
                                'role': 'user' if m['role'] == 'user' else 'assistant', 'snippet': self.snippet(m['content'], w), 'timestamp': m['ts']})
        for x in self.sessions(s):
            for m in x['messages']:
                w = hit(m['content'])
                if w:
                    out.append({'id': m['id'], 'session_id': x['id'], 'chat_id': None, 'source': x['source'], 'title': x['title'],
                                'role': m['role'], 'snippet': self.snippet(m['content'], w), 'timestamp': m['timestamp']})
        out.sort(key=lambda x: -x['timestamp'])
        return J({'results': out[:40], 'query': q})

    # ------------------------------------------------------------ other sessions (Elsewhere)
    @staticmethod
    def session_add(x, role, content):
        n = x.get('next', len(x['messages']))
        x['messages'] = (x['messages'] + [{'id': f"{x['id']}:{n}", 'role': role, 'content': content, 'timestamp': time.time()}])[-500:]
        x['next'] = n + 1

    def session_or_404(self, s, sid):
        return next((x for x in self.sessions(s) if x['id'] == sid), None)

    def save_session(self, s, sess):
        all_ = self.sessions(s)
        s.kv_put('sessions', [sess if x['id'] == sess['id'] else x for x in all_])

    async def h_sessions(self, r):
        return J({'sessions': [{k: x[k] for k in ('id', 'source', 'title', 'model', 'started_at', 'ended_at')} | {'cost': 0.0}
                               for x in sorted(self.sessions(r['store']), key=lambda x: -x['started_at'])]})

    async def h_session_messages(self, r):
        s = r['store']
        x = self.session_or_404(s, r.match_info['sid'])
        if not x:
            return E('session not found', 404)
        limit = int(r.query.get('limit', '40'))
        if not 1 <= limit <= 100:
            return E('limit must be between 1 and 100')
        before = int(r.query['before']) if r.query.get('before') else None
        msgs = x['messages']
        end = len(msgs) if before is None else min(before, len(msgs))
        start = max(0, end - limit)
        page = [{**m, 'position': i} for i, m in enumerate(msgs) if start <= i < end]
        return J({'session_id': x['id'], 'messages': page, 'nextBefore': start if start > 0 else None, 'hasMore': start > 0,
                  'running': x['running'], 'generation': x['generation'], 'prompts': x['prompts'], 'pending': [],
                  'readOnly': x['read_only'], 'info': {'title': x['title'], 'model': x['model'], 'source': x['source']}})

    async def h_session_send(self, r):
        s, did = r['store'], r['device']['id']
        x = self.session_or_404(s, r.match_info['sid'])
        if not x:
            return E('session not found', 404)
        b = await self.body(r)
        text, sub = str(b.get('text') or '').strip(), str(b.get('submission_id') or '')[:100]
        if not text:
            return E('empty message')
        if len(text) > 60000:
            return E('message is too long')
        if x['read_only']:
            return E('This session was superseded. Open its continuation to send messages.', 409)
        if sub and sub in x['submissions']:
            return J({'ok': True, 'submission_id': sub, 'duplicate': True})
        self.session_add(x, 'user', text[:8000])
        x['submissions'] = [*x['submissions'], sub][-50:] if sub else x['submissions']
        x['running'], x['generation'] = True, x['generation'] + 1
        self.save_session(s, x)
        self.runtime(did)['session_tasks'][x['id']] = asyncio.get_running_loop().create_task(self.session_reply(s, x['id'], x['generation']))
        return J({'ok': True, 'submission_id': sub, 'queued': True})

    async def session_reply(self, s, sid, generation):
        await asyncio.sleep(self.REPLY_STEP * 3)
        x = self.session_or_404(s, sid)
        if x and x['running'] and x['generation'] == generation:
            self.session_add(x, 'assistant', f"Synthetic {x['source']} session reply: message received. {NOTE}")
            x['running'] = False
            self.save_session(s, x)

    async def h_session_prompt(self, r):
        s = r['store']
        sid, pid = r.match_info['sid'], r.match_info['pid']
        x = self.session_or_404(s, sid)
        if not x:
            return E('session not found', 404)
        b = await self.body(r)
        kind, choice = str(b.get('kind') or ''), str(b.get('choice') or '')
        prompt = next((p for p in x['prompts'] if p['prompt_id'] == pid), None)
        if not prompt or kind not in ('approval', 'clarify') or prompt['kind'] != kind:
            return E('invalid prompt response')
        if int(b.get('generation') or b.get('execution_generation') or 0) != prompt['generation']:
            return E('That prompt belongs to an earlier run.', 409)
        if prompt['state'] != 'pending':
            return J({'ok': True, 'duplicate': True})
        if kind == 'approval':
            if choice not in ('once', 'session', 'always', 'deny'):
                return E('bad choice')
            if choice in ('session', 'always') and (r['device'].get('nolock') or '') == '1':
                return E('This phone has no screen lock, so it can only approve once', 403)
            if choice != 'deny' and not self.signed(r, f"approve\n{sid}:{pid}\n{choice}\n{r.headers.get('X-HM-Ts', '')}".encode()):
                return E('biometric signature required', 403)
            prompt['state'] = 'denied' if choice == 'deny' else 'answered'
            reply = ('Denied. Nothing ran.' if choice == 'deny' else f'Approved ({choice}). {NOTE}')
        else:
            answer = str(b.get('answer') or '').strip()
            if not answer or len(answer) > 16384:
                return E('invalid answer')
            prompt['state'] = 'answered'
            self.session_add(x, 'user', answer[:2000])
            reply = f'Thanks, noted: “{answer[:120]}”. (synthetic)'
        self.session_add(x, 'assistant', reply)
        self.save_session(s, x)
        return J({'ok': True})

    async def h_session_stop(self, r):
        s = r['store']
        x = self.session_or_404(s, r.match_info['sid'])
        if not x:
            return E('session not found', 404)
        b = await self.body(r)
        if int(b.get('generation') or 0) < 1:
            return E('invalid execution generation')
        if x['running']:
            x['running'] = False
            self.session_add(x, 'system', 'Interrupted. (synthetic)')
            self.save_session(s, x)
        return J({'ok': True})

    async def h_session_close(self, r):
        return J({'ok': True})

    # ------------------------------------------------------------ cron
    def default_jobs(self, now):
        return [
            {'id': 'demo-digest', 'name': 'Synthetic morning digest', 'schedule_expr': 'every day at 9am', 'enabled': True, 'deliver': 'mobile',
             'prompt': 'Prepare a synthetic checklist', 'model': '', 'provider': '', 'reasoning_effort': '', 'skills': [], 'repeat': None,
             'last_status': 'ok', 'last_error': '', 'paused_reason': '', 'created_at': now - 86400 * 3, 'last_run_at': None},
            {'id': 'demo-weekly', 'name': 'Synthetic weekly review', 'schedule_expr': 'every monday 9am', 'enabled': False,
             'deliver': 'telegram,mobile', 'prompt': 'Summarize the week (synthetic)', 'model': 'example-mini', 'provider': 'example',
             'reasoning_effort': 'low', 'skills': ['meeting-notes'], 'repeat': None, 'last_status': 'ok', 'last_error': '',
             'paused_reason': 'Paused in the sandbox', 'created_at': now - 86400 * 9, 'last_run_at': None}]

    def tz(self, s):
        name = self.settings(s).get('timezone') or ''
        try:
            return ZoneInfo(name) if name else timezone.utc
        except Exception:
            return timezone.utc

    def job_view(self, s, j):
        sched = parse_schedule(j['schedule_expr'])
        now, base = time.time(), j.get('last_run_at') or j['created_at']
        if not j['enabled']:
            nxt = None
        elif sched['kind'] == 'cron':
            nxt = next_cron(sched['expr'], now, self.tz(s))
        elif sched['kind'] == 'interval':
            nxt = base + sched['seconds'] * max(1, math.ceil((now - base) / sched['seconds']) if now > base else 1)
        else:
            nxt = j['created_at'] + sched['seconds'] if not j.get('last_run_at') else None
        outs = s.kv_get('cron_out:' + j['id']) or {}
        return {**{k: j.get(k) for k in ('id', 'name', 'enabled', 'deliver', 'model', 'provider', 'reasoning_effort', 'skills', 'repeat',
                                       'last_status', 'last_error', 'paused_reason', 'created_at', 'last_run_at', 'prompt')},
                'schedule': sched['display'], 'schedule_display': sched['display'], 'schedule_expr': j['schedule_expr'],
                'next_run_at': nxt, 'failure_streak': 0, 'runs': sorted(outs, reverse=True)[:14], 'demo': True}

    def jobs(self, s):
        jobs = self.kv(s, 'jobs', lambda: self.default_jobs(time.time()))
        # Due runs materialise lazily when someone looks: synthetic output, never a scheduler or tool.
        changed = False
        for j in jobs:
            v = self.job_view(s, j)
            due = v['next_run_at']
            if j['enabled'] and due and due <= time.time() and (j.get('last_run_at') or 0) < due:
                self.cron_deliver(s, j, f"# {j['name']}\nScheduled synthetic run. {NOTE}", due)
                changed = True
        if changed:
            s.kv_put('jobs', jobs)
        return jobs

    def cron_deliver(self, s, j, text, ts):
        outs = s.kv_get('cron_out:' + j['id']) or {}
        outs[run_id(ts)] = text
        s.kv_put('cron_out:' + j['id'], dict(sorted(outs.items())[-14:]))
        j.update(last_run_at=ts, last_status='ok', last_error='')
        if parse_schedule(j['schedule_expr'])['kind'] == 'once':
            j['enabled'] = False
        if 'mobile' in j['deliver']:
            s.add_message('cron-job-' + j['id'], 'agent', text, meta={'delivery': True, 'job_id': j['id'], 'demo': True},
                          title=j['name'], chat_kind='digest')

    def save_job(self, s, job):
        s.kv_put('jobs', [job if x['id'] == job['id'] else x for x in self.jobs(s)])

    async def h_cron(self, r):
        s = r['store']
        return J({'jobs': [self.job_view(s, j) for j in self.jobs(s)], 'now': time.time(), 'demo': True})

    async def h_cron_action(self, r):
        s = r['store']
        jid, action = r.match_info['jid'], r.match_info['action']
        job = next((j for j in self.jobs(s) if j['id'] == jid), None)
        if action not in ('pause', 'resume', 'run'):
            return E('bad action')
        if not job:
            return E('unknown job', 404)
        if action == 'run':
            self.cron_deliver(s, job, f"# {job['name']}\nRun requested from the app at {datetime.now(self.tz(s)).strftime('%H:%M')}. {NOTE}", time.time())
        else:
            job['enabled'] = action == 'resume'
            job['paused_reason'] = '' if job['enabled'] else 'Paused from the app'
        self.save_job(s, job)
        return J({'job': self.job_view(s, job), 'demo': True})

    async def h_cron_output(self, r):
        s = r['store']
        outs = s.kv_get('cron_out:' + r.match_info['jid']) or {}
        run = r.query.get('run')
        key = run if run in outs else (sorted(outs)[-1] if outs else None)
        if not key:
            return E('no output yet', 404)
        return J({'run': key, 'text': outs[key]})

    def job_fields(self, b, create):
        out = {}
        for k in ('name', 'schedule', 'prompt', 'deliver', 'model', 'provider', 'reasoning_effort', 'skills', 'repeat'):
            if k not in b:
                continue
            v = b[k]
            if k == 'skills':
                v = [x.strip()[:80] for x in (v or []) if isinstance(x, str) and x.strip()][:10]
            elif k == 'repeat':
                v = int(v) if v not in (None, '', 0) else None
            else:
                v = str(v or '').strip()[:4000]
            out[k] = v
        if 'deliver' in out and out['deliver'] not in ('mobile', 'telegram,mobile', 'telegram', 'local', ''):
            raise ValueError('bad deliver')
        if 'reasoning_effort' in out and out['reasoning_effort'] not in ('', *C.EFFORTS):
            raise ValueError('bad reasoning')
        return out

    async def h_cronjob_get(self, r):
        s = r['store']
        job = next((j for j in self.jobs(s) if j['id'] == r.match_info['jid']), None)
        return J(self.job_view(s, job)) if job else E('unknown job', 404)

    async def h_cronjob_create(self, r):
        s = r['store']
        f = self.job_fields(await self.body(r), True)
        if not f.get('schedule'):
            return J({'success': False, 'error': "A schedule is required (e.g. 'every 2h', '0 9 * * *', '30m')."}, 400)
        if not f.get('prompt') and not f.get('skills'):
            return J({'success': False, 'error': 'Give the job a prompt or at least one skill.'}, 400)
        try:
            parse_schedule(f['schedule'])
        except ValueError as e:
            return J({'success': False, 'error': str(e)}, 400)
        jobs = self.jobs(s)
        if len(jobs) >= 40:
            return J({'success': False, 'error': 'The sandbox keeps at most 40 jobs. Delete one first.'}, 400)
        job = {'id': S.new_id('job')[:16], 'name': f.get('name') or f['prompt'][:40] or 'Synthetic job', 'schedule_expr': f.pop('schedule'),
               'enabled': True, 'deliver': f.get('deliver') or 'mobile', 'prompt': f.get('prompt', ''), 'model': f.get('model', ''),
               'provider': f.get('provider', ''), 'reasoning_effort': f.get('reasoning_effort', ''), 'skills': f.get('skills', []),
               'repeat': f.get('repeat'), 'last_status': None, 'last_error': '', 'paused_reason': '', 'created_at': time.time(), 'last_run_at': None}
        s.kv_put('jobs', [*jobs, job])
        return J({'success': True, 'job_id': job['id'], 'job': self.job_view(s, job), 'message': 'Scheduled (synthetic: no scheduler runs it)'})

    async def h_cronjob_update(self, r):
        s = r['store']
        job = next((j for j in self.jobs(s) if j['id'] == r.match_info['jid']), None)
        if not job:
            return J({'success': False, 'error': 'unknown job'}, 404)
        f = self.job_fields(await self.body(r), False)
        if 'schedule' in f:
            try:
                parse_schedule(f['schedule'])
            except ValueError as e:
                return J({'success': False, 'error': str(e)}, 400)
            job['schedule_expr'] = f.pop('schedule')
        if not (f.get('prompt', job['prompt']) or f.get('skills', job['skills'])):
            return J({'success': False, 'error': 'Give the job a prompt or at least one skill.'}, 400)
        job.update({k: v for k, v in f.items() if k != 'deliver' or v})
        self.save_job(s, job)
        return J({'success': True, 'job_id': job['id'], 'job': self.job_view(s, job)})

    async def h_cronjob_delete(self, r):
        s = r['store']
        jid = r.match_info['jid']
        jobs = self.jobs(s)
        if not any(j['id'] == jid for j in jobs):
            return J({'success': False, 'error': 'unknown job'}, 404)
        s.kv_put('jobs', [j for j in jobs if j['id'] != jid])
        return J({'success': True, 'message': 'Removed (past outputs stay in the sandbox)'})

    def delivery_summary(self, s):
        counts = {}
        for j in self.jobs(s):
            counts[j['deliver'] or 'local'] = counts.get(j['deliver'] or 'local', 0) + 1
        return {'counts': counts, 'total': sum(counts.values())}

    async def h_cron_delivery(self, r):
        s = r['store']
        if r.method == 'GET':
            return J(self.delivery_summary(s))
        b = await self.body(r)
        target, only = str(b.get('deliver') or ''), str(b.get('only_from') or '')
        if target not in ('mobile', 'telegram,mobile', 'telegram', 'local'):
            return J({'success': False, 'error': "deliver must be one of ['mobile', 'telegram,mobile', 'telegram', 'local']"}, 400)
        moved, jobs = [], self.jobs(s)
        for j in jobs:
            d = j['deliver'] or 'local'
            if d == target or (only and d != only) or d == 'local':
                continue
            j['deliver'] = target
            moved.append(j['name'])
        s.kv_put('jobs', jobs)
        return J({'success': True, 'moved': moved, 'failed': [], **self.delivery_summary(s)})

    # ------------------------------------------------------------ memory
    async def h_memory(self, r):
        s = r['store']
        mem = s.kv_get('memory')
        settings = self.settings(s)
        if r.method == 'POST':
            b = await self.body(r)
            target, action = b.get('target'), b.get('action')
            if target not in mem or action not in ('add', 'replace', 'remove'):
                return J({'success': False, 'error': 'target must be memory|user and action add|replace|remove'}, 400)
            content = str(b.get('content') or '').strip()[:4000]
            if action != 'remove' and not content:
                return J({'success': False, 'error': 'Content is empty.'}, 400)
            if action == 'add':
                mem[target].append(content)
            else:
                if b.get('old_text') not in mem[target]:
                    return J({'success': False, 'error': 'entry not found'}, 409)
                i = mem[target].index(b['old_text'])
                if action == 'remove':
                    mem[target].pop(i)
                else:
                    mem[target][i] = content
            limit = int(settings.get('memory.memory_char_limit' if target == 'memory' else 'memory.user_char_limit') or 8000)
            if sum(map(len, mem[target])) > limit:
                return J({'success': False, 'error': f'Memory is full ({limit} characters). Forget an entry first.'}, 400)
            s.kv_put('memory', mem)
            return J({'success': True, 'message': {'add': 'Added', 'replace': 'Updated', 'remove': 'Forgotten'}[action], 'demo': True})
        out = {}
        for k, v in mem.items():
            limit = int(settings.get('memory.memory_char_limit' if k == 'memory' else 'memory.user_char_limit') or 8000)
            out[k] = {'entries': v, 'chars': sum(map(len, v)), 'limit': limit, 'updated': None,
                      'enabled': bool(settings.get('memory.memory_enabled' if k == 'memory' else 'memory.user_profile_enabled', True))}
        return J(out)

    # ------------------------------------------------------------ settings
    def settings(self, s):
        return self.kv(s, 'settings', dict(C.DEFAULT_SETTINGS))

    def settings_put(self, s, key, value):
        cur = self.settings(s)
        cur[key] = value
        s.kv_put('settings', cur)

    def setting_fields(self):
        out = {f['key']: f for g in C.GROUPS for f in g['fields']}
        for key, cat, typ, help_, opts in C.EXTRA_SETTINGS:
            out[key] = {'key': key, 'type': typ, 'category': cat, 'help': help_, **({'options': opts} if opts else {})}
        return out

    async def h_settings(self, r):
        s = r['store']
        values, d = self.settings(s), self.defaults(s)
        groups = []
        for g in C.GROUPS:
            fields = []
            for f in g['fields']:
                f = {**f, 'help': f['help'].replace('{agent}', 'Hermes Demo'), 'label': f['label'].replace('{agent}', 'Hermes Demo')}
                f['value'] = d['model'] if f['key'] == 'model.default' else values.get(f['key'])
                if f['key'] == 'model.default':
                    f['provider'] = d['provider']
                fields.append(f)
            groups.append({'title': g['title'], 'tab': g['tab'], 'fields': fields})
        return J({'groups': groups, 'demo': True})

    async def h_settings_all(self, r):
        values = self.settings(r['store'])
        out = []
        for key, f in self.setting_fields().items():
            if key == 'model.default':
                continue
            item = {'key': key, 'type': f.get('type', 'text'), 'category': f.get('category') or key.split('.')[0],
                    'help': f.get('help') or f.get('label', ''), 'value': values.get(key)}
            if f.get('options'):
                item['options'] = list(f['options'])
            out.append(item)
        return J({'fields': out})

    async def h_settings_put(self, r):
        s = r['store']
        b = await self.body(r)
        key, value = str(b.get('key') or ''), b.get('value')
        if key == 'model.default':
            model, prov = str(value or ''), str(b.get('provider') or '')
            prov = prov or next((p['slug'] for p in C.MODELS['providers'] if model in p['models']), '')
            if not C.known_model(prov, model):
                return J({'ok': False, 'error': f'Unknown model {model} in the sandbox catalogue.'}, 400)
            s.kv_put('defaults', {'model': model, 'provider': prov})
            return J({'ok': True, 'model': model, 'provider': prov, 'warning': ''})
        f = self.setting_fields().get(key)
        if not f:
            return J({'ok': False, 'error': f"{key} isn't editable from the app"}, 400)
        if C.GUARDED.match(key) and not self.signed(r, guard_msg('config', key, value, r.headers.get('X-HM-Ts', ''))):
            return J({'ok': False, 'error': 'biometric confirmation required', 'need_sig': True}, 403)
        typ = f.get('type', 'text')
        try:
            if typ == 'bool':
                if isinstance(value, str) and value.lower() in ('true', 'false'):
                    value = value.lower() == 'true'
                if not isinstance(value, bool):
                    raise ValueError('expected on or off')
            elif typ == 'number':
                num = float(value)
                if not math.isfinite(num):
                    raise ValueError('not a number')
                if ('min' in f and num < f['min']) or ('max' in f and num > f['max']):
                    raise ValueError(f"must be between {f.get('min')} and {f.get('max')}")
                value = int(num) if num.is_integer() else num
            elif typ == 'select':
                value = '' if value is None else str(value)
                if value not in f.get('options', []):
                    raise ValueError('pick one of the listed options')
            else:
                value = '' if value is None else str(value)[:200]
        except (TypeError, ValueError) as e:
            return J({'ok': False, 'error': f'{key}: {e}'}, 400)
        self.settings_put(s, key, value)
        return J({'ok': True, 'key': key, 'value': value, 'message': 'Saved in the sandbox.', 'error': ''})

    # ------------------------------------------------------------ skills, plugins, MCP, restart
    def hub(self, s):
        return self.kv(s, 'hub', lambda: {'skills': {'default': {C.HUB_SEED_INSTALLED['identifier']: dict(C.HUB_SEED_INSTALLED['entry'])}, 'research': {}},
                                          'plugins': {}, 'mcp': {}, 'jobs': {}})

    def skills(self, s):
        base = self.kv(s, 'skills', lambda: [{**x, 'enabled': True} for x in C.SKILLS])
        hub = self.hub_settle(s)
        extra = [{'name': e['name'], 'description': C.hub_skill(i)['description'] if C.hub_skill(i) else '', 'category': (C.hub_skill(i) or {}).get('category', 'hub'),
                  'provenance': 'hub', 'usage': 0, 'enabled': e.get('enabled', True), 'identifier': i}
                 for i, e in hub['skills']['default'].items()]
        return base, extra

    async def h_skills(self, r):
        base, extra = self.skills(r['store'])
        return J({'skills': base + extra})

    async def h_skill(self, r):
        s = r['store']
        name = r.query.get('name', '')
        base, extra = self.skills(s)
        x = next((k for k in base + extra if k['name'] == name), None)
        if not x:
            return E('not found', 404)
        body = C.SKILL_BODY.format(name=name, description=x['description'], title=name.replace('-', ' ').title())
        return J({'name': name, 'description': x['description'], 'content': body, 'files': ['references/example.md'],
                  'path': f'sandbox://skills/{name}/SKILL.md', 'readiness': 'ready',
                  'missing_env': ['MAPS_API_KEY'] if name == 'trip-planner' else [], 'missing_commands': []})

    async def h_skill_toggle(self, r):
        s = r['store']
        b = await self.body(r)
        name, enabled = str(b.get('name') or ''), bool(b.get('enabled'))
        base, extra = self.skills(s)
        x = next((k for k in base if k['name'] == name), None)
        if x:
            if not x.get('locked'):
                x['enabled'] = enabled
                s.kv_put('skills', base)
            return J({'ok': True, 'name': name, 'enabled': x['enabled'] if not x.get('locked') else True})
        hub = self.hub(s)
        entry = hub['skills']['default'].get(next((k['identifier'] for k in extra if k['name'] == name), ''))
        if not entry:
            return E('unknown skill', 404)
        entry['enabled'] = enabled
        s.kv_put('hub', hub)
        return J({'ok': True, 'name': name, 'enabled': enabled})

    def plugins(self, s):
        base = self.kv(s, 'plugins', lambda: [{**p, 'enabled': p.get('configured', True), 'configured': p.get('configured', True)} for p in C.PLUGINS])
        hub = self.hub_settle(s)
        out = []
        for p in base:
            out.append(dict(p))
        for name, e in hub['plugins'].items():
            c = C.hub_plugin(name) or {}
            out.append({'name': name, 'key': name, 'kind': 'tool', 'version': e.get('version', ''), 'description': c.get('description', ''),
                        'tools': len(c.get('capabilities', {}).get('provides_tools', [])), 'hooks': len(c.get('capabilities', {}).get('provides_hooks', [])),
                        'commands': 0, 'source': 'hub', 'enabled': e.get('loaded', False), 'configured': e.get('configured', True)})
        for p in out:
            p['auto'] = p['kind'] in ('backend', 'platform', 'model-provider', 'exclusive') and p['source'] == 'bundled'
            p['locked'] = p['name'] == 'mobile' or p['auto']
            p['pending'] = p['configured'] != p['enabled'] and not p['auto']
        return base, out

    async def h_plugins(self, r):
        return J({'plugins': self.plugins(r['store'])[1]})

    async def h_plugin_toggle(self, r):
        s = r['store']
        b = await self.body(r)
        name, enabled = str(b.get('name') or ''), bool(b.get('enabled'))
        if not self.signed(r, guard_msg('plugin', name, enabled, r.headers.get('X-HM-Ts', ''))):
            return J({'ok': False, 'error': 'biometric confirmation required', 'need_sig': True}, 403)
        if name == 'mobile':
            return J({'ok': False, 'error': 'This app runs on the mobile plugin. Disable it from the CLI if you mean it.'}, 400)
        base, _ = self.plugins(s)
        x = next((p for p in base if p['key'] == name or p['name'] == name), None)
        if x:
            if x['source'] == 'bundled' and x['kind'] in ('backend', 'platform'):
                return J({'ok': False, 'error': f'{name} loads on its own.'}, 400)
            x['configured'] = enabled
            s.kv_put('plugins', base)
        else:
            hub = self.hub(s)
            if name not in hub['plugins']:
                return J({'ok': False, 'error': f'unknown plugin {name}'}, 404)
            hub['plugins'][name]['configured'] = enabled
            s.kv_put('hub', hub)
        return J({'ok': True, 'name': name, 'enabled': enabled, 'message': 'Restart Hermes to apply (synthetic).'})

    def mcp_servers(self, s):
        base = self.kv(s, 'mcp', lambda: [dict(x) for x in C.MCP])
        hub = self.hub_settle(s)
        extra = [{'name': n, 'transport': (C.hub_mcp(n) or {}).get('transport', 'stdio'), 'tools': e.get('tools', 0) if e.get('loaded') else 0,
                  'connected': bool(e.get('loaded')), 'disabled': False, 'status': 'connected' if e.get('loaded') else 'pending',
                  **({} if e.get('loaded') else {'error': 'Restart Hermes to connect (synthetic).'})} for n, e in hub['mcp'].items()]
        return base + extra

    async def h_mcp(self, r):
        return J({'servers': self.mcp_servers(r['store'])})

    async def h_restart(self, r):
        s = r['store']
        base, _ = self.plugins(s)
        for p in base:
            p['enabled'] = p['configured']
        s.kv_put('plugins', base)
        hub = self.hub(s)
        for e in [*hub['plugins'].values(), *hub['mcp'].values()]:
            e['loaded'] = e.get('configured', True)
        s.kv_put('hub', hub)
        reply = '♻️ Restart acknowledged. The sandbox reloaded its synthetic config and plugins; no process restarted on any machine.'
        s.add_message('c-hermes', 'agent', reply, kind='status', meta={'quiet': True, 'demo': True}, title='Hermes')
        return J({'ok': True, 'reply': reply})

    # ------------------------------------------------------------ Hub (synthetic catalogue, synthetic jobs)
    def hub_job(self, s, hub, kind, action, target, profile, effect):
        job = {'id': 'hj-' + secrets.token_hex(6), 'kind': kind, 'action': action, 'target': target, 'profile': profile or 'default',
               'state': 'running', 'started': time.time(), 'ended': None, 'log': f'Synthetic {action} of {target}: nothing is downloaded or run.',
               'error': '', 'result': None, 'effect': effect}
        hub['jobs'][job['id']] = job
        hub['jobs'] = dict(list(hub['jobs'].items())[-40:])
        s.kv_put('hub', hub)
        return job

    @staticmethod
    def job_out(job):
        return {k: v for k, v in job.items() if k != 'effect'}

    def hub_settle(self, s):
        """Finish synthetic jobs whose time has come and apply their effect to tenant state."""
        hub = self.hub(s)
        changed = False
        for job in hub['jobs'].values():
            if job['state'] != 'running' or time.time() - job['started'] < self.JOB_SECONDS:
                continue
            e, changed = job['effect'], True
            state, result, log = 'ok', {'ok': True}, []
            if e['op'] == 'skill_install':
                hub['skills'].setdefault(e['profile'], {})[e['ident']] = {'name': e['name'], 'trust_level': e['trust'], 'scan_verdict': e['verdict'],
                                                                          'source': e['source'], 'installed_at': datetime.now().isoformat(timespec='seconds'),
                                                                          'updated_at': None, 'version': e['version'], 'enabled': True}
                log = ['Security scan: ' + e['verdict'], f"Installed {e['name']} into {e['profile']} (synthetic)"]
            elif e['op'] == 'skill_update':
                entry = hub['skills'].get(e['profile'], {}).get(e['ident'])
                if entry:
                    entry.update(version=e['version'], updated_at=datetime.now().isoformat(timespec='seconds'))
                log = [f"Updated {e['name']} to {e['version']} (synthetic)"]
            elif e['op'] == 'skill_uninstall':
                hub['skills'].get(e['profile'], {}).pop(e['ident'], None)
                log = [f"Removed {e['name']} (synthetic)"]
            elif e['op'] == 'plugin_install':
                c = C.hub_plugin(e['name'])
                hub['plugins'][e['name']] = {'sha': c['sha'], 'version': c['version'], 'configured': True, 'loaded': False}
                result = {'ok': True, 'plugin_name': e['name'], 'warnings': ['Synthetic install: no code was downloaded.'],
                          'python_dependencies': [], 'missing_env': c.get('capabilities', {}).get('requires_env', []), 'enabled': True,
                          'gateway_reloaded': False, 'activation': 'restart', 'restart_required': True, 'known_issues': c.get('known_issues', [])}
                log = [f"Installed plugin {e['name']} (synthetic)", 'Restart Hermes to load it.']
            elif e['op'] == 'plugin_update':
                c = C.hub_plugin(e['name'])
                if c.get('update_adds') and not e.get('accept'):
                    state = 'consent'
                    result = {'ok': False, 'consent_required': True, 'name': e['name'], 'sha': c['sha'],
                              'error': f"Updating '{e['name']}' to {c['sha'][:8]} adds {c['update_adds']}. Confirm to continue.",
                              'delta': {}, 'delta_lines': [c['update_adds']]}
                    job['error'] = result['error']
                else:
                    hub['plugins'][e['name']].update(sha=c['sha'], version=c['version'], loaded=False)
                    result = {'ok': True, 'name': e['name'], 'sha': c['sha'], 'unchanged': False}
                    log = [f"Updated plugin {e['name']} to {c['version']} (synthetic)", 'Restart Hermes to load it.']
            elif e['op'] == 'plugin_remove':
                hub['plugins'].pop(e['name'], None)
                log = [f"Removed plugin {e['name']} (synthetic)"]
            elif e['op'] == 'mcp_install':
                c = C.hub_mcp(e['name'])
                hub['mcp'][e['name']] = {'configured': True, 'loaded': False, 'tools': c.get('tools', 2), 'env_keys': e['env_keys']}
                result = {'ok': True, 'error': '', 'post_install': c.get('post_install', '')}
                log = [f"Added MCP server {e['name']} (synthetic). Credentials are discarded, never stored."]
            elif e['op'] == 'mcp_remove':
                hub['mcp'].pop(e['name'], None)
                log = [f"Removed MCP server {e['name']} (synthetic)"]
            job.update(state=state, ended=time.time(), result=result, log=job['log'] + '\n' + '\n'.join(log))
        if changed:
            s.kv_put('hub', hub)
        return hub

    def hub_profile(self, r, value=None):
        p = r.query.get('profile', '') if value is None else str(value or '')
        if p in ('', 'default', 'Hermes Review Sandbox'):
            return 'default'
        if p == 'research':
            return p
        raise LookupError(f'unknown profile {p}')

    def hub_signed(self, r, key):
        return self.signed(r, guard_msg('hub', key, True, r.headers.get('X-HM-Ts', '')))

    def hub_annotate(self, item, installed):
        names = {v['name']: k for k, v in installed.items()}
        return {**item, 'installed': item['identifier'] in installed, 'installed_local': item['name'] in {k['name'] for k in C.SKILLS},
                'installed_name': installed[item['identifier']]['name'] if item['identifier'] in installed else names.get(item['name'])}

    def hub_local(self, installed):
        rows = [{'inventory_id': hashlib.sha256(x['name'].encode()).hexdigest(), 'name': x['name'], 'description': x['description'],
                 'category': x['category'], 'relative_path': f"{x['category']}/{x['name']}", 'directory_name': x['name'], 'identifier': None,
                 'managed': False} for x in C.SKILLS]
        for i, e in installed.items():
            c = C.hub_skill(i) or {}
            rows.append({'inventory_id': hashlib.sha256(i.encode()).hexdigest(), 'name': e['name'], 'description': c.get('description', ''),
                         'category': c.get('category', 'hub'), 'relative_path': f"hub/{e['name']}", 'directory_name': e['name'],
                         'identifier': i, 'managed': True})
        return rows

    def lock_view(self, installed):
        return {i: {k: e.get(k) for k in ('name', 'trust_level', 'scan_verdict', 'source', 'installed_at', 'updated_at')} for i, e in installed.items()}

    async def h_hub_installed(self, r):
        try:
            prof = self.hub_profile(r)
        except LookupError as e:
            return E(str(e), 404)
        inst = self.hub_settle(r['store'])['skills'].get(prof, {})
        return J({'installed': self.lock_view(inst), 'local': self.hub_local(inst), 'profile': prof, 'profiles': ['default', 'research'], 'gateway_profile': 'default'})

    async def h_hub_browse(self, r):
        try:
            prof = self.hub_profile(r)
        except LookupError as e:
            return E(str(e), 404)
        inst = self.hub_settle(r['store'])['skills'].get(prof, {})
        items = [self.hub_annotate(x, inst) for x in C.HUB_SKILLS]
        return J({'sources': C.HUB_SOURCES, 'index_available': True, 'featured': [x for x in items if x.get('featured')],
                  'official': [x for x in items if x['source'] == 'official'], 'installed': self.lock_view(inst), 'local': self.hub_local(inst)})

    async def h_hub_search(self, r):
        try:
            prof = self.hub_profile(r)
        except LookupError as e:
            return E(str(e), 400)
        q, source = (r.query.get('q') or '').strip().lower(), r.query.get('source', 'all')
        try:
            limit = max(1, min(int(r.query.get('limit', 30)), 50))
        except ValueError:
            return E('limit must be a number')
        inst = self.hub_settle(r['store'])['skills'].get(prof, {})
        hits = [self.hub_annotate(x, inst) for x in C.HUB_SKILLS if q and (source in ('all', '') or x['source'] == source)
                and (q in x['name'] or q in x['description'].lower() or any(q in t for t in x['tags']))][:limit]
        counts = {}
        for x in hits:
            counts[x['source']] = counts.get(x['source'], 0) + 1
        return J({'results': hits, 'source_counts': counts, 'timed_out': [], 'installed': self.lock_view(inst), 'local': self.hub_local(inst)})

    async def h_hub_updates(self, r):
        try:
            prof = self.hub_profile(r)
        except LookupError as e:
            return E(str(e), 404)
        inst = self.hub_settle(r['store'])['skills'].get(prof, {})
        out = []
        for i, e in inst.items():
            c = C.hub_skill(i)
            status = 'orphaned' if not c else ('update_available' if c['version'] != e.get('version') else 'up_to_date')
            out.append({'name': e['name'], 'identifier': i, 'source': e['source'], 'status': status})
        return J({'updates': out})

    async def h_hub_preview(self, r):
        try:
            prof = self.hub_profile(r)
        except LookupError as e:
            return E(str(e), 404)
        ident = r.query.get('identifier', '')
        c = C.hub_skill(ident)
        if not c:
            return E(f'Skill not found: {ident}', 404)
        inst = self.hub_settle(r['store'])['skills'].get(prof, {})
        a = self.hub_annotate(c, inst)
        lock = self.lock_view(inst).get(ident)
        return J({**{k: c.get(k) for k in ('name', 'description', 'source', 'identifier', 'trust_level', 'repo', 'tags')},
                  'source_label': next((x['label'] for x in C.HUB_SOURCES if x['id'] == c['source']), c['source']),
                  'skill_md': C.SKILL_BODY.format(name=c['name'], description=c['description'], title=c['name'].replace('-', ' ').title()),
                  'files': ['SKILL.md', 'references/example.md'], 'installed': lock, 'installed_local': a['installed_local'] and not lock,
                  'installed_name': a['installed_name']})

    async def h_hub_scan(self, r):
        ident = r.query.get('identifier', '')
        c = C.hub_skill(ident)
        if not c:
            return E(f'Skill not found: {ident}', 404)
        return J({'name': c['name'], 'identifier': ident, 'source': c['source'], 'trust_level': c['trust_level'], **C.hub_scan(c)})

    async def h_hub_skill_write(self, r):
        s, action = r['store'], r.match_info['action']
        b = await self.body(r)
        try:
            prof = self.hub_profile(r, b.get('profile'))
        except LookupError as e:
            return E(str(e), 404)
        hub = self.hub_settle(s)
        inst = hub['skills'].setdefault(prof, {})
        if action == 'install':
            ident = str(b.get('identifier') or '')
            c = C.hub_skill(ident)
            if not re.fullmatch(r'[\w.:/@+#~\-]{1,300}', ident) or ident.startswith('-'):
                return E('identifier is required')
            if not c:
                return E(f'Skill not found: {ident}', 404)
            if not self.hub_signed(r, f'skill:install:{ident}'):
                return J({'ok': False, 'error': 'biometric confirmation required', 'need_sig': True}, 403)
            scan = C.hub_scan(c)
            if scan['policy'] != 'allow':
                return J({'ok': False, 'error': 'The security scan blocked it: ' + scan['policy_reason']}, 409)
            if ident in inst:
                return J({'ok': False, 'error': f"{c['name']} is already installed."}, 409)
            job = self.hub_job(s, hub, 'skill', 'install', ident, prof, {'op': 'skill_install', 'ident': ident, 'name': c['name'], 'profile': prof,
                                                                          'trust': c['trust_level'], 'verdict': scan['verdict'], 'source': c['source'], 'version': c['version']})
            return J({'ok': True, 'job': self.job_out(job)})
        name = str(b.get('name') or '')
        if not re.fullmatch(r'[\w.\-]{1,120}', name) or name.startswith('-'):
            return E('name is required')
        ident = next((i for i, e in inst.items() if e['name'] == name), None)
        if not ident:
            return E(f"{name} isn't a hub-installed skill in this profile", 404)
        if action == 'update':
            if not self.hub_signed(r, f'skill:update:{name}'):
                return J({'ok': False, 'error': 'biometric confirmation required', 'need_sig': True}, 403)
            c = C.hub_skill(ident)
            if not c:
                return E(f'{ident} is no longer in its source', 404)
            job = self.hub_job(s, hub, 'skill', 'update', name, prof, {'op': 'skill_update', 'ident': ident, 'name': name, 'profile': prof, 'version': c['version']})
            return J({'ok': True, 'job': self.job_out(job), 'scan': C.hub_scan(c)})
        job = self.hub_job(s, hub, 'skill', 'uninstall', name, prof, {'op': 'skill_uninstall', 'ident': ident, 'name': name, 'profile': prof})
        return J({'ok': True, 'job': self.job_out(job)})

    async def h_hub_plugins(self, r):
        hub = self.hub_settle(r['store'])
        entries = []
        for c in C.HUB_PLUGINS:
            e = hub['plugins'].get(c['name'])
            entries.append({**{k: v for k, v in c.items() if k not in ('update_adds',)}, 'sha_short': c['sha'][:7], 'installed': bool(e),
                            'installed_sha': e['sha'] if e else None, 'update_available': bool(e and e['sha'] != c['sha']),
                            'runtime_status': (('enabled' if e.get('loaded') else 'inactive') if e else None),
                            **({'installed_name': c['name']} if e else {})})
        return J({'entries': entries, 'categories': sorted({c['category'] for c in C.HUB_PLUGINS}),
                  'removed': [{'name': 'old-scraper', 'repo': None, 'reason': 'Withdrawn by its maintainer (synthetic example).', 'date': '2026-09-01'}],
                  'generated_at': datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')})

    async def h_hub_plugin_write(self, r):
        s, action = r['store'], r.match_info['action']
        b = await self.body(r)
        name = str(b.get('name') or '')
        if not re.fullmatch(r'[a-z0-9_-]{1,64}', name):
            return E('plugin name is required')
        if name == 'mobile':
            return E('This app runs on the mobile plugin. Remove it from the CLI if you mean it.')
        c = C.hub_plugin(name)
        hub = self.hub_settle(s)
        key = f'plugin:{action}:{name}' + (':accept' if action == 'update' and b.get('accept_capabilities') else '')
        if not c:
            return E(f"No catalog entry '{name}'", 404)
        if action == 'install' and name in hub['plugins']:
            return E(f'{name} is already installed', 409)
        if action in ('update', 'remove') and name not in hub['plugins']:
            return E(f'{name} is not installed', 404)
        if not self.hub_signed(r, key):
            return J({'ok': False, 'error': 'biometric confirmation required', 'need_sig': True}, 403)
        job = self.hub_job(s, hub, 'plugin', action, name, '', {'op': 'plugin_' + action, 'name': name, 'accept': bool(b.get('accept_capabilities'))})
        return J({'ok': True, 'job': self.job_out(job)})

    async def h_hub_mcp(self, r):
        hub = self.hub_settle(r['store'])
        return J({'entries': [{**c, 'installed': c['name'] in hub['mcp'], 'enabled': c['name'] in hub['mcp']} for c in C.HUB_MCP], 'diagnostics': []})

    async def h_hub_mcp_write(self, r):
        s, action = r['store'], r.match_info['action']
        b = await self.body(r)
        name = str(b.get('name') or '')
        if not re.fullmatch(r'[\w.\-]{1,80}', name):
            return E('name is required')
        c = C.hub_mcp(name)
        hub = self.hub_settle(s)
        if action == 'install':
            env = b.get('env') or {}
            if not isinstance(env, dict) or not all(isinstance(k, str) and isinstance(v, str) for k, v in env.items()):
                return E('env must be a map of strings')
            if not c:
                return E(f"No catalog entry '{name}'", 404)
            declared = {x['name'] for x in c['required_env']}
            if set(env) - declared:
                return E(f"'{name}' does not declare: " + ', '.join(sorted(set(env) - declared)))
            missing = [x['name'] for x in c['required_env'] if x.get('required', True) and not env.get(x['name'])]
            if missing:
                return E('Needs ' + ', '.join(missing))
            if name in hub['mcp']:
                return E(f"'{name}' is already installed", 409)
            if not self.hub_signed(r, f'mcp:install:{name}'):
                return J({'ok': False, 'error': 'biometric confirmation required', 'need_sig': True}, 403)
            # Credential values are dropped here: only their names are kept, so the sandbox never stores a secret.
            job = self.hub_job(s, hub, 'mcp', 'install', name, '', {'op': 'mcp_install', 'name': name, 'env_keys': sorted(env)})
            return J({'ok': True, 'job': self.job_out(job)})
        if name not in hub['mcp']:
            return E(f"'{name}' isn't an installed catalog MCP", 404)
        if not self.hub_signed(r, f'mcp:remove:{name}'):
            return J({'ok': False, 'error': 'biometric confirmation required', 'need_sig': True}, 403)
        job = self.hub_job(s, hub, 'mcp', 'remove', name, '', {'op': 'mcp_remove', 'name': name})
        return J({'ok': True, 'job': self.job_out(job)})

    async def h_hub_job(self, r):
        job = self.hub_settle(r['store'])['jobs'].get(r.match_info['jid'])
        return J({'job': self.job_out(job)}) if job else E('no such job', 404)

    async def h_hub_jobs(self, r):
        jobs = sorted(self.hub_settle(r['store'])['jobs'].values(), key=lambda j: -j['started'])
        return J({'jobs': [{k: v for k, v in self.job_out(j).items() if k != 'log'} for j in jobs]})

    # ------------------------------------------------------------ Wake (alarm clock)
    def wake(self, s):
        def make():
            salt = secrets.token_hex(16)
            return {'config': {'alarms': [{'id': 'a-' + secrets.token_hex(8), 'time': '07:30', 'on': True, 'days': [0, 1, 2, 3, 4]}],
                               'contacts': [{'name': 'Example contact', 'phone': '+15555550100'}], 'owner_phone': '+15555550199', 'tz': '',
                               'recheck_min': 10, 'checkin_min': 2, 'answer_min': 5, 'escalate_min': 30, 'level_base': 1,
                               'level_auto': True, 'level_since': time.time()},
                    'last_fired': {}, 'session': None, 'photos': [],
                    'pw': {'salt': salt, 'hash': self.pw_hash(REVIEW_PASSWORD, salt)}}
        return self.kv(s, 'wake', make)

    @staticmethod
    def pw_hash(pw, salt):
        return hashlib.pbkdf2_hmac('sha256', pw.encode(), bytes.fromhex(salt), 200_000).hex()

    def wake_tz(self, w):
        try:
            return ZoneInfo(w['config'].get('tz') or 'UTC')
        except Exception:
            return timezone.utc

    def wake_level(self, w):
        cfg = w['config']
        if not cfg.get('level_auto', True):
            return cfg['level_base'], None
        n = int((time.time() - cfg['level_since']) // (14 * 86400))
        step = min(8, cfg['level_base'] + n)
        return step, (None if step >= 8 else cfg['level_since'] + (n + 1) * 14 * 86400)

    def alarm_next(self, w, a, after):
        if not a.get('on', True):
            return None
        tz = self.wake_tz(w)
        h, mi = map(int, a['time'].split(':'))
        base = max(after, w['last_fired'].get(a['id'], 0))
        day = datetime.fromtimestamp(base, tz)
        for i in range(9):
            d = (day + timedelta(days=i)).replace(hour=h, minute=mi, second=0, microsecond=0)
            if d.timestamp() > base and (not a.get('days') or d.weekday() in a['days']):
                return d.timestamp()
        return None

    def wake_content(self, step):
        topic = C.WAKE_READINGS[(step - 1) % len(C.WAKE_READINGS)]
        return {'title': topic['title'], 'topic': topic['topic'], 'reading': topic['reading'], 'questions': [q for q, _ in topic['questions']]}

    def wake_begin(self, w, alarm_id, fire, test):
        step, _ = self.wake_level(w)
        w['session'] = {'id': 'ws-' + secrets.token_hex(6), 'alarm_id': alarm_id, 'fire': fire, 'test': test, 'rings': [fire],
                        'proof': None, 'checkin': None, 'done': None, 'escalated': False, 'step': step,
                        'content': self.wake_content(step), 'answers': None, 'feedback': None}
        if alarm_id:
            w['last_fired'][alarm_id] = fire
            for a in w['config']['alarms']:
                if a['id'] == alarm_id and not a.get('days'):
                    a['on'] = False

    def wake_close(self, s, w, outcome):
        x = w['session']
        w['session'] = None
        if outcome in ('awake', 'override'):
            hhmm = datetime.fromtimestamp(x['fire'], self.wake_tz(w)).strftime('%H:%M')
            if outcome == 'override':
                text = f'**Wake {hhmm}**: stopped with the emergency password. Nobody was messaged. ({NOTE})'
            else:
                lines = [f"**Wake {hhmm}**: awake, proof: {(x['proof'] or {}).get('kind', '-')}. (synthetic)"]
                if x.get('answers'):
                    lines.append(f"\n## {x['content']['title']}\n")
                    for i, q in enumerate(x['content']['questions']):
                        fb = (x.get('feedback') or [{}] * 5)[i]
                        lines.append(f"**{i + 1}. {q}**\nYou: {x['answers'][i] or '(blank)'}\n{fb.get('verdict', '')}: {fb.get('note', '')}\n")
                text = '\n'.join(lines)
            s.add_message('wake', 'agent', text, meta={'demo': True}, title='Wake', chat_kind='digest')

    def wake_tick(self, s, w):
        """Advance the alarm state lazily whenever the phone asks. No timers run in the sandbox."""
        now, x, cfg = time.time(), w['session'], w['config']
        # Sessions start only when the phone's own alarm reports in (/fired) or from Test: with no push in the
        # sandbox, a session the server started on its own would never reach the phone.
        if x is None:
            return
        if x['done']:
            if now - x['done'] > 30:
                self.wake_close(s, w, 'awake')
            return
        if now - x['fire'] > 7200:
            w['session'] = None
            return
        ck = x['checkin']
        if ck:
            if not ck['sent'] and now >= ck['due']:
                ck['sent'] = now
            elif ck['sent'] and now - ck['sent'] >= cfg['answer_min'] * 60:
                ck.update(misses=ck['misses'] + 1, logic_i=ck['logic_i'] + 1, sent=now)
        elif not x['proof'] and now - x['fire'] >= cfg['recheck_min'] * 60 and now - x['rings'][-1] >= 240:
            x['rings'].append(now)
        if not x['escalated'] and now >= x['fire'] + cfg['escalate_min'] * 60:
            x['escalated'] = True
            who = 'you' if x['test'] else 'your escalation contacts'
            s.add_message('wake', 'agent', f'Synthetic escalation: the sandbox would message {who} now. Nothing was sent.',
                          kind='status', meta={'demo': True}, title='Wake', chat_kind='digest')

    def wake_view(self, w):
        x = w['session']
        if not x:
            return None
        cfg = w['config']
        ck = x['checkin']
        phase = 'done' if x['done'] else 'checkin' if ck and ck['sent'] else 'wait' if x['proof'] else 'ring'
        due = ck['due'] if ck else None
        logic = C.WAKE_LOGIC[ck['logic_i'] % len(C.WAKE_LOGIC)][0] if phase == 'checkin' else None
        return {'id': x['id'], 'fire': x['fire'], 'test': x['test'], 'phase': phase, 'proof': x['proof'], 'checkin_due': due,
                'checkin_misses': ck['misses'] if ck else 0, 'escalate_at': x['fire'] + cfg['escalate_min'] * 60,
                'escalated': x['escalated'], 'done': x['done'], 'rings': len(x['rings']), 'steps_needed': 30 + 10 * (x['step'] - 1),
                'logic': logic, 'content': x['content']}

    def wake_overview(self, w):
        nxt = None
        for a in w['config']['alarms']:
            t = self.alarm_next(w, a, time.time())
            if t and (nxt is None or t < nxt['ts']):
                nxt = {'ts': t, 'alarm_id': a['id']}
        step, level_next = self.wake_level(w)
        return {'config': w['config'], 'next': nxt, 'session': self.wake_view(w), 'server_now': time.time(), 'level': step,
                'level_max': 8, 'level_next': level_next, 'whatsapp': 'sandbox', 'demo': True}

    def wake_save(self, s, w):
        s.kv_put('wake', w)

    async def h_wake(self, r):
        s = r['store']
        w = self.wake(s)
        tz = r.query.get('tz', '')
        if tz:
            try:
                ZoneInfo(tz)
                w['config']['tz'] = tz
            except Exception:
                pass
        self.wake_tick(s, w)
        self.wake_save(s, w)
        return J(self.wake_overview(w))

    async def h_wake_config(self, r):
        s = r['store']
        w = self.wake(s)
        b = await self.body(r)
        cfg, now = w['config'], time.time()
        try:
            if 'alarms' in b:
                old = {a['id']: a for a in cfg['alarms']}
                alarms = []
                for a in b['alarms'][:20]:
                    t = str(a.get('time') or '')
                    if not re.fullmatch(r'\d{1,2}:\d{2}', t) or int(t.split(':')[0]) > 23 or int(t.split(':')[1]) > 59:
                        raise ValueError(f"bad time '{t}'")
                    aid = str(a.get('id') or '') if str(a.get('id') or '') in old else 'a-' + secrets.token_hex(8)
                    item = {'id': aid, 'time': '%02d:%02d' % tuple(map(int, t.split(':'))), 'on': bool(a.get('on', True)),
                            'days': sorted({int(d) for d in (a.get('days') or []) if 0 <= int(d) <= 6})}
                    if old.get(aid) != item:
                        w['last_fired'][aid] = now
                    alarms.append(item)
                cfg['alarms'] = alarms
                w['last_fired'] = {k: v for k, v in w['last_fired'].items() if k in {a['id'] for a in alarms}}
            if 'contacts' in b:
                cfg['contacts'] = [{'name': str(c.get('name') or '')[:60], 'phone': str(c.get('phone') or '')[:24]}
                                   for c in b['contacts'][:10] if str(c.get('phone') or '').strip()]
            if 'owner_phone' in b:
                cfg['owner_phone'] = str(b['owner_phone'] or '')[:24]
            for k in ('recheck_min', 'checkin_min', 'answer_min', 'escalate_min'):
                if k in b:
                    cfg[k] = max(1, min(240, int(b[k])))
            if 'level_base' in b:
                cfg.update(level_base=max(1, min(8, int(b['level_base']))), level_since=now)
            if 'level_auto' in b and bool(b['level_auto']) != cfg['level_auto']:
                step, _ = self.wake_level(w)
                cfg.update(level_base=step, level_since=now, level_auto=bool(b['level_auto']))
        except (TypeError, ValueError, AttributeError) as e:
            return E(str(e))
        self.wake_save(s, w)
        return J(self.wake_overview(w))

    async def h_wake_session(self, r):
        s = r['store']
        w = self.wake(s)
        self.wake_tick(s, w)
        self.wake_save(s, w)
        return J({'session': self.wake_view(w), 'server_now': time.time()})

    async def h_wake_fired(self, r):
        s = r['store']
        w = self.wake(s)
        b = await self.body(r)
        if not w['session']:
            aid, now = str(b.get('alarm_id') or ''), time.time()
            due = [(self.alarm_next(w, a, now - 1800), a['id']) for a in w['config']['alarms']]
            due = [d for d in due if d[0] and d[0] <= now]
            self.wake_begin(w, due[0][1] if due else aid if any(a['id'] == aid for a in w['config']['alarms']) else '',
                            due[0][0] if due else now, False)
        self.wake_save(s, w)
        return J({'session': self.wake_view(w), 'server_now': time.time()})

    async def h_wake_proof(self, r):
        s = r['store']
        w = self.wake(s)
        self.wake_tick(s, w)
        b = await self.body(r)
        x, kind, now = w['session'], str(b.get('kind') or ''), time.time()
        if kind not in ('read', 'photo', 'steps', 'logic'):
            return J({'ok': False, 'reason': f"Unknown proof kind '{kind}'."})
        if not x or (b.get('sid') and b['sid'] != x['id']):
            return J({'ok': False, 'reason': 'No active wake session.', 'session': self.wake_view(w)})
        phase = self.wake_view(w)['phase']
        out = {}
        if kind == 'logic':
            if phase != 'checkin':
                return J({'ok': False, 'reason': 'Do a wake-up proof first (reading, photo or steps).' if not x['proof'] else "The check-in isn't open yet.", 'session': self.wake_view(w)})
            q, ans = C.WAKE_LOGIC[x['checkin']['logic_i'] % len(C.WAKE_LOGIC)]
            given = re.sub(r'\s+', '', str(b.get('answer') or '').lower()).lstrip('0')
            ok = given == ans.lstrip('0') or given.replace('.', ':') == ans.lstrip('0')
            out = {'ok': ok, 'reason': 'Correct. Good morning.' if ok else 'Not quite. Try once more.', 'correct': ok, 'note': f'Expected: {ans}'}
            if ok:
                x['done'] = now
        else:
            if kind == 'read':
                answers = [str(a or '').strip() for a in (b.get('answers') or [])][:5]
                answers += [''] * (5 - len(answers))
                topic = next(t for t in C.WAKE_READINGS if t['title'] == x['content']['title'])
                fb = []
                for given, (_, keys) in zip(answers, topic['questions']):
                    low = given.lower()
                    verdict = 'correct' if any(k in low for k in keys) else 'partial' if len(given.split()) >= 2 else 'incorrect'
                    fb.append({'verdict': verdict, 'note': 'Expected: ' + ' / '.join(keys)})
                ok = sum(f['verdict'] != 'incorrect' for f in fb) >= 3
                x['answers'], x['feedback'] = answers, fb
                out = {'ok': ok, 'reason': 'Your answers show you read it. (scored by a fixed sandbox key, no model)' if ok else 'Too many answers missed the text. Read it again.', 'feedback': fb}
            elif kind == 'photo':
                try:
                    data = base64.b64decode(str(b.get('image') or ''), validate=True)
                except (binascii.Error, ValueError):
                    data = b''
                digest = hashlib.sha256(data).hexdigest()
                if len(data) < 5000:
                    out = {'ok': False, 'reason': 'Photo is too small.'}
                elif digest in w['photos']:
                    out = {'ok': False, 'reason': 'That exact photo was used before. Take a new one.'}
                else:
                    # The bytes are hashed for the duplicate check and discarded; no image analysis runs.
                    w['photos'] = [*w['photos'], digest][-20:]
                    out = {'ok': True, 'reason': f'Photo received ({len(data) // 1024} KB). The sandbox accepts any new photo; no image analysis runs.'}
            else:
                steps = sorted(float(t) for t in (b.get('steps') or [])[:5000])
                need = 30 + 10 * (x['step'] - 1)
                span = steps[-1] - steps[0] if len(steps) > 1 else 0
                if len(steps) < need:
                    out = {'ok': False, 'reason': f'{len(steps)} steps, need {need}.'}
                else:
                    ok = span >= max(10, need * 0.3) and len(steps) / max(span, 1e-6) <= 3
                    out = {'ok': ok, 'reason': f'{len(steps)} steps over {int(span)} s ({len(steps) / max(span, 1e-6):.1f} per second).' + ('' if ok else ' Too quick to be walking.')}
            if out['ok'] and not x['proof']:
                x['proof'] = {'kind': kind, 'ts': now, 'reason': out['reason']}
                x['checkin'] = {'due': max(x['fire'] + w['config']['checkin_min'] * 60, now + 60), 'sent': None, 'misses': 0, 'logic_i': 0}
        self.wake_save(s, w)
        return J({**out, 'session': self.wake_view(w)})

    async def h_wake_test(self, r):
        s = r['store']
        w = self.wake(s)
        w['session'] = None
        self.wake_begin(w, '', time.time(), True)
        self.wake_save(s, w)
        return J({'session': self.wake_view(w), 'server_now': time.time()})

    async def h_wake_cancel(self, r):
        s = r['store']
        w = self.wake(s)
        if w['session'] and w['session']['test']:
            self.wake_close(s, w, 'cancelled')
            self.wake_save(s, w)
            return J({'ok': True})
        return J({'ok': False})

    async def h_wake_override(self, r):
        s = r['store']
        w = self.wake(s)
        b = await self.body(r)
        if not w['session']:
            return J({'ok': False, 'reason': 'No alarm is running.'})
        if not hmac.compare_digest(self.pw_hash(str(b.get('password') or ''), w['pw']['salt']), w['pw']['hash']):
            return J({'ok': False, 'reason': 'Wrong password.'})
        self.wake_close(s, w, 'override')
        self.wake_save(s, w)
        return J({'ok': True})

    async def h_wake_password(self, r):
        s = r['store']
        w = self.wake(s)
        b = await self.body(r)
        if not hmac.compare_digest(self.pw_hash(str(b.get('old') or ''), w['pw']['salt']), w['pw']['hash']):
            return J({'ok': False, 'reason': 'Current password is wrong.'})
        new = str(b.get('new') or '')
        if len(new) < 8:
            return J({'ok': False, 'reason': 'Use at least 8 characters.'})
        salt = secrets.token_hex(16)
        w['pw'] = {'salt': salt, 'hash': self.pw_hash(new, salt)}
        self.wake_save(s, w)
        return J({'ok': True})

    async def h_wake_whatsapp(self, r):
        w = self.wake(r['store'])
        if not w['config'].get('owner_phone'):
            return J({'ok': False, 'error': 'Set your own number first.'}, 400)
        return J({'ok': False, 'error': 'Sandbox: WhatsApp is simulated, so nothing was sent. On a real gateway this sends a test message to your own number.'})

    # ------------------------------------------------------------ Radar (synthetic scout findings and Momentum)
    def radar(self, s):
        def make():
            now = time.time()
            findings = [{**f, 'id': f['id'], 'ts': now - f['age'], 'updated': now - f['age'], 'batch_id': 'b-sandbox-1' if f['age'] < 4 * 3600 else 'b-sandbox-0',
                         'vote': 0, 'spawned': 0, 'feedback': []} for f in C.RADAR_FINDINGS]
            return {'batch': {'id': 'b-sandbox-1', 'ts': now - 1800, 'model': 'synthetic-demo', 'info': {}}, 'findings': findings,
                    'notes': [{**n, 'ts': now - 5400, 'acked': 0} for n in C.RADAR_NOTES], 'momentum_at': now - 600}
        return self.kv(s, 'radar', make)

    def finding_view(self, f):
        return {**{k: v for k, v in f.items() if k != 'age'}, 'type_label': C.RADAR_TYPES.get(f['type'], f['type'])}

    async def h_radar(self, r):
        s = r['store']
        view = r.query.get('view', 'current')
        if view not in ('current', 'saved', 'history'):
            return E('bad view')
        rd = self.radar(s)
        fs = rd['findings']
        if view == 'current':
            fs = sorted([f for f in fs if f['batch_id'] == rd['batch']['id']], key=lambda f: -f['confidence'])
        elif view == 'saved':
            fs = sorted([f for f in fs if f['state'] == 'saved'], key=lambda f: -f['updated'])
        else:
            fs = sorted(fs, key=lambda f: -f['ts'])
        counts = {}
        for f in rd['findings']:
            counts[f['state']] = counts.get(f['state'], 0) + 1
        return J({'view': view, 'batch': rd['batch'], 'findings': [self.finding_view(f) for f in fs], 'notes': rd['notes'][:10],
                  'counts': counts, 'types': C.RADAR_TYPES, 'next_run': None, 'scout': True, 'demo': True})

    async def h_radar_finding(self, r):
        rd = self.radar(r['store'])
        f = next((f for f in rd['findings'] if f['id'] == r.match_info['fid']), None)
        return J({'finding': self.finding_view(f)}) if f else E('not found', 404)

    async def h_radar_act(self, r):
        s = r['store']
        fid, action = r.match_info['fid'], r.match_info['action']
        rd = self.radar(s)
        f = next((f for f in rd['findings'] if f['id'] == fid), None)
        b = await self.body(r)
        if action == 'spawn':
            if not f:
                return E('not found', 404)
            mode, prompt = b.get('mode'), str(b.get('prompt') or f['prompt']).strip()[:6000]
            if mode not in ('chat', 'task'):
                return E('mode must be chat or task')
            out = {'ok': True}
            if mode == 'chat':
                chat = s.ensure_chat(S.new_id('c'), ('Radar: ' + f['title'])[:80])
                full = f"{prompt}\n\n---\nFrom Radar finding {fid} ({C.RADAR_TYPES.get(f['type'], f['type'])}): {f['title']}\nWhy: {f['why']}"
                s.add_message(chat['id'], 'user', full, meta={'demo': True})
                self.spawn_reply(r['device']['id'], s, chat['id'], prompt, [])
                out['chat_id'] = chat['id']
                f['feedback'].append({'id': len(f['feedback']) + 1, 'finding_id': fid, 'ts': time.time(), 'kind': 'spawn_chat', 'text': None, 'meta': {'chat_id': chat['id']}})
            else:
                out['task_id'] = 't_' + secrets.token_hex(4)
                f['feedback'].append({'id': len(f['feedback']) + 1, 'finding_id': fid, 'ts': time.time(), 'kind': 'spawn_task', 'text': None, 'meta': {'task_id': out['task_id']}})
            f['spawned'] += 1
            f['updated'] = time.time()
            f['feedback'] = f['feedback'][-50:]
            s.kv_put('radar', rd)
            return J(out)
        if action not in ('like', 'dislike', 'clear-vote', 'feedback', 'note', 'dismiss', 'save', 'unsave', 'restore'):
            return E('bad action')
        if not f:
            return E('not found', 404)
        text = str(b.get('text') or '').strip()[:4000]
        if action in ('feedback', 'note') and not text:
            return E('text required')
        f.update({'like': {'vote': 1}, 'dislike': {'vote': -1}, 'clear-vote': {'vote': 0}, 'dismiss': {'state': 'dismissed'},
                  'save': {'state': 'saved'}, 'unsave': {'state': 'new'}, 'restore': {'state': 'new'}}.get(action, {}), updated=time.time())
        if action in ('feedback', 'note'):
            f['feedback'] = (f['feedback'] + [{'id': len(f['feedback']) + 1, 'finding_id': fid, 'ts': time.time(), 'kind': action, 'text': text, 'meta': None}])[-50:]
        s.kv_put('radar', rd)
        return J({'finding': self.finding_view(f)})

    async def h_radar_note(self, r):
        n = next((n for n in self.radar(r['store'])['notes'] if n['id'] == r.match_info['nid']), None)
        return J({'note': n}) if n else E('not found', 404)

    async def h_radar_ack(self, r):
        s = r['store']
        rd = self.radar(s)
        n = next((n for n in rd['notes'] if n['id'] == r.match_info['nid']), None)
        if not n:
            return J({'ok': False}, 404)
        n['acked'] = 1
        s.kv_put('radar', rd)
        return J({'ok': True})

    async def h_momentum(self, r):
        rd = self.radar(r['store'])
        report = json.loads(json.dumps(C.MOMENTUM))
        report['counts'] = {k: max(report['counts'].get(k, 0), len(report[k])) for k in ('needs_you', 'problems', 'running', 'shipped', 'decided')}
        return J({'available': True, 'report': report, 'fetched_at': rd['momentum_at'], 'checked_at': time.time(), 'stale': False,
                  'error': None, 'scout': True, 'demo': True})

    # ------------------------------------------------------------ Fleet (synthetic agents, synthetic terminal)
    TERM_COLS, TERM_ROWS = 46, 12

    def fleet(self, s):
        def make():
            now = time.time()
            agents = []
            for i, a in enumerate(C.FLEET_AGENTS):
                x = {k: v for k, v in a.items() if k != 'terminal'}
                x['last'] = now - 60 * (i + 1) * (30 if a['group'] == 'recent' else 1)
                x['started'] = x['last'] - 1800
                if a.get('terminal'):
                    x['term'] = {'buffer': list(a['lines']), 'input': '', 'selected': 0, 'offset': 0}
                agents.append(x)
            return {'agents': agents}
        return self.kv(s, 'fleet', make)

    def term_render(self, a):
        t = a['term']
        opts = [('› ' if i == t['selected'] else '  ') + o for i, o in enumerate(C.TERMINAL_OPTIONS)]
        body = t['buffer'] + opts
        rows = self.TERM_ROWS - 2
        end = len(body) - t['offset']
        shown = body[max(0, end - rows):end]
        pad = rows - len(shown)
        first = max(0, end - rows)
        rowmap = {pad + i: idx - len(t['buffer']) for i, idx in enumerate(range(first, end)) if idx >= len(t['buffer'])}
        lines = [''] * pad + [x[:self.TERM_COLS] for x in shown] + ['─' * self.TERM_COLS, ('> ' + t['input'])[:self.TERM_COLS]]
        frame = hashlib.sha256(json.dumps({'lines': lines, 'offset': t['offset'], 'sel': t['selected']}, sort_keys=True).encode()).hexdigest()
        return {'available': True, 'frame': frame, 'columns': self.TERM_COLS, 'rows': self.TERM_ROWS, 'lines': lines, 'mouse': True}, rowmap

    def fleet_rows(self, s, did, recent):
        rows = []
        for a in self.fleet(s)['agents']:
            if a['group'] == 'recent' and not recent:
                continue
            rows.append({k: v for k, v in a.items() if k not in ('lines', 'term')} | {'state': a['group'], 'live': a['group'] != 'recent', 'stale': False,
                                                                                      'host_error': '', 'cwd': '', 'prs': [], 'hold': False,
                                                                                      'can_keys': 'term' in a, 'can_click': 'term' in a, 'can_scroll': 'term' in a})
        for cid, since in self.runtime(did)['working'].items():
            chat = s.get_chat(cid) or {}
            rows.append({'id': 'hm:' + cid, 'kind': 'hermes', 'name': chat.get('title') or 'Chat', 'group': 'working', 'state': 'working',
                         'detail': 'Hermes Demo is working (synthetic)', 'where': 'mobile', 'host': '', 'project': '', 'model': '',
                         'last': since, 'can_reply': True, 'can_stop': True, 'unguarded': False, 'via': '', 'chat_id': cid})
        for x in self.sessions(s):
            if x['live']:
                rows.append({'id': 'hs:' + x['id'], 'kind': 'hermes', 'name': x['title'], 'group': 'working', 'state': 'working',
                             'detail': x['source'], 'where': x['source'], 'host': '', 'project': '', 'model': x['model'], 'last': time.time() - 90,
                             'can_reply': False, 'can_stop': False, 'unguarded': False, 'via': '', 'session_id': x['id'], 'source': x['source']})
        order = {'waiting': 0, 'working': 1, 'done': 2, 'recent': 3}
        return sorted(rows, key=lambda a: (order.get(a['group'], 4), -a['last']))

    def fleet_agent(self, s, aid):
        f = self.fleet(s)
        return f, next((a for a in f['agents'] if a['id'] == aid), None)

    async def h_fleet(self, r):
        s, did = r['store'], r['device']['id']
        rows = self.fleet_rows(s, did, r.query.get('recent') == '1')
        counts = {g: sum(1 for a in self.fleet_rows(s, did, True) if a['group'] == g) for g in ('waiting', 'working', 'done', 'recent')}
        return J({'agents': rows, 'counts': counts, 'error': None, 'events': [], 'status_beat': time.time(), 'at': time.time(), 'demo': True})

    async def h_fleet_tail(self, r):
        s, aid = r['store'], r.match_info['aid']
        if aid.startswith('hm:'):
            return J({'lines': [], 'chat_id': aid[3:], 'terminal': {'available': False}})
        if aid.startswith('hs:'):
            x = self.session_or_404(s, aid[3:])
            return J({'lines': [f"{m['role'].upper()}: {m['content']}" for m in (x or {}).get('messages', [])][-40:], 'terminal': {'available': False}})
        _, a = self.fleet_agent(s, aid)
        if not a:
            return E('no such session', 404)
        n = max(1, min(int(r.query.get('n', 60)), 400))
        if 'term' in a:
            term, _ = self.term_render(a)
            return J({'lines': term['lines'], 'terminal': term})
        return J({'lines': a['lines'][-n:], 'terminal': {'available': False}})

    async def h_fleet_send(self, r):
        s, did, aid = r['store'], r['device']['id'], r.match_info['aid']
        raw = await self.body(r)
        text = raw.get('text')
        if not isinstance(text, str) or not text.strip():
            return J({'ok': False, 'error': 'empty'}, 409)
        if aid.startswith('hm:'):
            cid = aid[3:]
            if not s.get_chat(cid):
                return J({'ok': False, 'error': 'no such session'}, 409)
            s.add_message(cid, 'user', text.strip()[:8000], kind='steer', meta={'mode': 'steer', 'demo': True})
            self.runtime(did)['steer'][cid] = text.strip()
            return J({'ok': True, 'line': 'steered'})
        f, a = self.fleet_agent(s, aid)
        if not a:
            return J({'ok': False, 'error': 'no such session'}, 409)
        # Same rule as production: an agent that acts without asking needs the biometric proof bound to this exact text.
        if a.get('unguarded') and not self.signed(r, f"fleet\n{aid}\n{hashlib.sha256(text.encode()).hexdigest()}\n{r.headers.get('X-HM-Ts', '')}".encode()):
            return J({'ok': False, 'error': 'biometric confirmation required', 'need_sig': True}, 403)
        if not a.get('can_reply', True):
            return J({'ok': False, 'error': f"{a['name']} runs in a terminal without remote control; reply there"}, 409)
        reply = f'Synthetic agent: got “{text.strip()[:60]}”. Nothing ran.'
        if 'term' in a:
            a['term']['buffer'] = (a['term']['buffer'] + ['> ' + text.strip()[:200], reply])[-200:]
            a['term']['offset'] = 0
        else:
            a['lines'] = (a['lines'] + ['> ' + text.strip()[:200], reply])[-200:]
        if a['group'] == 'waiting':
            a.update(group='working', detail='Working on your answer (synthetic)')
        a['last'] = time.time()
        s.kv_put('fleet', f)
        return J({'ok': True, 'line': f"queued on {a['host']} (synthetic)" if a.get('host') else 'sent (synthetic)'})

    async def h_fleet_stop(self, r):
        s, did, aid = r['store'], r['device']['id'], r.match_info['aid']
        if aid.startswith('hm:'):
            self.stop_replies(did, aid[3:])
            return J({'ok': True})
        f, a = self.fleet_agent(s, aid)
        if not a:
            return J({'ok': False, 'error': 'no such session'}, 409)
        if a.get('host'):
            return J({'ok': False, 'error': 'interrupt is local only for remote sessions'}, 409)
        if not a.get('can_stop'):
            return J({'ok': False, 'error': "this session can't be interrupted from here"}, 409)
        a['lines'] = (a['lines'] + ['^[ (Esc sent, synthetic: nothing was interrupted)'])[-200:]
        s.kv_put('fleet', f)
        return J({'ok': True, 'line': 'Sent Esc (synthetic)'})

    async def h_fleet_input(self, r):
        s, aid = r['store'], r.match_info['aid']
        raw = await r.read()
        # Raw terminal input always needs the biometric proof over the exact bytes, before anything is parsed.
        if not self.signed(r, f"fleet-input\n{aid}\n{hashlib.sha256(raw).hexdigest()}\n{r.headers.get('X-HM-Ts', '')}".encode()):
            return J({'ok': False, 'error': 'biometric confirmation required', 'need_sig': True}, 403)

        def no_dupes(pairs):
            keys = [k for k, _ in pairs]
            if len(keys) != len(set(keys)):
                raise ValueError('duplicate key')
            return dict(pairs)
        try:
            body = json.loads(raw.decode('utf-8'), object_pairs_hook=no_dupes)
            action = body.get('action') if isinstance(body, dict) else None
            fields = {'key': {'action', 'key', 'shift', 'ctrl', 'alt'}, 'click': {'action', 'row', 'col', 'frame'},
                      'scroll': {'action', 'direction', 'amount', 'frame'}}
            if action not in fields or set(body) - fields[action]:
                raise ValueError
            if action == 'key':
                if body.get('key') not in C.TERMINAL_KEYS or any(type(body.get(k, False)) is not bool for k in ('shift', 'ctrl', 'alt')):
                    raise ValueError
            else:
                if not isinstance(body.get('frame'), str) or not re.fullmatch('[0-9a-f]{64}', body['frame']):
                    raise ValueError
                if action == 'click' and any(type(body.get(k)) is not int or body[k] < 0 for k in ('row', 'col')):
                    raise ValueError
                if action == 'scroll' and (body.get('direction') not in ('up', 'down') or type(body.get('amount')) is not int or not 1 <= body['amount'] <= 100):
                    raise ValueError
        except (ValueError, UnicodeDecodeError):
            return J({'ok': False, 'error': 'malformed terminal input'}, 400)
        f, a = self.fleet_agent(s, aid)
        if not a:
            return J({'ok': False, 'error': 'no such session'}, 409)
        if a.get('host'):
            return J({'ok': False, 'error': 'raw terminal input is local only; use reply for remote sessions'}, 409)
        if 'term' not in a:
            return J({'ok': False, 'available': False, 'error': 'this session has no terminal controls'}, 409)
        t = a['term']
        term, rowmap = self.term_render(a)
        say = lambda line: t.update(buffer=(t['buffer'] + [line])[-200:], offset=0)
        if action in ('click', 'scroll') and body['frame'] != term['frame']:
            return J({'ok': False, 'available': False, 'error': 'stale terminal frame; refresh before input'}, 409)
        n = len(C.TERMINAL_OPTIONS)
        if action == 'click':
            if body['row'] >= self.TERM_ROWS or body['col'] >= self.TERM_COLS:
                return J({'ok': False, 'available': False, 'error': 'click outside terminal geometry'}, 409)
            if body['row'] in rowmap:
                t['selected'] = rowmap[body['row']]
        elif action == 'scroll':
            limit = max(0, len(t['buffer']) + n - (self.TERM_ROWS - 2))
            t['offset'] = max(0, min(limit, t['offset'] + (body['amount'] if body['direction'] == 'up' else -body['amount'])))
        else:
            k = body['key']
            if body.get('ctrl') and k in ('space', 'escape'):
                say('^C (synthetic: nothing was interrupted)')
            elif k.isdigit() and int(k) <= n:
                t['selected'] = int(k) - 1
                say(f"Picked {C.TERMINAL_OPTIONS[t['selected']]} (synthetic)")
            elif k.isdigit() or k == 'space':
                t['input'] = (t['input'] + (' ' if k == 'space' else k))[:40]
            elif k == 'backspace':
                t['input'] = t['input'][:-1]
            elif k in ('up', 'down', 'tab'):
                t['selected'] = (t['selected'] + (-1 if k == 'up' or (k == 'tab' and body.get('shift')) else 1)) % n
            elif k == 'enter':
                if t['input'].strip():
                    say('> ' + t['input'])
                    say('Synthetic agent: noted. Nothing ran.')
                    t['input'] = ''
                else:
                    say(f"Picked {C.TERMINAL_OPTIONS[t['selected']]} (synthetic)")
            elif k == 'escape':
                say('(Esc) Stopped the synthetic draft.')
            elif k in ('page_up', 'page_down'):
                limit = max(0, len(t['buffer']) + n - (self.TERM_ROWS - 2))
                t['offset'] = max(0, min(limit, t['offset'] + ((self.TERM_ROWS - 2) if k == 'page_up' else -(self.TERM_ROWS - 2))))
        a['last'] = time.time()
        s.kv_put('fleet', f)
        return J({'ok': True})

    # ------------------------------------------------------------ Projects (synthetic git repos)
    def projects(self, s):
        def make():
            now = time.time()
            return [{**{k: v for k, v in p.items() if k != 'age'}, 'files': [dict(f) for f in p['files']], 'touched': now - p['age'],
                     'pr': None, 'commits': []} for p in C.PROJECTS]
        return self.kv(s, 'projects', make)

    @staticmethod
    def repo_status(p):
        files = p['files']
        return {'branch': p['branch'], 'defaultBranch': p['defaultBranch'], 'detached': False, 'ahead': p['ahead'], 'behind': p['behind'],
                'staged': sum(f['staged'] for f in files), 'unstaged': sum(not f['staged'] and not f['untracked'] for f in files),
                'untracked': sum(f['untracked'] for f in files), 'conflicted': sum(f['conflicted'] for f in files), 'changed': len(files),
                'added': sum(f['added'] for f in files), 'removed': sum(f['removed'] for f in files),
                'files': [{'path': f['path'], 'staged': f['staged'], 'unstaged': not f['staged'] and not f['untracked'],
                           'untracked': f['untracked'], 'conflicted': f['conflicted']} for f in files]}

    async def h_projects(self, r):
        repos = []
        for p in sorted(self.projects(r['store']), key=lambda p: -p['touched']):
            st = self.repo_status(p)
            repos.append({'id': p['id'], 'path': f"sandbox://projects/{p['id']}", 'touched': p['touched'],
                          **{k: st[k] for k in ('branch', 'ahead', 'behind', 'changed', 'added', 'removed', 'conflicted')}})
        return J({'root': 'sandbox://projects', 'repos': repos, 'demo': True})

    def repo(self, r):
        all_ = self.projects(r['store'])
        return all_, next((p for p in all_ if p['id'] == r.match_info['rid']), None)

    async def h_project(self, r):
        _, p = self.repo(r)
        if not p:
            return E('unknown repo', 404)
        return J({'status': self.repo_status(p), 'review': {'files': [{'path': f['path'], 'added': f['added'], 'removed': f['removed'],
                                                                        'status': '?' if f['untracked'] else 'M', 'staged': f['staged']} for f in p['files']], 'base': None},
                  'ship': {'ghReady': True, 'pr': p['pr']}})

    async def h_project_diff(self, r):
        _, p = self.repo(r)
        path = r.query.get('file', '')
        f = next((f for f in (p or {}).get('files', []) if f['path'] == path), None)
        if not p or not f or '..' in path or path.startswith('/'):
            return E('bad file')
        return J({'diff': f['diff']})

    async def h_project_action(self, r):
        s, action = r['store'], r.match_info['action']
        all_, p = self.repo(r)
        if not p:
            return E('unknown repo', 404)
        b = await self.body(r)
        if action in ('push', 'create-pr') and not self.signed(r, f"git\n{p['id']}\n{action}\n{r.headers.get('X-HM-Ts', '')}".encode()):
            return E('biometric signature required', 403)
        result = {'ok': True}
        if action in ('stage', 'unstage'):
            path = b.get('file')
            hits = [f for f in p['files'] if path is None or f['path'] == path]
            if path is not None and not hits:
                return E('bad file')
            for f in hits:
                f['staged'] = action == 'stage'
                if action == 'stage':
                    f['untracked'] = False
        elif action == 'commit':
            msg = str(b.get('message') or '').strip()
            if not msg:
                return E('commit message required')
            if not any(f['staged'] for f in p['files']):
                for f in p['files']:
                    f['staged'] = True
            p['files'] = [f for f in p['files'] if not f['staged']]
            p['commits'] = (p['commits'] + [msg[:200]])[-20:]
            p['ahead'] += 1
        elif action == 'push':
            if p['ahead'] == 0:
                return E('Nothing to push.')
            p['ahead'] = 0
        elif action == 'create-pr':
            if p['branch'] == p['defaultBranch']:
                return E('Open a pull request from a branch, not from ' + p['defaultBranch'] + '.')
            if not p['pr']:
                number = 7 + all_.index(p)
                p['pr'] = {'number': number, 'state': 'OPEN', 'title': (p['commits'][-1] if p['commits'] else p['branch']) + ' (synthetic)',
                           'url': f"{self.url}/review/pr"}
            result = {'url': p['pr']['url']}
        else:
            return E('bad action')
        p['touched'] = time.time()
        s.kv_put('projects', all_)
        return J({'ok': True, 'result': result})

    # ------------------------------------------------------------ devices, push, capabilities, fallback
    async def h_devices(self, r):
        return J({'devices': [d for d in self.auth.devices() if d['id'] == r['device']['id']], 'this': r['device']['id']})

    async def h_revoke(self, r):
        did = r.match_info['did']
        if did != r['device']['id']:
            return E('not found', 404)
        return J({'ok': self.auth.revoke(did)})

    async def h_push_config(self, r):
        return J({'enabled': False, 'config': {}})

    async def h_fcm(self, r):
        return J({'ok': True, 'push': False})  # Deliberately discard token.

    async def h_capabilities(self, r):
        return J({'demo': True, 'unsupported': UNSUPPORTED, 'limits': LIMITS, 'surfaces': sorted({f'{m} {p}' for m, p, _ in self.routes()}),
                  'submission_ready': not UNSUPPORTED})

    async def unsupported(self, r):
        return J({'error': 'Unsupported in synthetic review sandbox', 'demo': True, 'surface': r.path}, 501)

    def routes(self):
        return [
            ('GET', '/v1/me', self.h_me), ('GET', '/v1/sync', self.h_sync), ('GET', '/v1/commands', self.h_commands),
            ('GET', '/v1/profiles', self.h_profiles), ('GET', '/v1/chats', self.h_chats), ('POST', '/v1/chats', self.h_chats),
            ('PATCH', '/v1/chats/{cid}', self.h_chat_patch), ('GET', '/v1/chats/{cid}/messages', self.h_messages),
            ('POST', '/v1/chats/{cid}/messages', self.h_post_message), ('POST', '/v1/chats/{cid}/read', self.h_read),
            ('POST', '/v1/chats/{cid}/stop', self.h_stop), ('GET', '/v1/chats/{cid}/run', self.h_run),
            ('GET', '/v1/chats/{cid}/usage', self.h_chat_usage), ('GET', '/v1/chats/{cid}/model', self.h_chat_model),
            ('POST', '/v1/chats/{cid}/model', self.h_set_chat_model), ('POST', '/v1/chats/{cid}/confirm', self.h_confirm),
            ('POST', '/v1/chats/{cid}/session', self.h_chat_session), ('GET', '/v1/activity/{mid}', self.h_activity),
            ('POST', '/v1/approvals/{aid}', self.h_approve), ('POST', '/v1/upload', self.h_upload),
            ('GET', '/v1/media/{name}', self.h_media), ('POST', '/v1/tts', self.h_tts),
            ('GET', '/v1/models', self.h_models), ('GET', '/v1/pulse', self.h_pulse), ('GET', '/v1/usage', self.h_usage),
            ('GET', '/v1/search', self.h_search), ('GET', '/v1/sessions', self.h_sessions),
            ('GET', '/v1/sessions/{sid}/messages', self.h_session_messages), ('PUT', '/v1/sessions/{sid}/messages', self.h_session_send),
            ('PUT', '/v1/sessions/{sid}/prompts/{pid}', self.h_session_prompt), ('POST', '/v1/sessions/{sid}/stop', self.h_session_stop),
            ('DELETE', '/v1/sessions/{sid}/viewer', self.h_session_close),
            ('GET', '/v1/cron', self.h_cron), ('POST', '/v1/cron/{jid}/{action}', self.h_cron_action),
            ('GET', '/v1/cron/{jid}/output', self.h_cron_output), ('GET', '/v1/cronjob/{jid}', self.h_cronjob_get),
            ('POST', '/v1/cronjob', self.h_cronjob_create), ('PUT', '/v1/cronjob/{jid}', self.h_cronjob_update),
            ('DELETE', '/v1/cronjob/{jid}', self.h_cronjob_delete), ('GET', '/v1/cron-delivery', self.h_cron_delivery),
            ('POST', '/v1/cron-delivery', self.h_cron_delivery),
            ('GET', '/v1/memory', self.h_memory), ('POST', '/v1/memory', self.h_memory),
            ('GET', '/v1/settings', self.h_settings), ('GET', '/v1/settings/all', self.h_settings_all), ('PUT', '/v1/settings', self.h_settings_put),
            ('GET', '/v1/skills', self.h_skills), ('GET', '/v1/skill', self.h_skill), ('POST', '/v1/skills/toggle', self.h_skill_toggle),
            ('GET', '/v1/plugins', self.h_plugins), ('POST', '/v1/plugins/toggle', self.h_plugin_toggle), ('GET', '/v1/mcp', self.h_mcp),
            ('POST', '/v1/restart', self.h_restart),
            ('GET', '/v1/hub/skills/installed', self.h_hub_installed), ('GET', '/v1/hub/skills/browse', self.h_hub_browse),
            ('GET', '/v1/hub/skills/search', self.h_hub_search), ('GET', '/v1/hub/skills/updates', self.h_hub_updates),
            ('GET', '/v1/hub/skills/preview', self.h_hub_preview), ('GET', '/v1/hub/skills/scan', self.h_hub_scan),
            ('POST', '/v1/hub/skills/{action:install|update|uninstall}', self.h_hub_skill_write),
            ('GET', '/v1/hub/plugins', self.h_hub_plugins), ('POST', '/v1/hub/plugins/{action:install|update|remove}', self.h_hub_plugin_write),
            ('GET', '/v1/hub/mcp', self.h_hub_mcp), ('POST', '/v1/hub/mcp/{action:install|uninstall}', self.h_hub_mcp_write),
            ('GET', '/v1/hub/jobs', self.h_hub_jobs), ('GET', '/v1/hub/jobs/{jid}', self.h_hub_job),
            ('GET', '/v1/wake', self.h_wake), ('PUT', '/v1/wake/config', self.h_wake_config), ('GET', '/v1/wake/session', self.h_wake_session),
            ('POST', '/v1/wake/fired', self.h_wake_fired), ('POST', '/v1/wake/proof', self.h_wake_proof), ('POST', '/v1/wake/test', self.h_wake_test),
            ('POST', '/v1/wake/cancel', self.h_wake_cancel), ('POST', '/v1/wake/override', self.h_wake_override),
            ('POST', '/v1/wake/password', self.h_wake_password), ('POST', '/v1/wake/whatsapp-test', self.h_wake_whatsapp),
            ('GET', '/v1/radar', self.h_radar), ('GET', '/v1/radar/findings/{fid}', self.h_radar_finding),
            ('POST', '/v1/radar/findings/{fid}/{action}', self.h_radar_act), ('GET', '/v1/radar/notes/{nid}', self.h_radar_note),
            ('POST', '/v1/radar/notes/{nid}/ack', self.h_radar_ack), ('GET', '/v1/radar/momentum', self.h_momentum),
            ('GET', '/v1/fleet', self.h_fleet), ('GET', '/v1/fleet/{aid}/tail', self.h_fleet_tail),
            ('POST', '/v1/fleet/{aid}/send', self.h_fleet_send), ('POST', '/v1/fleet/{aid}/stop', self.h_fleet_stop),
            ('POST', '/v1/fleet/{aid}/input', self.h_fleet_input),
            ('GET', '/v1/projects', self.h_projects), ('GET', '/v1/projects/{rid}', self.h_project),
            ('GET', '/v1/projects/{rid}/diff', self.h_project_diff), ('POST', '/v1/projects/{rid}/{action}', self.h_project_action),
            ('GET', '/v1/devices', self.h_devices), ('DELETE', '/v1/devices/{did}', self.h_revoke),
            ('GET', '/v1/push-config', self.h_push_config), ('POST', '/v1/fcm', self.h_fcm),
            ('GET', '/v1/review/capabilities', self.h_capabilities),
        ]

    def app(self):
        # Uploads and photo proofs may be larger than ordinary JSON; the middleware keeps 1 MiB for everything else.
        app = web.Application(middlewares=[self.auth_mw], client_max_size=MAX_UPLOAD)
        app.router.add_get('/v1/health', self.public)
        app.router.add_get('/review', self.public)
        app.router.add_get('/review/pr', self.public)
        app.router.add_post('/review/enroll', self.public)
        app.router.add_post('/v1/pair', self.public)
        app.router.add_get('/v1/events', self.events)
        for method, path, handler in self.routes():
            app.router.add_route(method, path, handler)
        app.router.add_route('*', '/{path:.*}', self.unsupported)

        async def close(app):
            tasks = [t for rt in self.rt.values() for t in [*rt['tasks'].values(), *rt['session_tasks'].values(),
                                                            *(x for g in rt.get('all', {}).values() for x in g)] if not t.done()]
            for t in tasks:
                t.cancel()
            # Let cancelled replies record their stop line before the databases close.
            await asyncio.gather(*tasks, return_exceptions=True)
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
    parser.add_argument('--client-ip-header', default=None,
                        help='Header the TLS proxy sets to the client IP (for example X-Forwarded-For); per-client limits key on it')
    args = parser.parse_args()
    backend = Backend(args.state, args.public_url, client_header=args.client_ip_header)
    print('Synthetic sandbox only; invitation file:', backend.home / 'invitation.txt', flush=True)
    web.run_app(backend.app(), host='127.0.0.1', port=args.port, access_log=None)


if __name__ == '__main__':
    main()
