"""Fixed no-UI Kitty kitten for Fleet. Runs on Kitty's event-loop thread.

Uses installed Kitty 0.45 APIs: Screen.visual_line, Child.foreground_processes,
Window.send_key and fast_data_types.send_mouse_event. The latter inspects the
CURRENT native mouse tracking mode/protocol and refuses disabled mouse input;
no API text is ever interpreted as an escape sequence. It does not move the
GUI pointer or run Kitty's configurable mouse mappings.
"""
import hashlib
import json
import os
import re
from pathlib import Path

from kittens.tui.handler import result_handler
from kitty.fast_data_types import PRESS, RELEASE, send_mouse_event

KEYS = frozenset(('escape enter tab up down left right home end page_up page_down backspace space ' +
                  ' '.join(str(i) for i in range(1, 10))).split())


def main(args):
    pass


def identity(pid):
    base = Path('/proc') / str(pid)
    stat = base.joinpath('stat').read_text().rsplit(')', 1)[1].split()
    argv = [x.decode('utf-8', 'strict') for x in base.joinpath('cmdline').read_bytes().split(b'\0') if x]
    if not argv or not any(re.match(r'^(claude|codex|opencode|hermes)(?:[-_.].*)?$', os.path.basename(x)) for x in argv[:4]):
        raise ValueError('foreground process is not an agent')
    return {'pid': pid, 'start': stat[19], 'argv': argv}


def check_window(w, expected):
    if not w or identity(expected['pid']) != expected:
        raise ValueError('agent exited or changed identity')
    # The exact discovered agent must STILL own the PTY foreground group,
    # not merely be alive somewhere behind a shell or unrelated program.
    if expected['pid'] not in {p['pid'] for p in w.child.foreground_processes}:
        raise ValueError('foreground agent mismatch; refusing shell/background input')


def snapshot(w, expected):
    s = w.screen
    lines = [str(s.visual_line(i)) for i in range(s.lines)]
    # No arbitrary character cap: native physical rows bound the read and
    # preserve CJK, combining marks, emoji, and significant spaces.
    state = {'identity': expected, 'window': w.id, 'columns': s.columns,
             'rows': s.lines, 'lines': lines, 'offset': s.scrolled_by,
             'alternate': s.is_using_alternate_linebuf(),
             'cursor': (s.cursor.x, s.cursor.y), 'keyboard': s.current_key_encoding_flags()}
    frame = hashlib.sha256(json.dumps(state, ensure_ascii=False, sort_keys=True).encode()).hexdigest()
    return {'available': True, 'frame': frame, 'columns': s.columns, 'rows': s.lines, 'lines': lines,
            # Native Kitty gates every actual mouse event against current
            # modes. This is native mouse-routing availability, not a claim
            # that the child has opted into tracking (not exposed by Screen).
            'mouse': callable(send_mouse_event)}


def mouse(w, col, row, button, action):
    g = w.geometry
    # Pixel coordinates are needed by native SGR-pixel mode. Cell inputs
    # remain zero-based; Kitty does protocol-specific encoding itself.
    px = int((col + .5) * (g.right - g.left) / w.screen.columns)
    py = int((row + .5) * (g.bottom - g.top) / w.screen.lines)
    return send_mouse_event(w.screen, col, row, button, action, 0, px, py)


@result_handler(no_ui=True)
def handle_result(args, data, target_window_id, boss):
    try:
        if len(args) != 2:
            raise ValueError('invalid fixed helper arguments')
        payload = json.loads(args[1])
        expected = payload['identity']
        w = boss.window_id_map.get(target_window_id)
        check_window(w, expected)
        snap = snapshot(w, expected)
        body = payload.get('input')
        if body is None:
            return json.dumps(snap, ensure_ascii=False)
        action = body.get('action')
        if action in ('click', 'scroll') and body.get('frame') != snap['frame']:
            raise ValueError('stale terminal frame; refresh before input')
        # Validate again inside the trusted execution boundary, even though
        # the HTTP layer has already validated the fixed JSON schema.
        if action == 'key':
            if body.get('key') not in KEYS or set(body) - {'action', 'key', 'shift', 'ctrl', 'alt'}:
                raise ValueError('unsupported key')
            if any(type(body.get(k, False)) is not bool for k in ('shift', 'ctrl', 'alt')):
                raise ValueError('invalid modifiers')
            key = '+'.join([k for k in ('shift', 'ctrl', 'alt') if body.get(k)] + [body['key']])
            check_window(w, expected)
            w.send_key(key)
        elif action == 'click':
            row, col = body.get('row'), body.get('col')
            if set(body) != {'action', 'row', 'col', 'frame'} or type(row) is not int or type(col) is not int or not (0 <= row < snap['rows'] and 0 <= col < snap['columns']):
                raise ValueError('click outside terminal geometry')
            if w.screen.scrolled_by:
                raise ValueError('cannot click scrollback')
            check_window(w, expected)
            if not mouse(w, col, row, 1, PRESS):
                raise ValueError('terminal mouse tracking is disabled or unsupported')
            mouse(w, col, row, 1, RELEASE)
        elif action == 'scroll':
            amount, direction = body.get('amount'), body.get('direction')
            if set(body) != {'action', 'direction', 'amount', 'frame'} or type(amount) is not int or not 1 <= amount <= 100 or direction not in ('up', 'down'):
                raise ValueError('invalid scroll')
            check_window(w, expected)
            # Main-buffer TUIs can request mouse tracking too (for example
            # prompt/choice modals). Route their wheel events before falling
            # back to history; never inject keys into a mouse-disabled prompt.
            button = 4 if direction == 'up' else 5
            for _ in range(amount):
                if not w.screen.scrolled_by and mouse(w, 0, 0, button, PRESS):
                    continue
                if w.screen.is_main_linebuf():
                    w.screen.scroll(1, direction == 'up')
                else:
                    w.send_key('page_up' if direction == 'up' else 'page_down')
        else:
            raise ValueError('unsupported input action')
        return json.dumps({'ok': True})
    except (OSError, ValueError, KeyError, TypeError) as exc:
        return json.dumps({'ok': False, 'available': False, 'error': str(exc)})
