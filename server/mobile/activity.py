"""Tool activity for the phone: one collapsible "activity" card per run of tool calls.

The agent thread fires ``pre_tool_call`` / ``post_tool_call`` with the gateway session's ContextVars bound, so
the hooks know which mobile chat a call belongs to. They condense each call (label, command/args, capped and
redacted output, diff) off the loop, then hand it to the adapter's event loop, where :class:`Tracker` owns every
store write and SSE emit. A card stays open while it is the newest message in its chat; commentary, approvals,
user messages and the final reply close it, so the chat reads commentary -> card -> commentary -> card.
"""
from __future__ import annotations

import json
import logging
import re
import sqlite3
import time
import uuid
from typing import Any, Dict, List, Optional, Tuple

logger = logging.getLogger(__name__)

_LIVE = None  # the connected MobileAdapter
_SESS: Dict[str, str] = {}  # session_id -> mobile chat_id
_SNAPS: Dict[str, Any] = {}  # call id -> pre-write file snapshot (diff for tools that don't report one)
_SNAP_MAX_BYTES = 2_000_000

OUT_HEAD, OUT_TAIL = 7000, 2500
DETAIL_CAP = 8000
DIFF_CAP = 60000

# tool -> (verb, singular, plural) for the card headline; edits are counted per distinct file instead.
_VERBS: Dict[str, Tuple[str, str, str]] = {
    "terminal": ("ran", "command", "commands"), "execute_code": ("ran", "script", "scripts"),
    "read_file": ("read", "file", "files"), "web_extract": ("read", "page", "pages"),
    "skill_view": ("read", "skill", "skills"), "search_files": ("searched", "path", "paths"),
    "web_search": ("searched", "query", "queries"), "session_search": ("searched", "session", "sessions"),
    "browser_navigate": ("browsed", "page", "pages"), "skill_manage": ("updated", "skill", "skills"),
    "memory": ("updated", "memory", "memories"), "todo_list": ("updated", "task list", "task lists"),
    "delegate_task": ("delegated", "task", "tasks"),
}
_EDIT_TOOLS = frozenset({"write_file", "patch"})
_VERB_ORDER = ("edited", "ran", "read", "searched", "browsed", "updated", "delegated")


# ---------------------------------------------------------------- condensing (hook threads)
def _cap(s: str, head: int, tail: int = 0) -> str:
    if len(s) <= head + tail + 200:
        return s
    end = s[-tail:] if tail else ""
    return s[:head] + f"\n\n… {len(s) - head - tail:,} chars omitted …\n\n" + end


def _redact(s: str) -> str:
    try:
        from agent.redact import redact_sensitive_text
        return redact_sensitive_text(s, force=True)
    except Exception:
        return s


def _label(tool: str, args: Any) -> str:
    s = None
    try:
        from agent.display import build_tool_label
        s = build_tool_label(tool, args if isinstance(args, dict) else {}, max_len=140)
    except Exception:
        pass
    s = (s or "").strip().splitlines()[0] if (s or "").strip() else tool
    return _redact(s)[:200]


def _detail(tool: str, args: Any) -> str:
    a = dict(args) if isinstance(args, dict) else {}
    if tool == "terminal" and isinstance(a.get("command"), str):
        return a["command"]
    if tool == "execute_code" and isinstance(a.get("code"), str):
        return a["code"]
    if tool == "write_file" and isinstance(a.get("content"), str):
        return a["content"]
    for k, v in list(a.items()):
        if isinstance(v, str) and len(v) > 1200:
            a[k] = v[:1200] + f"… ({len(v):,} chars)"
    return json.dumps(a, ensure_ascii=False, indent=2, default=str) if a else ""


def _diff_counts(diff: str) -> Tuple[int, int]:
    body = [ln for ln in diff.splitlines() if not ln.startswith(("+++", "---"))]
    return sum(ln.startswith("+") for ln in body), sum(ln.startswith("-") for ln in body)


def _snapshot(tool: str, args: Any):
    if tool not in _EDIT_TOOLS and tool != "skill_manage":
        return None
    try:
        from agent.display import _resolve_local_edit_paths, capture_local_edit_snapshot
        if any(p.exists() and p.stat().st_size > _SNAP_MAX_BYTES for p in _resolve_local_edit_paths(tool, args)):
            return None
        return capture_local_edit_snapshot(tool, args)
    except Exception:
        return None


