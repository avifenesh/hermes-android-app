"""Disposable Hermes homes for the integration harnesses: each one its own Hermes root, outside ~/.hermes.

A home under ~/.hermes belongs to the user's real root (hermes_constants._hermes_root_of): profile listing,
the auth.json fallback, credential rotation and the runtime lock would all reach live state. A root of its
own has no dependency environment (Hermes keeps one per data root), so the harness relaunches once, before
any home exists, into the owner's committed environment: a venv interpreter keeps its own packages. The
relaunched process leases that dependency generation like a normal boot, so nothing collects it mid-run.

Stdlib only until enter() imports hermes_bootstrap. Usage, first thing in a harness:

    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from isolated_home import enter
    _HOME = enter("my-harness-")  # .name is the forced HERMES_HOME, .cleanup() removes it
"""
from __future__ import annotations

import atexit
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

CHECKOUT = Path.home() / ".hermes/hermes-agent"
LIVE = (Path.home() / ".hermes").resolve()
SCRATCH = Path.home() / ".cache/hermes-harness"
_RELAUNCHED = "HM_HARNESS_RELAUNCHED"
_MADE = "HM_HARNESS_HOME"  # the home this run made, so a re-exec after it adopts it instead of orphaning it
_PROBE = ("import sys; sys.path.insert(0, sys.argv[1]); from pathlib import Path;"
          " from pm.environments import committed_venv; print(committed_venv(Path(sys.argv[1])) or '')")


class Home:
    def __init__(self, name: str):
        self.name = name

    def cleanup(self) -> None:
        shutil.rmtree(self.name, ignore_errors=True)


def owner_environment() -> Path | None:
    """The dependency environment the owner's root committed, asked in an isolated child (no HERMES_HOME)."""
    env = {k: v for k, v in os.environ.items() if k != "HERMES_HOME"}
    out = subprocess.run([sys.executable, "-I", "-c", _PROBE, str(CHECKOUT)], env=env, capture_output=True,
                         text=True, timeout=60)
    venv = out.stdout.strip()
    return Path(venv) if venv and (Path(venv) / "bin/python").is_file() else None


def _relaunch() -> None:
    """Run in the owner's committed environment, whatever started the harness: the managed store Python
    (which refuses a root with no environment) or the legacy checkout venv (stale packages)."""
    venv = owner_environment()
    if venv is not None and Path(sys.prefix).resolve() == venv.resolve():
        return
    if os.environ.get(_RELAUNCHED) == "1":
        raise RuntimeError("relaunched harness is not running in Hermes' dependency environment")
    if venv is None:
        raise RuntimeError("no committed Hermes dependency environment; run `hermes` once or `hermes pm repair`")
    os.environ[_RELAUNCHED] = "1"
    python = str(venv / "bin/python")
    os.execv(python, [python, *sys.argv])


def _home_for(prefix: str) -> Home:
    """A fresh home, or the one this same run made before something re-executed it (exec skips exit handlers)."""
    made = os.environ.get(_MADE, "")
    prior = Path(made) if made and os.environ.get("HERMES_HOME") == made else None
    if prior is not None and prior.is_dir() and prior.parent.resolve() == SCRATCH.resolve() and prior.name.startswith(prefix):
        return Home(str(prior))
    home = Home(tempfile.mkdtemp(prefix=prefix, dir=SCRATCH))
    os.environ[_MADE] = home.name
    return home


def enter(prefix: str) -> Home:
    if "hermes_bootstrap" in sys.modules or "hermes_constants" in sys.modules:
        raise RuntimeError("Run this harness directly in a fresh Hermes Python process")
    _relaunch()
    SCRATCH.mkdir(parents=True, exist_ok=True)
    home = _home_for(prefix)
    # Before any Hermes import, so it runs after their shutdown hooks (which can recreate data dirs).
    atexit.register(home.cleanup)
    os.environ["HERMES_HOME"] = home.name  # FORCE, never inherit a gateway home
    os.environ["HERMES_ENABLE_PROJECT_PLUGINS"] = "false"
    # Never auto-update the checkout or re-exec bootstrap into another interpreter.
    os.environ["HERMES_DISABLE_LAZY_INSTALLS"] = "1"
    resolved = Path(home.name).resolve()
    assert resolved != LIVE and not resolved.is_relative_to(LIVE), resolved
    sys.path.insert(0, str(CHECKOUT))
    import hermes_bootstrap  # noqa: F401
    from hermes_constants import get_default_hermes_root
    root = Path(get_default_hermes_root()).resolve()
    assert root == resolved and root != LIVE, f"harness root is {root}, not its own home {resolved}"
    from hermes_cli.runtime_state import lease_generation
    lease_generation(Path(sys.prefix))  # held until exit, as a normal boot holds it
    # Leasing without the runtime lock: re-read the selection so a generation swapped meanwhile is refused.
    current = owner_environment()
    if current is None or current.resolve() != Path(sys.prefix).resolve():
        raise RuntimeError("the Hermes dependency environment changed during startup; run the harness again")
    return home
