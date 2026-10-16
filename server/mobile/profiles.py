"""Chats that belong to another Hermes profile (seller, manager, ...).

The default gateway can't host another profile's agent in-process: profiles are isolated homes with their
own credentials. So a phone message in such a chat runs ``hermes -p <profile> chat -q ... --format
stream-json``, a one-shot child built with ``served_profile_child_env`` (that profile's own secrets, never
the launch profile's). The child resumes the chat's session, streams text and tool events, and its result
lands as one agent message, edited live. Approvals inside the child have nobody to ask (single-query mode
denies dangerous commands); the reply says so if it happens.

One run per chat at a time; messages sent meanwhile wait in a per-chat FIFO and run in order on the same session
(a one-shot child can't be steered, so steer queues too). Stop clears the queue. Attachments: the first image goes
through ``--image``; voice notes are transcribed with Hermes' own STT; anything else is listed by absolute path in
the prompt so the agent can open it with its tools.
"""
from __future__ import annotations

import asyncio
import json
import logging
import mimetypes
import sys
import time
from pathlib import Path
from typing import Any, Dict, List, Optional

from . import store as S

logger = logging.getLogger(__name__)


def profile_names() -> List[str]:
    try:
        from hermes_cli.profiles import list_profiles
        out = []
        for p in list_profiles():
            n = getattr(p, "name", None) or (p.get("name") if isinstance(p, dict) else None)
            if n and n not in out:
                out.append(n)
        return out or ["default"]
    except Exception as e:
        logger.debug("[mobile] list_profiles failed: %s", e)
        base = S.hermes_home()
        return ["default"] + sorted(d.name for d in (base / "profiles").glob("*") if (d / "config.yaml").exists())


def profile_home(name: str) -> Optional[Path]:
    if not name or name == "default":
        return None
    p = S.hermes_home() / "profiles" / name
    return p if p.is_dir() else None


