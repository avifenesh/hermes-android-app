"""Who is talking to whom. The app shows the agent's name and the server addresses the user by name; neither is
hardcoded. Order: MOBILE_AGENT_NAME / MOBILE_USER_NAME, then the agent's SOUL.md ("You are <Name>"), then defaults."""
from __future__ import annotations

import contextlib
import logging
import os
import re

from . import store as S


def _env(name: str) -> str:
    try:
        from gateway.platforms._shared import get_scoped_secret
        v = get_scoped_secret(name, "")
        if v:
            return str(v).strip()
    except Exception:
        pass
    return os.environ.get(name, "").strip()


def agent_name() -> str:
    v = _env("MOBILE_AGENT_NAME")
    if v:
        return v[:40]
    try:
        soul = (S.hermes_home() / "SOUL.md").read_text(errors="replace")[:4000]
        m = re.search(r"\bYou are ([A-Z][\w-]{1,30})(?: Agent)?\b", soul)
        if m:
            return m.group(1)
    except Exception:
        pass
    return "Hermes"


def user_name() -> str:
    return _env("MOBILE_USER_NAME")[:40]


def user_ref() -> str:
    """How prompts and hints refer to the user: their name, else 'the user'."""
    return user_name() or "the user"


def phone_cc() -> str:
    """Country code used to turn a local number (leading 0) into an international one for WhatsApp."""
    return re.sub(r"\D", "", _env("MOBILE_PHONE_CC"))


_warned = False


def launch_scope():
    """Bind the launch profile's runtime scope (HERMES_HOME override, secrets, terminal policy) for code that runs
    outside a gateway turn: API handlers and the plugin's background loops. With ``gateway.multiplex_profiles`` on,
    an unscoped secret read raises UnscopedSecretError (it broke /v1/models)."""
    global _warned
    try:
        from hermes_cli.config import get_process_hermes_home
        from tui_gateway.launch_profile_policy import launch_profile_runtime_scope
        return launch_profile_runtime_scope(get_process_hermes_home())
    except Exception as e:
        if not _warned:
            _warned = True
            logging.getLogger(__name__).warning("[mobile] launch profile scope unavailable: %s", e)
        return contextlib.nullcontext()
