"""Second control batch: memory edits, cron create/edit/delete, cross-session search, usage history.

Writes go through Hermes' own owners: ``MemoryStore`` (locking, limits, injection scan),
``tools.cronjob_tools.cronjob`` (the same entry point ``hermes cron create/edit/remove`` uses),
the gateway's ``SessionDB.search_messages`` (FTS5 + trigram + CJK fallbacks).
"""
from __future__ import annotations

import asyncio
import json
import logging
import sqlite3
import time
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional

from . import ident as ID
from . import store as S

logger = logging.getLogger(__name__)

DELIVER_CHOICES = ["mobile", "telegram,mobile", "telegram", "local"]


# ---------------------------------------------------------------- memory
def memory_edit(body: Dict[str, Any]) -> Dict[str, Any]:
    from tools.memory_tool import load_on_disk_store
    target = str(body.get("target") or "")
    action = str(body.get("action") or "")
    if target not in ("memory", "user") or action not in ("add", "replace", "remove"):
        return {"success": False, "error": "target must be memory|user and action add|replace|remove"}
    content = str(body.get("content") or "")
    old = str(body.get("old_text") or "")
    store = load_on_disk_store()
    if action == "add":
        res = store.add(target, content)
    elif action == "replace":
        res = store.replace(target, old, content)
    else:
        res = store.remove(target, old)
    keep = ("success", "error", "message", "usage")
    return {k: res.get(k) for k in keep if k in res}


# ---------------------------------------------------------------- cron
_CRON_FIELDS = ("name", "schedule", "prompt", "deliver", "model", "provider", "reasoning_effort", "repeat", "skills")


def _cron(**kw) -> Dict[str, Any]:
    from tools.cronjob_tools import cronjob
    return json.loads(cronjob(**kw))


def cron_get(jid: str) -> Optional[Dict[str, Any]]:
    from cron.jobs import get_job
    j = get_job(jid)
    if not j:
        return None
    keys = ("id", "name", "prompt", "schedule", "schedule_display", "deliver", "failure_deliver", "model", "provider",
            "reasoning_effort", "repeat", "skills", "skill", "enabled", "no_agent", "script", "workdir", "next_run_at",
            "last_run_at", "last_status", "last_error", "paused_reason")
    v = {k: j.get(k) for k in keys}
    if isinstance(v["schedule"], dict):
        v["schedule_expr"] = v["schedule"].get("expr") or v["schedule"].get("display") or ""
        v["schedule"] = v["schedule"].get("display") or v["schedule"].get("expr") or ""
    if isinstance(v["repeat"], dict):
        v["repeat"] = v["repeat"].get("times")
    if not v.get("skills") and v.get("skill"):
        v["skills"] = [v["skill"]]
    if isinstance(v.get("deliver"), list):
        v["deliver"] = ",".join(v["deliver"])
    return v


def _cron_kwargs(body: Dict[str, Any], create: bool) -> Dict[str, Any]:
    kw: Dict[str, Any] = {}
    for k in _CRON_FIELDS:
        if k not in body:
            continue
        v = body[k]
        if k == "skills":
            v = [s for s in (v or []) if isinstance(s, str) and s.strip()]
        elif k == "repeat":
            v = int(v) if v not in (None, "", 0) else (0 if not create else None)
        elif isinstance(v, str):
            v = v.strip()
            if create and not v:
                v = None
        kw[k] = v
    return kw


def cron_create(body: Dict[str, Any]) -> Dict[str, Any]:
    kw = _cron_kwargs(body, create=True)
    if not kw.get("schedule"):
        return {"success": False, "error": "A schedule is required (e.g. 'every 2h', '0 9 * * *', '30m')."}
    if not kw.get("prompt") and not kw.get("skills"):
        return {"success": False, "error": "Give the job a prompt or at least one skill."}
    kw.setdefault("deliver", "mobile")
    if body.get("paused"):
        kw["paused"] = True
    return _cron(action="create", **kw)


def cron_update(jid: str, body: Dict[str, Any]) -> Dict[str, Any]:
    return _cron(action="update", job_id=jid, **_cron_kwargs(body, create=False))


def cron_delivery_summary() -> Dict[str, Any]:
    from cron.jobs import list_jobs
    counts: Dict[str, int] = {}
    for j in list_jobs(True):
        d = j.get("deliver")
        d = ",".join(d) if isinstance(d, list) else (d or "local")
        counts[d] = counts.get(d, 0) + 1
    return {"counts": counts, "total": sum(counts.values())}