class ProfileChats:
    def __init__(self, ad):
        self.ad = ad
        self.procs: Dict[str, asyncio.subprocess.Process] = {}
        self.queues: Dict[str, List[Dict[str, Any]]] = {}
        self._busy: set = set()

    # ---------------------------------------------------------------- binding
    def profile_of(self, cid: str) -> str:
        v = self._kv(cid)
        if v.get("profile"):
            return v["profile"]
        m = getattr(self.ad, "_mirror", None)
        return m.profile_of(cid) if m else ""

    def bind(self, cid: str, profile: str, session: str = "") -> None:
        self._put(cid, {"profile": profile, "session": session})
        ch = self.ad.store.get_chat(cid)
        if ch is not None and (ch.get("profile") or "") != profile:
            ch = self.ad.store.update_chat(cid, profile=profile)
            self.ad._emit({"type": "chat", "seq": ch["seq"], "chat": ch})

    def _kv(self, cid: str) -> Dict[str, Any]:
        row = self.ad.store._db.execute("SELECT v FROM kv WHERE k=?", ("prof:" + cid,)).fetchone()
        return json.loads(row["v"]) if row else {}

    def _put(self, cid: str, v: Dict[str, Any]) -> None:
        with self.ad.store._lock:
            self.ad.store._db.execute("INSERT INTO kv(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v",
                                      ("prof:" + cid, json.dumps(v)))

    def _resume_id(self, cid: str) -> str:
        v = self._kv(cid)
        if v.get("session"):
            return v["session"]
        m = getattr(self.ad, "_mirror", None)
        if not m:
            return ""
        row = m.st._db.execute("SELECT tip FROM mirror WHERE chat_id=?", (cid,)).fetchone()
        if row and row[0]:
            m._tx("UPDATE mirror SET taken=1 WHERE chat_id=?", (cid,))  # the phone owns it now; stop mirroring
            return row[0]
        return ""

    # ---------------------------------------------------------------- queue
    def busy(self, cid: str) -> bool:
        """A run is in flight or about to start (set synchronously, so two quick sends can't both start)."""
        return cid in self._busy

    def running(self, cid: str) -> bool:
        p = self.procs.get(cid)
        return bool(p and p.returncode is None)

    async def stop(self, cid: str) -> bool:
        dropped = len(self.queues.pop(cid, []))
        p = self.procs.get(cid)
        if p and p.returncode is None:
            p.terminate()
        if dropped:
            note = self.ad.store.add_message(cid, "agent", f"Stopped. Dropped {dropped} queued message{'s' if dropped > 1 else ''}.",
                                             kind="status", meta={"quiet": True})
            self.ad._emit_msg(note, push=None)
        return self.busy(cid) or bool(dropped)

    async def send(self, cid: str, profile: str, text: str, atts: Optional[List[Dict[str, Any]]] = None) -> None:
        """Run now, or queue behind the current run of this chat."""
        self.queues.setdefault(cid, []).append({"text": text, "atts": list(atts or [])})
        if cid not in self._busy:
            self._busy.add(cid)
            asyncio.create_task(self._drain(cid, profile))

    async def _drain(self, cid: str, profile: str) -> None:
        try:
            while self.queues.get(cid):
                item = self.queues[cid].pop(0)
                await self._run(cid, profile, item["text"], item["atts"])
        finally:
            self.queues.pop(cid, None)
            self._busy.discard(cid)

    # ---------------------------------------------------------------- attachments
    async def _prepare(self, text: str, atts: List[Dict[str, Any]]):
        """(prompt, image path or None): first image via --image, voice transcribed, other files listed by path."""
        image: Optional[str] = None
        voice: List[str] = []
        files: List[str] = []
        for a in atts:
            p = S.data_dir() / "media" / Path(str(a.get("id", ""))).name
            if not p.is_file():
                continue
            mime = str(a.get("mime") or mimetypes.guess_type(p.name)[0] or "")
            kind = a.get("kind") or ("image" if mime.startswith("image/") else "voice" if mime.startswith("audio/") else "file")
            if kind == "image" and image is None:
                image = str(p)
            elif kind == "voice":
                voice.append(await self._transcribe(str(p)))
            else:
                files.append(str(p.resolve()))
        parts = voice + ([text] if text else [])
        if files:
            parts.append("[Attached files (read them with your file tools): " + ", ".join(files) + "]")
        prompt = "\n\n".join(parts)
        if not prompt and image:
            prompt = "(image attached)"
        return prompt, image

    @staticmethod
    async def _transcribe(path: str) -> str:
        """Hermes' own STT (configured provider, then local fallback), formatted like the gateway's voice notes."""
        try:
            from tools.transcription_tools import transcribe_audio, transcribe_audio_local_fallback
            r = await asyncio.to_thread(transcribe_audio, path, None, "gateway")
            if not r.get("success"):
                r = await asyncio.to_thread(transcribe_audio_local_fallback, path)
            t = (r.get("transcript") or "").strip() if r.get("success") else ""
            if t:
                return f'"{t}"'
        except Exception as e:
            logger.info("[mobile] profile-chat transcription failed for %s: %s", path, e)
        return f"[The user sent a voice message that could not be transcribed automatically; the audio is at: {path}]"

    def _argv(self, profile: str, text: str, resume: str, image: Optional[str] = None) -> List[str]:
        # --query=… so a message starting with "-" isn't read as a flag
        argv = [sys.executable, "-m", "hermes_cli.main", "-p", profile, "chat", "-Q", "--format", "stream-json",
                "--source", "mobile", f"--query={text}"]
        if image:
            argv += ["--image", image]
        if resume:
            argv += ["--resume", resume]
        return argv

    def _env(self, profile: str) -> Dict[str, str]:
        home = profile_home(profile)
        try:
            from tools.environments.local import served_profile_child_env
            env = served_profile_child_env(target_home=home, inherit_credentials=True)
        except Exception as e:
            logger.warning("[mobile] served_profile_child_env unavailable (%s); minimal env", e)
            import os
            env = {k: v for k, v in os.environ.items() if k in ("PATH", "HOME", "USER", "LANG", "TZ", "XDG_RUNTIME_DIR")}
            env["HERMES_HOME"] = str(home)
        for k in ("HERMES_EXEC_ASK", "HERMES_GATEWAY_SESSION", "HERMES_SESSION_PLATFORM"):
            env.pop(k, None)
        # The gateway may run on Hermes' managed runtime (`python3 -I` + a launcher that puts the checkout on
        # sys.path in-process), so a bare `sys.executable -m hermes_cli.main` can't import hermes_cli. Pin the
        # checkout and its dependency generation on the child's PYTHONPATH, as kanban and cron workers do.
        try:
            import hermes_cli
            from cron.scheduler_worker_env import pin_hermes_tree_on_pythonpath
            pin_hermes_tree_on_pythonpath(env, Path(hermes_cli.__file__).resolve().parents[1])
        except Exception as e:
            logger.warning("[mobile] could not pin the Hermes tree for profile children: %s", e)
        return env

    async def _run(self, cid: str, profile: str, text: str, atts: Optional[List[Dict[str, Any]]] = None) -> None:
        ad = self.ad
        resume = self._resume_id(cid)
        started = time.time()
        ad._working[cid] = {"since": started, "last": started}
        ad._emit({"type": "working", "chat_id": cid, "working": True, "since": started})
        msg: Optional[Dict[str, Any]] = None
        buf, tools, last_edit, sid, final, code = "", [], 0.0, "", None, 0
        try:
            prompt, image = await self._prepare(text, atts or [])
            proc = await asyncio.create_subprocess_exec(*self._argv(profile, prompt, resume, image), env=self._env(profile),
                                                        cwd=str(profile_home(profile) or S.hermes_home()),
                                                        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
                                                        limit=8 * 1024 * 1024)
            self.procs[cid] = proc
            assert proc.stdout
            async for raw in proc.stdout:
                ad._working.setdefault(cid, {"since": started})["last"] = time.time()
                try:
                    ev = json.loads(raw.decode(errors="replace"))
                except ValueError:
                    continue
                t = ev.get("type")
                if t == "system":
                    sid = ev.get("session_id") or sid
                elif t == "text":
                    buf += ev.get("text") or ""
                elif t == "tool_use":
                    tools.append(str(ev.get("name") or "tool"))
                elif t == "result":
                    sid = ev.get("session_id") or sid
                    final = ev.get("text")
                    code = int(ev.get("exit_code") or 0)
                body = buf.strip()
                if body and time.time() - last_edit > 0.8:
                    last_edit = time.time()
                    msg = self._upsert(cid, msg, body, profile, tools, partial=True)
            err = (await proc.stderr.read()).decode(errors="replace")[-600:] if proc.stderr else ""
            rc = await proc.wait()
            if sid:
                self.bind(cid, profile, sid)
            text_out = (final if isinstance(final, str) and final.strip() else buf).strip()
            if rc < 0:
                text_out = (text_out + "\n\n" if text_out else "") + "_Stopped._"
            elif not text_out:
                text_out = f"{profile} returned nothing (exit {rc}). {err.strip()[-300:]}".strip()
            msg = self._upsert(cid, msg, text_out, profile, tools, partial=False, failed=bool(code or rc > 0))
        except Exception as e:
            logger.exception("[mobile] profile run failed")
            note = ad.store.add_message(cid, "agent", f"Couldn't run {profile}: {e}", kind="status")
            ad._emit_msg(note, push=None)
        finally:
            self.procs.pop(cid, None)
            if ad._working.pop(cid, None):
                ad._emit({"type": "working", "chat_id": cid, "working": False})

    def _upsert(self, cid, msg, text, profile, tools, partial, failed=False):
        st = self.ad.store
        meta = {"profile": profile, "tools": tools[-30:], "partial": partial, **({"failed": True} if failed else {})}
        if msg is None:
            msg = st.add_message(cid, "agent", text, kind="text", meta=meta)
            self.ad._emit_msg(msg, push=None if partial else "message")
        else:
            msg = st.edit_message(msg["id"], content=text, meta=meta)
            self.ad._emit({"type": "message", "seq": msg["seq"], "message": msg, "chat": st.get_chat(cid)})
            if not partial:
                self.ad._push("message", cid)
        return msg


def routes(r, pc: ProfileChats, j) -> None:
    async def h_profiles(request):
        return j({"profiles": profile_names(), "current": "default"})

    r.add_get("/v1/profiles", h_profiles)
