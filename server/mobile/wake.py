"""Wake: alarms that only stop on proof of being awake, a follow-up check-in, and a WhatsApp escalation.

The server owns the session, so killing the phone alarm is not an escape: no proof by the deadline means
the phone rings again, and no completed check-in by ``escalate_min`` means the escalation contacts get a
WhatsApp message through a send-only Baileys bridge on loopback.
"""
from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import logging
import os
import random
import re
import time
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Dict, List, Optional
from zoneinfo import ZoneInfo

from . import ident as ID
from . import store as S

logger = logging.getLogger(__name__)

TICK_S = 15
CATCHUP_S = 30 * 60
PREP_S = 45 * 60
CLOSE_S = 120 * 60
RERING_S = 4 * 60
WA_URL = os.environ.get("WAKE_WA_URL", "http://127.0.0.1:3017")

DEFAULTS: Dict[str, Any] = {
    "alarms": [], "contacts": [], "owner_phone": "", "tz": "",
    "recheck_min": 10, "checkin_min": 15, "answer_min": 5, "escalate_min": 30, "level_base": 1, "level_auto": True,
}


def _local_tz() -> ZoneInfo:
    """Server-local IANA zone: $TZ, else the /etc/localtime symlink target, else UTC."""
    cands = [(os.environ.get("TZ") or "").lstrip(":")]
    try:
        p = str(Path("/etc/localtime").resolve())
        cands.append(p.split("zoneinfo/", 1)[1] if "zoneinfo/" in p else "")
    except Exception:
        pass
    for c in cands:
        if c:
            try:
                return ZoneInfo(c)
            except Exception:
                pass
    return ZoneInfo("UTC")

TOPICS = {
    "beginner": [
        "what a model actually learns: weights and parameters", "training vs inference", "what a loss function measures",
        "gradient descent as walking downhill", "the learning rate: too big vs too small", "overfitting vs underfitting",
        "why we split train, validation and test data", "features and labels", "classification vs regression",
        "what an epoch and a batch are", "what a neural network layer does", "activation functions like ReLU",
        "what an embedding is", "tokens: how text becomes numbers", "what a language model predicts",
        "temperature when sampling text", "accuracy vs precision vs recall", "why we normalize input data",
        "what a GPU is good at and why ML uses it", "pretraining vs fine-tuning", "what attention means in transformers, intuitively",
        "decision trees in plain words", "k-nearest neighbors", "what a confusion matrix shows", "what dropout is for",
        "why more data usually helps", "what a context window is", "what quantization means for a model", "what a prompt is to a model",
        "bias in training data",
    ],
    "intermediate": [
        "backpropagation and the chain rule", "the Adam optimizer vs plain SGD", "regularization: L2 and dropout", "batch norm vs layer norm",
        "learning-rate schedules and warmup", "softmax and cross-entropy", "the transformer block step by step", "self-attention with Q, K and V",
        "positional encodings", "the KV cache during text generation", "LoRA fine-tuning", "embeddings and cosine similarity",
        "retrieval-augmented generation (RAG)", "beam search vs sampling", "knowledge distillation", "mixed precision training",
        "evaluation leakage", "the bias-variance tradeoff", "gradient boosting", "contrastive learning",
        "int8 and 4-bit quantization", "mixture of experts, intuitively", "RLHF in plain terms", "tokenizers and BPE",
    ],
    "advanced": [
        "KV-cache paging and fragmentation", "speculative decoding acceptance math", "FlashAttention tiling and online softmax",
        "MoE routing and load balancing", "NVFP4 and microscaling formats", "GPTQ vs AWQ", "rotary embeddings and context extension",
        "Muon and Shampoo vs Adam", "DPO vs PPO", "roofline analysis for decode vs prefill", "state-space models (Mamba)",
        "continuous batching and chunked prefill", "grouped-query and multi-latent attention", "prefix caching with radix trees",
    ],
}

MAX_STEP = 8
STEP_DAYS = 14
_BEGIN = ("They are learning ML: comfortable with Python and school math, no ML degree. It is early morning and they just woke up. "
          "Plain everyday words; explain every technical term the first time; one concrete everyday analogy. Short paragraphs. ")