def cron_delivery_move(target: str, only_from: str = "") -> Dict[str, Any]:
    """Point every job's delivery at ``target`` through the same cronjob tool the CLI uses (one update per job)."""
    if target not in DELIVER_CHOICES:
        return {"success": False, "error": f"deliver must be one of {DELIVER_CHOICES}"}
    from cron.jobs import list_jobs
    moved, failed = [], []
    for j in list_jobs(True):
        d = j.get("deliver")
        d = ",".join(d) if isinstance(d, list) else (d or "local")
        if d == target or (only_from and d != only_from) or d == "local":
            continue  # local-only jobs stay local: they were never meant to notify
        r = _cron(action="update", job_id=j["id"], deliver=target)
        (moved if r.get("success", True) and not r.get("error") else failed).append(j.get("name") or j["id"])
    return {"success": not failed, "moved": moved, "failed": failed, **cron_delivery_summary()}


def cron_remove(jid: str) -> Dict[str, Any]:
    return _cron(action="remove", job_id=jid)


# ---------------------------------------------------------------- search
def _ro(path) -> sqlite3.Connection:
    c = sqlite3.connect(f"file:{path}?mode=ro", uri=True, timeout=5)
    c.row_factory = sqlite3.Row
    return c


async def search(runner, q: str, limit: int = 40) -> Dict[str, Any]:
    import inspect
    db = getattr(runner, "_session_db", None) if runner is not None else None
    if db is None:
        return {"results": [], "error": "session database not available"}
    rows = db.search_messages(q, exclude_sources=["subagent"], role_filter=["user", "assistant"], limit=limit,
                              fields=("id", "session_id", "role", "snippet", "timestamp", "source", "model", "session_started"))
    if inspect.isawaitable(rows):  # the gateway holds the async SessionDB facade
        rows = await rows
    return await asyncio.to_thread(_decorate, rows, q)


def _decorate(rows: List[Dict[str, Any]], q: str) -> Dict[str, Any]:
    ids = sorted({r["session_id"] for r in rows if r.get("session_id")})
    meta: Dict[str, Dict[str, Any]] = {}
    if ids:
        with _ro(S.hermes_home() / "state.db") as c:
            for r in c.execute(f"SELECT id, title, chat_id, source FROM sessions WHERE id IN ({','.join('?' * len(ids))})", ids):
                meta[r["id"]] = dict(r)
    out = []
    for r in rows:
        m = meta.get(r.get("session_id"), {})
        out.append({**r, "title": m.get("title") or "", "chat_id": m.get("chat_id") if (m.get("source") or r.get("source")) == "mobile" else None})
    return {"results": out, "query": q}


# ---------------------------------------------------------------- usage
_MAX_USD_PER_TOKEN = 0.002  # $2,000 per 1M tokens; no real model is close, anything above is a unit error
_SUBSCRIPTION = ("-oauth", "openai-codex", "copilot", "claude-code")


def _is_subscription(provider: str) -> bool:
    p = (provider or "").lower()
    return any(p.endswith(x) or p == x for x in _SUBSCRIPTION)


def _reprice(model: str, provider: str, base_url: str, toks: Dict[str, int]) -> Optional[float]:
    """Same chain as the per-chat meter: Hermes' table, then pricing.json. None = genuinely unknown/subscription."""
    from . import usage as U
    return U.price_session(model, provider, base_url, toks)["cost"]