def _late_diff(tool: str, args: Any, result: Any, snap) -> Optional[str]:
    if snap is None:
        return None
    try:
        from agent.display import extract_edit_diff
        return extract_edit_diff(tool, result if isinstance(result, str) else json.dumps(result, default=str),
                                 function_args=args if isinstance(args, dict) else None, snapshot=snap)
    except Exception:
        return None


def _status(status: Optional[str], data: Any) -> str:
    if status == "cancelled":
        return "cancelled"
    if status not in (None, "", "ok"):
        return "error"
    if status is None and isinstance(data, dict) and data.get("error"):
        return "error"
    return "ok"


def _result_fields(tool: str, args: Any, result: Any, diff_hint: Optional[str] = None) -> Dict[str, Any]:
    raw = result if isinstance(result, str) else json.dumps(result, ensure_ascii=False, default=str)
    raw = raw or ""
    data = None
    if raw.lstrip().startswith("{"):
        try:
            data = json.loads(raw, strict=False)
        except Exception:
            data = None
    out, diff = raw, None
    if isinstance(data, dict):
        d = data.get("diff")
        diff = d if isinstance(d, str) and d.strip() else None
    diff = diff or diff_hint
    if isinstance(data, dict):
        if isinstance(data.get("output"), str):
            out = data["output"]
            ec = data.get("exit_code")
            if ec not in (0, None):
                out += f"\n[exit {ec}]"
        elif isinstance(data.get("content"), str):
            out = data["content"]
        elif diff:
            out = ""
        else:
            out = json.dumps(data, ensure_ascii=False, indent=2, default=str)
    f: Dict[str, Any] = {"output": _cap(_redact(out), OUT_HEAD, OUT_TAIL), "_data": data}
    a = args if isinstance(args, dict) else {}
    if tool in _EDIT_TOOLS:
        path = a.get("path")
        if not path and isinstance(data, dict):
            fm = data.get("files_modified") or data.get("files_created")
            path = fm[0] if isinstance(fm, list) and fm else None
        f["path"] = str(path or "")
        if diff:
            f["diff"] = _cap(_redact(diff), DIFF_CAP)
            f["added"], f["removed"] = _diff_counts(diff)
        elif tool == "write_file" and isinstance(a.get("content"), str):
            f["added"], f["removed"] = a["content"].count("\n") + (0 if a["content"].endswith("\n") else 1), 0
    return f


def _mobile_chat() -> Optional[str]:
    try:
        from gateway.session_context import get_session_env
    except Exception:
        return None
    if get_session_env("HERMES_SESSION_PLATFORM") != "mobile":
        return None
    return get_session_env("HERMES_SESSION_CHAT_ID") or None


def _hand_off(fn, rec: Dict[str, Any]) -> None:
    ad = _LIVE
    loop = getattr(ad, "_mobile_loop", None) if ad else None
    if loop is not None and not loop.is_closed():
        loop.call_soon_threadsafe(fn, rec)


def pre_hook(tool_name: str = "", args: Any = None, tool_call_id: str = "", session_id: str = "", **_: Any):
    try:
        if _LIVE is None or not tool_call_id or not tool_name or tool_name.startswith("_"):
            return None
        cid = _mobile_chat()
        if not cid:
            return None
        if session_id:
            _SESS[session_id] = cid
        snap = _snapshot(tool_name, args)
        if snap is not None:
            if len(_SNAPS) > 64:
                _SNAPS.clear()
            _SNAPS[tool_call_id] = snap
        rec = {"id": tool_call_id, "chat_id": cid, "tool": tool_name, "label": _label(tool_name, args),
               "detail": _cap(_redact(_detail(tool_name, args)), DETAIL_CAP), "ts": time.time()}
        _hand_off(_LIVE._activity.on_pre, rec)
    except Exception:
        logger.debug("[mobile] activity pre hook failed", exc_info=True)
    return None


