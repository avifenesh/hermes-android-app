"""Per-turn model usage for the phone: model, tokens in/out, cache hit, tok/s, cost, context fill.

``post_api_request`` fires once per model call on the agent thread with the normalized usage buckets.
The hook only resolves which mobile chat the call belongs to and hands the raw numbers to the adapter loop;
:class:`Meter` prices the call off-loop, folds it into the chat's current turn, persists it and emits a
``usage`` SSE event. The final reply of a turn carries a frozen copy in ``meta.usage``.

Subagent calls (another session id, same chat via ContextVars) count toward spend and tokens but not toward
context fill or tok/s, which describe the main conversation only.
"""
from __future__ import annotations

import asyncio
import json
import logging
import re
import sqlite3
import time
from pathlib import Path
from typing import Any, Dict, Optional

logger = logging.getLogger(__name__)

_LIVE = None
_SPLIT: Dict[str, Dict[str, int]] = {}  # api_request_id -> {"1h": n, "5m": n} cache-write split
# Flat-fee routes: tokens are covered by a subscription, not billed per token.
SUBSCRIPTION_PROVIDERS = frozenset({"xai-oauth", "openai-codex", "copilot", "zai", "alibaba-token-plan",
                                    "alibaba-token-plan-cn", "dashscope-token-plan", "dashscope-token-plan-cn"})
# Bedrock regional (geo) inference profiles bill 10% over the global profile for Claude 4.5+.
_REGIONAL_PREFIX = re.compile(r"^(us|eu|apac|jp|au)\.anthropic\.")
_MAIN: Dict[str, Optional[str]] = {}   # session_id -> mobile chat_id (None = not a mobile main session)
_CTX: Dict[str, int] = {}              # "provider|model|base_url" -> context length
TURN_GAP = 900                         # a reply more than this after the last call doesn't get usage attached


def _main_chat(session_id: str) -> Optional[str]:
    if not session_id:
        return None
    if session_id in _MAIN:
        return _MAIN[session_id]
    try:
        from hermes_constants import get_hermes_home
        with sqlite3.connect(f"file:{get_hermes_home() / 'state.db'}?mode=ro", uri=True, timeout=5) as c:
            row = c.execute("SELECT chat_id FROM sessions WHERE id=? AND source='mobile'", (session_id,)).fetchone()
    except Exception:
        return None
    cid = row[0] if row and row[0] else None
    if row is not None:
        _MAIN[session_id] = cid
    return cid


def _ctx_chat() -> Optional[str]:
    try:
        from gateway.session_context import get_session_env
        if get_session_env("HERMES_SESSION_PLATFORM") == "mobile":
            return get_session_env("HERMES_SESSION_CHAT_ID") or None
    except Exception:
        pass
    return None


def _cache_split(usage: Any) -> Optional[Dict[str, int]]:
    cc = getattr(usage, "cache_creation", None)
    if cc is None and isinstance(usage, dict):
        cc = usage.get("cache_creation")
    if cc is None:
        return None
    get = (lambda k: cc.get(k)) if isinstance(cc, dict) else (lambda k: getattr(cc, k, None))
    h1, m5 = get("ephemeral_1h_input_tokens"), get("ephemeral_5m_input_tokens")
    if h1 is None and m5 is None:
        return None
    return {"1h": int(h1 or 0), "5m": int(m5 or 0)}


def llm_middleware(request: Any = None, next_call=None, api_request_id: str = "", **_: Any) -> Any:
    """Pass-through: read the 1h/5m cache-write split off the provider response for the usage hook."""
    response = next_call(request)
    try:
        if api_request_id:
            split = _cache_split(getattr(response, "usage", None))
            if split:
                if len(_SPLIT) > 256:
                    _SPLIT.clear()
                _SPLIT[api_request_id] = split
    except Exception:
        pass
    return response


def post_api_hook(session_id: str = "", turn_id: str = "", platform: str = "", model: str = "", provider: str = "",
                  base_url: str = "", api_duration: float = 0.0, started_at: float = 0.0, first_chunk_at: Any = None,
                  usage: Optional[Dict[str, Any]] = None, response_model: Any = None, api_request_id: str = "",
                  **_: Any) -> None:
    try:
        ad = _LIVE
        loop = getattr(ad, "_mobile_loop", None) if ad else None
        if loop is None or loop.is_closed() or not isinstance(usage, dict):
            return
        main = _main_chat(session_id)
        cid = main or _ctx_chat()
        if not cid:
            return
        gen = None
        if isinstance(first_chunk_at, (int, float)) and first_chunk_at and started_at:
            gen = max(0.0, (started_at + (api_duration or 0)) - first_chunk_at)
        elif api_duration:
            gen = float(api_duration)  # non-streaming: whole-call time, so tok/s is a lower bound
        rec = {"chat_id": cid, "main": bool(main), "session_id": session_id, "turn_id": turn_id or "",
               "model": str(response_model or model or ""), "req_model": model or "", "provider": provider or "",
               "base_url": base_url or "", "duration": float(api_duration or 0), "gen": gen, "at": time.time(),
               "split": _SPLIT.pop(api_request_id, None) if api_request_id else None,
               **{k: int(usage.get(k) or 0) for k in ("input_tokens", "output_tokens", "cache_read_tokens",
                                                       "cache_write_tokens", "reasoning_tokens")}}
        loop.call_soon_threadsafe(ad._meter.on_call, rec)
    except Exception:
        logger.debug("[mobile] usage hook failed", exc_info=True)


