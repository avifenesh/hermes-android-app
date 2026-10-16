"""Hub for the app: browse, search, preview, scan, install, update and remove skills, plugins and MCP servers.

Three catalogs, the same ones the dashboard and the CLI use:
- skills: the skills hub (``tools.skills_hub_*``: official optional skills, the Hermes index, skills.sh, GitHub,
  ClawHub, LobeHub, ...). Installs run ``hermes [-p <profile>] skills install <id> --yes`` in a child process,
  so Hermes' own quarantine + security scan decides. ``--yes`` only skips the terminal prompt (the app's confirm
  sheet replaces it); ``--force`` is never passed, so a scan that says "block" or "ask" refuses the install.
  Updates are pre-scanned here and refused unless the scan policy is "allow" (``hermes skills update`` reinstalls
  with ``force``, which would otherwise skip a non-dangerous block).
- plugins: the curated plugin catalog (``hermes_cli.plugin_catalog``), installed at its pinned SHA through
  ``dashboard_install_plugin`` (kill list, consent and platform gates included). Update/remove go through the
  dashboard writers, which refuse while a gateway runs; that refusal is passed through as is.
- mcp: the Nous-approved MCP catalog (``hermes_cli.mcp_catalog``). Declared credentials go to .env, the rest to
  config.yaml, through ``install_entry`` like the dashboard.

Skills can target another profile (``-p``). Plugins and MCP servers go to the gateway's own profile.
Writes that add or change code the agent runs need the biometric key (``kind=hub``), like plugin toggles.
Long actions run as jobs the app polls (``GET /v1/hub/jobs/{id}``).
"""
from __future__ import annotations

import asyncio
import logging
import os
import re
import sys
import time
import uuid
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

from . import profiles as PR
from . import store as S

logger = logging.getLogger(__name__)

_ANSI = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\x07]*\x07")
_IDENT = re.compile(r"^[\w.:/@+#~\-]{1,300}$")
_NAME = re.compile(r"^[\w.\-]{1,120}$")
_SOURCE_LABELS = {
    "official": "Official (Nous)", "hermes-index": "Hermes Index", "skills-sh": "skills.sh", "well-known": "Well-Known",
    "url": "Direct URL", "github": "GitHub", "clawhub": "ClawHub", "lobehub": "LobeHub", "browse-sh": "browse.sh",
}
_TRUST_RANK = {"builtin": 2, "trusted": 1, "community": 0}
JOB_KEEP = 40


def _gateway_profile() -> str:
    home = S.hermes_home()
    return home.name if home.parent.name == "profiles" else "default"


def _profile(raw: Any) -> str:
    """'' / the gateway's own profile -> '' (in-process); another known profile -> its name; else ValueError."""
    p = str(raw or "").strip()
    if not p or p == _gateway_profile():
        return ""
    if p not in PR.profile_names() or PR.profile_home(p) is None:
        raise ValueError(f"unknown profile {p!r}")
    return p


def _lock(profile: str):
    from tools.skills_hub import HubLockFile
    if not profile:
        return HubLockFile()
    home = PR.profile_home(profile)
    if home is None:
        raise ValueError(f"unknown profile {profile!r}")
    return HubLockFile(home / "skills" / ".hub" / "lock.json")


def installed_skills(profile: str = "") -> Dict[str, Dict[str, Any]]:
    """identifier -> {name, trust_level, scan_verdict, source, installed_at, updated_at} from the hub lock."""
    try:
        lock = _lock(profile)
    except ValueError:
        raise
    try:
        keep = ("name", "trust_level", "scan_verdict", "source", "installed_at", "updated_at")
        return {e["identifier"]: {k: e.get(k) for k in keep} for e in lock.list_installed() if e.get("identifier")}
    except Exception as e:
        logger.debug("[mobile] hub lock unreadable: %s", e)
        return {}


def _meta(m) -> Dict[str, Any]:
    return {"name": m.name, "description": m.description or "", "source": m.source, "identifier": m.identifier,
            "trust_level": m.trust_level, "repo": m.repo, "tags": list(m.tags or [])}


