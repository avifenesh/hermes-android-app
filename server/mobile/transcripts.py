"""Voice-note transcripts for the phone: pair Hermes' STT echo with the voice message it transcribes.

Hermes transcribes an inbound voice clip with its configured STT provider. With
``stt.echo_transcripts`` on (the default) it sends each transcript back through the delivering
adapter as one plain ``send`` of ``🎙️ "<text>"`` with no reply anchor
(``gateway/run_inbound.py`` ``_echo_stt_transcripts``, locale key
``gateway.voice.transcript_echo_short``). The plugin runs no STT of its own. That echo is the
transcript it receives, but the echo names no message, so the adapter pairs it only when the match
is certain:

- no message in the chat was dropped from waiting while its echo could still come (see below),
- exactly one message in the chat is waiting for a transcript, and
- that message carried exactly one clip the core transcribes.

Anything else stays the plain echo bubble it always was.

The core awaits the echo before the clip's turn reaches the agent: on the normal inbound path,
before a busy steer, and before a busy interrupt. A queued note is transcribed only when its turn
is drained. So a message is certainly past STT once a run that started after it was sent answers
it, and so is every message sent before that one (turns run in arrival order). That certain close
is the only way a message stops waiting without its echo. Busy acks, notices and replies to a
message that arrived during the answering run do not count: the busy ack for a queued note goes
out before the note is transcribed.

The one uncertain drop is the per-chat bound: more waiting messages than the core can hold in
flight for one session. The dropped message's echo may still come, so the next echo in that chat
counts as ambiguous instead of landing on a different message.
"""
from __future__ import annotations

import re
from typing import Dict, List, Optional, Tuple

# English and most catalogs render 🎙️ "text"; Ukrainian renders 🎙️ «text». The variation selector
# is optional so a catalog that drops it still matches.
_ECHO_RE = re.compile(r'\A\s*\U0001f399\ufe0f?\s*(?:"(?P<q>.*)"|\u00ab(?P<g>.*)\u00bb)\s*\Z', re.S)


def echo_text(content: Optional[str]) -> Optional[str]:
    """The transcript inside a core STT echo, or None when ``content`` is not one."""
    m = _ECHO_RE.match(content or "")
    if not m:
        return None
    text = m.group("q") if m.group("q") is not None else m.group("g")
    return text if text.strip() else None


class Pairing:
    """Per-chat FIFO of voice messages whose transcript has not arrived. In memory only: after a
    gateway restart nothing waits, so a late echo simply stays a bubble."""

    def __init__(self, cap: int) -> None:
        self._cap = max(1, cap)
        # chat_id -> [(message id, message ts, attachment index or None when it had several clips)]
        self._wait: Dict[str, List[Tuple[str, float, Optional[int]]]] = {}
        # chat_id -> messages dropped while their echo could still arrive; each makes one echo ambiguous
        self._unsure: Dict[str, int] = {}

    def expect(self, chat_id: str, msg_id: str, ts: float, clips: List[int]) -> None:
        """``msg_id`` (sent at ``ts``) was dispatched with ``clips``, indexes into its ``meta.attachments``."""
        if not clips:
            return
        wait = self._wait.setdefault(chat_id, [])
        wait.append((msg_id, ts, clips[0] if len(clips) == 1 else None))
        while len(wait) > self._cap:
            wait.pop(0)
            self._unsure[chat_id] = self._unsure.get(chat_id, 0) + 1

    def take(self, chat_id: str) -> Optional[Tuple[str, int]]:
        """``(message id, attachment index)`` for an echo that arrived now, or None when ambiguous."""
        unsure = self._unsure.get(chat_id, 0)
        if unsure:
            if unsure == 1:
                self._unsure.pop(chat_id)
            else:
                self._unsure[chat_id] = unsure - 1
            return None
        wait = self._wait.get(chat_id) or []
        if len(wait) != 1 or wait[0][2] is None:
            return None
        msg_id, _, index = wait.pop()
        self._wait.pop(chat_id, None)
        return msg_id, index

    def answered(self, chat_id: str, msg_id: str, run_since: Optional[float]) -> None:
        """A real answer to ``msg_id`` from a run that started at ``run_since``. When the message
        was sent before that run began, it and every older waiting message are past STT."""
        wait = self._wait.get(chat_id)
        if not wait or run_since is None:
            return
        for i, entry in enumerate(wait):
            if entry[0] == msg_id:
                if entry[1] < run_since:
                    del wait[:i + 1]
                break
        if not wait:
            self._wait.pop(chat_id, None)

    def waiting(self, chat_id: str) -> List[str]:
        return [e[0] for e in self._wait.get(chat_id) or []]

    def unsure(self, chat_id: str) -> int:
        return self._unsure.get(chat_id, 0)
