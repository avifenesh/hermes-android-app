"""Radar: a recurring scout (cron + the ``radar`` skill) that surfaces the 5 most useful things to point out,
and the phone page that shows them and collects feedback.

This file is three things, all on one SQLite store (``~/.hermes/radar/radar.db``, override ``RADAR_HOME``):

- the store (findings, batches, feedback, recurring signals, curator notes, cursors),
- the mobile API routes (mounted by ``adapter.py`` with one ``RD.routes(...)`` line),
- a CLI the scout and curator call: ``python3 ~/.hermes/plugins/mobile/radar.py <cmd>``.

It must import with no package context (the CLI runs it as a plain script), so nothing here imports from the
plugin package at module level.
"""
from __future__ import annotations

import argparse
import asyncio
import contextlib
import hashlib
import json
import os
import re
import sqlite3
import sys
import time
import uuid
from pathlib import Path
from typing import Any, Dict, List, Optional

TYPES = {
    "code": "Code improvement",
    "tool-errors": "Repeated tool errors",
    "stale-git": "Stale worktrees/branches",
    "research": "New research",
    "tiyuvta": "tiyuvta direction",
    "career": "Business/career",
    "mention": "Web mention",
    "infra": "Machine/infra",
    "other": "Other",
}
ACTIONS = ("like", "dislike", "clear-vote", "feedback", "note", "dismiss", "save", "unsave", "restore")
MAX_PER_TYPE = 2
BATCH_SIZE = 5
DEDUP_DAYS = 14
TEST_DEVICE = "radar-check"


def home() -> Path:
    d = Path(os.environ.get("RADAR_HOME") or Path.home() / ".hermes" / "radar")
    d.mkdir(parents=True, exist_ok=True)
    return d


SCHEMA = """
CREATE TABLE IF NOT EXISTS batches (id TEXT PRIMARY KEY, ts REAL NOT NULL, model TEXT, info TEXT);
CREATE TABLE IF NOT EXISTS findings (
  id TEXT PRIMARY KEY, batch_id TEXT, ts REAL NOT NULL, type TEXT NOT NULL, key TEXT NOT NULL,
  title TEXT NOT NULL, why TEXT NOT NULL, evidence TEXT NOT NULL, confidence REAL, actionability TEXT,
  freshness TEXT NOT NULL, prompt TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'new', vote INTEGER NOT NULL DEFAULT 0,
  spawned INTEGER NOT NULL DEFAULT 0, updated REAL NOT NULL);
CREATE INDEX IF NOT EXISTS findings_key ON findings(key);
CREATE INDEX IF NOT EXISTS findings_batch ON findings(batch_id);
CREATE TABLE IF NOT EXISTS feedback (
  id INTEGER PRIMARY KEY AUTOINCREMENT, finding_id TEXT NOT NULL, ts REAL NOT NULL, kind TEXT NOT NULL,
  text TEXT, meta TEXT);
CREATE TABLE IF NOT EXISTS signals (
  sig TEXT PRIMARY KEY, source TEXT, tool TEXT, sample TEXT, refs TEXT, count INTEGER NOT NULL DEFAULT 0,
  first_seen REAL, last_seen REAL);
CREATE TABLE IF NOT EXISTS notes (
  id TEXT PRIMARY KEY, ts REAL NOT NULL, kind TEXT, title TEXT, body TEXT, diff TEXT, acked INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT);
"""


def connect() -> sqlite3.Connection:
    c = sqlite3.connect(home() / "radar.db", timeout=15)
    c.row_factory = sqlite3.Row
    c.execute("PRAGMA journal_mode=WAL")
    c.executescript(SCHEMA)
    return c


@contextlib.contextmanager
def db():
    """Commit on success, roll back on error, and always close: the gateway is long-lived, so a bare
    ``with sqlite3.connect()`` (which never closes) would leak a file descriptor per request."""
    c = connect()
    try:
        with c:
            yield c
    finally:
        c.close()