_MID = "They know ML basics (training loops, neural nets, transformers at a high level) and are still learning. "
_ADV = "They are comfortable with ML internals and systems. "
STEPS = {
    1: _BEGIN + "Write 220-280 words. Questions: 5 very easy recall questions, each answerable in a few words straight from the text. "
                "Logic: 3 tiny puzzles, about 10-15 seconds each (simple sequences, simple riddles).",
    2: _BEGIN + "Write 280-340 words. Questions: 5 easy questions answerable in a few words, mostly understanding, no tricks. "
                "Logic: 3 light puzzles, about 15-20 seconds each.",
    3: _BEGIN + "Write 330-400 words, with one tiny number example. Questions: 5 questions answerable in a short sentence; "
                "at least two ask why something happens. Logic: 3 light puzzles, about 20-30 seconds each.",
    4: _MID + "Write 380-450 words. One mechanism explained clearly with one small example. Questions: 5 one-sentence questions, "
              "at least two about why or what would change. Logic: 3 puzzles, about 30 seconds each.",
    5: _MID + "Write 430-500 words. One mechanism with a small worked example. Questions: 5 one-sentence questions; one asks them to apply "
              "the idea to a new small case. Logic: 3 puzzles, about 30-40 seconds each.",
    6: _MID + "Write 480-560 words, more precise terms allowed. Questions: 5 questions needing a one-sentence explanation; two apply "
              "the idea to new cases. Logic: 3 puzzles, about 40-50 seconds each (small deductions).",
    7: _ADV + "Write 560-650 words, technically dense, one real mechanism with a small worked number. Questions: 5 questions needing "
              "a one-sentence answer that shows understanding, including one tradeoff. Logic: 3 puzzles under a minute each.",
    8: _ADV + "Write 620-750 words, dense and precise, one real mechanism with a worked number and its limits. Questions: 5 questions "
              "that need reasoning, including one what-if. Logic: 3 puzzles about a minute each (multi-step deduction).",
}


def _pool(step: int) -> List[str]:
    if step <= 2:
        return TOPICS["beginner"]
    if step == 3:
        return TOPICS["beginner"] + TOPICS["intermediate"][:8]
    if step <= 5:
        return TOPICS["intermediate"]
    if step == 6:
        return TOPICS["intermediate"] + TOPICS["advanced"][:5]
    return TOPICS["advanced"] + TOPICS["intermediate"][-8:]


GEN_SYS = (
    "You write a short morning reading for {who}, to prove they are awake and to teach them something. {level} "
    "No history lessons, no filler. Expected answers must be short (a few words to one sentence). "
    'Return JSON only: {{"title": str, "reading": markdown str, "questions": [{{"q": str, "a": str}}], "logic": [{{"q": str, "a": str}}]}}'
)

JUDGE_READ = (
    "{who} just woke up and answered 5 questions about a short text they read. Decide whether the answers show they are AWAKE and engaged. "
    "Correctness does NOT decide this. Awake means the answers relate to their questions and show an attempt to think, even if wrong, "
    "short, typo-ridden or in Hebrew. Not awake means empty, gibberish, keyboard mashing, single characters, one text pasted into every "
    "answer, the question copied back, or unrelated text. Accept when at least 4 of 5 are genuine attempts. Then grade each answer "
    "against the expected answer, and for anything wrong, partial or blank write a short, friendly correction (1-2 plain sentences) for someone still learning ML. "
    'Return JSON only: {"awake": bool, "reason": str, "feedback": [{"verdict": "correct"|"partial"|"wrong"|"blank", "note": str}]}'
)

JUDGE_LOGIC = (
    "{who} is doing a wake-up check-in: one light logic puzzle. Decide whether their answer shows an awake mind. A wrong answer with visible "
    "reasoning or a plausible attempt counts as awake; empty, gibberish, mashing or unrelated text does not. Also say whether it is correct "
    'and give the right answer with a one-line explanation. Return JSON only: {"awake": bool, "correct": bool, "reason": str, "note": str}'
)

VISION = (
    "This photo is offered as proof that the person got out of bed and walked to a bathroom. Decide: bathroom = the photo clearly shows "
    "part of a bathroom or toilet room (sink, faucet, toilet, shower, bathtub, tiled wall with fixtures, bathroom mirror); hotel or unfamiliar "
    "bathrooms count. screen = it is a photo of a screen or a printed picture rather than a real space. "
    'Return JSON only: {"bathroom": bool, "screen": bool, "reason": str}'
)


def _now() -> float:
    return time.time()


def _jid(phone: str) -> str:
    d = re.sub(r"\D", "", phone or "")
    cc = ID.phone_cc()
    if d.startswith("0") and cc:
        d = cc + d[1:]
    return f"{d}@s.whatsapp.net" if d else ""


def _json_from(text: str) -> Dict[str, Any]:
    text = (text or "").strip()
    text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text)
    try:
        return json.loads(text)
    except Exception:
        m = re.search(r"\{.*\}", text, re.S)
        if not m:
            raise ValueError("no JSON in model reply")
        return json.loads(m.group(0))


async def _llm(messages: List[Dict[str, Any]], *, task: str = "wake", max_tokens: int = 2500, timeout: float = 120) -> Dict[str, Any]:
    from agent.auxiliary_client import async_call_llm, extract_content_or_reasoning
    r = await async_call_llm(task=task, messages=messages, max_tokens=max_tokens, timeout=timeout)
    return _json_from(extract_content_or_reasoning(r))


