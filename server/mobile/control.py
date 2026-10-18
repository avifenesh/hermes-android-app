"""Control surface for the app: model / reasoning / fast per chat, Hermes settings, skills, plugins, MCP, memory.

Writes go through Hermes' own paths, never a hand-edited config.yaml:
- per-chat model, reasoning and fast run the real ``/model``, ``/reasoning`` and ``/fast`` handlers;
- settings run ``hermes config set`` (same canonicalization, coercion and warnings as the CLI);
- the default model goes through ``switch_model`` + ``persist_model_selection`` (what ``/model --global`` does);
- skill and plugin toggles use the dashboard's writers.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import sqlite3
import subprocess
import sys
import time
from typing import Any, Dict, List, Optional, Tuple

from gateway.platforms.event import MessageEvent, MessageType

from . import ident as ID
from . import store as S

logger = logging.getLogger(__name__)

OWNER = "owner"
SYSTEM_CHAT = "c-hermes"
EFFORTS = ["none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra"]
_SECRET = re.compile(r"(api_?key|token|secret|password|passwd|credential|private_key|cookie)", re.I)
_MODELS_TTL = 600.0
# From docs.x.ai text-to-speech voices table (2026-09-29). Ara first: the chosen default.
XAI_VOICES = ['ara', 'eve', 'carina', 'zagan', 'helix', 'orion', 'luna', 'iris', 'altair', 'zenith', 'perseus', 'helios', 'lux', 'kepler', 'rigel', 'cosmo', 'celeste', 'ursa', 'sirius', 'lumen', 'castor', 'naksh', 'atlas', 'aurora', 'liora', 'leo', 'rex', 'sal']


# ---------------------------------------------------------------- curated settings
def _f(key, label, help="", type=None, options=None, restart=False, unit="", min=None, max=None):
    d = {"key": key, "label": label, "help": help}
    if type:
        d["type"] = type
    if options is not None:
        d["options"] = options
    if restart:
        d["restart"] = True
    if unit:
        d["unit"] = unit
    if min is not None:
        d["min"] = min
    if max is not None:
        d["max"] = max
    return d


GROUPS: List[Dict[str, Any]] = [
    {"title": "Model", "tab": "Model", "fields": [
        _f("model.default", "Default model", "New chats start on this. A chat can override it from the composer.", "model"),
        _f("agent.reasoning_effort", "Reasoning effort", "Default thinking budget. Per-model overrides still win.", "select",
           ["", *EFFORTS]),
        _f("agent.service_tier", "Fast mode", "Priority processing where the provider supports it.", "select"),
        _f("display.personality", "Personality", "Tone layered on top of the system prompt.", "select"),
        _f("agent.max_turns", "Max tool turns", "Tool-calling iterations per message before {agent} stops.", "number", min=1, max=1000),
    ]},
    {"title": "Approvals & safety", "tab": "Safety", "fields": [
        _f("approvals.mode", "Approval mode", "manual asks for every flagged command, smart lets a reviewer clear safe ones, off never asks.",
           "select"),
        _f("approvals.timeout", "Approval timeout", "How long a request waits for you.", "number", unit="s", min=5, max=3600),
        _f("approvals.cron_mode", "Cron approvals", "Cron runs have nobody to ask: deny or approve flagged commands.", "select",
           ["deny", "approve"]),
        _f("security.redact_secrets", "Redact secrets", "Mask keys and tokens in tool output and logs.", "bool"),
        _f("skills.write_approval", "Approve skill edits", "Ask before {agent} creates or edits a skill.", "bool"),
        _f("memory.write_approval", "Approve memory edits", "Ask before {agent} writes to memory.", "bool"),
    ]},
    {"title": "Memory & context", "tab": "Memory", "fields": [
        _f("memory.memory_enabled", "Memory", "{agent}'s own notes, injected every session.", "bool"),
        _f("memory.user_profile_enabled", "User profile", "What {agent} knows about you.", "bool"),
        _f("compression.enabled", "Auto-compress", "Summarize old turns when the context fills.", "bool"),
        _f("compression.threshold", "Compress at", "Fraction of the context window that triggers it.", "number", min=0.1, max=0.95),
        _f("curator.enabled", "Skill curator", "Periodic review that archives stale skills.", "bool"),
    ]},
    {"title": "Subagents", "tab": "Model", "fields": [
        _f("delegation.reasoning_effort", "Subagent reasoning", "Thinking budget for delegated tasks.", "select"),
        _f("delegation.max_concurrent_children", "Parallel subagents", "", "number", min=1, max=32),
        _f("delegation.max_iterations", "Subagent turn limit", "", "number", min=1, max=2000),
    ]},
    {"title": "Tools", "tab": "Tools", "fields": [
        _f("terminal.backend", "Terminal backend", "Where commands run.", "select", restart=True),
        _f("terminal.timeout", "Command timeout", "Foreground command limit.", "number", unit="s", min=5, max=3600),
        _f("web.search_backend", "Web search", "", "text"),
        _f("browser.backend", "Browser", "", "text"),
        _f("code_execution.mode", "Code execution", "project runs in your repo, strict in a scratch dir.", "select", ["project", "strict"]),
        _f("checkpoints.enabled", "File checkpoints", "Snapshot files before edits so they can be rolled back.", "bool"),
    ]},
    {"title": "In this app", "tab": "Chat", "fields": [
        _f("display.platforms.mobile.show_reasoning", "Show reasoning", "Prepend the model's reasoning to replies in the app.", "bool"),
        _f("display.platforms.mobile.tool_progress", "Tool progress bubbles",
           "The stock 'Running…' messages. Keep off: activity cards replace them.", "select", ["off", "new", "all", "verbose"],
           restart=True),
        _f("display.busy_input_mode", "While {agent} works", "What a plain message does mid-run (the composer's Steer/Queue still win).",
           "select", restart=True),
    ]},
    {"title": "Voice", "tab": "Chat", "fields": [
        _f("tts.provider", "Voice provider", "Who makes {agent}'s voice for Talk and voice clips.", "select"),
        _f("tts.xai.voice_id", "xAI voice", "Used when the provider is xai.", "select", XAI_VOICES),
        _f("stt.enabled", "Transcribe voice notes", "", "bool"),
        _f("stt.provider", "Speech to text", "", "select", ["local", "groq", "openai", "xai", "elevenlabs"]),
    ]},
    {"title": "System", "tab": "Tools", "fields": [
        _f("timezone", "Timezone", "Blank uses the machine's.", "select"),
        _f("logging.level", "Log level", "", "select", restart=True),
    ]},
]


def _dotget(d: Any, key: str, default: Any = None) -> Any:
    cur = d
    for part in key.split("."):
        if not isinstance(cur, dict) or part not in cur:
            return default
        cur = cur[part]
    return cur


def _schema() -> Dict[str, Dict[str, Any]]:
    try:
        from hermes_cli.web_server_config import CONFIG_SCHEMA
        return CONFIG_SCHEMA
    except Exception as e:
        logger.debug("[mobile] config schema unavailable: %s", e)
        return {}


def _personality_options(cfg: Dict[str, Any]) -> List[str]:
    try:
        from hermes_cli.personality import available_personalities
        return ["none", *sorted(available_personalities(cfg))]
    except Exception:
        return ["none"]


def _is_secret(key: str) -> bool:
    return bool(_SECRET.search(key.rsplit(".", 1)[-1]))


def _mobile_display(cfg_eff: Dict[str, Any], setting: str, fallback: Any) -> Any:
    try:
        from gateway.display_config import resolve_display_setting
        return resolve_display_setting(cfg_eff, "mobile", setting, fallback)
    except Exception:
        return _dotget(cfg_eff, f"display.platforms.mobile.{setting}", fallback)


def settings_view() -> Dict[str, Any]:
    from hermes_cli.config import load_config
    from gateway.run import _load_gateway_config
    cfg = load_config()
    eff = _load_gateway_config()
    schema = _schema()
    groups = []
    agent = ID.agent_name()
    for g in GROUPS:
        fields = []
        for f in g["fields"]:
            f = dict(f)
            f["help"], f["label"] = f["help"].replace("{agent}", agent), f["label"].replace("{agent}", agent)
            sch = schema.get(f["key"], {})
            if "options" not in f and sch.get("options"):
                f["options"] = list(sch["options"])
            if f["key"] == "display.personality":
                f["options"] = _personality_options(cfg)
            if "type" not in f:
                f["type"] = {"boolean": "bool", "number": "number", "select": "select"}.get(sch.get("type"), "text")
            if f["key"].startswith("display.platforms.mobile."):
                name = f["key"].rsplit(".", 1)[-1]
                v = _mobile_display(eff, name, {"show_reasoning": False, "tool_progress": "off"}.get(name))
                if name == "tool_progress" and isinstance(v, bool):
                    v = "all" if v else "off"
                f["value"] = v
            else:
                f["value"] = _dotget(cfg, f["key"])
            if f["key"] == "model.default":
                f["provider"] = _dotget(cfg, "model.provider") or ""
            if f["type"] == "select" and f.get("options") and f["value"] not in (None, "") and str(f["value"]) not in map(str, f["options"]):
                f["options"] = [str(f["value"]), *f["options"]]
            fields.append(f)
        groups.append({"title": g["title"], "tab": g.get("tab", "Other"), "fields": fields})
    return {"groups": groups}


def all_settings() -> Dict[str, Any]:
    from hermes_cli.config import load_config
    cfg = load_config()
    out = []
    for key, sch in _schema().items():
        if _is_secret(key) or key in ("model", "model_context_length"):
            continue
        val = _dotget(cfg, key)
        if isinstance(val, (dict, list)):
            continue
        t = {"boolean": "bool", "number": "number", "select": "select"}.get(sch.get("type"), "text")
        item = {"key": key, "type": t, "category": sch.get("category") or key.split(".")[0],
                "help": sch.get("description") or "", "value": val}
        if sch.get("options"):
            item["options"] = list(sch["options"])
        out.append(item)
    return {"fields": out}


# Settings that loosen what the agent may do without asking (or what runs on the host) carry the same weight as
# approving a command, so writing them needs the biometric approval key, bound to key + value.
_GUARDED = re.compile(r"^(approvals\.|security\.|terminal\.|hooks|code_execution\.|browser\.|mcp|plugins|delegation\.|"
                      r"tools\.|toolsets|command_allowlist|agent\.(environment|execution)|gateway\.|"
                      # who may reach the agent, what it runs unasked, where its traffic and secrets go
                      r"[a-z_]+\.(allowed_|allow_|extra\.allow|free_response_|dm_role_auth)|skills\.(inline_shell|trusted|write_approval)|"
                      r"memory\.write_approval|cron\.allow_agent|auth\.|dashboard\.|secrets\.|vault\.|proxy\.|"
                      r"computer_use\.|lsp\.(trusted|install)|auxiliary\.[a-z_]+\.(base_url|provider|model)$|"
                      r"auxiliary\.stream_only|model_catalog\.|models_dev\.|cron\.chronos\.)")


def guarded_key(key: str) -> bool:
    return bool(_GUARDED.match(key or ""))


def guard_ok(request, kind: str, key: str, value: Any) -> bool:
    import hashlib
    from . import sig as SG
    ts = request.headers.get("X-HM-Ts", "")
    # Same normalization config_set applies (and the app mirrors): bools as true/false, null, else str(value).
    norm = ("true" if value else "false") if isinstance(value, bool) else ("null" if value is None else str(value))
    digest = hashlib.sha256(norm.encode()).hexdigest()
    msg = f"{kind}\n{key}\n{digest}\n{ts}".encode()
    return SG.approval_ok(request, msg)


def _allowed_key(key: str) -> bool:
    if not key or _is_secret(key) or not re.fullmatch(r"[A-Za-z0-9_.\-]+", key) or key.startswith("_"):
        return False
    curated = {f["key"] for g in GROUPS for f in g["fields"]}
    return key in curated or key in _schema()


def config_set(key: str, value: Any) -> Tuple[bool, str]:
    """Write one key through ``hermes config set`` so every CLI rule applies."""
    if isinstance(value, bool):
        value = "true" if value else "false"
    elif value is None:
        value = "null"
    value = str(value)
    if key == "agent.reasoning_effort" and value == "none":
        value = "false"  # YAML false = reasoning disabled; "none" would coerce to null (= default)
    env = dict(os.environ, HERMES_HOME=str(S.hermes_home()), NO_COLOR="1")
    try:
        p = subprocess.run([sys.executable, "-m", "hermes_cli.main", "config", "set", key, value],
                           capture_output=True, text=True, timeout=30, env=env)
    except Exception as e:
        return False, str(e)
    msg = "\n".join(x for x in (p.stdout.strip(), p.stderr.strip()) if x)
    return p.returncode == 0, msg


# ---------------------------------------------------------------- skills / plugins / mcp / memory
def skills_list() -> Dict[str, Any]:
    from hermes_cli.config import load_config
    from hermes_cli.skills_config import get_disabled_skills
    from tools.skills_tool import _find_all_skills
    from tools.skill_usage import activity_count, is_bundled, is_hub_installed, load_usage
    disabled = get_disabled_skills(load_config())
    usage = load_usage()
    out = []
    for s in _find_all_skills(skip_disabled=True):
        n = s["name"]
        out.append({"name": n, "description": s.get("description") or "", "category": s.get("category") or "",
                    "enabled": n not in disabled, "usage": activity_count(usage.get(n, {})),
                    "provenance": "hub" if is_hub_installed(n) else "bundled" if is_bundled(n) else "agent"})
    try:
        from hermes_cli.plugins import get_plugin_manager
        for m in get_plugin_manager().list_plugin_skill_metadata():
            out.append({"name": m["name"], "description": m.get("description") or "", "category": "plugin",
                        "enabled": True, "usage": 0, "provenance": "plugin", "locked": True})
    except Exception:
        pass
    try:
        from agent.skill_utils import ESSENTIAL_SKILLS
        for s in out:
            if s["name"] in ESSENTIAL_SKILLS:
                s["locked"] = True
    except Exception:
        pass
    return {"skills": out}


def skill_content(name: str) -> Optional[Dict[str, Any]]:
    """Raw SKILL.md through the agent's own resolver (frontmatter names, nested dirs, plugin:skill).
    ``skill_view`` itself never bumps usage; only the tool wrapper does."""
    from pathlib import Path
    from tools.skills_tool import skill_view
    try:
        d = json.loads(skill_view(name, preprocess=False))
    except Exception as e:
        logger.debug("[mobile] skill_view failed: %s", e)
        return None
    if not d.get("success"):
        return None
    files: List[str] = []
    root = Path(d["skill_dir"]) if d.get("skill_dir") else None
    if root and root.is_dir():
        files = sorted(str(p.relative_to(root)) for p in root.rglob("*") if p.is_file() and p.name != "SKILL.md"
                       and "__pycache__" not in p.parts)[:80]
    return {"name": d.get("name") or name, "description": d.get("description") or "", "content": (d.get("content") or "")[:200000],
            "files": files, "path": d.get("path") or "", "readiness": d.get("readiness_status") or "",
            "missing_env": d.get("missing_required_environment_variables") or [],
            "missing_commands": d.get("missing_required_commands") or []}


def skill_toggle(name: str, enabled: bool) -> Dict[str, Any]:
    from hermes_cli.config import load_config
    from hermes_cli.skills_config import get_disabled_skills, save_disabled_skills
    cfg = load_config()
    disabled = get_disabled_skills(cfg)
    (disabled.discard if enabled else disabled.add)(name)
    save_disabled_skills(cfg, disabled)
    return {"ok": True, "name": name, "enabled": name not in get_disabled_skills(load_config())}


def plugins_list() -> Dict[str, Any]:
    from hermes_cli.plugins import get_plugin_manager
    try:
        from hermes_cli.plugins_cmd import _get_disabled_set, _get_enabled_set
        en, dis = _get_enabled_set(), _get_disabled_set()
    except Exception:
        en, dis = set(), set()
    out = []
    for p in get_plugin_manager().list_plugins():
        names = {p["name"], p["key"]}
        kind = p.get("kind") or ""
        if names & dis:
            want = False
        elif kind == "model-provider" or (p.get("source") == "bundled" and kind in ("backend", "platform")):
            want = True  # auto-loaded; selection happens elsewhere (provider config)
        elif kind == "exclusive":
            want = bool(p.get("enabled"))  # activated via <category>.provider, not plugins.enabled
        else:
            want = bool(names & en)
        auto = kind in ("backend", "platform", "model-provider", "exclusive") and p.get("source") == "bundled"
        out.append({**p, "configured": want, "pending": want != bool(p.get("enabled")) and not auto,
                    "locked": p["name"] == "mobile" or auto, "auto": auto})
    return {"plugins": out}


def plugin_toggle(name: str, enabled: bool) -> Dict[str, Any]:
    if name in ("mobile",):
        return {"ok": False, "error": "This app runs on the mobile plugin. Disable it from the CLI if you mean it."}
    from hermes_cli.plugins_cmd import dashboard_set_agent_plugin_enabled
    return dashboard_set_agent_plugin_enabled(name, enabled=enabled)


def mcp_list() -> Dict[str, Any]:
    try:
        from tools.mcp_tool_discovery import get_mcp_status
        rows = get_mcp_status()
    except Exception as e:
        return {"servers": [], "error": str(e)}
    keep = ("name", "transport", "tools", "connected", "disabled", "status", "error")
    return {"servers": [{k: r.get(k) for k in keep if k in r} for r in rows]}


def memory_view() -> Dict[str, Any]:
    out = {}
    try:
        from hermes_cli.config import load_config
        mc = load_config().get("memory") or {}
    except Exception:
        mc = {}
    for key, fname, limit in (("memory", "MEMORY.md", "memory_char_limit"), ("user", "USER.md", "user_char_limit")):
        p = S.hermes_home() / "memories" / fname
        text = p.read_text(encoding="utf-8", errors="replace") if p.exists() else ""
        entries = [e.strip() for e in re.split(r"\n?§\n?", text) if e.strip()]
        out[key] = {"entries": entries, "chars": len(text), "limit": int(mc.get(limit) or 0),
                    "enabled": bool(mc.get("memory_enabled" if key == "memory" else "user_profile_enabled", True)),
                    "updated": p.stat().st_mtime if p.exists() else None}
    return out


# ---------------------------------------------------------------- models
class Control:
    def __init__(self, ad):
        self.ad = ad
        self._models: Optional[Dict[str, Any]] = None
        self._models_at = 0.0
        self._models_lock = asyncio.Lock()

    # ---- gateway plumbing
    @property
    def runner(self):
        return getattr(self.ad, "gateway_runner", None)

    def source(self, cid: str):
        chat = self.ad.store.get_chat(cid) or {}
        return self.ad.build_source(chat_id=cid, chat_name=chat.get("title"), chat_type="dm", user_id=OWNER, user_name=ID.user_name() or None)

    async def command(self, cid: str, text: str) -> str:
        """Run a slash command through the gateway's handler and return its reply text (not posted to the chat)."""
        handler = getattr(self.ad, "_message_handler", None)
        if handler is None:
            raise RuntimeError("gateway handler not attached")
        ev = MessageEvent(text=text, message_type=MessageType.COMMAND, source=self.source(cid), message_id=S.new_id("ctl"))
        resp = await handler(ev)
        txt, _ = self.ad._unwrap_ephemeral(resp)
        return txt or ""

    @staticmethod
    def _cfg() -> Dict[str, Any]:
        from gateway.run import _load_gateway_config
        return _load_gateway_config() or {}

    @staticmethod
    def _listing_kwargs(cfg: Dict[str, Any]) -> Dict[str, Any]:
        mc = cfg.get("model") if isinstance(cfg.get("model"), dict) else {"default": cfg.get("model") or ""}
        try:
            from hermes_cli.config_providers import get_compatible_custom_providers
            custom = get_compatible_custom_providers(cfg)
        except Exception:
            custom = cfg.get("custom_providers")
        excl = (cfg.get("model_catalog") or {}).get("excluded_providers")
        return dict(current_provider=mc.get("provider") or "", current_base_url=mc.get("base_url") or "",
                    current_model=mc.get("default") or "", user_providers=cfg.get("providers"), custom_providers=custom,
                    excluded_providers=excl if isinstance(excl, list) else None)

    def _recent(self) -> List[Dict[str, str]]:
        out, seen = [], set()
        try:
            p = S.hermes_home() / "state.db"
            with sqlite3.connect(f"file:{p}?mode=ro", uri=True, timeout=5) as c:
                for m, prov in c.execute("SELECT model, billing_provider FROM sessions WHERE model IS NOT NULL AND model!=''"
                                         " AND started_at>? GROUP BY model, billing_provider ORDER BY MAX(started_at) DESC LIMIT 12",
                                         (time.time() - 30 * 86400,)):
                    if m not in seen:
                        seen.add(m)
                        out.append({"model": m, "provider": prov or ""})
        except Exception:
            pass
        return out

    def _build_models(self, refresh: bool) -> Dict[str, Any]:
        from hermes_cli.model_switch_providers import list_picker_providers
        if refresh:
            try:
                from hermes_cli.models import clear_provider_models_cache
                clear_provider_models_cache()
            except Exception:
                pass
        cfg = self._cfg()
        kw = self._listing_kwargs(cfg)
        provs = list_picker_providers(max_models=80, include_moa=False, **kw)
        keep = ("slug", "name", "models", "total_models", "is_current", "capabilities", "warning", "source", "is_user_defined")
        providers = [{k: p.get(k) for k in keep if k in p} for p in provs if p.get("models")]
        labels = {p["slug"]: p.get("name") or p["slug"] for p in providers}
        recent = [r for r in self._recent() if not r["provider"] or r["provider"] in labels or r["provider"] == "custom"]
        return {"providers": providers, "recent": recent[:8], "efforts": EFFORTS, "fetched": time.time(),
                "default": {"model": kw["current_model"], "provider": kw["current_provider"]}}

    async def models(self, refresh: bool = False) -> Dict[str, Any]:
        async with self._models_lock:
            if refresh or self._models is None or time.time() - self._models_at > _MODELS_TTL:
                self._models = await asyncio.wait_for(asyncio.to_thread(self._build_models, refresh), timeout=60)
                self._models_at = time.time()
            m = dict(self._models)
        cfg = self._cfg()
        mc = cfg.get("model") if isinstance(cfg.get("model"), dict) else {}
        m["default"] = {"model": mc.get("default") or "", "provider": mc.get("provider") or ""}
        return m

    def _caps(self, provider: str, model: str) -> Dict[str, Any]:
        for p in (self._models or {}).get("providers", []):
            if p.get("slug") == provider:
                return (p.get("capabilities") or {}).get(model) or {}
        return {}

    def chat_state(self, cid: str) -> Dict[str, Any]:
        cfg = self._cfg()
        mc = cfg.get("model") if isinstance(cfg.get("model"), dict) else {"default": cfg.get("model") or ""}
        default = {"model": mc.get("default") or "", "provider": mc.get("provider") or ""}
        r = self.runner
        sk, ov, rc, r_scope, tier = None, None, None, "default", None
        if r is not None:
            try:
                sk = r._session_key_for_source(self.source(cid))
                ov = r._session_model_override(sk)
                if not ov and getattr(r, "session_store", None) is not None:
                    ov = r.session_store.get_model_override(sk)
            except Exception as e:
                logger.debug("[mobile] model override read failed: %s", e)
        eff = {"model": (ov or {}).get("model") or default["model"], "provider": (ov or {}).get("provider") or default["provider"]}
        if r is not None and sk:
            try:
                rc = r._resolve_session_reasoning_config(session_key=sk, model=eff["model"])
                st = r._peek_session_state(sk)
                if st is not None and st.conversation.reasoning_override is not None:
                    r_scope = "chat"
                tier = r._resolve_session_service_tier(session_key=sk)
            except Exception as e:
                logger.debug("[mobile] reasoning/tier read failed: %s", e)
        else:
            try:
                from hermes_constants import resolve_reasoning_config
                rc = resolve_reasoning_config(cfg, eff["model"])
            except Exception:
                rc = None
        reasoning = "none" if (rc or {}).get("enabled") is False else (rc or {}).get("effort") or "medium"
        return {"chat_id": cid, "model": eff["model"], "provider": eff["provider"], "scope": "chat" if ov else "default",
                "default": default, "reasoning": reasoning, "reasoning_scope": r_scope,
                "fast": tier == "priority", "service_tier": tier or "normal", "caps": self._caps(eff["provider"], eff["model"])}

    def set_default_model(self, model: str, provider: str) -> Dict[str, Any]:
        from hermes_cli.model_switch import persist_model_selection, switch_model
        cfg = self._cfg()
        kw = self._listing_kwargs(cfg)
        res = switch_model(raw_input=model, current_provider=kw["current_provider"], current_model=kw["current_model"],
                           current_base_url=kw["current_base_url"], current_api_key="", is_global=True,
                           explicit_provider=provider or "", user_providers=kw["user_providers"],
                           custom_providers=kw["custom_providers"])
        if not res.success:
            return {"ok": False, "error": res.error_message}
        persist_model_selection(res, S.hermes_home() / "config.yaml")
        return {"ok": True, "model": res.new_model, "provider": res.target_provider, "warning": res.warning_message or ""}