def usage(days: int = 30) -> Dict[str, Any]:
    days = max(1, min(int(days or 30), 120))
    day0 = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)
    start = (day0 - timedelta(days=days - 1)).timestamp()
    with _ro(S.hermes_home() / "state.db") as c:
        rows = c.execute(
            "SELECT started_at, model, billing_provider, billing_base_url, source, input_tokens, output_tokens, cache_read_tokens,"
            " cache_write_tokens, reasoning_tokens, api_call_count, cost_status, COALESCE(actual_cost_usd, estimated_cost_usd) AS cost"
            " FROM sessions WHERE started_at>=?", (start,)).fetchall()
    # Unpriced rows are re-priced per (day, model, provider, base_url) aggregate; pricing is linear per token.
    groups: Dict[tuple, Dict[str, Any]] = {}
    priced_rows: List[Dict[str, Any]] = []
    bucket = ("input_tokens", "output_tokens", "cache_read_tokens", "cache_write_tokens", "reasoning_tokens")
    for r in rows:
        d = dict(r)
        d["day"] = datetime.fromtimestamp(d["started_at"]).strftime("%Y-%m-%d")
        d["tokens"] = sum(int(d[k] or 0) for k in bucket[:4])
        if d["cost"] is not None and (d["cost_status"] or "") != "unknown":
            d["cost"] = float(d["cost"]); d["how"] = "hermes"
            priced_rows.append(d)
        else:
            g = groups.setdefault((d["day"], d["model"] or "", d["billing_provider"] or "", d["billing_base_url"] or ""), {"rows": [], **{k: 0 for k in bucket}})
            g["rows"].append(d)
            for k in bucket:
                g[k] += int(d[k] or 0)
    for (day, model, prov, base), g in groups.items():
        cost = _reprice(model, prov, base, g) if model and any(g[k] for k in bucket) else None
        total = sum(x["tokens"] for x in g["rows"]) or 1
        for x in g["rows"]:
            x["cost"] = None if cost is None else cost * x["tokens"] / total
            x["how"] = "fallback" if cost is not None else "unknown"
            priced_rows.append(x)
    suspect = {"sessions": 0, "cost": 0.0}
    by_day: Dict[str, Dict[str, Any]] = {}
    by_model: Dict[str, Dict[str, Any]] = {}
    by_source: Dict[str, Dict[str, Any]] = {}
    unpriced = included = 0
    for d in priced_rows:
        cost = d["cost"]
        if cost is not None and d["tokens"] and cost / d["tokens"] > _MAX_USD_PER_TOKEN:
            suspect["sessions"] += 1; suspect["cost"] += cost
            continue
        if cost is None:
            if _is_subscription(d["billing_provider"]):
                included += 1; d["how"] = "included"
            elif (d["api_call_count"] or 0) > 0:
                unpriced += 1
            cost = 0.0
        day = by_day.setdefault(d["day"], {"cost": 0.0, "tokens": 0, "sessions": 0})
        day["cost"] += cost; day["tokens"] += d["tokens"]; day["sessions"] += 1
        mk = d["model"] or "?"
        m = by_model.setdefault(mk, {"model": mk, "provider": d["billing_provider"] or "", "cost": 0.0, "tokens": 0, "sessions": 0,
                                     "calls": 0, "output": 0, "cache_read": 0, "prompt": 0, "unpriced": 0})
        m["cost"] += cost; m["tokens"] += d["tokens"]; m["sessions"] += 1; m["calls"] += int(d["api_call_count"] or 0)
        m["output"] += int(d["output_tokens"] or 0); m["cache_read"] += int(d["cache_read_tokens"] or 0)
        m["prompt"] += int(d["input_tokens"] or 0) + int(d["cache_read_tokens"] or 0) + int(d["cache_write_tokens"] or 0)
        if d["cost"] is None and d.get("how") != "included":
            m["unpriced"] += 1
        m["included"] = _is_subscription(d["billing_provider"])
        sk = d["source"] or "?"
        s = by_source.setdefault(sk, {"source": sk, "cost": 0.0, "sessions": 0})
        s["cost"] += cost; s["sessions"] += 1
    series = []
    for i in range(days):
        dk = (day0 - timedelta(days=days - 1 - i)).strftime("%Y-%m-%d")
        v = by_day.get(dk, {"cost": 0.0, "tokens": 0, "sessions": 0})
        series.append({"day": dk, "cost": round(v["cost"], 4), "tokens": v["tokens"], "sessions": v["sessions"]})
    models = sorted(by_model.values(), key=lambda m: (-m["cost"], -m["tokens"]))
    for m in models:
        m["cost"] = round(m["cost"], 4)
        m["cache_hit"] = round(m["cache_read"] / m["prompt"], 4) if m["prompt"] else None
    sources = sorted(by_source.values(), key=lambda s: (-s["cost"], -s["sessions"]))
    for s in sources:
        s["cost"] = round(s["cost"], 4)
    return {"days": days, "total": round(sum(x["cost"] for x in series), 4), "sessions": len(rows), "unpriced_sessions": unpriced, "included_sessions": included,
            "suspect": {"sessions": suspect["sessions"], "cost": round(suspect["cost"], 2)},
            "series": series, "models": models[:20], "sources": sources}