DEFAULT_PW = "Af!@#$56789"


def _pw_hash(pw: str, salt: Optional[str] = None) -> Dict[str, str]:
    salt = salt or base64.b64encode(os.urandom(12)).decode()
    return {"salt": salt, "hash": hashlib.pbkdf2_hmac("sha256", pw.encode(), salt.encode(), 200_000).hex()}


def _pw_ok(pw: str, rec: Dict[str, str]) -> bool:
    import hmac
    return bool(rec) and hmac.compare_digest(_pw_hash(pw, rec["salt"])["hash"], rec["hash"])


def _genuine(a: str) -> bool:
    a = (a or "").strip()
    return len(a) >= 3 and len(set(a.lower())) >= 3 and not re.fullmatch(r"(.)\1*", a)


class Wake:
    def __init__(self, adapter, readonly: bool = False):
        self.ad = adapter
        self.path = S.data_dir() / "wake.json"
        self.log_path = S.data_dir() / "wake-log.jsonl"
        self.st = self._load()
        if not readonly:  # `hermes mobile check` reads beside the live gateway; never rewrite its alarm state
            self._save()
        self._task: Optional[asyncio.Task] = None
        self._gen: Optional[asyncio.Task] = None
        self._gen_fail = 0.0

    # ------------------------------------------------------------ state
    def _load(self) -> Dict[str, Any]:
        try:
            st = json.loads(self.path.read_text())
        except Exception:
            st = {}
        st.setdefault("config", {})
        for k, v in DEFAULTS.items():
            st["config"].setdefault(k, v)
        st.setdefault("last_fired", {})
        st.setdefault("session", None)
        st.setdefault("prepared", None)
        st.setdefault("recent_topics", [])
        st.setdefault("photo_hashes", [])
        c = st["config"]
        if "level" in c:
            c["level_base"] = {"beginner": 1, "intermediate": 4, "advanced": 7}.get(c.pop("level"), 1)
        c.setdefault("level_since", _now())
        if not st.get("override"):
            st["override"] = _pw_hash(DEFAULT_PW)
        return st

    def _save(self) -> None:
        tmp = self.path.with_suffix(".tmp")
        fd = os.open(str(tmp), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            json.dump(self.st, f)
        os.replace(tmp, self.path)

    @property
    def cfg(self) -> Dict[str, Any]:
        return self.st["config"]

    def _tz(self) -> ZoneInfo:
        # Client-sent tz (stored via set_config) wins; unset means server-local.
        try:
            return ZoneInfo(str(self.cfg.get("tz"))) if self.cfg.get("tz") else _local_tz()
        except Exception:
            return _local_tz()

    # ------------------------------------------------------------ difficulty
    def step(self) -> int:
        c = self.cfg
        base = max(1, min(MAX_STEP, int(c.get("level_base", 1))))
        if not c.get("level_auto", True):
            return base
        return min(MAX_STEP, base + int((_now() - float(c.get("level_since", _now()))) // (STEP_DAYS * 86400)))

    def next_step_ts(self) -> Optional[float]:
        c = self.cfg
        if not c.get("level_auto", True) or self.step() >= MAX_STEP:
            return None
        since = float(c.get("level_since", _now()))
        n = int((_now() - since) // (STEP_DAYS * 86400)) + 1
        return since + n * STEP_DAYS * 86400

    # ------------------------------------------------------------ schedule
    def _next_after(self, alarm: Dict[str, Any], after: float) -> Optional[float]:
        if not alarm.get("on", True):
            return None
        try:
            hh, mm = (int(x) for x in str(alarm["time"]).split(":"))
        except Exception:
            return None
        days = alarm.get("days")
        days = list(range(7)) if not days else [int(d) for d in days]
        tz = self._tz()
        base = datetime.fromtimestamp(after, tz)
        for i in range(8):
            d = (base + timedelta(days=i)).date()
            cand = datetime(d.year, d.month, d.day, hh, mm, tzinfo=tz).timestamp()
            if cand > after and d.weekday() in days:
                return cand
        return None

    def next_fire(self) -> Optional[Dict[str, Any]]:
        best = None
        now = _now()
        for a in self.cfg["alarms"]:
            t = self._next_after(a, max(now, self.st["last_fired"].get(a.get("id", ""), 0)))
            if t and (best is None or t < best["ts"]):
                best = {"ts": t, "alarm_id": a.get("id")}
        return best

    def _due_alarm(self) -> Optional[Dict[str, Any]]:
        now = _now()
        for a in self.cfg["alarms"]:
            aid = a.get("id", "")
            t = self._next_after(a, max(now - CATCHUP_S, self.st["last_fired"].get(aid, 0)))
            if t and t <= now:
                return {"ts": t, "alarm": a}
        return None

    # ------------------------------------------------------------ loop
    def start(self) -> None:
        self._task = asyncio.get_running_loop().create_task(self._loop())

    def stop(self) -> None:
        if self._task:
            self._task.cancel()

    async def _loop(self) -> None:
        while True:
            try:
                await self.tick()
            except asyncio.CancelledError:
                raise
            except Exception as e:
                logger.warning("[wake] tick failed: %s", e)
            await asyncio.sleep(TICK_S)

    async def tick(self) -> None:
        now = _now()
        s = self.st["session"]
        if s is None:
            due = self._due_alarm()
            if due:
                self._begin(due["alarm"].get("id", ""), due["ts"], test=False)
                if not due["alarm"].get("days"):
                    due["alarm"]["on"] = False
                self._save()
                return
            nf = self.next_fire()
            prep = self.st.get("prepared")
            if prep and prep.get("level") != self.step():
                self.st["prepared"] = prep = None
            if nf and nf["ts"] - now < PREP_S and not prep:
                self._ensure_content()
            return
        await self._advance(s, now)

    def _begin(self, alarm_id: str, fire: float, *, test: bool) -> Dict[str, Any]:
        if alarm_id:
            self.st["last_fired"][alarm_id] = fire
        s = {"id": S.new_id("w"), "alarm_id": alarm_id, "fire": fire, "test": test, "proof": None, "attempts": [],
             "checkin": None, "logic_i": 0, "rings": [], "escalated": None, "done": None, "content": None}
        prep = self.st.get("prepared")
        if prep:
            s["content"] = prep
            self.st["prepared"] = None
        self.st["session"] = s
        self._save()
        if not s["content"]:
            self._ensure_content()
        self._push("ring", s["id"])
        s["rings"].append(_now())
        logger.info("[wake] session %s started (alarm %s, test=%s)", s["id"], alarm_id or "-", test)
        return s

    async def _advance(self, s: Dict[str, Any], now: float) -> None:
        c = self.cfg
        fire = s["fire"]
        if s["done"] or now - fire > CLOSE_S:
            self._close(s, "awake" if s["done"] else "missed")
            return
        if not s["proof"]:
            if now - fire >= c["recheck_min"] * 60 and now - (s["rings"][-1] if s["rings"] else 0) >= RERING_S:
                self._ring(s, "no proof yet")
        else:
            ck = s["checkin"]
            if ck is None:
                due = max(fire + c["checkin_min"] * 60, s["proof"]["ts"] + 5 * 60)
                s["checkin"] = ck = {"due": due, "sent": None, "done": None, "misses": 0}
                self._save()
            if not ck["done"]:
                if ck["sent"] is None and now >= ck["due"]:
                    ck["sent"] = now
                    self._save()
                    self._push("checkin", s["id"])
                elif ck["sent"] and now - ck["sent"] >= c["answer_min"] * 60:
                    ck["misses"] += 1
                    ck["sent"] = now
                    s["logic_i"] += 1
                    self._save()
                    self._ring(s, "check-in missed")
        if not s["done"] and not s["escalated"] and now - fire >= c["escalate_min"] * 60:
            await self._escalate(s)

    def _ring(self, s: Dict[str, Any], why: str) -> None:
        s["rings"].append(_now())
        self._save()
        logger.info("[wake] re-ring %s: %s", s["id"], why)
        self._push("ring", s["id"])

    def _close(self, s: Dict[str, Any], outcome: str) -> None:
        rec = {k: s.get(k) for k in ("id", "alarm_id", "fire", "test", "proof", "attempts", "checkin", "rings", "escalated", "done", "override")}
        rec["outcome"] = outcome
        rec["title"] = (s.get("content") or {}).get("title")
        with open(self.log_path, "a") as f:
            f.write(json.dumps(rec) + "\n")
        self.st["session"] = None
        self._save()
        self._push("done", s["id"])
        logger.info("[wake] session %s closed: %s", s["id"], outcome)

    # ------------------------------------------------------------ content
    def _ensure_content(self) -> None:
        if self._gen and not self._gen.done():
            return
        if _now() - self._gen_fail < 120:
            return
        self._gen = asyncio.get_running_loop().create_task(self._generate())

    async def _generate(self) -> None:
        level = self.step()
        pool = _pool(level)
        recent = self.st["recent_topics"][-20:]
        topic = random.choice([t for t in pool if t not in recent] or pool)
        try:
            d = await _llm([{"role": "system", "content": GEN_SYS.format(level=STEPS[level], who=ID.user_ref())}, {"role": "user", "content": f"Topic: {topic}"}],
                           max_tokens=3000, timeout=180)
            qs = [q for q in d.get("questions", []) if isinstance(q, dict) and q.get("q")][:5]
            lg = [q for q in d.get("logic", []) if isinstance(q, dict) and q.get("q")]
            if not d.get("reading") or len(qs) < 5 or not lg:
                raise ValueError("incomplete content")
            content = {"level": level, "topic": topic, "title": str(d.get("title") or topic), "reading": str(d["reading"]), "questions": qs, "logic": lg,
                       "made": _now()}
        except Exception as e:
            self._gen_fail = _now()
            logger.warning("[wake] content generation failed: %s", e)
            return
        self.st["recent_topics"] = (recent + [topic])[-30:]
        s = self.st["session"]
        if s is not None and not s.get("content"):
            s["content"] = content
            self._push("content", s["id"])
        else:
            self.st["prepared"] = content
        self._save()
        logger.info("[wake] content ready: %s", content["title"])

    # ------------------------------------------------------------ proofs
    async def proof(self, body: Dict[str, Any]) -> Dict[str, Any]:
        s = self.st["session"]
        if not s or (body.get("sid") and body["sid"] != s["id"]):
            return {"ok": False, "reason": "No active wake session.", "session": self.view()}
        kind = str(body.get("kind") or "")
        fn = {"read": self._p_read, "photo": self._p_photo, "logic": self._p_logic, "steps": self._p_steps}.get(kind)
        if not fn:
            return {"ok": False, "reason": f"Unknown proof kind {kind!r}."}
        if kind == "logic" and self.phase(s) != "checkin":
            why = "Do a wake-up proof first (reading, photo or steps)." if not s["proof"] else "The check-in isn't open yet."
            return {"ok": False, "reason": why, "session": self.view()}
        res = await fn(s, body)
        s["attempts"].append({"kind": kind, "ts": _now(), "ok": bool(res.get("ok")), "reason": res.get("reason", "")})
        if res.get("ok"):
            if kind == "logic":
                s["checkin"]["done"] = _now()
                s["done"] = _now()
            elif not s["proof"]:
                s["proof"] = {"kind": kind, "ts": _now(), "reason": res.get("reason", "")}
        self._save()
        if s["done"]:
            self._summary(s)
        res["session"] = self.view()
        return res

    async def _p_read(self, s, body) -> Dict[str, Any]:
        c = s.get("content")
        if not c:
            return {"ok": False, "reason": "The reading is still being prepared."}
        answers = [str(a or "") for a in (body.get("answers") or [])][:5]
        answers += [""] * (5 - len(answers))
        s["answers"] = answers
        qa = "\n\n".join(f"Q{i + 1}: {q['q']}\nExpected: {q.get('a', '')}\nAnswer: {answers[i] or '(blank)'}" for i, q in enumerate(c["questions"]))
        try:
            d = await _llm([{"role": "system", "content": JUDGE_READ.replace("{who}", ID.user_ref())}, {"role": "user", "content": qa}], max_tokens=1500)
            fb = d.get("feedback") or []
            s["feedback"] = fb
            return {"ok": bool(d.get("awake")), "reason": str(d.get("reason") or ""), "feedback": fb}
        except Exception as e:
            logger.warning("[wake] read judge failed, heuristic used: %s", e)
            good = sum(_genuine(a) for a in answers) >= 4 and len({a.strip().lower() for a in answers}) >= 4
            fb = [{"verdict": "unchecked", "note": f"Expected: {q.get('a', '')}"} for q in c["questions"]]
            s["feedback"] = fb
            return {"ok": good, "reason": "Judge unavailable, checked the answers locally.", "feedback": fb}

    async def _p_photo(self, s, body) -> Dict[str, Any]:
        raw = body.get("image") or ""
        try:
            img = base64.b64decode(raw, validate=False)
        except Exception:
            return {"ok": False, "reason": "Could not read the photo."}
        if len(img) < 5000:
            return {"ok": False, "reason": "Photo is too small."}
        h = hashlib.sha256(img).hexdigest()
        if h in self.st["photo_hashes"]:
            return {"ok": False, "reason": "That exact photo was used before. Take a new one."}
        url = "data:image/jpeg;base64," + base64.b64encode(img).decode()
        try:
            d = await _llm([{"role": "user", "content": [{"type": "text", "text": VISION}, {"type": "image_url", "image_url": {"url": url}}]}],
                           task="vision", max_tokens=300, timeout=90)
        except Exception as e:
            logger.warning("[wake] vision check failed: %s", e)
            return {"ok": False, "reason": "The photo check is unavailable right now. Use the reading instead."}
        ok = bool(d.get("bathroom")) and not d.get("screen")
        if ok:
            self.st["photo_hashes"] = (self.st["photo_hashes"] + [h])[-200:]
        return {"ok": ok, "reason": str(d.get("reason") or "")}

    def logic_q(self, s) -> Optional[Dict[str, Any]]:
        lg = (s.get("content") or {}).get("logic") or []
        return lg[s["logic_i"] % len(lg)] if lg else None

    async def _p_logic(self, s, body) -> Dict[str, Any]:
        q = self.logic_q(s)
        ans = str(body.get("answer") or "")
        if not q:
            return {"ok": _genuine(ans), "reason": "No puzzle available, accepted any real answer."}
        try:
            d = await _llm([{"role": "system", "content": JUDGE_LOGIC.replace("{who}", ID.user_ref())},
                            {"role": "user", "content": f"Puzzle: {q['q']}\nExpected: {q.get('a', '')}\nAnswer: {ans or '(blank)'}"}], max_tokens=400)
            s["logic_feedback"] = {"q": q["q"], "answer": ans, "correct": bool(d.get("correct")), "note": str(d.get("note") or "")}
            return {"ok": bool(d.get("awake")), "reason": str(d.get("reason") or ""), "correct": bool(d.get("correct")), "note": str(d.get("note") or "")}
        except Exception as e:
            logger.warning("[wake] logic judge failed, heuristic used: %s", e)
            return {"ok": _genuine(ans), "reason": "Judge unavailable, checked the answer locally.", "note": f"Expected: {q.get('a', '')}"}

    def steps_needed(self) -> int:
        return 120 + 20 * (self.step() - 1)

    async def _p_steps(self, s, body) -> Dict[str, Any]:
        """Phone step-detector timestamps (seconds). The sensor batches events, so judge the overall pace and spread, not step-to-step gaps:
        a minute of walking (even in circles) passes; a few seconds of shaking does not."""
        ts = sorted(float(t) for t in (body.get("steps") or []))
        need = self.steps_needed()
        if len(ts) < need:
            return {"ok": False, "reason": f"{len(ts)} steps, need {need}."}
        span = ts[-1] - ts[0]
        rate = len(ts) / span if span > 0 else 99.0
        min_span = max(45.0, need * 0.35)
        ok = span >= min_span and rate <= 3.0
        why = f"{len(ts)} steps over {span:.0f} s ({rate:.1f} per second)."
        return {"ok": ok, "reason": why if ok else why + f" Too quick to be walking: spread it over at least {min_span:.0f} s."}

    # ------------------------------------------------------------ escalation
    async def _escalate(self, s: Dict[str, Any]) -> None:
        s["escalated"] = _now()
        self._save()
        hhmm = datetime.fromtimestamp(s["fire"], self._tz()).strftime("%H:%M")
        if s["test"]:
            targets = [{"name": "me", "phone": self.cfg.get("owner_phone", "")}]
            text = f"[auto, test] Wake test from {hhmm}: no completed check-in, this is where the escalation message would go."
        else:
            targets = [c for c in self.cfg["contacts"] if c.get("phone")]
            who = f"{ID.user_name()}'s" if ID.user_name() else "My"
            text = (f"[auto] {who} alarm system here: I missed my wake-up check at {hhmm} and I'm probably asleep. "
                    "Not an emergency, please call me.")
        sent = []
        for t in targets:
            r = await wa_send(t.get("phone", ""), text)
            sent.append({"name": t.get("name", ""), "ok": r.get("ok", False), "error": r.get("error", "")})
        s["escalation"] = sent
        self._save()
        self._ring(s, "escalated")
        logger.info("[wake] escalated %s: %s", s["id"], sent)

    # ------------------------------------------------------------ summary into the app
    def _summary(self, s: Dict[str, Any]) -> None:
        try:
            c = s.get("content") or {}
            hhmm = datetime.fromtimestamp(s["fire"], self._tz()).strftime("%H:%M")
            took = int((s["done"] - s["fire"]) // 60)
            lines = [f"**Wake {hhmm}**: awake in {took} min, proof: {s['proof']['kind'] if s['proof'] else '-'}."]
            if s.get("answers") and c.get("questions"):
                lines.append(f"\n## {c.get('title', 'Reading')}\n")
                fb = s.get("feedback") or []
                for i, q in enumerate(c["questions"]):
                    f = fb[i] if i < len(fb) else {}
                    lines.append(f"**{i + 1}. {q['q']}**\nYou: {s['answers'][i] or '(blank)'}\n{f.get('verdict', '')}: {f.get('note') or q.get('a', '')}\n")
            lf = s.get("logic_feedback")
            if lf:
                lines.append(f"**Check-in:** {lf['q']}\nYou: {lf['answer']}\n{'correct' if lf['correct'] else 'not quite'}: {lf['note']}")
            if c.get("reading") and s.get("proof", {}).get("kind") != "read":
                lines.append(f"\nToday's reading, if you want it: **{c.get('title', '')}**\n\n{c['reading']}")
            msg = self.ad.store.add_message("wake", "agent", "\n".join(lines), title="Wake", chat_kind="digest")
            self.ad._emit_msg(msg, push="digest")
        except Exception as e:
            logger.warning("[wake] summary failed: %s", e)

    # ------------------------------------------------------------ views
    def phase(self, s: Optional[Dict[str, Any]]) -> str:
        if not s:
            return "idle"
        if s["done"]:
            return "done"
        if not s["proof"]:
            return "ring"
        ck = s["checkin"]
        if ck and ck["sent"] and not ck["done"]:
            return "checkin"
        return "wait"

    def view(self) -> Optional[Dict[str, Any]]:
        s = self.st["session"]
        if not s:
            return None
        c = s.get("content")
        ck = s.get("checkin")
        due = ck["due"] if ck else (max(s["fire"] + self.cfg["checkin_min"] * 60, s["proof"]["ts"] + 300) if s["proof"] else None)
        lq = self.logic_q(s)
        return {
            "id": s["id"], "fire": s["fire"], "test": s["test"], "phase": self.phase(s), "proof": s["proof"],
            "checkin_due": due, "checkin_misses": (ck or {}).get("misses", 0), "escalate_at": s["fire"] + self.cfg["escalate_min"] * 60,
            "escalated": s["escalated"], "done": s["done"], "rings": len(s["rings"]), "steps_needed": self.steps_needed(),
            "logic": lq["q"] if lq and self.phase(s) == "checkin" else None,
            "content": {"title": c["title"], "topic": c["topic"], "reading": c["reading"], "questions": [q["q"] for q in c["questions"]]} if c else None,
        }

    def overview(self) -> Dict[str, Any]:
        nf = self.next_fire()
        return {"config": self.cfg, "next": nf, "session": self.view(), "server_now": _now(),
                "level": self.step(), "level_max": MAX_STEP, "level_next": self.next_step_ts()}

    def set_config(self, body: Dict[str, Any]) -> None:
        c = self.cfg
        if "alarms" in body:
            out = []
            for a in body["alarms"] or []:
                if not re.fullmatch(r"\d{1,2}:\d{2}", str(a.get("time", ""))):
                    raise ValueError(f"bad time {a.get('time')!r}")
                out.append({"id": str(a.get("id") or S.new_id("a")), "time": a["time"], "on": bool(a.get("on", True)),
                            "days": sorted({int(d) for d in (a.get("days") or []) if 0 <= int(d) <= 6})})
            old = {a.get("id"): a for a in c["alarms"]}
            c["alarms"] = out
            known = {a["id"] for a in out}
            for aid in list(self.st["last_fired"]):
                if aid not in known:
                    del self.st["last_fired"][aid]
            for a in out:
                if old.get(a["id"]) != a:
                    self.st["last_fired"][a["id"]] = _now()
        if "contacts" in body:
            c["contacts"] = [{"name": str(x.get("name") or ""), "phone": str(x.get("phone") or "")} for x in body["contacts"] or [] if x.get("phone")]
        if "owner_phone" in body:
            c["owner_phone"] = str(body["owner_phone"] or "")
        if body.get("tz"):
            ZoneInfo(str(body["tz"]))
            c["tz"] = str(body["tz"])
        if "level_base" in body:
            c["level_base"] = max(1, min(MAX_STEP, int(body["level_base"])))
            c["level_since"] = _now()
            self.st["prepared"] = None
        if "level_auto" in body and bool(body["level_auto"]) != c.get("level_auto", True):
            c["level_base"] = self.step()
            c["level_since"] = _now()
            c["level_auto"] = bool(body["level_auto"])
        for k in ("recheck_min", "checkin_min", "answer_min", "escalate_min"):
            if k in body:
                c[k] = max(1, min(240, int(body[k])))
        self._save()
        self._push("config", "")

    def fired(self, alarm_id: str) -> None:
        if self.st["session"] is None:
            due = self._due_alarm()
            self._begin(due["alarm"].get("id", "") if due else alarm_id, due["ts"] if due else _now(), test=False)

    def test(self) -> None:
        self.st["session"] = None
        self._begin("", _now(), test=True)

    def override(self, pw: str) -> Dict[str, Any]:
        s = self.st["session"]
        if not s:
            return {"ok": False, "reason": "No alarm is running."}
        if not _pw_ok(pw, self.st["override"]):
            logger.info("[wake] emergency stop: wrong password for %s", s["id"])
            return {"ok": False, "reason": "Wrong password."}
        s["override"] = _now()
        self._save()
        self._close(s, "override")
        try:
            hhmm = datetime.fromtimestamp(s["fire"], self._tz()).strftime("%H:%M")
            msg = self.ad.store.add_message("wake", "agent", f"**Wake {hhmm}**: stopped with the emergency password at "
                                            f"{datetime.fromtimestamp(s['override'], self._tz()).strftime('%H:%M')}. Nobody was messaged.",
                                            title="Wake", chat_kind="digest")
            self.ad._emit_msg(msg, push="digest")
        except Exception as e:
            logger.warning("[wake] override note failed: %s", e)
        return {"ok": True}

    def change_pw(self, old: str, new: str) -> Dict[str, Any]:
        if not _pw_ok(old, self.st["override"]):
            return {"ok": False, "reason": "Current password is wrong."}
        if len(new) < 8:
            return {"ok": False, "reason": "Use at least 8 characters."}
        self.st["override"] = _pw_hash(new)
        self._save()
        return {"ok": True}

    def cancel_test(self) -> bool:
        s = self.st["session"]
        if s and s["test"]:
            self._close(s, "cancelled")
            return True
        return False

    # ------------------------------------------------------------ push
    def _push(self, action: str, sid: str) -> None:
        try:
            self.ad._emit({"type": "wake", "a": action, "sid": sid})
        except Exception:
            pass
        asyncio.get_running_loop().create_task(push(self.ad.store, action, sid))


async def push(store: S.Store, action: str, sid: str) -> int:
    from .adapter import _fcm_config, _fcm_token
    info = _fcm_config()
    if not info:
        return 0
    targets = [r["fcm_token"] for r in store._db.execute("SELECT fcm_token FROM devices WHERE revoked=0 AND fcm_token!=''")]
    if not targets:
        return 0
    try:
        import httpx
        token = await asyncio.to_thread(_fcm_token, info)
        url = f"https://fcm.googleapis.com/v1/projects/{info['project_id']}/messages:send"
        sent = 0
        async with httpx.AsyncClient(timeout=10) as cl:
            for t in targets:
                payload = {"message": {"token": t, "data": {"t": "wake", "a": action, "sid": sid},
                                       "android": {"priority": "HIGH", "ttl": "300s"}}}
                r = await cl.post(url, json=payload, headers={"Authorization": f"Bearer {token}"})
                if r.status_code == 200:
                    sent += 1
                else:
                    logger.warning("[wake] FCM %s: %s", r.status_code, r.text[:200])
        return sent
    except Exception as e:
        logger.warning("[wake] push failed: %s", e)
        return 0


async def wa_send(phone: str, text: str) -> Dict[str, Any]:
    jid = _jid(phone)
    if not jid:
        return {"ok": False, "error": "no phone number"}
    try:
        import httpx
        async with httpx.AsyncClient(timeout=70) as cl:
            r = await cl.post(f"{WA_URL}/send", json={"chatId": jid, "message": text})
            if r.status_code == 200:
                return {"ok": True}
            return {"ok": False, "error": f"bridge {r.status_code}: {r.text[:160]}"}
    except Exception as e:
        return {"ok": False, "error": f"bridge unreachable: {e}"}


async def wa_status() -> str:
    try:
        import httpx
        async with httpx.AsyncClient(timeout=3) as cl:
            r = await cl.get(f"{WA_URL}/health")
            return str(r.json().get("status") or "unknown")
    except Exception:
        return "down"


# ---------------------------------------------------------------- HTTP
def routes(r, w: Wake, j) -> None:
    async def h_get(request):
        tz = request.query.get("tz")
        if tz and tz != w.cfg.get("tz"):
            try:
                w.set_config({"tz": tz})
            except Exception:
                pass
        o = w.overview()
        o["whatsapp"] = await wa_status()
        return j(o)

    async def h_config(request):
        try:
            w.set_config(await request.json())
        except Exception as e:
            return j({"error": str(e)}, 400)
        return j(w.overview())

    async def h_session(request):
        return j({"session": w.view(), "server_now": _now()})

    async def h_fired(request):
        try:
            body = await request.json()
        except Exception:
            body = {}
        w.fired(str(body.get("alarm_id") or ""))
        return j({"session": w.view(), "server_now": _now()})

    async def h_proof(request):
        return j(await w.proof(await request.json()))

    async def h_test(request):
        w.test()
        return j({"session": w.view(), "server_now": _now()})

    async def h_cancel(request):
        return j({"ok": w.cancel_test()})

    async def h_wa_test(request):
        phone = w.cfg.get("owner_phone", "")
        if not phone:
            return j({"ok": False, "error": "Set your own number first."}, 400)
        return j(await wa_send(phone, "[auto, test] Hermes wake: WhatsApp escalation works."))

    async def h_override(request):
        return j(w.override(str((await request.json()).get("password") or "")))

    async def h_pw(request):
        b = await request.json()
        return j(w.change_pw(str(b.get("old") or ""), str(b.get("new") or "")))

    r.add_post("/v1/wake/override", h_override)
    r.add_post("/v1/wake/password", h_pw)
    r.add_get("/v1/wake", h_get)
    r.add_put("/v1/wake/config", h_config)
    r.add_get("/v1/wake/session", h_session)
    r.add_post("/v1/wake/fired", h_fired)
    r.add_post("/v1/wake/proof", h_proof)
    r.add_post("/v1/wake/test", h_test)
    r.add_post("/v1/wake/cancel", h_cancel)
    r.add_post("/v1/wake/whatsapp-test", h_wa_test)
