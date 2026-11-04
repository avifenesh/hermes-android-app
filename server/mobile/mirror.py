"""Mirror Hermes conversations and cron reports from other surfaces/profiles into the mobile Inbox.

Read-only against each profile's state.db and cron output. A default-profile chat keeps following its
source until the user replies from the phone; then the phone takes it over (/resume). Chats from other
profiles (e.g. seller) run on their own gateway, so they stay read-only in the app.
"""
from __future__ import annotations

import hashlib
import json
import logging
import re
import sqlite3
import time
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from . import store as S

logger = logging.getLogger(__name__)
SOURCES = ("telegram", "cli", "desktop", "webui", "tui")
MAX_CHATS = 80
INIT_MSGS = 60
INIT_RUNS = 5
BACK_WINDOW = 48 * 3600
SETTLE = 20
INJECTED = ("[PRIOR CONTEXT", "[OUT-OF-BAND", "[ASYNC DELEGATION", "[System", "[SYSTEM", "[CONTEXT COMPACTION")
DROP_PREFIX = ("[CONTEXT COMPACTION", "You've reached the maximum number of tool-calling")
# Core mirrors a delivered cron brief or webhook result into the target session as a USER turn,
# f"[Cron delivery: {name}]\n{text}" / f"[Webhook delivery: {route}]\n{content}" (cron/scheduler_delivery.py,
# gateway/platforms/webhook.py), to keep strict role alternation. It is not the user. The name may contain "]".
_DELIVERY = re.compile(r"\[(Cron|Webhook) delivery: [^\n]*\]\n")
# Session chat types that are not the owner's private chat. Mirrored read-only: the phone would resume a reply in
# its own DM lane.
SHARED_CHAT_TYPES = ("group", "forum", "channel", "supergroup")
SHARED_PASS = "mirror_shared_flagged"  # kv: the one-time read-only backfill over every mirror row ran


def _delivery(row, text: str, shared: str) -> Optional[Tuple[str, str]]:
    """("cron" | "webhook", body without the label) for a row core's delivery mirror wrote, else None.

    Core writes it with role, content and nothing else (gateway/mirror.py _append_to_sqlite). A message a person
    sent carries the platform's message id, so a typed label is never a delivery; nor is anything in a shared chat.
    """
    if row["role"] != "user" or shared or row["pmid"]:
        return None
    m = _DELIVERY.match(text)
    return (m.group(1).lower(), text[m.end():].strip()) if m else None


def _columns(c: sqlite3.Connection, table: str) -> set:
    return {r[1] for r in c.execute(f"PRAGMA table_info({table})")}


def _has_chat_type(c: sqlite3.Connection) -> bool:
    return "chat_type" in _columns(c, "sessions")


def _pmid_col(c: sqlite3.Connection) -> str:
    return "platform_message_id" if "platform_message_id" in _columns(c, "messages") else "NULL"


def _clean(role: str, text: Any) -> str:
    t = (text if isinstance(text, str) else "").strip()
    if not t or t.startswith(DROP_PREFIX):
        return ""
    if role == "user":
        if t.startswith("Gateway message origin"):
            i = t.find("Do not guess a reply destination")
            j = t.find("\n", i) if i >= 0 else -1
            t = t[j + 1:].strip() if j >= 0 else ""
        k = t.find("\n\n[System: ")
        if k > 0:
            t = t[:k].rstrip()
    return t


def _hash(role: str, text: str) -> Optional[str]:
    if len(text) <= 40:  # short replies ("yes", "continue") legitimately repeat
        return None
    return hashlib.sha1((role + "\0" + text[:4000]).encode()).hexdigest()[:16]


def _ts(v: Any, fb: float) -> float:
    if isinstance(v, (int, float)) and v > 0:
        return float(v) / 1000.0 if v > 1e12 else float(v)
    try:
        return datetime.fromisoformat(str(v)).timestamp()
    except Exception:
        return fb


def discover_profiles(default_home: Path) -> List[Tuple[str, Path]]:
    import os
    raw = os.environ.get("MOBILE_MIRROR_PROFILES")
    if raw is None:
        # Unset: mirror every discovered profile that has a state.db.
        prof_dir = default_home / "profiles"
        want = sorted(p.name for p in prof_dir.iterdir() if (p / "state.db").exists()) if prof_dir.is_dir() else []
    else:
        # Set (even empty): exactly the listed profiles; empty means none.
        want = [x.strip() for x in raw.split(",") if x.strip()]
    out = [("", default_home)]
    for name in want:
        p = default_home / "profiles" / name
        if (p / "state.db").exists():
            out.append((name, p))
    return out


