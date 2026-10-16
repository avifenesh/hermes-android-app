"""Projects: the repos under ~/projects (most recently touched first), their git state, per-file diffs, and the
review actions the desktop's coding rail has (stage, commit, push, open PR).

Every git operation goes through ``hermes_cli.web_git`` (the dashboard's own implementation, with its timeouts,
porcelain parsing and gh handling), never a hand-rolled ``git`` call. Paths are confined to the projects root:
a repo id is a directory name, resolved and checked to stay inside it. Writes that leave the machine (push,
create PR) need the biometric approval signature, the same one approvals use.
"""
from __future__ import annotations

import asyncio
import logging
import os
import time
from pathlib import Path
from typing import Any, Dict, List, Optional

from . import sig as SG

logger = logging.getLogger(__name__)

ROOT = Path(os.environ.get("HM_PROJECTS_ROOT") or Path.home() / "projects").resolve()
_cache: Dict[str, Any] = {"t": 0.0, "v": None}


async def _body(request) -> Dict[str, Any]:
    """JSON body or {}. Not ``can_read_body``: the auth middleware already read the body to verify its signature,
    so the stream is at EOF and can_read_body is False even though request.read()/json() return the cached bytes."""
    if not request.body_exists:
        return {}
    try:
        b = await request.json()
        return b if isinstance(b, dict) else {}
    except Exception:
        return {}


def repo_path(rid: str) -> Optional[Path]:
    if not rid or "/" in rid or "\\" in rid or rid.startswith(".") or "\0" in rid:
        return None
    p = (ROOT / rid).resolve()
    if p.parent != ROOT or not (p / ".git").exists():
        return None
    return p


def _wg():
    from hermes_cli import web_git
    return web_git


def _mtime(p: Path) -> float:
    best = 0.0
    for f in (p / ".git" / "index", p / ".git" / "HEAD", p / ".git" / "FETCH_HEAD", p):
        try:
            best = max(best, f.stat().st_mtime)
        except OSError:
            pass
    return best


def list_repos(limit: int = 30) -> List[Dict[str, Any]]:
    now = time.time()
    if _cache["v"] is not None and now - _cache["t"] < 20:
        return _cache["v"]
    repos = []
    try:
        for d in ROOT.iterdir():
            if d.is_dir() and not d.name.startswith(".") and (d / ".git").exists():
                repos.append((d, _mtime(d)))
    except OSError:
        return []
    repos.sort(key=lambda x: -x[1])
    out = []
    wg = _wg()
    from concurrent.futures import ThreadPoolExecutor

    def stat(d):
        try:
            return wg.repo_status(str(d))
        except Exception as e:
            logger.debug("[mobile] repo_status %s: %s", d.name, e)
            return None
    with ThreadPoolExecutor(8) as ex:
        sts = list(ex.map(stat, [d for d, _ in repos[:limit]]))
    for (d, m), st in zip(repos[:limit], sts):
        st = st or {}
        out.append({"id": d.name, "path": str(d), "touched": m, "branch": st.get("branch") or "",
                    "ahead": st.get("ahead") or 0, "behind": st.get("behind") or 0, "changed": st.get("changed") or 0,
                    "added": st.get("added") or 0, "removed": st.get("removed") or 0,
                    "conflicted": st.get("conflicted") or 0})
    _cache.update(t=now, v=out)
    return out


def _signed(request, rid: str, action: str) -> bool:
    msg = f"git\n{rid}\n{action}\n{request.headers.get('X-HM-Ts', '')}".encode()
    return SG.approval_ok(request, msg)


def routes(r, j) -> None:
    async def h_list(request):
        return j({"root": str(ROOT), "repos": await asyncio.to_thread(list_repos)})

    async def h_status(request):
        p = repo_path(request.match_info["rid"])
        if not p:
            return j({"error": "unknown repo"}, 404)
        wg = _wg()
        st = await asyncio.to_thread(wg.repo_status, str(p))
        rev = await asyncio.to_thread(wg.review_list, str(p), "uncommitted", None)
        try:
            ship = await asyncio.wait_for(asyncio.to_thread(wg.review_ship_info, str(p)), 12)
        except Exception:
            ship = {}
        return j({"status": st, "review": rev, "ship": ship})

    async def h_diff(request):
        p = repo_path(request.match_info["rid"])
        f = request.query.get("file") or ""
        if not p or not f or ".." in Path(f).parts or Path(f).is_absolute():
            return j({"error": "bad file"}, 400)
        staged = request.query.get("staged") == "1"
        d = await asyncio.to_thread(_wg().review_diff, str(p), f, "uncommitted", None, staged)
        return j({"diff": (d or "")[:200_000]})

    async def h_action(request):
        rid, action = request.match_info["rid"], request.match_info["action"]
        p = repo_path(rid)
        if not p:
            return j({"error": "unknown repo"}, 404)
        body = await _body(request)
        wg = _wg()
        f = body.get("file") or None
        if f and (".." in Path(f).parts or Path(f).is_absolute()):
            return j({"error": "bad file"}, 400)
        if action in ("push", "create-pr", "commit-push", "revert") and not _signed(request, rid, action):
            return j({"error": "biometric signature required"}, 403)
        try:
            if action == "stage":
                res = await asyncio.to_thread(wg.review_stage, str(p), f)
            elif action == "unstage":
                res = await asyncio.to_thread(wg.review_unstage, str(p), f)
            elif action == "revert":
                res = await asyncio.to_thread(wg.review_revert, str(p), f)
            elif action in ("commit", "commit-push"):
                msg = str(body.get("message") or "").strip()
                if not msg:
                    return j({"error": "commit message required"}, 400)
                res = await asyncio.to_thread(wg.review_commit, str(p), msg, action == "commit-push")
            elif action == "push":
                res = await asyncio.to_thread(wg.review_push, str(p))
            elif action == "create-pr":
                res = await asyncio.to_thread(wg.review_create_pr, str(p))
            else:
                return j({"error": "bad action"}, 400)
        except Exception as e:
            return j({"error": str(getattr(e, "detail", "") or e)[:400]}, 400)
        _cache["v"] = None
        return j({"ok": True, "result": res})

    r.add_get("/v1/projects", h_list)
    r.add_get("/v1/projects/{rid}", h_status)
    r.add_get("/v1/projects/{rid}/diff", h_diff)
    r.add_post("/v1/projects/{rid}/{action}", h_action)
