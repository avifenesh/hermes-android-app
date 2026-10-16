"""What memory recall injected, for the phone: tap the recall notice to read the facts.

Hermes announces recall with a count only (``🧠 tacit facts — recalled 3 memories``, via
``MemoryManager.describe_recall`` -> ``_emit_status``). The injected text itself rides inside the
turn's API user message as a ``<memory-context>`` block. ``pre_api_request`` fires on every attempt
with the exact request messages, so the hook lifts the block out of the LAST user message (earlier
turns replay their own blocks), once per turn, and hands it to the adapter loop. The adapter pairs
it with the notice it already stored and stamps the text into that message's ``meta.recall``;
the app shows it when the notice row is expanded.
"""
from __future__ import annotations

import logging
import re
import time
from typing import Any, List, Optional

logger = logging.getLogger(__name__)

_LIVE = None
_SEEN: dict = {}  # (session_id, turn_id) -> ts, turns already captured (hook fires per attempt)
CAP = 8000

# describe_recall renders "{glyph} {label} — recalled N memories" / "— recalled relevant memory".
_NOTICE_RE = re.compile(r"— recalled (?:\d+ memor|relevant memory)")
_BLOCK_RE = re.compile(r"<\s*memory-context\s*>(.*?)</\s*memory-context\s*>", re.IGNORECASE | re.DOTALL)
_NOTE_RE = re.compile(r"^\[System note:[^\]]*\]\s*", re.DOTALL)


def is_recall_notice(content: str) -> bool:
    return bool(content) and len(content) < 500 and bool(_NOTICE_RE.search(content))


def _text_of(content: Any) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, dict):
        return str(content.get("text") or content.get("content") or "")
    if isinstance(content, list):
        return "\n".join(t for t in (_text_of(p) for p in content) if t)
    return ""


def _clean(block: str) -> str:
    text = _NOTE_RE.sub("", block.strip()).strip()
    return text[:CAP] if len(text) > CAP else text


def extract(messages: Optional[List[Any]]) -> str:
    """The memory-context block of the last user message, display-cleaned; '' when none."""
    if not isinstance(messages, list):
        return ""
    for m in reversed(messages):
        if not isinstance(m, dict):
            continue
        if m.get("role") != "user":
            continue
        # Live history carries the composed bytes in the api_content sidecar; the wire
        # copy has them substituted into content. Check both, then stop: only the last
        # user message can hold THIS turn's injection.
        for raw in (m.get("api_content"), _text_of(m.get("content"))):
            if isinstance(raw, str) and raw:
                hit = _BLOCK_RE.search(raw)
                if hit:
                    return _clean(hit.group(1))
        return ""
    return ""


def pre_api_hook(session_id: str = "", turn_id: str = "", request_messages: Any = None,
                 conversation_history: Any = None, **_: Any) -> None:
    try:
        ad = _LIVE
        loop = getattr(ad, "_mobile_loop", None) if ad else None
        if loop is None or loop.is_closed() or not session_id:
            return
        key = (session_id, turn_id or "")
        if key in _SEEN:
            return
        text = extract(request_messages) or extract(conversation_history)
        if not text:
            return  # not marked seen: a later attempt of this turn cannot gain a block either
        now = time.time()
        if len(_SEEN) > 512:
            for k in [k for k, ts in _SEEN.items() if now - ts > 3600]:
                _SEEN.pop(k, None)
        _SEEN[key] = now
        from . import usage as U
        cid = U._main_chat(session_id) or U._ctx_chat()
        if cid:
            loop.call_soon_threadsafe(ad._on_recall, cid, text)
    except Exception:
        logger.debug("[mobile] recall hook failed", exc_info=True)


def bind(adapter) -> None:
    global _LIVE
    _LIVE = adapter


def unbind(adapter) -> None:
    global _LIVE
    if _LIVE is adapter:
        _LIVE = None