def _label(prof: str) -> str:
    return prof[:1].upper() + prof[1:]


class Mirror:
    def __init__(self, store: "S.Store", state_db, profiles: Optional[List[Tuple[str, Path]]] = None) -> None:
        self.st = store
        self.profiles = profiles or discover_profiles(Path(state_db).parent)
        self._tx("CREATE TABLE IF NOT EXISTS mirror (chat_id TEXT PRIMARY KEY, root TEXT, tip TEXT, source TEXT,"
                 " last_id INTEGER DEFAULT 0, taken INTEGER DEFAULT 0)")
        if "profile" not in [r[1] for r in self.st._db.execute("PRAGMA table_info(mirror)")]:
            self._tx("ALTER TABLE mirror ADD COLUMN profile TEXT DEFAULT ''")
        self._tx("UPDATE chats SET profile=(SELECT m.profile FROM mirror m WHERE m.chat_id=chats.id)"
                 " WHERE COALESCE(profile,'')='' AND id IN (SELECT chat_id FROM mirror WHERE COALESCE(profile,'')!='')")
        self._tx("CREATE TABLE IF NOT EXISTS backsync (session_id TEXT PRIMARY KEY, chat_id TEXT, last_id INTEGER DEFAULT 0)")
        self.hashes: Dict[str, set] = {}
        for cid, meta in self.st._db.execute("SELECT chat_id, meta FROM messages WHERE chat_id LIKE 'h-%'"):
            try:
                h = json.loads(meta or "{}").get("h")
            except Exception:
                h = None
            if h:
                self.hashes.setdefault(cid, set()).add(h)

    def _tx(self, sql: str, args: tuple = ()) -> None:
        with self.st._lock:
            self.st._db.execute("BEGIN IMMEDIATE")
            try:
                self.st._db.execute(sql, args)
                self.st._db.execute("COMMIT")
            except Exception:
                self.st._db.execute("ROLLBACK")
                raise

    @staticmethod
    def _src(home: Path) -> sqlite3.Connection:
        c = sqlite3.connect(f"file:{home / 'state.db'}?mode=ro", uri=True, timeout=5)
        c.row_factory = sqlite3.Row
        return c

    # ---------------------------------------------------------------- conversations
    def chains(self, c: sqlite3.Connection, sources=SOURCES) -> List[Dict[str, Any]]:
        ct = "LOWER(COALESCE(chat_type,''))" if _has_chat_type(c) else "''"
        q = ("SELECT id, source, parent_session_id AS p, end_reason, title, message_count, started_at,"
             f" COALESCE(last_activity_at, started_at) AS la, {ct} AS ct FROM sessions"
             f" WHERE source IN ({','.join('?' * len(sources))}) AND COALESCE(hidden,0)=0")
        rows = [dict(r) for r in c.execute(q, tuple(sources))]
        by = {r["id"]: r for r in rows}
        groups: Dict[str, List[Dict[str, Any]]] = {}
        for r in rows:
            x, n = r, 0
            while x["p"] in by and by[x["p"]]["end_reason"] == "compression" and by[x["p"]]["source"] == x["source"] and n < 1000:
                x, n = by[x["p"]], n + 1
            groups.setdefault(x["id"], []).append(r)
        out = []
        for root, ss in groups.items():
            ss.sort(key=lambda s: s["started_at"] or 0)
            if sum(s["message_count"] or 0 for s in ss) <= 2:
                continue  # one-shot pings ("Reply with exactly ...")
            tip = ss[-1]
            out.append({"root": root, "ids": [s["id"] for s in ss], "tip": tip["id"], "source": tip["source"],
                        "title": next((s["title"] for s in reversed(ss) if s["title"]), None),
                        "la": max(float(s["la"] or 0) for s in ss),
                        # "telegram:group": mirrored read-only (the phone refuses to reply into it)
                        "shared": f"{tip['source']}:{tip['ct']}" if tip["ct"] in SHARED_CHAT_TYPES else ""})
        out.sort(key=lambda g: -g["la"])
        return out[:MAX_CHATS]

    @staticmethod
    def _rows(c: sqlite3.Connection, ids: List[str], after: int):
        q = (f"SELECT id, role, content, timestamp, {_pmid_col(c)} AS pmid FROM messages"
             f" WHERE session_id IN ({','.join('?' * len(ids))}) AND id>? AND role IN ('user','assistant')"
             " AND COALESCE(content,'')!='' AND COALESCE(_compressed_summary,0)=0"
             " AND (display_kind IS NULL OR display_kind='steer') ORDER BY id")
        return c.execute(q, (*ids, after)).fetchall()

    def _store(self, cid: str, title: Optional[str], kind: str, cand, first: bool, bump: int,
               mirror_row: tuple, readonly: str = "") -> List[str]:
        """cand: [(role, text, ts, meta)]. mirror_row: (root, tip, source, last_id, profile).
        readonly: "telegram:group" for a shared conversation the phone may read but not reply to."""
        new: List[str] = []
        with self.st._lock:
            db = self.st._db
            db.execute("BEGIN IMMEDIATE")
            try:
                if cand:
                    if not db.execute("SELECT 1 FROM chats WHERE id=?", (cid,)).fetchone():
                        db.execute("INSERT INTO chats(id,title,kind,created,updated,unread,seq,profile,readonly) VALUES(?,?,?,?,?,0,?,?,?)",
                                   (cid, title, kind, cand[0][2], cand[-1][2], self.st._next_seq(), mirror_row[4] or "", readonly))
                    for role, t, ts, meta in cand:
                        mid = S.new_id("m")
                        db.execute("INSERT INTO messages(id,chat_id,role,kind,content,meta,ts,seq) VALUES(?,?,?,?,?,?,?,?)",
                                   (mid, cid, role, "text", t, json.dumps(meta), ts, self.st._next_seq()))
                        new.append(mid)
                    db.execute("UPDATE chats SET updated=MAX(COALESCE(updated,0),?), title=COALESCE(?,title),"
                               " unread=unread+?, seq=? WHERE id=?", (cand[-1][2], title, 0 if first else bump,
                                                                       self.st._next_seq(), cid))
                # A chat mirrored before it was marked (older plugin) gets the flag too, and syncs it to the phone.
                if db.execute("SELECT 1 FROM chats WHERE id=? AND COALESCE(readonly,'')!=?", (cid, readonly)).fetchone():
                    db.execute("UPDATE chats SET readonly=?, seq=? WHERE id=?", (readonly, self.st._next_seq(), cid))
                db.execute("INSERT INTO mirror(chat_id,root,tip,source,last_id,taken,profile) VALUES(?,?,?,?,?,0,?)"
                           " ON CONFLICT(chat_id) DO UPDATE SET tip=excluded.tip, last_id=excluded.last_id",
                           (cid, *mirror_row))
                db.execute("COMMIT")
            except Exception:
                db.execute("ROLLBACK")
                raise
        return new

    def _conv(self, prof: str, home: Path, known: Dict[str, tuple]) -> List[str]:
        c = self._src(home)
        new: List[str] = []
        try:
            for ch in self.chains(c, SOURCES if not prof else ("telegram",)):
                cid = "h-" + ch["root"] if not prof else f"h-{prof}-{ch['root']}"
                k = known.get(cid)
                if k and k[2]:
                    continue
                rows = self._rows(c, ch["ids"], k[1] if k else 0)
                if k and not rows and k[0] == ch["tip"] and not self._readonly_stale(cid, ch["shared"]):
                    continue
                last_id = rows[-1]["id"] if rows else (k[1] if k else 0)
                first = k is None
                hs = self.hashes.setdefault(cid, set())
                cand = []
                for r in rows:
                    role = "agent" if r["role"] == "assistant" else "user"
                    t = _clean(r["role"], r["content"])
                    meta = {"mirror": ch["source"]}
                    if (d := _delivery(r, t, ch["shared"])) is not None:
                        # Delivered by cron/webhook on that surface: the phone has no copy, so show what was sent.
                        # Its own tag: mirror=="cron" means this plugin's own report threads (reply context).
                        meta["delivery_mirror"], role, t = d[0], "agent", d[1]
                    if not t:
                        continue
                    h = _hash(role, t)
                    if h and h in hs:
                        continue
                    if h:
                        hs.add(h)
                    meta["h"] = h
                    if prof:
                        meta["profile"] = prof
                    cand.append((role, t, _ts(r["timestamp"], ch["la"] or time.time()), meta))
                if first:
                    cand = cand[-INIT_MSGS:]
                title = (ch["title"] or "").strip()[:60] or None
                if first and not title:
                    title = next((x[1][:48] for x in cand if x[0] == "user"), None) or "Conversation"
                if title and prof:
                    title = f"{_label(prof)} \u00b7 {title}"
                bump = sum(1 for x in cand if x[0] == "agent")
                new += self._store(cid, title, "chat", cand, first, bump,
                                   (ch["root"], ch["tip"], ch["source"], last_id, prof), readonly=ch["shared"])
        finally:
            c.close()
        return new

    # ---------------------------------------------------------------- cron reports of other profiles
    def _cron(self, prof: str, home: Path, known: Dict[str, tuple]) -> List[str]:
        try:
            d = json.loads((home / "cron" / "jobs.json").read_text())
            jobs = d["jobs"] if isinstance(d, dict) and "jobs" in d else d
        except Exception:
            return []
        new: List[str] = []
        now = time.time()
        for j in jobs:
            jid = str(j.get("id") or "")
            if not jid or str(j.get("deliver") or "local").strip() == "local":
                continue  # only reports that would reach the user somewhere
            odir = home / "cron" / "output" / jid
            if not odir.is_dir():
                continue
            cid = f"p-{prof}-{jid}"
            k = known.get(cid)
            files = sorted(f.name for f in odir.glob("*.md") if now - f.stat().st_mtime > 3)
            files = [f for f in files if f > (k[0] or "")] if k else files[-INIT_RUNS:]
            if not files:
                continue
            cand = []
            for fn in files:
                p = odir / fn
                txt = p.read_text(errors="replace")
                if "**Status:** silent" in txt[:800]:
                    continue
                body = txt.split("\n---\n", 1)[1].strip() if "\n---\n" in txt else txt.strip()
                if body:
                    cand.append(("agent", body, p.stat().st_mtime, {"mirror": "cron", "profile": prof, "job": jid}))
            title = f"{_label(prof)} \u00b7 {j.get('name') or jid}"
            new += self._store(cid, title, "digest", cand, k is None, len(cand), (jid, files[-1], "cron", 0, prof))
        return new

    def sync_once(self) -> List[str]:
        """Import new chats/messages/reports. Returns ids of newly stored messages."""
        try:
            self._flag_shared_mirrors()
        except Exception as e:
            logger.debug("[mobile] mirror read-only pass failed: %s", e)
        known = {r[0]: (r[1], r[2], r[3]) for r in self.st._db.execute("SELECT chat_id, tip, last_id, taken FROM mirror")}
        new: List[str] = []
        for prof, home in self.profiles:
            try:
                new += self._conv(prof, home, known)
                if not prof:
                    new += self._back(home)
                if prof:
                    new += self._cron(prof, home, known)
            except Exception as e:
                logger.debug("[mobile] mirror %s failed: %s", prof or "default", e)
        return new

    # ---------------------------------------------------------------- back-sync of phone chats used elsewhere
    busy = staticmethod(lambda cid: False)  # adapter sets this: chats with a phone-run turn in flight

    @staticmethod
    def _norm(t: str) -> str:
        t = re.sub(r"MEDIA:\S+", "", t or "")  # the phone turns MEDIA: lines into attachments
        return " ".join(t.split())[:40000]

    def _back(self, home: Path) -> List[str]:
        """Phone chats continued on another surface (desktop opening a mobile session, or the original session of
        a taken-over mirror). Those turns land in state.db but never pass the adapter, so import the user/agent
        lines the phone doesn't already have. Dedup is by content against the phone chat."""
        now = time.time()
        db = self.st._db
        chats = {r[0]: r[1] for r in db.execute("SELECT id, COALESCE(readonly,'') FROM chats")}
        cur = {r[0]: (r[1], r[2]) for r in db.execute("SELECT session_id, chat_id, last_id FROM backsync")}
        c = self._src(home)
        new: List[str] = []
        try:
            pmid = _pmid_col(c)
            # native: a phone chat's own mobile session; else the original session of a mirror the phone took over
            pairs = [(r["id"], r["chat_id"], True) for r in c.execute(
                "SELECT id, chat_id FROM sessions WHERE source='mobile' AND COALESCE(chat_id,'')!=''"
                " AND COALESCE(last_activity_at, started_at) > ?", (now - BACK_WINDOW,))]
            for cid, tip in db.execute("SELECT chat_id, tip FROM mirror WHERE taken=1 AND COALESCE(profile,'')=''"):
                pairs.append((tip, cid, False))
            for sid, cid, native in pairs:
                if cid not in chats or self.busy(cid):
                    continue
                last = cur.get(sid, (cid, 0))[1]
                rows = c.execute(f"SELECT id, role, content, timestamp, {pmid} AS pmid FROM messages WHERE session_id=? AND id>?"
                                 " AND role IN ('user','assistant') AND COALESCE(content,'')!=''"
                                 " AND COALESCE(_compressed_summary,0)=0"
                                 " AND (display_kind IS NULL OR display_kind='steer') ORDER BY id", (sid, last)).fetchall()
                if not rows:
                    continue
                # a phone turn writes state.db a moment before the adapter stores the reply; let it settle
                settled = [r for r in rows if _ts(r["timestamp"], now) < now - SETTLE]
                if len(settled) < len(rows):
                    rows = settled
                    if not rows:
                        continue
                top = rows[-1]["id"]
                if sid not in cur:  # first sight: only the recent window, never the whole history
                    rows = [r for r in rows if _ts(r["timestamp"], now) > now - BACK_WINDOW]
                have = [(m[0], self._norm(m[1]), m[2]) for m in db.execute(
                    "SELECT role, content, ts FROM messages WHERE chat_id=? AND ts>?", (cid, now - BACK_WINDOW - 86400))]
                cand = []
                for r in rows:
                    role = "agent" if r["role"] == "assistant" else "user"
                    t = _clean(r["role"], r["content"])
                    if not t or t.startswith(INJECTED):
                        continue
                    d = _delivery(r, t, chats[cid])
                    if d is not None:
                        if native:
                            continue  # a delivery into a phone chat: the phone already holds the delivered message
                        role, t = "agent", d[1]  # delivered on the taken-over chat's own surface: no phone copy
                    ts = _ts(r["timestamp"], now)
                    n = self._norm(t)
                    if len(n) > 40:
                        k = n[:120]  # phone copies may carry a "Reasoning" block before the same text
                        dup = any(h[0] == role and k in h[1] for h in have)
                    else:  # "now?", "yes": only a dup if the phone has it close in time
                        dup = any(h[0] == role and h[1] == n and abs(h[2] - ts) < 1800 for h in have)
                    if dup:
                        continue
                    have.append((role, n, ts))
                    meta = {"mirror": "elsewhere", "session": sid}
                    if d is not None:
                        meta["delivery_mirror"] = d[0]
                    if role == "user" and t.startswith(("\u2714 [", "\u2716 [")):  # kanban notices
                        cand.append(("agent", t, ts, {**meta, "quiet": True}, "status"))
                    else:
                        cand.append((role, t, ts, meta, "text"))
                with self.st._lock:
                    db.execute("BEGIN IMMEDIATE")
                    try:
                        for role, t, ts, meta, kind in cand:
                            mid = S.new_id("m")
                            db.execute("INSERT INTO messages(id,chat_id,role,kind,content,meta,ts,seq) VALUES(?,?,?,?,?,?,?,?)",
                                       (mid, cid, role, kind, t, json.dumps(meta), ts, self.st._next_seq()))
                            new.append(mid)
                        if cand:
                            bump = sum(1 for x in cand if x[0] == "agent" and x[4] == "text")
                            db.execute("UPDATE chats SET updated=MAX(COALESCE(updated,0),?), unread=unread+?, seq=? WHERE id=?",
                                       (cand[-1][2], bump, self.st._next_seq(), cid))
                        db.execute("INSERT INTO backsync(session_id,chat_id,last_id) VALUES(?,?,?) ON CONFLICT(session_id)"
                                   " DO UPDATE SET chat_id=excluded.chat_id, last_id=excluded.last_id", (sid, cid, top))
                        db.execute("COMMIT")
                    except Exception:
                        db.execute("ROLLBACK")
                        raise
        finally:
            c.close()
        return new

    def _home(self, prof: str) -> Optional[Path]:
        """The state.db home of profile ``prof`` ("" = default), also for a profile no longer mirrored."""
        homes = dict(self.profiles)
        if prof in homes:
            return homes[prof]
        base = homes.get("")
        return base / "profiles" / prof if base is not None else None

    def _source_type(self, prof: str, tip: str) -> Optional[str]:
        """Chat type of a mirrored chat's source session ("" when unknown or the column predates it). None: unreadable."""
        home = self._home(prof)
        if home is None or not (home / "state.db").exists():
            return None
        try:
            c = self._src(home)
            try:
                if not _has_chat_type(c):
                    return ""
                r = c.execute("SELECT LOWER(COALESCE(chat_type,'')) FROM sessions WHERE id=?", (tip,)).fetchone()
                return r[0] if r else ""
            finally:
                c.close()
        except Exception as e:
            logger.debug("[mobile] mirror source of %s unreadable: %s", tip, e)
            return None

    def _set_readonly(self, flags: Dict[str, str], done_key: Optional[str] = None) -> None:
        with self.st._lock:
            db = self.st._db
            db.execute("BEGIN IMMEDIATE")
            try:
                for cid, ro in flags.items():
                    if db.execute("SELECT 1 FROM chats WHERE id=? AND COALESCE(readonly,'')!=?", (cid, ro)).fetchone():
                        db.execute("UPDATE chats SET readonly=?, seq=? WHERE id=?", (ro, self.st._next_seq(), cid))
                if done_key:
                    db.execute("INSERT INTO kv(k,v) VALUES(?,'true') ON CONFLICT(k) DO UPDATE SET v=excluded.v", (done_key,))
                db.execute("COMMIT")
            except Exception:
                db.execute("ROLLBACK")
                raise

    def _flag_shared_mirrors(self) -> None:
        """Once per mobile.db: every chat mirrored from a group, forum, channel or supergroup session becomes
        read-only, whatever its age (conversation import visits only the newest MAX_CHATS chains), taken or not, in
        any profile. Recorded as done only when every source that still exists was read; sends also check live."""
        if self.st.kv_get(SHARED_PASS):
            return
        flags: Dict[str, str] = {}
        for cid, tip, source, prof in self.st._db.execute(
                "SELECT chat_id, tip, source, COALESCE(profile,'') FROM mirror").fetchall():
            ct = self._source_type(prof, tip)
            if ct is None:
                home = self._home(prof)
                if home is not None and (home / "state.db").exists():
                    return  # unreadable now: try the whole pass again later
                continue  # its profile is gone; a send into it is checked live and refused
            if ct in SHARED_CHAT_TYPES:
                flags[cid] = f"{source}:{ct}"
        self._set_readonly(flags, SHARED_PASS)

    def source_readonly(self, cid: str) -> Optional[str]:
        """The read-only flag a mirrored chat should carry, from its source session now (defense in depth before a
        send). "" for a private conversation or a chat that is no mirror; None when the source can't be read.
        A group found here keeps the flag."""
        row = self.st._db.execute("SELECT tip, source, COALESCE(profile,'') FROM mirror WHERE chat_id=?", (cid,)).fetchone()
        if not row:
            return ""
        ct = self._source_type(row[2], row[0])
        if ct is None:
            return None
        ro = f"{row[1]}:{ct}" if ct in SHARED_CHAT_TYPES else ""
        if ro:
            self._set_readonly({cid: ro})
        return ro

    def _readonly_stale(self, cid: str, readonly: str) -> bool:
        row = self.st._db.execute("SELECT COALESCE(readonly,'') FROM chats WHERE id=?", (cid,)).fetchone()
        return bool(row) and row[0] != readonly

    def profile_of(self, cid: str) -> str:
        row = self.st._db.execute("SELECT profile FROM mirror WHERE chat_id=?", (cid,)).fetchone()
        return (row[0] or "") if row else ""

    def take_over(self, cid: str) -> Optional[str]:
        """First phone reply in a mirrored default-profile chat: stop mirroring and return the session to /resume."""
        row = self.st._db.execute("SELECT tip, taken, COALESCE(profile,'') FROM mirror WHERE chat_id=?", (cid,)).fetchone()
        if not row or row[1] or row[2] or self._shared(row[0]):
            return None
        self._tx("UPDATE mirror SET taken=1 WHERE chat_id=?", (cid,))
        return row[0]

    def _shared(self, sid: str) -> bool:
        """A group/forum/channel session (mirrored before chains() filtered them) must not be resumed in the phone's
        DM lane. Unreadable state counts as shared: /resume would read the same database anyway."""
        home = next((h for p, h in self.profiles if not p), None)
        if home is None:
            return True
        try:
            c = self._src(home)
            try:
                if not _has_chat_type(c):
                    return False
                r = c.execute("SELECT chat_type FROM sessions WHERE id=?", (sid,)).fetchone()
            finally:
                c.close()
        except Exception as e:
            logger.debug("[mobile] mirror chat type of %s unreadable: %s", sid, e)
            return True
        return bool(r) and str(r[0] or "").lower() in SHARED_CHAT_TYPES