# ---------------------------------------------------------------- skills: read
def skills_browse(profile: str) -> Dict[str, Any]:
    """Sources, featured (the index's empty-query list), the official optional catalog by category, installed map."""
    from tools.skills_hub_official import OptionalSkillSource
    from tools.skills_hub_search import create_source_router
    sources, featured, index = [], [], False
    for src in create_source_router():
        sid = src.source_id()
        e: Dict[str, Any] = {"id": sid, "label": _SOURCE_LABELS.get(sid, sid)}
        if sid == "hermes-index":
            try:
                index = bool(src.is_available)
            except Exception:
                index = False
            e["available"] = index
            if index:
                try:
                    featured = [_meta(m) for m in src.search("", limit=12)]
                except Exception as ex:
                    logger.debug("[mobile] hub featured failed: %s", ex)
        sources.append(e)
    installed = installed_skills(profile)
    official = []
    for m in OptionalSkillSource().list_local():
        d = _meta(m)
        rel = d["identifier"].split("/", 1)[-1] if "/" in d["identifier"] else d["identifier"]
        d["category"] = rel.split("/", 1)[0] if "/" in rel else "general"
        d["installed"] = d["identifier"] in installed
        official.append(d)
    for d in featured:
        d["installed"] = d["identifier"] in installed
    return {"sources": sources, "index_available": index, "featured": featured, "official": official, "installed": installed}


def skills_search(q: str, source: str, limit: int, profile: str) -> Dict[str, Any]:
    from tools.skills_hub_search import create_source_router, parallel_search_sources
    q = (q or "").strip()
    installed = installed_skills(profile)
    if not q:
        return {"results": [], "source_counts": {}, "timed_out": [], "installed": installed}
    res, counts, timed_out = parallel_search_sources(create_source_router(), query=q, source_filter=source or "all",
                                                     overall_timeout=30)
    seen: Dict[str, Any] = {}
    for r in res:  # dedupe by identifier, higher trust wins (what the dashboard and unified_search do)
        prev = seen.get(r.identifier)
        if prev is None or _TRUST_RANK.get(r.trust_level, 0) > _TRUST_RANK.get(prev.trust_level, 0):
            seen[r.identifier] = r
    out = [_meta(m) for m in list(seen.values())[:max(1, min(limit, 50))]]
    for d in out:
        d["installed"] = d["identifier"] in installed
    return {"results": out, "source_counts": counts, "timed_out": list(timed_out or []), "installed": installed}


def _resolve(ident: str):
    from hermes_cli.skills_hub import _resolve_source_meta_and_bundle
    from tools.skills_hub_search import create_source_router
    meta, bundle, _src = _resolve_source_meta_and_bundle(ident, create_source_router())
    return meta, bundle


def skill_preview(ident: str, profile: str) -> Optional[Dict[str, Any]]:
    meta, bundle = _resolve(ident)
    if not bundle and not meta:
        return None
    files: Dict[str, str] = {}
    if bundle:
        for rel, content in (bundle.files or {}).items():
            if isinstance(content, bytes):
                try:
                    content = content.decode("utf-8")
                except UnicodeDecodeError:
                    content = "(binary file)"
            files[rel] = content
    m = meta or bundle
    ident2 = getattr(m, "identifier", ident) or ident
    src = getattr(m, "source", "") or ""
    inst = installed_skills(profile)
    return {"name": getattr(m, "name", ident), "description": getattr(m, "description", "") or "",
            "source": src, "source_label": _SOURCE_LABELS.get(src, src),
            "identifier": ident2, "trust_level": getattr(m, "trust_level", "community") or "community",
            "repo": getattr(m, "repo", None), "tags": list(getattr(m, "tags", None) or []),
            "skill_md": (files.get("SKILL.md") or "")[:200000], "files": sorted(files),
            "installed": inst.get(ident2) or inst.get(ident)}


def skill_scan(ident: str) -> Optional[Dict[str, Any]]:
    """The installer's own quarantine + scan_skill + should_allow_install, without installing."""
    import shutil
    from tools.skills_guard import scan_skill, should_allow_install
    from tools.skills_hub_install import quarantine_bundle
    meta, bundle = _resolve(ident)
    if not bundle:
        return None
    src = "official" if bundle.source == "official" else (getattr(bundle, "identifier", "") or getattr(meta, "identifier", "") or ident)
    q = quarantine_bundle(bundle)
    try:
        result = scan_skill(q, source=src)
    finally:
        shutil.rmtree(q, ignore_errors=True)
    allowed, reason = should_allow_install(result, force=False)
    counts = {s: 0 for s in ("critical", "high", "medium", "low")}
    for f in result.findings:
        if f.severity in counts:
            counts[f.severity] += 1
    return {"name": result.skill_name, "identifier": ident, "source": result.source, "trust_level": result.trust_level,
            "verdict": result.verdict, "summary": result.summary,
            "policy": "allow" if allowed is True else "ask" if allowed is None else "block", "policy_reason": reason,
            "findings": [{"severity": f.severity, "category": f.category, "file": f.file, "line": f.line,
                          "description": f.description} for f in result.findings][:200],
            "severity_counts": counts}