def post_hook(tool_name: str = "", args: Any = None, result: Any = None, tool_call_id: str = "", session_id: str = "",
              duration_ms: int = 0, status: Optional[str] = None, error_message: Optional[str] = None, **_: Any):
    try:
        if _LIVE is None or not tool_name or tool_name.startswith("_"):
            return None
        cid = _mobile_chat()
        if not cid:
            return None
        late = _late_diff(tool_name, args, result, _SNAPS.pop(tool_call_id, None))
        fields = _result_fields(tool_name, args, result, late)
        data = fields.pop("_data", None)
        st = _status(status, data)
        err = error_message or (data.get("error") if st == "error" and isinstance(data, dict) else None)
        rec = {"id": tool_call_id or "x-" + uuid.uuid4().hex[:12], "chat_id": cid, "tool": tool_name,
               "label": _label(tool_name, args), "detail": _cap(_redact(_detail(tool_name, args)), DETAIL_CAP),
               "status": st, "error": _redact(str(err))[:2000] if err else "",
               "ms": int(duration_ms or 0), "ended": time.time(), **fields}
        _hand_off(_LIVE._activity.on_post, rec)
    except Exception:
        logger.debug("[mobile] activity post hook failed", exc_info=True)
    return None


def interim_hook(text: str = "", session_id: str = "", surface: str = "", **_: Any) -> None:
    """Commentary is about to post: the next tool call starts a new card below it."""
    try:
        if _LIVE is None or surface != "mobile" or not text or not session_id:
            return
        cid = _SESS.get(session_id)
        if cid is None:
            from hermes_constants import get_hermes_home
            with sqlite3.connect(f"file:{get_hermes_home() / 'state.db'}?mode=ro", uri=True, timeout=5) as c:
                row = c.execute("SELECT chat_id FROM sessions WHERE id=? AND source='mobile'", (session_id,)).fetchone()
            cid = row[0] if row and row[0] else None
            if not cid:
                return
            _SESS[session_id] = cid
        _hand_off(_LIVE._activity.on_commentary, {"chat_id": cid, "text": text})
    except Exception:
        logger.debug("[mobile] activity interim hook failed", exc_info=True)


def bind(adapter) -> None:
    global _LIVE
    _LIVE = adapter


def unbind(adapter) -> None:
    global _LIVE
    if _LIVE is adapter:
        _LIVE = None


# ---------------------------------------------------------------- summary
def _n(k: int, one: str, many: str) -> str:
    return f"{k} {one if k == 1 else many}"


def summarize(calls: List[Dict[str, Any]]) -> Dict[str, Any]:
    files: Dict[str, List[int]] = {}
    nouns: Dict[str, Dict[Tuple[str, str], int]] = {}
    other = errors = 0
    running = ""
    for c in calls:
        if c["status"] == "running":
            running = c["label"] or c["tool"]
        elif c["status"] == "error":
            errors += 1
        if c["tool"] in _EDIT_TOOLS:
            if c["status"] != "error" and c.get("path"):
                fa = files.setdefault(c["path"], [0, 0])
                fa[0] += c.get("added") or 0
                fa[1] += c.get("removed") or 0
            continue
        g = _VERBS.get(c["tool"])
        if g is None:
            other += 1
            continue
        bucket = nouns.setdefault(g[0], {})
        bucket[(g[1], g[2])] = bucket.get((g[1], g[2]), 0) + 1
    segs = [f"edited {_n(len(files), 'file', 'files')}"] if files else []
    for verb in sorted(nouns, key=lambda v: _VERB_ORDER.index(v) if v in _VERB_ORDER else 99):
        segs.append(f"{verb} " + ", ".join(_n(k, s, p) for (s, p), k in nouns[verb].items()))
    if other:
        segs.append(f"used {_n(other, 'tool', 'tools')}")
    if len(segs) > 3:
        segs = segs[:3] + [f"+{len(segs) - 3} more"]
    head = " · ".join(segs) or "Tool activity"
    started = min((c["ts"] for c in calls if c.get("ts")), default=time.time())
    ended = max((c.get("ended") or 0 for c in calls), default=0) if not running else 0
    return {"summary": head[:1].upper() + head[1:], "count": len(calls), "files": len(files),
            "added": sum(v[0] for v in files.values()), "removed": sum(v[1] for v in files.values()),
            "errors": errors, "running": running, "started": started, "ended": ended or None,
            "tools": list(dict.fromkeys(c["tool"] for c in calls))[:8]}


def _key(text: str) -> str:
    return re.sub(r"\W+", "", text or "").lower()[:48]


