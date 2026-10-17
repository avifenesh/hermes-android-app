"""Strict Fleet terminal protocol and fixed-path Kitty transport.

Discovery remains claude-ctrl's responsibility. No caller-controlled command,
kitten path, text, escape sequence or remote-control match enters this module.
"""
from __future__ import annotations

import asyncio
import json
import os
import re
import shutil
from pathlib import Path

KEYS = frozenset(('escape enter tab up down left right home end page_up page_down backspace space ' +
                  ' '.join(str(i) for i in range(1, 10))).split())
ANSI = re.compile(r'\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)|\x1b\[[0-?]*[ -/]*[@-~]|\x1b[@-_]')


def validate_input(body):
    if not isinstance(body, dict):
        raise ValueError('input must be an object')
    action = body.get('action')
    fields = {'key': {'action', 'key', 'shift', 'ctrl', 'alt'},
              'click': {'action', 'row', 'col', 'frame'},
              'scroll': {'action', 'direction', 'amount', 'frame'}}
    if not isinstance(action, str) or action not in fields or set(body) - fields[action]:
        raise ValueError('unsupported input action or fields')
    if action == 'key':
        if not isinstance(body.get('key'), str) or body['key'] not in KEYS:
            raise ValueError('unsupported key')
        if any(type(body.get(k, False)) is not bool for k in ('shift', 'ctrl', 'alt')):
            raise ValueError('modifiers must be booleans')
    else:
        if not isinstance(body.get('frame'), str) or not re.fullmatch('[0-9a-f]{64}', body['frame']):
            raise ValueError('invalid frame')
        if action == 'click':
            if any(type(body.get(k)) is not int or body[k] < 0 for k in ('row', 'col')):
                raise ValueError('coordinates must be nonnegative integers')
        elif body.get('direction') not in ('up', 'down') or type(body.get('amount')) is not int or not 1 <= body['amount'] <= 100:
            # Bound repeated child writes in a single event-loop callback.
            raise ValueError('scroll amount must be an integer from 1 to 100')
    return dict(body)


def process_identity(pid):
    """PID + kernel start time + exact argv prevents PID reuse and shell execs."""
    if type(pid) is not int or pid <= 0:
        raise ValueError('invalid agent pid')
    base = Path('/proc') / str(pid)
    stat = base.joinpath('stat').read_text().rsplit(')', 1)[1].split()
    argv = base.joinpath('cmdline').read_bytes().split(b'\0')
    argv = [x.decode('utf-8', 'strict') for x in argv if x]
    if not argv or not any(re.match(r'^(claude|codex|opencode|hermes)(?:[-_.].*)?$', os.path.basename(x))
                           for x in argv[:4]):
        raise ValueError('foreground process is not an agent')
    return {'pid': pid, 'start': stat[19], 'argv': argv}


class KittyTerminal:
    async def call(self, agent, target, action=None):
        try:
            if not agent.get('live') or agent.get('pane') or not target:
                raise ValueError('session is read-only or not a live Kitty agent')
            identity = process_identity(int(agent.get('pid') or 0))
            sock, win = target
            if not str(win).isdigit() or not sock.startswith(('unix:', 'tcp:')):
                raise ValueError('invalid Kitty target')
            payload = {'identity': identity, 'input': validate_input(action) if action is not None else None}
            helper = str(Path(__file__).with_name('kitty_terminal.py').resolve())
            proc = await asyncio.create_subprocess_exec(shutil.which('kitty') or 'kitty', '@', '--to', sock,
                'kitten', '--match', 'id:' + str(win), helper, json.dumps(payload, ensure_ascii=False),
                stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
            try:
                out, err = await asyncio.wait_for(proc.communicate(), 10)
            except (asyncio.TimeoutError, asyncio.CancelledError):
                if proc.returncode is None:
                    proc.kill()
                await proc.wait()
                raise
            if proc.returncode:
                raise ValueError('Kitty terminal unavailable')
            result = json.loads(out.decode('utf-8', 'strict'))
            if not isinstance(result, dict):
                raise ValueError('Kitty window is gone')
            return result
        except (OSError, ValueError, asyncio.TimeoutError) as exc:
            return {'ok': False, 'available': False, 'error': str(exc)}

    async def snapshot(self, agent, target):
        return await self.call(agent, target)

    async def input(self, agent, target, body):
        return await self.call(agent, target, body)
