"""Voice-note transcripts for the phone: pair Hermes' STT echo with the voice message it transcribes.

Hermes transcribes an inbound voice clip with its configured STT provider. With
``stt.echo_transcripts`` on (the default) it sends each transcript back through the delivering
adapter as one plain ``send`` of ``🎙️ "<text>"`` before the agent loop starts
(``gateway/run_inbound.py`` ``_echo_stt_transcripts``, locale key
``gateway.voice.transcript_echo_short``). The plugin runs no STT of its own. That echo is the
transcript it receives, but the echo names no message, so the adapter pairs it only when the match
is certain:

- exactly one message in the chat is waiting for a transcript, and
- that message carried exactly one clip the core transcribes.

Anything else stays the plain echo bubble it always was. A message stops waiting when its
transcript arrives, when the agent replies to it or to a later message (its turn already ran, so
a missing echo means STT failed, came back empty, or echo is off), or when a run that started after
it was sent ends.
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

    def __init__(self) -> None:
        # chat_id -> [(message id, message ts, attachment index or None when it had several clips)]
        self._wait: Dict[str, List[Tuple[str, float, Optional[int]]]] = {}

    def expect(self, chat_id: str, msg_id: str, ts: float, clips: List[int]) -> None:
        """``msg_id`` was dispatched with ``clips`` (indexes into its ``meta.attachments``) for STT."""
        if not clips:
            return
        self._wait.setdefault(chat_id, []).append((msg_id, ts, clips[0] if len(clips) == 1 else None))

    def take(self, chat_id: str) -> Optional[Tuple[str, int]]:
        """``(message id, attachment index)`` for an echo that arrived now, or None when ambiguous."""
        wait = self._wait.get(chat_id) or []
        if len(wait) != 1 or wait[0][2] is None:
            return None
        msg_id, _, index = wait.pop()
        self._wait.pop(chat_id, None)
        return msg_id, index

    def replied(self, chat_id: str, msg_id: str) -> None:
        """The agent answered ``msg_id``: it and every older waiting message are past STT."""
        wait = self._wait.get(chat_id)
        if not wait:
            return
        for i, entry in enumerate(wait):
            if entry[0] == msg_id:
                del wait[:i + 1]
                break
        if not wait:
            self._wait.pop(chat_id, None)

    def run_ended(self, chat_id: str, since: float) -> None:
        """A run that started at ``since`` ended: messages sent before it started were processed."""
        wait = self._wait.get(chat_id)
        if not wait:
            return
        wait[:] = [e for e in wait if e[1] >= since]
        if not wait:
            self._wait.pop(chat_id, None)

    def waiting(self, chat_id: str) -> List[str]:
        return [e[0] for e in self._wait.get(chat_id) or []]