# ---------------------------------------------------------------- routes
def routes(r, ctl, j) -> None:
    async def h_memory_edit(request):
        res = await asyncio.to_thread(memory_edit, await request.json())
        return j(res, 200 if res.get("success") else 400)

    async def h_cron_get(request):
        v = await asyncio.to_thread(cron_get, request.match_info["jid"])
        return j(v) if v else j({"error": "unknown job"}, 404)

    async def h_cron_create(request):
        res = await asyncio.to_thread(cron_create, await request.json())
        return j(res, 200 if res.get("success") else 400)

    async def h_cron_update(request):
        res = await asyncio.to_thread(cron_update, request.match_info["jid"], await request.json())
        return j(res, 200 if res.get("success") else 400)

    async def h_cron_delete(request):
        res = await asyncio.to_thread(cron_remove, request.match_info["jid"])
        return j(res, 200 if res.get("success") else 400)

    async def h_search(request):
        q = (request.query.get("q") or "").strip()
        if len(q) < 2:
            return j({"results": [], "query": q})
        try:
            return j(await asyncio.wait_for(search(ctl.runner, q, int(request.query.get("limit", 40))), 20))
        except Exception as e:
            logger.warning("[mobile] search failed: %s", e)
            return j({"results": [], "error": str(e)}, 500)

    async def h_usage(request):
        return j(await asyncio.to_thread(usage, int(request.query.get("days", 30) or 30)))

    SESSION_CMDS = {"new": "/new", "undo": "/undo", "compress": "/compress", "title": "/title", "save": "/save md",
                    "status": "/status", "context": "/context"}

    async def h_session(request):
        """Run a session command (real gateway handler) for one chat. Results that change the conversation are also
        posted into the chat as a status line, so the timeline shows where a session was reset, undone or compressed.
        /retry is not here: it re-runs a turn, so the app sends it as a normal message."""
        cid = request.match_info["cid"]
        body = await request.json()
        action = str(body.get("action") or "")
        if action not in SESSION_CMDS:
            return j({"error": "bad action"}, 400)
        arg = str(body.get("arg") or "").strip().replace("\n", " ")[:120]
        ad = ctl.ad
        chat = ad.store.get_chat(cid)
        if not chat:
            return j({"error": "unknown chat"}, 404)
        if action in ("new", "undo", "compress") and cid in ad._working:
            return j({"error": f"{ID.agent_name()} is working in this chat. Stop the run first."}, 409)
        try:
            reply = await asyncio.wait_for(ctl.command(cid, SESSION_CMDS[action] + (f" {arg}" if arg else "")), 300)
            if action in ("new", "undo") and body.get("confirmed"):
                # The app already showed its own confirm dialog; resolve the gateway's destructive-command prompt
                # the way Hermes' button pickers do ("once" = this time only, the opt-out setting is untouched).
                from tools import slash_confirm as SC
                sk = ctl.runner._session_key_for_source(ctl.source(cid))
                pend = SC.get_pending(sk)
                if pend and pend.get("command") == action:
                    reply = await asyncio.wait_for(SC.resolve(sk, pend["confirm_id"], "once"), 300) or reply
        except Exception as e:
            logger.exception("[mobile] session command failed")
            return j({"error": str(e)}, 500)
        out: Dict[str, Any] = {"ok": True, "reply": reply}
        if action == "undo" and reply.startswith("↩"):
            msgs = ad.store.messages(cid, limit=400)
            idx = next((i for i in range(len(msgs) - 1, -1, -1) if msgs[i]["role"] == "user" and msgs[i]["kind"] == "text"
                        and not msgs[i]["content"].startswith("/")), None)
            if idx is not None:
                out["text"] = msgs[idx]["content"]
                for m in msgs[idx:]:
                    if m["kind"] != "deleted" and not m["meta"].get("undone"):
                        e = ad.store.edit_message(m["id"], meta={"undone": True})
                        ad._emit({"type": "message", "seq": e["seq"], "message": e})
        if action == "title" and arg and not reply.startswith(("⚠", "❌")):
            ch = ad.store.update_chat(cid, title=arg[:80])
            if ch:
                ad._emit({"type": "chat", "seq": ch["seq"], "chat": ch})
        if action in ("new", "undo", "compress") and reply and not reply.lstrip().startswith("⚠️ **Confirm"):
            note = ad.store.add_message(cid, "agent", reply.splitlines()[0][:300], kind="status", meta={"session_event": action, "quiet": True})
            ad._emit_msg(note, push=None)
        out["ok"] = not reply.lstrip().startswith(("❌", "⚠"))
        return j(out)

    r.add_post("/v1/chats/{cid}/session", h_session)

    r.add_post("/v1/memory", h_memory_edit)
    r.add_get("/v1/cronjob/{jid}", h_cron_get)
    r.add_post("/v1/cronjob", h_cron_create)
    r.add_put("/v1/cronjob/{jid}", h_cron_update)
    r.add_delete("/v1/cronjob/{jid}", h_cron_delete)
    async def h_cron_delivery(request):
        if request.method == "GET":
            return j(await asyncio.to_thread(cron_delivery_summary))
        b = await request.json()
        res = await asyncio.to_thread(cron_delivery_move, str(b.get("deliver") or ""), str(b.get("only_from") or ""))
        return j(res, 200 if res.get("success") else 400)

    r.add_get("/v1/cron-delivery", h_cron_delivery)
    r.add_post("/v1/cron-delivery", h_cron_delivery)
    r.add_get("/v1/search", h_search)
    r.add_get("/v1/usage", h_usage)