def bind(adapter) -> None:
    global _LIVE
    _LIVE = adapter


def unbind(adapter) -> None:
    global _LIVE
    if _LIVE is adapter:
        _LIVE = None


_FALLBACK: Dict[str, Any] = {"mtime": None, "table": {}}


def _fallback_table() -> Dict[str, Any]:
    """pricing.json beside this module, re-read when it changes (price updates need no restart)."""
    p = Path(__file__).with_name("pricing.json")
    try:
        mt = p.stat().st_mtime
        if mt != _FALLBACK["mtime"]:
            _FALLBACK.update(mtime=mt, table=json.loads(p.read_text()))
    except Exception:
        pass
    return _FALLBACK["table"]


def _norm_model(m: str) -> str:
    """'global.anthropic.claude-sonnet-5-5-v1:0', 'anthropic/claude-opus-4.5', 'claude-opus-5-5-20261001' -> 'sonnet-5-5' / 'opus-4-5' / 'opus-5-5'."""
    m = (m or "").lower().rsplit("/", 1)[-1]
    m = re.sub(r"^(global|us|eu|apac|jp|au)\.", "", m).removeprefix("anthropic.").removeprefix("claude-")
    m = re.sub(r"(-v\d+(:\d+)?)$", "", m)
    return re.sub(r"-\d{8}$", "", m).replace(".", "-")


def _fallback_price(rec: Dict[str, Any]) -> Optional[float]:
    """Provider-agnostic: Bedrock, Vertex and the first-party API bill Claude at the same list rates."""
    key = _norm_model(rec.get("req_model") or rec.get("model") or "")
    table = _fallback_table()
    rates = next((t[key] for t in (table.get("anthropic"), *[v for k, v in table.items() if isinstance(v, dict) and k != "anthropic"])
                  if isinstance(t, dict) and isinstance(t.get(key), dict)), None)
    if rates is None:
        return None
    cw = rec["cache_write_tokens"]
    split = rec.get("split") or {}
    h1 = min(cw, int(split.get("1h") or 0))
    w1h = float(rates.get("cache_write_1h", float(rates.get("input", 0)) * 2))
    total = (rec["input_tokens"] * float(rates.get("input", 0)) + rec["output_tokens"] * float(rates.get("output", 0))
             + rec["cache_read_tokens"] * float(rates.get("cache_read", 0))
             + (cw - h1) * float(rates.get("cache_write", 0)) + h1 * w1h) / 1e6
    return total


def _regional(rec: Dict[str, Any]) -> float:
    return 1.10 if (rec.get("provider") or "") == "bedrock" and _REGIONAL_PREFIX.match((rec.get("req_model") or "").lower()) else 1.0


def _price(rec: Dict[str, Any]) -> Dict[str, Any]:
    if (rec.get("provider") or "").lower() in SUBSCRIPTION_PROVIDERS:
        return {"cost": 0.0, "cost_status": "included"}
    r = _base_price(rec)
    if r["cost"]:
        r["cost"] *= _regional(rec)
    return r


def _base_price(rec: Dict[str, Any]) -> Dict[str, Any]:
    if (rec.get("split") or {}).get("1h"):
        fb = _fallback_price(rec)  # Hermes' table has one cache-write rate; ours prices the 1h share at 2x input
        if fb is not None:
            return {"cost": fb, "cost_status": "estimated"}
    try:
        from agent.usage_pricing import CanonicalUsage, estimate_usage_cost
        u = CanonicalUsage(input_tokens=rec["input_tokens"], output_tokens=rec["output_tokens"],
                           cache_read_tokens=rec["cache_read_tokens"], cache_write_tokens=rec["cache_write_tokens"],
                           reasoning_tokens=rec["reasoning_tokens"])
        r = estimate_usage_cost(rec["req_model"] or rec["model"], u, provider=rec["provider"] or None,
                                base_url=rec["base_url"] or None)
        if r.amount_usd is not None:
            return {"cost": float(r.amount_usd), "cost_status": r.status}
        if r.status == "included":
            return {"cost": 0.0, "cost_status": "included"}
    except Exception:
        pass
    fb = _fallback_price(rec)
    return {"cost": fb, "cost_status": "estimated"} if fb is not None else {"cost": None, "cost_status": "unknown"}