def kv_get(c, k: str, default: Any = None) -> Any:
    r = c.execute("SELECT v FROM kv WHERE k=?", (k,)).fetchone()
    return json.loads(r["v"]) if r else default


def kv_set(c, k: str, v: Any) -> None:
    c.execute("INSERT INTO kv(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v", (k, json.dumps(v)))


def _norm_key(k: str) -> str:
    return re.sub(r"\s+", " ", str(k or "").strip().lower())[:300]


def _row(r: sqlite3.Row, fb: Optional[List[Dict[str, Any]]] = None) -> Dict[str, Any]:
    d = dict(r)
    for f in ("evidence", "freshness"):
        try:
            d[f] = json.loads(d[f])
        except Exception:
            pass
    d["type_label"] = TYPES.get(d["type"], d["type"])
    if fb is not None:
        d["feedback"] = fb
    return d


def _feedback_for(c, ids: List[str]) -> Dict[str, List[Dict[str, Any]]]:
    out: Dict[str, List[Dict[str, Any]]] = {i: [] for i in ids}
    if not ids:
        return out
    q = ",".join("?" * len(ids))
    for r in c.execute(f"SELECT * FROM feedback WHERE finding_id IN ({q}) AND kind IN ('feedback','note','spawn_chat',"
                       f"'spawn_task') ORDER BY ts", ids):
        d = dict(r)
        d["meta"] = json.loads(d["meta"]) if d.get("meta") else None
        out[d["finding_id"]].append(d)
    return out


# ------------------------------------------------------------------ store ops
def listing(view: str = "current", limit: int = 60) -> Dict[str, Any]:
    with db() as c:
        last = c.execute("SELECT * FROM batches ORDER BY ts DESC LIMIT 1").fetchone()
        if view == "saved":
            rows = c.execute("SELECT * FROM findings WHERE state='saved' ORDER BY updated DESC LIMIT ?", (limit,)).fetchall()
        elif view == "history":
            rows = c.execute("SELECT * FROM findings ORDER BY ts DESC LIMIT ?", (limit,)).fetchall()
        else:
            rows = c.execute("SELECT * FROM findings WHERE batch_id=? ORDER BY confidence DESC", (last["id"],)).fetchall() if last else []
        fb = _feedback_for(c, [r["id"] for r in rows])
        notes = [dict(n) for n in c.execute("SELECT id,ts,kind,title,body,acked FROM notes ORDER BY ts DESC LIMIT 10")]
        counts = {r["state"]: r["n"] for r in c.execute("SELECT state, COUNT(*) n FROM findings GROUP BY state")}
        nxt = kv_get(c, "next_run")
    batch = None
    if last:
        batch = dict(last)
        batch["info"] = json.loads(batch["info"] or "{}")
    return {"view": view, "batch": batch, "findings": [_row(r, fb[r["id"]]) for r in rows], "notes": notes,
            "counts": counts, "types": TYPES, "next_run": nxt}


def get(fid: str) -> Optional[Dict[str, Any]]:
    with db() as c:
        r = c.execute("SELECT * FROM findings WHERE id=?", (fid,)).fetchone()
        if not r:
            return None
        fb = [dict(x) for x in c.execute("SELECT * FROM feedback WHERE finding_id=? ORDER BY ts", (fid,))]
    return _row(r, fb)


