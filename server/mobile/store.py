"""SQLite store shared by the gateway adapter, the standalone cron sender and the pairing CLI."""
from __future__ import annotations

import hashlib
import json
import os
import re
import secrets
import sqlite3
import threading
import time
import uuid
from pathlib import Path
from typing import Any, Dict, List, Optional

_SCHEMA = """
CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT);
CREATE TABLE IF NOT EXISTS chats (
  id TEXT PRIMARY KEY, title TEXT, kind TEXT DEFAULT 'chat', created REAL, updated REAL,
  unread INTEGER DEFAULT 0, pinned INTEGER DEFAULT 0, archived INTEGER DEFAULT 0, seq INTEGER DEFAULT 0);
CREATE TABLE IF NOT EXISTS messages (
  id TEXT PRIMARY KEY, chat_id TEXT, role TEXT, kind TEXT, content TEXT, meta TEXT, ts REAL, seq INTEGER);
CREATE INDEX IF NOT EXISTS ix_msg_chat ON messages(chat_id, ts);
CREATE INDEX IF NOT EXISTS ix_msg_seq ON messages(seq);
CREATE TABLE IF NOT EXISTS approvals (
  id TEXT PRIMARY KEY, chat_id TEXT, session_key TEXT, command TEXT, description TEXT, actions TEXT,
  state TEXT, choice TEXT, ts REAL, seq INTEGER);
CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY, name TEXT, token_hash TEXT UNIQUE, created REAL, last_seen REAL,
  fcm_token TEXT, revoked INTEGER DEFAULT 0, pub TEXT, apub TEXT);
CREATE TABLE IF NOT EXISTS pair_codes (code_hash TEXT PRIMARY KEY, expires REAL, used INTEGER DEFAULT 0);
CREATE TABLE IF NOT EXISTS signature_replays (identity TEXT PRIMARY KEY, expires REAL NOT NULL);
CREATE INDEX IF NOT EXISTS ix_signature_replays_expires ON signature_replays(expires);
CREATE TABLE IF NOT EXISTS calls (
  id TEXT PRIMARY KEY, msg_id TEXT, chat_id TEXT, tool TEXT, label TEXT, detail TEXT, status TEXT,
  error TEXT, output TEXT, path TEXT, diff TEXT, added INTEGER, removed INTEGER, ms INTEGER, ts REAL, ended REAL);
CREATE INDEX IF NOT EXISTS ix_calls_msg ON calls(msg_id, ts);
"""

TRACE_MARK = "\u2063"
_TRACE_RE = re.compile(r'^(\S+)\s+([a-z_][a-z0-9_]*)(?::\s*"?(.*?)"?)?(?:\.\.\.)?(\s*\(\u00d7\d+\))?$')


def hermes_home() -> Path:
    try:
        from hermes_constants import get_hermes_home
        return Path(get_hermes_home())
    except Exception:
        return Path(os.environ.get("HERMES_HOME") or Path.home() / ".hermes")


def data_dir() -> Path:
    d = hermes_home() / "mobile"
    (d / "media").mkdir(parents=True, exist_ok=True)
    return d


def sha(s: str) -> str:
    return hashlib.sha256(s.encode()).hexdigest()


def new_id(prefix: str) -> str:
    return f"{prefix}-{uuid.uuid4().hex[:16]}"


# Gateway housekeeping lines (tool progress, restart, recall, steer ack). They are not answers:
# the app shows them as thin system notices, never as reply bubbles.
_NOTICE_RE = re.compile(
    r"^\s*(?:\u23f3|\u267b\ufe0f?|\U0001f9e0|\U0001f4bb|\U0001f40d|\U0001f4da|\U0001f4d6|\U0001f441\ufe0f?|"
    r"\u270d\ufe0f?|\U0001f500|\U0001f310|\u23e9|\u26a1|\U0001f9f9|\U0001f5dc\ufe0f?|\U0001f504|\U0001f4be|\U0001f4ec|\u2705|\u274c)\s"
)
_NOTICE_LINE_RE = re.compile(r"^\s*(?:\U0001f4bb|\U0001f40d|\U0001f4da|\U0001f4d6|\U0001f441\ufe0f?|\u270d\ufe0f?|\U0001f500|\U0001f310)\s")