def _context_len(rec: Dict[str, Any]) -> Optional[int]:
    key = f"{rec['provider']}|{rec['req_model']}|{rec['base_url']}"
    if key not in _CTX:
        try:
            from agent.model_metadata import get_model_context_length
            n = get_model_context_length(rec["req_model"] or rec["model"], base_url=rec["base_url"], provider=rec["provider"])
            _CTX[key] = int(n) if n else 0
        except Exception:
            _CTX[key] = 0
    return _CTX[key] or None


def price_session(model: str, provider: str, base_url: str, s: Dict[str, Any]) -> Dict[str, Any]:
    return _price({"req_model": model, "model": model, "provider": provider, "base_url": base_url,
                   **{k: int(s.get(k) or 0) for k in ("input_tokens", "output_tokens", "cache_read_tokens",
                                                       "cache_write_tokens", "reasoning_tokens")}})


def _blank(cid: str, turn_id: str, at: float) -> Dict[str, Any]:
    return {"chat_id": cid, "turn_id": turn_id, "started": at, "updated": at, "calls": 0, "model": "",
            "input_tokens": 0, "output_tokens": 0, "cache_read_tokens": 0, "cache_write_tokens": 0,
            "reasoning_tokens": 0, "cost": 0.0, "cost_status": "", "gen_s": 0.0, "gen_tokens": 0,
            "context_used": 0, "context_max": None, "sub_calls": 0}


class Meter:
    """Loop-side owner of per-chat turn usage."""

    def __init__(self, adapter):
        self.ad = adapter
        self.turns: Dict[str, Dict[str, Any]] = {}   # chat -> current turn usage
        self.pending: Dict[str, set] = {}             # chat -> in-flight pricing tasks

    def on_call(self, rec: Dict[str, Any]) -> None:
        task = asyncio.get_running_loop().create_task(self._apply(rec))
        bag = self.pending.setdefault(rec["chat_id"], set())
        bag.add(task)
        task.add_done_callback(bag.discard)

    async def _apply(self, rec: Dict[str, Any]) -> None:
        try:
            priced = await asyncio.to_thread(_price, rec)
            ctx = await asyncio.wait_for(asyncio.to_thread(_context_len, rec), 20) if rec["main"] else None
        except Exception:
            priced, ctx = {"cost": None, "cost_status": "unknown"}, None
        cid = rec["chat_id"]
        t = self.turns.get(cid)
        if t is None or (rec["main"] and rec["turn_id"] and t["turn_id"] and rec["turn_id"] != t["turn_id"]):
            t = self.turns[cid] = _blank(cid, rec["turn_id"] if rec["main"] else "", rec["at"] - rec["duration"])
        if rec["main"] and not t["turn_id"]:
            t["turn_id"] = rec["turn_id"]
        t["calls"] += 1
        t["updated"] = rec["at"]
        for k in ("input_tokens", "output_tokens", "cache_read_tokens", "cache_write_tokens", "reasoning_tokens"):
            t[k] += rec[k]
        if priced["cost"] is None:
            t["cost_status"] = "unknown"
        else:
            t["cost"] += priced["cost"]
            if t["cost_status"] != "unknown":
                t["cost_status"] = priced["cost_status"]
        if rec["main"]:
            t["model"] = rec["model"] or t["model"]
            if rec["gen"] and rec["output_tokens"]:
                t["gen_s"] += rec["gen"]
                t["gen_tokens"] += rec["output_tokens"]
            t["context_used"] = rec["input_tokens"] + rec["cache_read_tokens"] + rec["cache_write_tokens"] + rec["output_tokens"]
            t["context_max"] = ctx or t["context_max"]
        else:
            t["sub_calls"] += 1
        view = summary(t)
        try:
            self.ad.store.put_turn(cid, view)
        except Exception:
            logger.debug("[mobile] turn persist failed", exc_info=True)
        self.ad._emit({"type": "usage", "chat_id": cid, "usage": view})

    async def for_reply(self, cid: str, after: float) -> Optional[Dict[str, Any]]:
        """Usage to freeze onto a final reply: only calls made after the user message it answers."""
        bag = list(self.pending.get(cid) or ())
        if bag:
            await asyncio.wait(bag, timeout=3)
        t = self.turns.get(cid)
        if not t or not t["calls"] or t["updated"] < after or time.time() - t["updated"] > TURN_GAP:
            return None
        return summary(t)


def summary(t: Dict[str, Any]) -> Dict[str, Any]:
    prompt = t["input_tokens"] + t["cache_read_tokens"] + t["cache_write_tokens"]
    v = {k: t[k] for k in ("model", "calls", "input_tokens", "output_tokens", "cache_read_tokens", "cache_write_tokens",
                           "reasoning_tokens", "context_used", "context_max", "sub_calls", "started", "updated")}
    v["prompt_tokens"] = prompt
    v["cache_hit"] = round(t["cache_read_tokens"] / prompt, 4) if prompt else None
    v["tok_s"] = round(t["gen_tokens"] / t["gen_s"], 1) if t["gen_s"] > 0.05 else None
    v["cost"] = round(t["cost"], 6) if t["cost_status"] != "unknown" else None
    v["cost_status"] = t["cost_status"] or "unknown"
    return v