def act(fid: str, action: str, text: str = "", meta: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    if action not in ACTIONS and action not in ("spawn_chat", "spawn_task"):
        raise ValueError("bad action")
    text = (text or "").strip()[:4000]
    if action in ("feedback", "note") and not text:
        raise ValueError("text required")
    now = time.time()
    with db() as c:
        r = c.execute("SELECT * FROM findings WHERE id=?", (fid,)).fetchone()
        if not r:
            raise KeyError(fid)
        sets: Dict[str, Any] = {}
        if action == "like":
            sets["vote"] = 1
        elif action == "dislike":
            sets["vote"] = -1
        elif action == "clear-vote":
            sets["vote"] = 0
        elif action == "dismiss":
            sets["state"] = "dismissed"
        elif action == "save":
            sets["state"] = "saved"
        elif action in ("unsave", "restore"):
            sets["state"] = "new"
        elif action in ("spawn_chat", "spawn_task"):
            sets["spawned"] = int(r["spawned"]) + 1
        sets["updated"] = now
        c.execute(f"UPDATE findings SET {', '.join(k + '=?' for k in sets)} WHERE id=?", (*sets.values(), fid))
        c.execute("INSERT INTO feedback(finding_id, ts, kind, text, meta) VALUES(?,?,?,?,?)",
                  (fid, now, action, text or None, json.dumps(meta) if meta else None))
    return get(fid)  # type: ignore[return-value]


def validate_batch(items: List[Dict[str, Any]], c: sqlite3.Connection) -> List[str]:
    errs: List[str] = []
    if not isinstance(items, list) or not items:
        return ["findings must be a non-empty list"]
    if len(items) > BATCH_SIZE:
        errs.append(f"at most {BATCH_SIZE} findings, got {len(items)}")
    per: Dict[str, int] = {}
    seen = set()
    cutoff = time.time() - DEDUP_DAYS * 86400
    for i, f in enumerate(items):
        tag = f"finding[{i}]"
        for k in ("type", "key", "title", "why", "evidence", "freshness", "prompt"):
            if not f.get(k):
                errs.append(f"{tag}: missing {k}")
        t = f.get("type")
        if t and t not in TYPES and not re.fullmatch(r"[a-z][a-z0-9-]{1,24}", str(t)):
            errs.append(f"{tag}: bad type {t!r}")
        per[t] = per.get(t, 0) + 1
        if not isinstance(f.get("evidence"), list) or not f.get("evidence"):
            errs.append(f"{tag}: evidence must be a non-empty list")
        fr = f.get("freshness")
        if not isinstance(fr, dict) or not fr.get("checked") or not fr.get("result"):
            errs.append(f"{tag}: freshness must be an object with 'checked' (what you re-verified, commands run) and "
                        f"'result' (fresh|downgraded)")
        elif str(fr.get("result")).lower() not in ("fresh", "downgraded"):
            errs.append(f"{tag}: freshness.result must be fresh or downgraded (drop stale items instead)")
        try:
            conf = float(f.get("confidence", 0))
            if not 0 <= conf <= 1:
                raise ValueError
        except Exception:
            errs.append(f"{tag}: confidence must be 0..1")
        k = _norm_key(f.get("key", ""))
        if k in seen:
            errs.append(f"{tag}: duplicate key in batch")
        seen.add(k)
        prev = c.execute("SELECT id, ts, state, vote FROM findings WHERE key=? ORDER BY ts DESC LIMIT 1", (k,)).fetchone()
        if prev and not f.get("resurface_reason"):
            if prev["state"] == "dismissed" or prev["vote"] < 0:
                errs.append(f"{tag}: key {k!r} was dismissed/disliked before ({prev['id']}); drop it, or set "
                            f"resurface_reason if something materially changed")
            elif prev["ts"] > cutoff:
                errs.append(f"{tag}: key {k!r} already surfaced {int((time.time() - prev['ts']) / 3600)}h ago "
                            f"({prev['id']}); drop it, or set resurface_reason if something materially changed")
    for t, n in per.items():
        if n > MAX_PER_TYPE:
            errs.append(f"type {t!r} appears {n} times; max {MAX_PER_TYPE} per batch")
    return errs


def commit_batch(payload: Dict[str, Any], dry: bool = False) -> Dict[str, Any]:
    items = payload.get("findings") or []
    with db() as c:
        errs = validate_batch(items, c)
        if errs:
            return {"ok": False, "errors": errs}
        if dry:
            return {"ok": True, "dry_run": True, "count": len(items)}
        now = time.time()
        bid = "b-" + time.strftime("%Y%m%d-%H%M", time.localtime(now)) + "-" + uuid.uuid4().hex[:4]
        c.execute("INSERT INTO batches(id, ts, model, info) VALUES(?,?,?,?)",
                  (bid, now, payload.get("model"), json.dumps(payload.get("run") or {})))
        ids = []
        for f in items:
            fid = "f-" + uuid.uuid4().hex[:10]
            ids.append(fid)
            c.execute("INSERT INTO findings(id,batch_id,ts,type,key,title,why,evidence,confidence,actionability,freshness,"
                      "prompt,updated) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                      (fid, bid, now, f["type"], _norm_key(f["key"]), str(f["title"])[:200], str(f["why"])[:1200],
                       json.dumps(f["evidence"]), float(f.get("confidence", 0.5)), str(f.get("actionability") or "")[:40],
                       json.dumps({**f["freshness"], **({"resurface_reason": f["resurface_reason"]}
                                                       if f.get("resurface_reason") else {})}),
                       str(f["prompt"])[:6000], now))
        pend = home() / "cursors.pending.json"
        if pend.exists():
            kv_set(c, "cursors", json.loads(pend.read_text()))
            pend.unlink()
        kv_set(c, "last_commit", now)
    return {"ok": True, "batch": bid, "ids": ids}