def skill_updates(profile: str) -> Dict[str, Any]:
    from tools.skills_hub_install import check_for_skill_updates
    rows = check_for_skill_updates(lock=_lock(profile))
    keep = ("name", "identifier", "source", "status")
    return {"updates": [{k: r.get(k) for k in keep} for r in rows]}


# ---------------------------------------------------------------- plugins / mcp: read
def plugins_catalog() -> Dict[str, Any]:
    """The curated catalog merged with installed state, built the way the dashboard's /plugins/catalog does."""
    from hermes_cli.plugins_cmd import _discover_all_plugins, _get_disabled_set, _get_enabled_set
    from hermes_cli.plugins_cmd_catalog import installed_catalog_state
    en, dis = _get_enabled_set(), _get_disabled_set()
    installed: Dict[str, Dict[str, Any]] = {}
    for name, _v, _d, _s, dir_str, key in _discover_all_plugins():
        aliases = {name, key} - {""}
        status = "disabled" if aliases & dis else "enabled" if aliases & en else "inactive"
        installed.update({a: {"dir": dir_str, "runtime_status": status} for a in aliases})
    out = installed_catalog_state(installed)
    # The dashboard writers (update/remove) take the installed directory name, which can differ from the catalog name.
    try:
        from hermes_cli.plugins_cmd_catalog import catalog_install_record
        dirs = {}
        for info in installed.values():
            rec = catalog_install_record(info["dir"])
            if rec and rec.get("catalog_name"):
                dirs[str(rec["catalog_name"])] = Path(info["dir"]).name
        for e in out.get("entries", []):
            if e.get("installed"):
                e["installed_name"] = dirs.get(e["name"]) or e["name"]
    except Exception as ex:
        logger.debug("[mobile] plugin install records unreadable: %s", ex)
    try:
        from hermes_cli.plugin_catalog import CATALOG_CATEGORIES
        out["categories"] = list(CATALOG_CATEGORIES)
    except Exception:
        pass
    return out


def mcp_catalog() -> Dict[str, Any]:
    from hermes_cli import mcp_catalog as MC
    out = []
    for e in MC.list_catalog():
        auth, tr, ins = e.auth, e.transport, e.install
        out.append({
            "name": e.name, "description": e.description, "source": e.source, "transport": tr.type,
            "auth_type": getattr(auth, "type", "none"),
            "required_env": [{"name": v.name, "prompt": v.prompt, "required": v.required, "secret": bool(getattr(v, "secret", True))}
                             for v in getattr(auth, "env", []) or []],
            "command": tr.command, "args": list(tr.args or []), "url": tr.url,
            "install_url": ins.url if ins else None, "bootstrap": list(ins.bootstrap) if ins else [],
            "post_install": e.post_install or "", "needs_install": ins is not None,
            "installed": MC.is_installed(e.name), "enabled": MC.is_enabled(e.name)})
    diags = []
    try:
        diags = [{"name": n, "kind": k, "message": m} for (n, k, m) in MC.catalog_diagnostics()]
    except Exception:
        pass
    return {"entries": out, "diagnostics": diags}