# ---------------------------------------------------------------- loop side
class Tracker:
    """Runs on the adapter's event loop only (hooks hand off via call_soon_threadsafe)."""

    def __init__(self, adapter):
        self.ad = adapter
        self.closed: set = set()                             # cards that commentary has closed
        self.call_card: Dict[str, str] = {}                  # call id -> card
        self.awaiting: Dict[str, Tuple[str, float]] = {}     # chat -> (commentary key, since)
        self.slide: Dict[str, Tuple[str, str, float]] = {}   # chat -> (card, commentary key, since)

    def _card_for(self, cid: str, ts: float) -> Optional[str]:
        st = self.ad.store
        last = st.last_message(cid)
        if last and last["kind"] == "activity" and last["id"] not in self.closed:
            return last["id"]
        if not st.get_chat(cid):
            return None
        msg = st.add_message(cid, "agent", "Tool activity", kind="activity",
                             meta={"summary": "Tool activity", "count": 0, "started": ts})
        if len(self.closed) > 500:
            self.closed.clear()
        aw = self.awaiting.pop(cid, None)
        if aw and time.time() - aw[1] < 30:
            self.slide[cid] = (msg["id"], aw[0], time.time())
        return msg["id"]

    def _refresh(self, mid: str) -> None:
        st = self.ad.store
        meta = summarize(st.calls(mid, light=True))
        msg = st.edit_message(mid, content=meta["summary"], meta=meta)
        if msg:
            self.ad._emit({"type": "message", "seq": msg["seq"], "message": msg, "chat": st.get_chat(msg["chat_id"])})

    def on_pre(self, rec: Dict[str, Any]) -> None:
        try:
            mid = self._card_for(rec["chat_id"], rec["ts"])
            if not mid:
                return
            self.call_card[rec["id"]] = mid
            self.ad.store.upsert_call({**rec, "msg_id": mid, "status": "running"})
            self._refresh(mid)
        except Exception:
            logger.exception("[mobile] activity pre failed")

    def on_post(self, rec: Dict[str, Any]) -> None:
        try:
            mid = self.call_card.pop(rec["id"], None) or self.ad.store.call_card(rec["id"])
            if not mid:
                mid = self._card_for(rec["chat_id"], rec["ended"])
                if not mid:
                    return
                rec = {**rec, "ts": rec["ended"] - (rec.get("ms") or 0) / 1000}
            self.ad.store.upsert_call({**rec, "msg_id": mid})
            self._refresh(mid)
        except Exception:
            logger.exception("[mobile] activity post failed")

    def on_commentary(self, rec: Dict[str, Any]) -> None:
        cid = rec["chat_id"]
        last = self.ad.store.last_message(cid)
        if last and last["kind"] == "activity":
            self.closed.add(last["id"])
        self.awaiting[cid] = (_key(rec["text"]), time.time())

    def on_message(self, msg: Dict[str, Any]) -> None:
        """An agent message landed. If it is the commentary that preceded the newest card but lost the race
        to it, slide the card just below the commentary so the chat keeps its real order."""
        cid = msg["chat_id"]
        k = _key(msg.get("content") or "")
        aw = self.awaiting.get(cid)
        if aw and k and (aw[0].startswith(k) or k.startswith(aw[0])):
            self.awaiting.pop(cid, None)
        sl = self.slide.get(cid)
        if not sl or time.time() - sl[2] > 60:
            self.slide.pop(cid, None)
            return
        if not k or not (sl[1].startswith(k) or k.startswith(sl[1])):
            return
        self.slide.pop(cid, None)
        st = self.ad.store
        card = st.edit_message(sl[0], ts=msg["ts"] + 0.001)
        if card:
            self.ad._emit({"type": "message", "seq": card["seq"], "message": card, "chat": st.get_chat(cid)})

    def detail(self, mid: str) -> Optional[Dict[str, Any]]:
        st = self.ad.store
        msg = st.get_message(mid)
        if not msg or msg["kind"] != "activity":
            return None
        calls = st.calls(mid)
        files: Dict[str, Dict[str, Any]] = {}
        for c in calls:
            if c["tool"] in _EDIT_TOOLS and c.get("path") and c["status"] != "error":
                f = files.setdefault(c["path"], {"path": c["path"], "added": 0, "removed": 0, "diff": ""})
                f["added"] += c.get("added") or 0
                f["removed"] += c.get("removed") or 0
                if c.get("diff"):
                    f["diff"] += c["diff"] if c["diff"].endswith("\n") else c["diff"] + "\n"
        return {"message": msg, "calls": calls, "files": list(files.values())}