def add_note(kind: str, title: str, body: str, diff: str = "") -> str:
    nid = "n-" + uuid.uuid4().hex[:10]
    with db() as c:
        c.execute("INSERT INTO notes(id, ts, kind, title, body, diff) VALUES(?,?,?,?,?,?)",
                  (nid, time.time(), kind, title[:200], body[:8000], diff[:60000]))
    return nid


def feedback_digest(since: Optional[float] = None) -> Dict[str, Any]:
    """Everything the curator needs: per-type signal scores and every finding with feedback since ``since``."""
    with db() as c:
        since = since if since is not None else float(kv_get(c, "curator_ts", 0) or 0)
        rows = c.execute("SELECT * FROM findings ORDER BY ts").fetchall()
        fbs: Dict[str, List[Dict[str, Any]]] = {}
        for r in c.execute("SELECT * FROM feedback ORDER BY ts"):
            fbs.setdefault(r["finding_id"], []).append(dict(r))
    per: Dict[str, Dict[str, float]] = {}
    recent = []
    for r in rows:
        # Feedback from the verification device (server/verify_radar.py) is audit only, never a learning signal.
        fb = [x for x in fbs.get(r["id"], []) if TEST_DEVICE not in (x.get("meta") or "")]
        r = dict(r)
        r["spawned"] = sum(1 for x in fb if x["kind"] in ("spawn_chat", "spawn_task"))
        s = score(r, fb)
        p = per.setdefault(r["type"], {"n": 0, "score": 0.0, "liked": 0, "disliked": 0, "dismissed": 0,
                                         "saved": 0, "spawned": 0})
        p["n"] += 1
        p["score"] += s
        p["liked"] += r["vote"] > 0
        p["disliked"] += r["vote"] < 0
        p["dismissed"] += r["state"] == "dismissed"
        p["saved"] += r["state"] == "saved"
        p["spawned"] += r["spawned"] > 0
        if any(x["ts"] > since for x in fb):
            recent.append({"id": r["id"], "type": r["type"], "title": r["title"], "why": r["why"], "vote": r["vote"],
                           "state": r["state"], "spawned": r["spawned"], "signal": round(s, 2),
                           "texts": [{"kind": x["kind"], "text": x["text"]} for x in fb if x.get("text")]})
    for p in per.values():
        p["mean"] = round(p["score"] / max(p["n"], 1), 3)
        # Laplace-smoothed weight in [0.5, 1.5]: unseen types stay neutral at 1.0.
        p["weight"] = round(max(0.5, min(1.5, 1.0 + p["score"] / (p["n"] + 2))), 2)
        p["score"] = round(p["score"], 2)
    return {"since": since, "types": per, "recent": recent, "total_findings": len(rows)}