def is_notice(content: str) -> bool:
    """True for gateway progress/housekeeping text. A multi-line message counts only if every line is one."""
    lines = [ln for ln in (content or "").splitlines() if ln.strip()]
    if not lines or len(content or "") > 2000:
        return False
    if len(lines) == 1:
        return bool(_NOTICE_RE.match(lines[0]))
    # Progress blocks: every line is a tool line. A wrapped tool argument (the 👁️ prompt text) may run on
    # for a line or two; accept such lines only when the block starts with a tool line and most lines are.
    hits = sum(1 for ln in lines if _NOTICE_LINE_RE.match(ln))
    return bool(_NOTICE_LINE_RE.match(lines[0])) and hits >= max(2, (len(lines) + 1) // 2)


def parse_trace(content: str) -> Optional[List[Dict[str, str]]]:
    """Tool-progress bubbles -> steps. Marked lines (our format_tool_event) or the stock 'emoji tool: "x"' shape."""
    lines = [ln.strip() for ln in (content or "").splitlines() if ln.strip()]
    if not lines:
        return None
    steps = []
    for ln in lines:
        if ln.startswith(TRACE_MARK):
            parts = ln.split(TRACE_MARK)
            name = parts[1] if len(parts) > 1 else "tool"
            body = parts[2] if len(parts) > 2 else ""
            m = _TRACE_RE.match(body)
            steps.append({"tool": name, "what": (m.group(3) if m and m.group(3) else body).strip()})
            continue
        m = _TRACE_RE.match(ln)
        if not m or len(m.group(1)) > 4:
            return None
        steps.append({"tool": m.group(2), "what": (m.group(3) or "").strip()})
    return steps


def _digest_slug(title: str) -> str:
    t = re.sub(r"[0-9]{1,4}([-/.:][0-9]{1,4})*", "", title.lower())
    t = re.sub(r"\b(mon|tue|wed|thu|fri|sat|sun)[a-z]*\b", "", t)
    t = re.sub(r"[^a-z]+", "-", t).strip("-")
    return (t or "digest")[:48]


def digest_title(content: str) -> str:
    for ln in (content or "").splitlines():
        s = re.sub(r"^[#>*\s_`]+|[*_`]+$", "", ln.strip()).strip()
        if s:
            return s[:80]
    return "Hermes"


class Store:
    def __init__(self, path: Optional[Path] = None):
        self.path = Path(path or data_dir() / "mobile.db")
        self._lock = threading.RLock()
        self._db = sqlite3.connect(str(self.path), check_same_thread=False, timeout=15, isolation_level=None)
        self._db.row_factory = sqlite3.Row
        self._db.execute("PRAGMA journal_mode=WAL")
        self._db.executescript(_SCHEMA)
        for tbl, col in (("devices", "pub"), ("devices", "apub"), ("chats", "profile"), ("approvals", "request_id"), ("devices", "nolock")):
            try:
                self._db.execute(f"ALTER TABLE {tbl} ADD COLUMN {col} TEXT DEFAULT ''")
            except sqlite3.OperationalError:
                pass

    def claim_signature(self, identity: str, expires: float) -> bool:
        """Atomic, durable single-use claim across threads, workers and restarts.

        Called only after signature verification. Never evict a still-valid
        claim to bound cache size: that would reopen the replay window.
        """
        with self._lock:
            self._db.execute("BEGIN IMMEDIATE")
            try:
                now = time.time()
                # Freshness accepts the endpoint, so expiry/pruning must too.
                if expires < now:
                    self._db.execute("ROLLBACK")
                    return False
                self._db.execute("DELETE FROM signature_replays WHERE expires < ?", (now,))
                inserted = self._db.execute(
                    "INSERT OR IGNORE INTO signature_replays(identity,expires) VALUES(?,?)",
                    (identity, expires)).rowcount == 1
                self._db.execute("COMMIT")
                return inserted
            except Exception:
                self._db.execute("ROLLBACK")
                raise

    # -- helpers ---------------------------------------------------------
    def _next_seq(self) -> int:
        row = self._db.execute("SELECT v FROM kv WHERE k='seq'").fetchone()
        n = int(row["v"]) + 1 if row else 1
        self._db.execute("INSERT INTO kv(k,v) VALUES('seq',?) ON CONFLICT(k) DO UPDATE SET v=excluded.v", (str(n),))
        return n

    def kv_get(self, k: str, default: Any = None) -> Any:
        row = self._db.execute("SELECT v FROM kv WHERE k=?", (k,)).fetchone()
        return json.loads(row["v"]) if row else default

    def kv_put(self, k: str, v: Any) -> None:
        with self._lock:
            self._db.execute("INSERT INTO kv(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v", (k, json.dumps(v)))

    def seq(self) -> int:
        row = self._db.execute("SELECT v FROM kv WHERE k='seq'").fetchone()
        return int(row["v"]) if row else 0

    @staticmethod
    def _msg(row) -> Dict[str, Any]:
        d = dict(row)
        d["meta"] = json.loads(d.get("meta") or "{}")
        return d

    # -- chats -----------------------------------------------------------
    def ensure_chat(self, chat_id: str, title: Optional[str] = None, kind: str = "chat") -> Dict[str, Any]:
        with self._lock:
            row = self._db.execute("SELECT * FROM chats WHERE id=?", (chat_id,)).fetchone()
            if row:
                return dict(row)
            now = time.time()
            self._db.execute("BEGIN IMMEDIATE")
            try:
                s = self._next_seq()
                self._db.execute("INSERT INTO chats(id,title,kind,created,updated,seq) VALUES(?,?,?,?,?,?)",
                                 (chat_id, title or "New chat", kind, now, now, s))
                self._db.execute("COMMIT")
            except Exception:
                self._db.execute("ROLLBACK")
                raise
            return dict(self._db.execute("SELECT * FROM chats WHERE id=?", (chat_id,)).fetchone())

    def update_chat(self, chat_id: str, **fields) -> Optional[Dict[str, Any]]:
        allowed = {k: v for k, v in fields.items() if k in ("title", "unread", "pinned", "archived", "updated", "profile")}
        if not allowed:
            return self.get_chat(chat_id)
        with self._lock:
            self._db.execute("BEGIN IMMEDIATE")
            try:
                s = self._next_seq()
                sets = ", ".join(f"{k}=?" for k in allowed) + ", seq=?"
                self._db.execute(f"UPDATE chats SET {sets} WHERE id=?", (*allowed.values(), s, chat_id))
                self._db.execute("COMMIT")
            except Exception:
                self._db.execute("ROLLBACK")
                raise
        return self.get_chat(chat_id)

    def get_chat(self, chat_id: str) -> Optional[Dict[str, Any]]:
        row = self._db.execute("SELECT * FROM chats WHERE id=?", (chat_id,)).fetchone()
        return dict(row) if row else None

    def list_chats(self, include_archived: bool = False) -> List[Dict[str, Any]]:
        q = """SELECT c.*, (SELECT content FROM messages m WHERE m.chat_id=c.id AND m.kind!='trace'
                 ORDER BY ts DESC LIMIT 1) AS preview,
                 (SELECT role FROM messages m WHERE m.chat_id=c.id ORDER BY ts DESC LIMIT 1) AS last_role
               FROM chats c""" + ("" if include_archived else " WHERE c.archived=0") + " ORDER BY c.pinned DESC, c.updated DESC"
        return [dict(r) for r in self._db.execute(q)]

    # -- messages --------------------------------------------------------
    def reply_context(self, chat_id: str, reply_to: Optional[str] = None) -> Optional[Dict[str, Any]]:
        """Resolve a quote locally, or the latest delivered report in this chat.

        Deliveries live in mobile.db, not the gateway conversation. Supply them on
        the *new* inbound event, never backfill a cached agent's history. A single
        anchor also avoids injecting duplicate mirrored/delivered report bodies.
        Explicit unknown/cross-chat anchors fail closed (no implicit fallback).
        """
        def usable(msg):
            return (msg and msg["chat_id"] == chat_id and msg["kind"] == "text"
                    and msg["role"] in ("agent", "user") and msg["content"].strip()
                    and not msg["meta"].get("undone") and not msg["meta"].get("quiet")
                    and not is_notice(msg["content"]) and not parse_trace(msg["content"]))

        if reply_to is not None:
            msg = self.get_message(reply_to) if isinstance(reply_to, str) else None
            return msg if usable(msg) else None
        chat = self.get_chat(chat_id) or {}
        # Ignore presentation-only rows; don't page through/limit long report bodies.
        rows = self._db.execute(
            "SELECT * FROM messages WHERE chat_id=? AND role='agent' AND kind='text' ORDER BY ts DESC, rowid DESC",
            (chat_id,))
        for row in rows:
            msg = self._msg(row)
            if not usable(msg):
                continue
            meta = msg["meta"]
            delivered = meta.get("delivery") or meta.get("mirror") == "cron"
            legacy_digest = (chat.get("kind") == "digest" and not meta.get("reply_to")
                             and not meta.get("profile"))
            # The most recent real answer supersedes an older report. Do not
            # resurrect that report just because it exists earlier in the chat.
            return msg if delivered or legacy_digest else None
        return None

    def add_message(self, chat_id: str, role: str, content: str, kind: str = "text",
                    meta: Optional[Dict[str, Any]] = None, msg_id: Optional[str] = None,
                    title: Optional[str] = None, chat_kind: str = "chat") -> Dict[str, Any]:
        self.ensure_chat(chat_id, title=title, kind=chat_kind)
        now = time.time()
        mid = msg_id or new_id("m")
        with self._lock:
            self._db.execute("BEGIN IMMEDIATE")
            try:
                s = self._next_seq()
                self._db.execute("INSERT INTO messages(id,chat_id,role,kind,content,meta,ts,seq) VALUES(?,?,?,?,?,?,?,?)",
                                 (mid, chat_id, role, kind, content, json.dumps(meta or {}), now, s))
                bump = ", unread=unread+1" if role != "user" and kind not in ("trace", "activity") and not (meta or {}).get("quiet") else ""
                s2 = self._next_seq()
                self._db.execute(f"UPDATE chats SET updated=?, seq=?{bump} WHERE id=?", (now, s2, chat_id))
                self._db.execute("COMMIT")
            except Exception:
                self._db.execute("ROLLBACK")
                raise
        return self.get_message(mid)

    def edit_message(self, msg_id: str, content: Optional[str] = None, kind: Optional[str] = None,
                     meta: Optional[Dict[str, Any]] = None, ts: Optional[float] = None) -> Optional[Dict[str, Any]]:
        cur = self.get_message(msg_id)
        if not cur:
            return None
        m = dict(cur["meta"])
        if meta:
            m.update(meta)
        with self._lock:
            self._db.execute("BEGIN IMMEDIATE")
            try:
                s = self._next_seq()
                self._db.execute("UPDATE messages SET content=?, kind=?, meta=?, seq=?, ts=? WHERE id=?",
                                 (cur["content"] if content is None else content, kind or cur["kind"], json.dumps(m), s,
                                  cur["ts"] if ts is None else ts, msg_id))
                self._db.execute("COMMIT")
            except Exception:
                self._db.execute("ROLLBACK")
                raise
        return self.get_message(msg_id)

    def get_message(self, msg_id: str) -> Optional[Dict[str, Any]]:
        row = self._db.execute("SELECT * FROM messages WHERE id=?", (msg_id,)).fetchone()
        return self._msg(row) if row else None

    def last_message(self, chat_id: str) -> Optional[Dict[str, Any]]:
        """Newest visible message, ignoring deletions and the gateway's ⏳ heartbeat lines."""
        row = self._db.execute("SELECT id, kind FROM messages WHERE chat_id=? AND kind!='deleted' "
                               "AND NOT (kind='status' AND content LIKE '⏳%') ORDER BY ts DESC LIMIT 1", (chat_id,)).fetchone()
        return dict(row) if row else None

    # -- per-chat latest turn usage ---------------------------------------
    def put_turn(self, chat_id: str, view: Dict[str, Any]) -> None:
        with self._lock:
            self._db.execute("INSERT INTO kv(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v",
                             ("turn:" + chat_id, json.dumps(view)))

    def get_turn(self, chat_id: str) -> Optional[Dict[str, Any]]:
        row = self._db.execute("SELECT v FROM kv WHERE k=?", ("turn:" + chat_id,)).fetchone()
        return json.loads(row["v"]) if row else None

    # -- tool calls (activity cards) -------------------------------------
    _CALL_COLS = ("id", "msg_id", "chat_id", "tool", "label", "detail", "status", "error", "output", "path", "diff",
                  "added", "removed", "ms", "ts", "ended")
    _CALL_LIGHT = ("id", "tool", "label", "status", "path", "added", "removed", "ms", "ts", "ended")

    def upsert_call(self, rec: Dict[str, Any]) -> None:
        cols = [c for c in self._CALL_COLS if c in rec]
        with self._lock:
            self._db.execute(
                f"INSERT INTO calls({','.join(cols)}) VALUES({','.join('?' * len(cols))}) ON CONFLICT(id) DO UPDATE SET "
                + ", ".join(f"{c}=excluded.{c}" for c in cols if c not in ("id", "msg_id", "ts")),
                tuple(rec[c] for c in cols))

    def calls(self, msg_id: str, light: bool = False) -> List[Dict[str, Any]]:
        cols = self._CALL_LIGHT if light else self._CALL_COLS
        return [dict(r) for r in self._db.execute(f"SELECT {','.join(cols)} FROM calls WHERE msg_id=? ORDER BY ts, rowid",
                                                  (msg_id,))]

    def call_card(self, call_id: str) -> Optional[str]:
        row = self._db.execute("SELECT msg_id FROM calls WHERE id=?", (call_id,)).fetchone()
        return row["msg_id"] if row else None

    def messages(self, chat_id: str, limit: int = 80, before_ts: Optional[float] = None) -> List[Dict[str, Any]]:
        if before_ts:
            rows = self._db.execute("SELECT * FROM messages WHERE chat_id=? AND ts<? ORDER BY ts DESC LIMIT ?",
                                    (chat_id, before_ts, limit))
        else:
            rows = self._db.execute("SELECT * FROM messages WHERE chat_id=? ORDER BY ts DESC LIMIT ?", (chat_id, limit))
        return [self._msg(r) for r in rows][::-1]

    def changes(self, since: int) -> Dict[str, Any]:
        if since <= 0:
            chats = self.list_chats(include_archived=True)
            msgs: List[Dict[str, Any]] = []
            for c in chats:
                msgs.extend(self.messages(c["id"], limit=40))
        else:
            chats = [c for c in self.list_chats(include_archived=True) if (c.get("seq") or 0) > since]
            msgs = [self._msg(r) for r in self._db.execute("SELECT * FROM messages WHERE seq>? ORDER BY seq LIMIT 2001", (since,))]
            if len(msgs) > 2000:  # big import: send the latest page of each changed chat instead of truncating
                msgs = []
                for c in chats:
                    msgs.extend(self.messages(c["id"], limit=40))
        appr = [self._appr(r) for r in self._db.execute("SELECT * FROM approvals WHERE seq>? OR state='pending'", (since,))]
        return {"seq": self.seq(), "chats": chats, "messages": msgs, "approvals": appr}

    # -- approvals -------------------------------------------------------
    @staticmethod
    def _appr(row) -> Dict[str, Any]:
        d = dict(row)
        d["actions"] = json.loads(d.get("actions") or "[]")
        d.pop("session_key", None)
        d.pop("request_id", None)
        return d

    def add_approval(self, chat_id: str, session_key: str, command: str, description: str,
                     actions: List[Any], request_id: str = "") -> Dict[str, Any]:
        aid = new_id("a")
        with self._lock:
            self._db.execute("BEGIN IMMEDIATE")
            try:
                s = self._next_seq()
                self._db.execute("INSERT INTO approvals(id,chat_id,session_key,command,description,actions,state,ts,seq,request_id)"
                                 " VALUES(?,?,?,?,?,?, 'pending', ?, ?, ?)",
                                 (aid, chat_id, session_key, command, description, json.dumps(actions), time.time(), s,
                                  request_id or ""))
                self._db.execute("COMMIT")
            except Exception:
                self._db.execute("ROLLBACK")
                raise
        return self.get_approval(aid)

    def approval_row(self, aid: str):
        return self._db.execute("SELECT * FROM approvals WHERE id=?", (aid,)).fetchone()

    def get_approval(self, aid: str) -> Optional[Dict[str, Any]]:
        row = self.approval_row(aid)
        return self._appr(row) if row else None

    def set_approval(self, aid: str, state: str, choice: Optional[str] = None) -> Optional[Dict[str, Any]]:
        with self._lock:
            self._db.execute("BEGIN IMMEDIATE")
            try:
                s = self._next_seq()
                self._db.execute("UPDATE approvals SET state=?, choice=?, seq=? WHERE id=?", (state, choice, s, aid))
                self._db.execute("COMMIT")
            except Exception:
                self._db.execute("ROLLBACK")
                raise
        return self.get_approval(aid)

    def pending_approvals(self) -> List[Dict[str, Any]]:
        return [self._appr(r) for r in self._db.execute("SELECT * FROM approvals WHERE state='pending' ORDER BY ts")]

    # -- devices / pairing -----------------------------------------------
    def create_pair_code(self, ttl: int = 600) -> str:
        code = secrets.token_urlsafe(9)
        with self._lock:
            self._db.execute("DELETE FROM pair_codes WHERE expires<?", (time.time(),))
            self._db.execute("INSERT INTO pair_codes(code_hash,expires) VALUES(?,?)", (sha(code), time.time() + ttl))
        return code

    def claim_pair_code(self, code: str, device_name: str, pub: str = "", apub: str = "",
                        nolock: bool = False) -> Optional[Dict[str, str]]:
        with self._lock:
            row = self._db.execute("SELECT * FROM pair_codes WHERE code_hash=?", (sha(code or ""),)).fetchone()
            if not row or row["used"] or row["expires"] < time.time():
                return None
            # Atomic claim: the pairing CLI and the gateway are separate processes on one DB, so check-then-set races.
            cur = self._db.execute("UPDATE pair_codes SET used=1 WHERE code_hash=? AND used=0 AND expires>=?",
                                   (row["code_hash"], time.time()))
            if cur.rowcount != 1:
                return None
            token = "hmd_" + secrets.token_urlsafe(32)
            did = new_id("d")
            self._db.execute("INSERT INTO devices(id,name,token_hash,created,last_seen,pub,apub,nolock) VALUES(?,?,?,?,?,?,?,?)",
                             (did, (device_name or "phone")[:60], sha(token), time.time(), time.time(), pub, apub,
                              "1" if nolock else ""))
            return {"device_id": did, "token": token}

    def device_for_token(self, token: str) -> Optional[Dict[str, Any]]:
        if not token:
            return None
        row = self._db.execute("SELECT * FROM devices WHERE token_hash=? AND revoked=0", (sha(token),)).fetchone()
        if not row:
            return None
        if time.time() - (row["last_seen"] or 0) > 60:
            with self._lock:
                self._db.execute("UPDATE devices SET last_seen=? WHERE id=?", (time.time(), row["id"]))
        return dict(row)

    def devices(self) -> List[Dict[str, Any]]:
        return [{k: r[k] for k in ("id", "name", "created", "last_seen", "revoked")}
                for r in self._db.execute("SELECT * FROM devices ORDER BY created")]

    def device_active(self, device_id: str) -> bool:
        return self._db.execute("SELECT 1 FROM devices WHERE id=? AND revoked=0", (device_id,)).fetchone() is not None

    def revoke(self, device_id: str) -> bool:
        with self._lock:
            cur = self._db.execute("UPDATE devices SET revoked=1 WHERE id=?", (device_id,))
        return cur.rowcount > 0

    def set_fcm(self, device_id: str, token: str) -> None:
        with self._lock:
            self._db.execute("UPDATE devices SET fcm_token=? WHERE id=?", (token, device_id))