# ---------------------------------------------------------------- jobs
class Jobs:
    """Install/update/remove runs. A job is a CLI child (``hermes -p … skills …``) or an in-process call."""

    def __init__(self):
        self.jobs: Dict[str, Dict[str, Any]] = {}

    def _new(self, kind: str, action: str, target: str, profile: str) -> Dict[str, Any]:
        jid = "hj-" + uuid.uuid4().hex[:12]
        job = {"id": jid, "kind": kind, "action": action, "target": target, "profile": profile or _gateway_profile(),
               "state": "running", "started": time.time(), "ended": None, "log": "", "error": "", "result": None}
        self.jobs[jid] = job
        for old in sorted(self.jobs.values(), key=lambda x: x["started"])[:-JOB_KEEP]:
            if old["state"] != "running":
                self.jobs.pop(old["id"], None)
        return job

    def busy(self, kind: str, target: str) -> Optional[Dict[str, Any]]:
        return next((x for x in self.jobs.values() if x["kind"] == kind and x["target"] == target and x["state"] == "running"), None)

    @staticmethod
    def _env(profile: str) -> Dict[str, str]:
        home = PR.profile_home(profile) if profile else None
        try:
            from tools.environments.local import served_profile_child_env
            env = served_profile_child_env(target_home=home, inherit_credentials=True)
        except Exception as e:
            logger.warning("[mobile] served_profile_child_env unavailable (%s); minimal env", e)
            env = {k: v for k, v in os.environ.items() if k in ("PATH", "HOME", "USER", "LANG", "TZ", "XDG_RUNTIME_DIR", "HERMES_HOME")}
            if home:
                env["HERMES_HOME"] = str(home)
        for k in ("HERMES_EXEC_ASK", "HERMES_GATEWAY_SESSION", "HERMES_SESSION_PLATFORM"):
            env.pop(k, None)
        env.update({"NO_COLOR": "1", "TERM": "dumb", "COLUMNS": "110", "PYTHONUNBUFFERED": "1"})
        try:  # the gateway may run on the managed `python3 -I` runtime; pin the checkout like profile chats do
            import hermes_cli
            from cron.scheduler_worker_env import pin_hermes_tree_on_pythonpath
            pin_hermes_tree_on_pythonpath(env, Path(hermes_cli.__file__).resolve().parents[1])
        except Exception as e:
            logger.warning("[mobile] could not pin the Hermes tree for hub jobs: %s", e)
        return env

    def run_cli(self, kind: str, action: str, target: str, profile: str, argv: List[str],
                verify: Optional[Callable[[], Optional[str]]] = None) -> Dict[str, Any]:
        """``verify`` runs after exit 0 and returns an error string when the expected end state isn't there."""
        job = self._new(kind, action, target, profile)
        cmd = [sys.executable, "-m", "hermes_cli.main"] + (["-p", profile] if profile else []) + argv

        async def go():
            try:
                proc = await asyncio.create_subprocess_exec(*cmd, env=self._env(profile), stdin=asyncio.subprocess.DEVNULL,
                                                            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT,
                                                            cwd=str(PR.profile_home(profile) or S.hermes_home()))
                assert proc.stdout
                async for raw in proc.stdout:
                    job["log"] = (job["log"] + _ANSI.sub("", raw.decode("utf-8", "replace")))[-20000:]
                rc = await asyncio.wait_for(proc.wait(), 30)
                err = "" if rc == 0 else f"exited with code {rc}"
                if not err and verify:
                    err = await asyncio.to_thread(verify) or ""
                job.update(state="failed" if err else "ok", error=err, result={"rc": rc})
            except Exception as e:
                logger.warning("[mobile] hub job %s failed", job["id"], exc_info=True)
                job.update(state="failed", error=str(e) or type(e).__name__)
            finally:
                job["ended"] = time.time()

        job["_task"] = asyncio.create_task(go())
        return job

    def run_call(self, kind: str, action: str, target: str, fn: Callable[[], Dict[str, Any]]) -> Dict[str, Any]:
        """In-process writer (dashboard_* helpers) on a thread; its {ok, error, ...} becomes the job result."""
        job = self._new(kind, action, target, "")

        async def go():
            try:
                res = await asyncio.to_thread(fn)
                res = dict(res) if isinstance(res, dict) else {"ok": bool(res)}
                res.pop("after_install_path", None)
                ok = bool(res.get("ok"))
                job.update(state="ok" if ok else ("consent" if res.get("consent_required") else "failed"),
                           error="" if ok else str(res.get("error") or "failed"), result=res)
            except Exception as e:
                logger.warning("[mobile] hub job %s raised", job["id"], exc_info=True)
                job.update(state="failed", error=str(e) or type(e).__name__)
            finally:
                job["ended"] = time.time()

        job["_task"] = asyncio.create_task(go())
        return job