def score(f: Dict[str, Any], fb: List[Dict[str, Any]]) -> float:
    """Spawned + liked = strongest positive; dismissed without a note = mild negative; dislike + note = explicit
    correction (strong negative). Saves are positive; a free-text reaction adds weight to the vote's direction."""
    has_text = any(x["kind"] in ("feedback", "note") for x in fb)
    s = 0.0
    if f["vote"] > 0:
        s += 1.0
    if f["vote"] < 0:
        s -= 1.5 if has_text else 1.0
    if f["spawned"]:
        s += 1.0 + (1.0 if f["vote"] > 0 else 0)
    if f["state"] == "saved":
        s += 0.5
    if f["state"] == "dismissed":
        s -= 0.25 if not has_text else 0.5
    return s


# ------------------------------------------------------------------ HTTP
async def _body(request) -> Dict[str, Any]:
    if not request.body_exists:
        return {}
    try:
        b = await request.json()
        return b if isinstance(b, dict) else {}
    except Exception:
        return {}


def spawn_prompt(f: Dict[str, Any], prompt: str) -> str:
    ev = "\n".join(f"- {e}" if isinstance(e, str) else f"- {json.dumps(e)}" for e in (f.get("evidence") or []))
    return (f"{prompt.strip()}\n\n---\nFrom Radar finding {f['id']} ({f['type_label']}): {f['title']}\n"
            f"Why: {f['why']}\nEvidence:\n{ev}")


def create_kanban_task(f: Dict[str, Any], prompt: str) -> str:
    from hermes_cli import kanban_db as kb
    from hermes_cli.kanban_db_connect import connect_closing
    alt = os.environ.get("RADAR_KANBAN_DB")  # test seam: verify_radar.py points this at a throwaway board
    with connect_closing(Path(alt) if alt else None) as conn:
        return kb.create_task(conn, title=f"Radar: {f['title']}"[:120], body=spawn_prompt(f, prompt), assignee="default",
                              created_by="radar", idempotency_key=f"radar:{f['id']}:{hashlib.sha1(prompt.encode()).hexdigest()[:8]}")


def _dev(request) -> Dict[str, Any]:
    d = request.get("device") or {}
    return {"device": d.get("name") or d.get("id") or "?"}


def routes(r, adapter, j) -> None:
    async def h_list(request):
        view = request.query.get("view", "current")
        if view not in ("current", "saved", "history"):
            return j({"error": "bad view"}, 400)
        return j(await asyncio.to_thread(listing, view, int(request.query.get("limit", 60))))

    async def h_get(request):
        f = await asyncio.to_thread(get, request.match_info["fid"])
        return j({"finding": f}) if f else j({"error": "not found"}, 404)

    async def h_act(request):
        body = await _body(request)
        try:
            f = await asyncio.to_thread(act, request.match_info["fid"], request.match_info["action"],
                                        str(body.get("text") or ""), _dev(request))
        except KeyError:
            return j({"error": "not found"}, 404)
        except ValueError as e:
            return j({"error": str(e)}, 400)
        return j({"finding": f})

    async def h_spawn(request):
        fid = request.match_info["fid"]
        f = await asyncio.to_thread(get, fid)
        if not f:
            return j({"error": "not found"}, 404)
        body = await _body(request)
        mode = body.get("mode")
        prompt = str(body.get("prompt") or f["prompt"]).strip()
        if mode == "chat":
            from . import store as S
            chat = adapter.store.ensure_chat(S.new_id("c"), title=f"Radar: {f['title']}"[:80])
            adapter._emit({"type": "chat", "seq": chat["seq"], "chat": chat})
            await adapter.post_from_app(chat["id"], spawn_prompt(f, prompt))
            await asyncio.to_thread(act, fid, "spawn_chat", "", {"chat_id": chat["id"], "prompt": prompt, **_dev(request)})
            return j({"ok": True, "chat_id": chat["id"]})
        if mode == "task":
            try:
                tid = await asyncio.to_thread(create_kanban_task, f, prompt)
            except Exception as e:
                return j({"error": f"kanban: {e}"[:400]}, 500)
            await asyncio.to_thread(act, fid, "spawn_task", "", {"task_id": tid, "prompt": prompt, **_dev(request)})
            return j({"ok": True, "task_id": tid})
        return j({"error": "mode must be chat or task"}, 400)

    async def h_ack(request):
        with db() as c:
            n = c.execute("UPDATE notes SET acked=1 WHERE id=?", (request.match_info["nid"],)).rowcount
        return j({"ok": bool(n)}, 200 if n else 404)

    async def h_note(request):
        with db() as c:
            n = c.execute("SELECT * FROM notes WHERE id=?", (request.match_info["nid"],)).fetchone()
        return j({"note": dict(n)}) if n else j({"error": "not found"}, 404)

    r.add_get("/v1/radar", h_list)
    r.add_get("/v1/radar/findings/{fid}", h_get)
    r.add_post("/v1/radar/findings/{fid}/spawn", h_spawn)
    r.add_post("/v1/radar/findings/{fid}/{action}", h_act)
    r.add_get("/v1/radar/notes/{nid}", h_note)
    r.add_post("/v1/radar/notes/{nid}/ack", h_ack)