# ---------------------------------------------------------------- routes
def routes(r, ctl: Control, j) -> None:
    def ok_or(res: Dict[str, Any], status_err=400):
        return j(res, 200 if res.get("ok", True) else status_err)

    async def h_models(request):
        try:
            return j(await ctl.models(refresh=request.query.get("refresh") == "1"))
        except Exception as e:
            logger.warning("[mobile] model listing failed: %s", e)
            return j({"error": f"model listing failed: {e}"}, 500)

    async def h_chat_model(request):
        cid = request.match_info["cid"]
        if ctl._models is None and not ctl._models_lock.locked():
            async def _warm():
                try:
                    await ctl.models()
                except Exception as e:
                    logger.debug("[mobile] model warm-up failed: %s", e)
            asyncio.create_task(_warm())  # caps fill in on the next read; never block the chip on a cold catalog
        return j(await asyncio.to_thread(ctl.chat_state, cid))

    async def h_set_chat_model(request):
        cid = request.match_info["cid"]
        body = await request.json()
        replies: List[str] = []
        scope = "--global" if body.get("scope") == "global" else "--session"

        async def result(error="", confirm=None):
            return j({"ok": not error, "replies": replies, "error": error, "confirm": confirm,
                      "state": await asyncio.to_thread(ctl.chat_state, cid)})

        try:
            model = str(body.get("model") or "").strip()
            if model:
                prov = str(body.get("provider") or "").strip()
                if not re.fullmatch(r"[\w.:/@+\-]+", model) or (prov and not re.fullmatch(r"[\w.:\-]+", prov)):
                    return j({"error": "bad model name"}, 400)
                if cid in getattr(ctl.ad, "_working", {}):
                    return await result("Agent is running. Wait or stop the run before switching models.")
                from tools import slash_confirm as SC
                sk = ctl.runner._session_key_for_source(ctl.source(cid)) if ctl.runner else None
                previous = SC.get_pending(sk) if sk else None
                previous_id = previous.get("confirm_id") if previous else None
                replies.append(await ctl.command(cid, f"/model {model}" + (f" --provider {prov}" if prov else "") + f" {scope}"))
                # A confirmation is not a completed switch. Do not change reasoning/fast
                # on the old model while the user is deciding whether to switch.
                pend = SC.get_pending(sk) if sk else None
                if (pend and pend.get("command") == "model" and pend.get("confirm_id") != previous_id
                        and time.time() - float(pend.get("created_at") or 0) < 30):
                    return await result(confirm={"id": pend["confirm_id"], "message": replies[-1]})
                if replies[-1].lstrip().startswith(("❌", "⚠")):
                    return await result(replies[-1])
                # The slash handler owns alias/provider resolution. Its localized
                # success receipt must name the canonical route we can read back;
                # matching old state alone cannot prove that it accepted this command.
                state = await asyncio.to_thread(ctl.chat_state, cid)
                target = state.get("default", {}) if scope == "--global" else state
                from hermes_cli.model_switch import format_model_for_display
                from hermes_cli.models import normalize_provider
                from agent.i18n import t
                receipt = t("gateway.model.switched", model=format_model_for_display(target.get("model") or ""))
                accepted = bool(replies[-1]) and replies[-1].splitlines()[0] == receipt
                if not accepted or (prov and target.get("provider") != normalize_provider(prov)):
                    detail = replies[-1] or "The previous selection is still active."
                    return await result("Model change was not applied. " + detail)
            eff = str(body.get("reasoning") or "").strip().lower()
            if eff:
                if eff not in EFFORTS + ["reset"]:
                    return j({"error": "bad reasoning level"}, 400)
                replies.append(await ctl.command(cid, f"/reasoning {eff}" + (" --global" if body.get("scope") == "global" else "")))
            if "fast" in body:
                replies.append(await ctl.command(cid, "/fast " + ("on" if body.get("fast") else "off")))
        except Exception as e:
            logger.exception("[mobile] chat control failed")
            return j({"error": str(e), "replies": replies}, 500)
        failed = [x for x in replies if x.lstrip().startswith(("❌", "⚠"))]
        return await result(failed[0] if failed else "")

    async def h_settings(request):
        return j(await asyncio.to_thread(settings_view))

    async def h_settings_all(request):
        return j(await asyncio.to_thread(all_settings))

    async def h_settings_put(request):
        body = await request.json()
        key = str(body.get("key") or "")
        if key == "model.default":
            res = await asyncio.to_thread(ctl.set_default_model, str(body.get("value") or ""), str(body.get("provider") or ""))
            if res.get("ok"):
                ctl._models_at = 0
            return ok_or(res)
        if not _allowed_key(key):
            return j({"ok": False, "error": f"{key} isn't editable from the app"}, 400)
        if guarded_key(key) and not guard_ok(request, "config", key, body.get("value")):
            return j({"ok": False, "error": "biometric confirmation required", "need_sig": True}, 403)
        ok, msg = await asyncio.to_thread(config_set, key, body.get("value"))
        from hermes_cli.config import load_config
        val = _dotget(await asyncio.to_thread(load_config), key)
        return j({"ok": ok, "key": key, "value": val, "message": msg, "error": "" if ok else (msg or "config set failed")},
                 200 if ok else 400)

    async def h_skills(request):
        return j(await asyncio.to_thread(skills_list))

    async def h_skill(request):
        d = await asyncio.to_thread(skill_content, request.query.get("name", ""))
        return j(d) if d else j({"error": "not found"}, 404)

    async def h_skill_toggle(request):
        body = await request.json()
        return ok_or(await asyncio.to_thread(skill_toggle, str(body.get("name") or ""), bool(body.get("enabled"))))

    async def h_plugins(request):
        return j(await asyncio.to_thread(plugins_list))

    async def h_plugin_toggle(request):
        body = await request.json()
        if not guard_ok(request, "plugin", str(body.get("name") or ""), bool(body.get("enabled"))):
            return j({"ok": False, "error": "biometric confirmation required", "need_sig": True}, 403)
        return ok_or(await asyncio.to_thread(plugin_toggle, str(body.get("name") or ""), bool(body.get("enabled"))))

    async def h_mcp(request):
        return j(await asyncio.to_thread(mcp_list))

    async def h_memory(request):
        return j(await asyncio.to_thread(memory_view))

    async def h_restart(request):
        ad = ctl.ad
        ad.store.ensure_chat(SYSTEM_CHAT, title="Hermes")
        try:
            reply = await ctl.command(SYSTEM_CHAT, "/restart")
        except Exception as e:
            return j({"ok": False, "error": str(e)}, 500)
        return j({"ok": True, "reply": reply})

    async def h_confirm(request):
        """Resolve a pending slash confirmation (e.g. the cost guard on /model) raised by a control call."""
        cid = request.match_info["cid"]
        body = await request.json()
        choice = str(body.get("choice") or "")
        if choice not in ("once", "always", "cancel"):
            return j({"error": "bad choice"}, 400)
        from tools import slash_confirm as SC
        sk = ctl.runner._session_key_for_source(ctl.source(cid))
        out = await SC.resolve(sk, str(body.get("id") or ""), choice)
        if out is None:
            return j({"ok": False, "error": "That confirmation expired"}, 410)
        return j({"ok": not out.lstrip().startswith(("❌", "🟡")), "reply": out, "state": await asyncio.to_thread(ctl.chat_state, cid)})

    r.add_get("/v1/models", h_models)
    r.add_get("/v1/chats/{cid}/model", h_chat_model)
    r.add_post("/v1/chats/{cid}/model", h_set_chat_model)
    r.add_get("/v1/settings", h_settings)
    r.add_get("/v1/settings/all", h_settings_all)
    r.add_put("/v1/settings", h_settings_put)
    r.add_get("/v1/skills", h_skills)
    r.add_get("/v1/skill", h_skill)
    r.add_post("/v1/skills/toggle", h_skill_toggle)
    r.add_get("/v1/plugins", h_plugins)
    r.add_post("/v1/plugins/toggle", h_plugin_toggle)
    r.add_get("/v1/mcp", h_mcp)
    r.add_get("/v1/memory", h_memory)
    r.add_post("/v1/restart", h_restart)
    r.add_post("/v1/chats/{cid}/confirm", h_confirm)