def view(job: Dict[str, Any], log: bool = True) -> Dict[str, Any]:
    d = {k: v for k, v in job.items() if not k.startswith("_")}
    if not log:
        d.pop("log", None)
    else:
        d["log"] = d["log"][-8000:]
    return d


# ---------------------------------------------------------------- routes
def routes(r, j) -> Jobs:
    from . import control as C
    jobs = Jobs()

    def bad(msg: str, status: int = 400):
        return j({"ok": False, "error": msg}, status)

    def need_sig(request, key: str):
        if C.guard_ok(request, "hub", key, True):
            return None
        return j({"ok": False, "error": "biometric confirmation required", "need_sig": True}, 403)

    async def body_of(request) -> Dict[str, Any]:
        try:
            b = await request.json()
            return b if isinstance(b, dict) else {}
        except Exception:
            return {}

    async def read(fn, *a, what: str):
        try:
            return j(await asyncio.to_thread(fn, *a))
        except ValueError as e:
            return bad(str(e), 404)
        except Exception as e:
            logger.warning("[mobile] hub %s failed", what, exc_info=True)
            return j({"error": f"{what} failed: {e}"}, 502)

    def prof_of(raw) -> str:
        return _profile(raw)

    # skills
    async def h_browse(request):
        try:
            prof = prof_of(request.query.get("profile"))
        except ValueError as e:
            return bad(str(e), 404)
        return await read(skills_browse, prof, what="Hub browse")

    async def h_search(request):
        q = request.query
        try:
            prof = prof_of(q.get("profile"))
            limit = int(q.get("limit") or 30)
        except ValueError as e:
            return bad(str(e))
        return await read(skills_search, q.get("q", ""), q.get("source", "all"), limit, prof, what="Hub search")

    async def h_preview(request):
        ident = (request.query.get("identifier") or "").strip()
        if not _IDENT.match(ident):
            return bad("identifier is required")
        try:
            prof = prof_of(request.query.get("profile"))
            d = await asyncio.to_thread(skill_preview, ident, prof)
        except ValueError as e:
            return bad(str(e), 404)
        except Exception as e:
            logger.warning("[mobile] hub preview failed", exc_info=True)
            return j({"error": f"Hub preview failed: {e}"}, 502)
        return j(d) if d else j({"error": f"Skill not found: {ident}"}, 404)

    async def h_scan(request):
        ident = (request.query.get("identifier") or "").strip()
        if not _IDENT.match(ident):
            return bad("identifier is required")
        try:
            d = await asyncio.to_thread(skill_scan, ident)
        except Exception as e:
            logger.warning("[mobile] hub scan failed", exc_info=True)
            return j({"error": f"Hub scan failed: {e}"}, 502)
        return j(d) if d else j({"error": f"Skill not found: {ident}"}, 404)

    async def h_installed(request):
        try:
            prof = prof_of(request.query.get("profile"))
        except ValueError as e:
            return bad(str(e), 404)
        return j({"installed": await asyncio.to_thread(installed_skills, prof), "profile": prof or _gateway_profile(),
                  "profiles": PR.profile_names(), "gateway_profile": _gateway_profile()})

    async def h_updates(request):
        try:
            prof = prof_of(request.query.get("profile"))
        except ValueError as e:
            return bad(str(e), 404)
        return await read(skill_updates, prof, what="Update check")

    def _by_name(prof: str, name: str) -> Optional[Dict[str, Any]]:
        return next(({"identifier": k, **v} for k, v in installed_skills(prof).items() if v.get("name") == name), None)

    async def h_skill_install(request):
        b = await body_of(request)
        ident = str(b.get("identifier") or "").strip()
        if not _IDENT.match(ident) or ident.startswith("-"):
            return bad("identifier is required")
        try:
            prof = prof_of(b.get("profile"))
        except ValueError as e:
            return bad(str(e), 404)
        if (resp := need_sig(request, f"skill:install:{ident}")) is not None:
            return resp
        if busy := jobs.busy("skill", ident):
            return j({"ok": True, "job": view(busy)})

        def verify():
            if ident in installed_skills(prof):
                return None
            return "Hermes didn't install it (a scan block, a name clash, or it was already there). The log says which."
        job = jobs.run_cli("skill", "install", ident, prof, ["skills", "install", ident, "--yes"], verify)
        return j({"ok": True, "job": view(job)})

    async def h_skill_uninstall(request):
        b = await body_of(request)
        name = str(b.get("name") or "").strip()
        if not _NAME.match(name) or name.startswith("-"):
            return bad("name is required")
        try:
            prof = prof_of(b.get("profile"))
        except ValueError as e:
            return bad(str(e), 404)
        if not _by_name(prof, name):
            return bad(f"{name} isn't a hub-installed skill in this profile", 404)
        if busy := jobs.busy("skill", name):
            return j({"ok": True, "job": view(busy)})
        job = jobs.run_cli("skill", "uninstall", name, prof, ["skills", "uninstall", name, "--yes"],
                           lambda: "It is still installed. See the log." if _by_name(prof, name) else None)
        return j({"ok": True, "job": view(job)})

    async def h_skill_update(request):
        b = await body_of(request)
        name = str(b.get("name") or "").strip()
        if not _NAME.match(name) or name.startswith("-"):
            return bad("name is required")
        try:
            prof = prof_of(b.get("profile"))
        except ValueError as e:
            return bad(str(e), 404)
        entry = _by_name(prof, name)
        if not entry:
            return bad(f"{name} isn't a hub-installed skill in this profile", 404)
        if (resp := need_sig(request, f"skill:update:{name}")) is not None:
            return resp
        try:
            scan = await asyncio.to_thread(skill_scan, entry["identifier"])
        except Exception as e:
            return j({"ok": False, "error": f"Couldn't scan the new version: {e}"}, 502)
        if not scan:
            return bad(f"{entry['identifier']} is no longer in its source", 404)
        if scan["policy"] != "allow":
            return j({"ok": False, "error": f"The new version didn't pass the security scan: {scan['policy_reason']}",
                      "scan": scan}, 409)
        job = jobs.run_cli("skill", "update", name, prof, ["skills", "update", name])
        return j({"ok": True, "job": view(job), "scan": scan})

    # plugins
    async def h_plugins(request):
        return await read(plugins_catalog, what="Plugin catalog")

    async def h_plugin_install(request):
        b = await body_of(request)
        name = str(b.get("name") or "").strip()
        if not re.fullmatch(r"[a-z0-9_-]{1,64}", name):
            return bad("catalog name is required")
        if (resp := need_sig(request, f"plugin:install:{name}")) is not None:
            return resp
        if busy := jobs.busy("plugin", name):
            return j({"ok": True, "job": view(busy)})
        from hermes_cli.plugins_cmd import dashboard_install_plugin
        enable = b.get("enable", True) is not False
        job = jobs.run_call("plugin", "install", name,
                            lambda: dashboard_install_plugin("", force=False, enable=enable, catalog_name=name))
        return j({"ok": True, "job": view(job)})

    def _plugin_name(b) -> str:
        name = str(b.get("name") or "").strip().strip("/")
        return "" if (not name or ".." in name or "\\" in name or len(name) > 120) else name

    async def h_plugin_update(request):
        b = await body_of(request)
        name = _plugin_name(b)
        if not name:
            return bad("plugin name is required")
        accept = b.get("accept_capabilities") is True
        if (resp := need_sig(request, f"plugin:update:{name}" + (":accept" if accept else ""))) is not None:
            return resp
        from hermes_cli.plugins_cmd import dashboard_update_user_plugin
        job = jobs.run_call("plugin", "update", name, lambda: dashboard_update_user_plugin(name, accept_capabilities=accept))
        return j({"ok": True, "job": view(job)})

    async def h_plugin_remove(request):
        b = await body_of(request)
        name = _plugin_name(b)
        if not name:
            return bad("plugin name is required")
        if name == "mobile":
            return bad("This app runs on the mobile plugin. Remove it from the CLI if you mean it.")
        if (resp := need_sig(request, f"plugin:remove:{name}")) is not None:
            return resp
        from hermes_cli.plugins_cmd import dashboard_remove_user_plugin
        job = jobs.run_call("plugin", "remove", name, lambda: dashboard_remove_user_plugin(name))
        return j({"ok": True, "job": view(job)})

    # mcp
    async def h_mcp(request):
        return await read(mcp_catalog, what="MCP catalog")

    async def h_mcp_install(request):
        b = await body_of(request)
        name = str(b.get("name") or "").strip()
        env = b.get("env") or {}
        if not _NAME.match(name):
            return bad("name is required")
        if not isinstance(env, dict) or not all(isinstance(k, str) and isinstance(v, str) for k, v in env.items()):
            return bad("env must be a map of strings")
        from hermes_cli import mcp_catalog as MC
        entry = MC.get_entry(name)
        if entry is None:
            return bad(f"No catalog entry '{name}'", 404)
        specs = list(entry.auth.env or [])
        if extra := sorted(set(env) - {s.name for s in specs}):
            return bad(f"'{name}' does not declare: {', '.join(extra)}")
        if missing := [s.name for s in specs if s.required and not env.get(s.name, "").strip() and not os.environ.get(s.name)]:
            return bad("Needs " + ", ".join(missing))
        if MC.is_installed(name):
            return bad(f"'{name}' is already installed", 409)
        if (resp := need_sig(request, f"mcp:install:{name}")) is not None:
            return resp
        if busy := jobs.busy("mcp", name):
            return j({"ok": True, "job": view(busy)})

        def write_secrets():
            from hermes_cli.config import save_env_value, validate_env_var_name_for_write
            for s in specs:
                v = env.get(s.name)
                if v and getattr(s, "secret", True):
                    validate_env_var_name_for_write(s.name)
                    save_env_value(s.name, v)

        if entry.install is not None:  # git clone + bootstrap: the CLI path, in the background, like the dashboard
            try:
                await asyncio.to_thread(write_secrets)
            except Exception as e:
                return bad(str(e))
            job = jobs.run_cli("mcp", "install", name, "", ["mcp", "install", name],
                               lambda: None if MC.is_installed(name) else "Not in config after the install. See the log.")
            return j({"ok": True, "job": view(job)})

        def install():
            write_secrets()
            MC.install_entry(entry, enable=b.get("enable", True) is not False, preloaded_env=env or None)
            ok = MC.is_installed(name)
            return {"ok": ok, "error": "" if ok else "not in config after install", "post_install": entry.post_install or ""}
        job = jobs.run_call("mcp", "install", name, install)
        return j({"ok": True, "job": view(job)})

    async def h_mcp_uninstall(request):
        b = await body_of(request)
        name = str(b.get("name") or "").strip()
        if not _NAME.match(name):
            return bad("name is required")
        from hermes_cli import mcp_catalog as MC
        if MC.get_entry(name) is None or not MC.is_installed(name):
            return bad(f"'{name}' isn't an installed catalog MCP", 404)
        if (resp := need_sig(request, f"mcp:remove:{name}")) is not None:
            return resp
        job = jobs.run_call("mcp", "remove", name, lambda: {"ok": MC.uninstall_entry(name)})
        return j({"ok": True, "job": view(job)})

    # jobs
    async def h_job(request):
        job = jobs.jobs.get(request.match_info["jid"])
        return j({"job": view(job)}) if job else j({"error": "no such job"}, 404)

    async def h_jobs(request):
        return j({"jobs": [view(x, log=False) for x in sorted(jobs.jobs.values(), key=lambda x: -x["started"])]})

    r.add_get("/v1/hub/skills/browse", h_browse)
    r.add_get("/v1/hub/skills/search", h_search)
    r.add_get("/v1/hub/skills/preview", h_preview)
    r.add_get("/v1/hub/skills/scan", h_scan)
    r.add_get("/v1/hub/skills/installed", h_installed)
    r.add_get("/v1/hub/skills/updates", h_updates)
    r.add_post("/v1/hub/skills/install", h_skill_install)
    r.add_post("/v1/hub/skills/uninstall", h_skill_uninstall)
    r.add_post("/v1/hub/skills/update", h_skill_update)
    r.add_get("/v1/hub/plugins", h_plugins)
    r.add_post("/v1/hub/plugins/install", h_plugin_install)
    r.add_post("/v1/hub/plugins/update", h_plugin_update)
    r.add_post("/v1/hub/plugins/remove", h_plugin_remove)
    r.add_get("/v1/hub/mcp", h_mcp)
    r.add_post("/v1/hub/mcp/install", h_mcp_install)
    r.add_post("/v1/hub/mcp/uninstall", h_mcp_uninstall)
    r.add_get("/v1/hub/jobs", h_jobs)
    r.add_get("/v1/hub/jobs/{jid}", h_job)
    return jobs