# ------------------------------------------------------------------ CLI
def _main(argv: List[str]) -> int:
    ap = argparse.ArgumentParser(prog="radar")
    sub = ap.add_subparsers(dest="cmd", required=True)
    cm = sub.add_parser("commit", help="validate + store a batch (JSON on stdin or --file)")
    cm.add_argument("--file")
    cm.add_argument("--dry-run", action="store_true")
    sub.add_parser("list").add_argument("--view", default="current")
    sub.add_parser("history", help="recent finding keys/titles/states for dedup").add_argument("--days", type=int, default=30)
    fd = sub.add_parser("feedback-digest")
    fd.add_argument("--since", type=float)
    nt = sub.add_parser("note")
    nt.add_argument("--kind", default="curator")
    nt.add_argument("--title", required=True)
    nt.add_argument("--body", required=True)
    nt.add_argument("--diff-file")
    a = sub.add_parser("act")
    a.add_argument("fid")
    a.add_argument("action")
    a.add_argument("--text", default="")
    a = ap.parse_args(argv)
    if a.cmd == "commit":
        raw = Path(a.file).read_text() if a.file else sys.stdin.read()
        try:
            payload = json.loads(raw)
        except Exception as e:
            print(json.dumps({"ok": False, "errors": [f"bad JSON: {e}"]}))
            return 2
        if isinstance(payload, list):
            payload = {"findings": payload}
        res = commit_batch(payload, dry=a.dry_run)
        print(json.dumps(res, indent=1))
        return 0 if res["ok"] else 1
    if a.cmd == "list":
        print(json.dumps(listing(a.view), indent=1, default=str))
    elif a.cmd == "history":
        with db() as c:
            rows = c.execute("SELECT f.id, f.ts, f.type, f.key, f.title, f.state, f.vote, f.spawned FROM findings f "
                             "WHERE ts > ? ORDER BY ts DESC", (time.time() - a.days * 86400,)).fetchall()
        for r in rows:
            print(f"{time.strftime('%m-%d %H:%M', time.localtime(r['ts']))} {r['type']:<11} state={r['state']:<9} "
                  f"vote={r['vote']:+d} spawned={r['spawned']} key={r['key']} | {r['title']}")
    elif a.cmd == "feedback-digest":
        print(json.dumps(feedback_digest(a.since), indent=1))
    elif a.cmd == "note":
        print(add_note(a.kind, a.title, a.body, Path(a.diff_file).read_text() if a.diff_file else ""))
    elif a.cmd == "act":
        print(json.dumps(act(a.fid, a.action, a.text), indent=1, default=str))
    return 0


if __name__ == "__main__":
    sys.exit(_main(sys.argv[1:]))
