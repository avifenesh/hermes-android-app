"""Isolated aiohttp model-control regressions; no live gateway or provider calls.

Run directly with Hermes' managed Python. Real Control command/state paths and
HTTP handlers run against a synthetic gateway handler. This is not phone QA.
"""
import atexit
import asyncio
import os
from pathlib import Path
import shutil
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

SCRATCH = Path.home() / ".cache/model-switch-fix"
SCRATCH.mkdir(parents=True, exist_ok=True)
_HOME = tempfile.TemporaryDirectory(prefix="routes-", dir=SCRATCH)
atexit.register(shutil.rmtree, _HOME.name, ignore_errors=True)
os.environ["HERMES_HOME"] = _HOME.name
os.environ["HERMES_ENABLE_PROJECT_PLUGINS"] = "false"
os.environ["HERMES_DISABLE_LAZY_INSTALLS"] = "1"
assert Path(_HOME.name).resolve() != (Path.home() / ".hermes").resolve()
sys.path.insert(0, str(Path.home() / ".hermes/hermes-agent"))
import hermes_bootstrap  # noqa: E402,F401
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from mobile import control as C
from tools import slash_confirm as SC


class ModelControlIntegration(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.default = {"model": "fake-a", "provider": "fakelab"}
        self.override = None
        self.pending = None
        self.commands = []
        self.mode = "success"
        self.working = set()
        self.runner = SimpleNamespace(
            _session_key_for_source=lambda source: source.chat_id,
            _session_model_override=lambda sk: self.override,
            _resolve_session_reasoning_config=lambda **kw: {"effort": "medium"},
            _peek_session_state=lambda sk: None,
            _resolve_session_service_tier=lambda sk: None,
        )
        self.ad = SimpleNamespace(
            gateway_runner=self.runner, _working=self.working,
            store=SimpleNamespace(get_chat=lambda cid: {"title": "Synthetic"}),
            build_source=lambda **kw: SimpleNamespace(**kw),
            _message_handler=self.handle,
            _unwrap_ephemeral=lambda response: (response, False),
        )
        self.ctl = C.Control(self.ad)
        self.ctl._models = {}  # Do not warm the external provider catalog in a route test.
        self.saved_tier = None
        self.ctl._cfg = lambda: {"model": {"default": self.default["model"], "provider": self.default["provider"]}, "agent": {"service_tier": self.saved_tier}}
        p = patch.object(SC, "get_pending", lambda sk: self.pending)
        p.start()
        self.addCleanup(p.stop)
        app = web.Application()
        C.routes(app.router, self.ctl, lambda data, status=200: web.json_response(data, status=status))
        self.client = TestClient(TestServer(app))
        await self.client.start_server()
        self.addAsyncCleanup(self.client.close)

    async def handle(self, event):
        self.commands.append(event.text)
        if self.mode == "reject":
            return "Agent is running — wait or /stop first, then switch models."
        if self.mode == "silent":
            return ""
        if self.mode == "error":
            return "❌ Synthetic provider failure"
        if self.mode == "confirm":
            import time
            self.pending = {"command": "model", "created_at": time.time(), "confirm_id": "synthetic-confirm"}
            return "⚠ Confirm switching provider"
        if event.text.startswith("/model "):
            self.override = {"model": "real-model" if self.mode == "alias" else event.text.split()[1], "provider": "fakelab"}
            if "--global" in event.text and self.mode != "global-failed":
                self.default = dict(self.override)
                self.override = None
            from agent.i18n import t
            return t("gateway.model.switched", model=(self.override or self.default)["model"])
        if event.text.startswith("/fast "):
            from agent.i18n import t
            enabled = event.text.split()[1] == "on"
            if self.mode != "fast-noop":
                self.runner._resolve_session_service_tier = lambda **kw: "priority" if enabled else None
            label = t("gateway.fast.label_fast" if enabled else "gateway.fast.label_normal")
            if "--global" in event.text:
                self.saved_tier = "fast" if enabled else "normal"
                return t("gateway.fast.saved", label=label)
            return t("gateway.fast.session_only", label=label)
        return "✓ Applied"

    async def apply(self, **body):
        response = await self.client.post("/v1/chats/synthetic/model", json=body)
        self.assertEqual(response.status, 200, await response.text())
        return await response.json()

    async def test_busy_plain_text_rejection_is_not_success(self):
        self.mode = "reject"
        data = await self.apply(model="fake-b", provider="fakelab")
        self.assertFalse(data["ok"], data)
        self.assertIn("wait or /stop", data["error"])
        self.assertEqual(data["state"]["model"], "fake-a")

    async def test_same_route_rejection_is_not_success(self):
        self.mode = "reject"
        data = await self.apply(model="fake-a", provider="fakelab", reasoning="high", fast=True)
        self.assertFalse(data["ok"], data)
        self.assertIn("wait or /stop", data["error"])
        self.assertEqual(len(self.commands), 1)

    async def test_alias_receipt_matches_canonical_readback(self):
        self.mode = "alias"
        data = await self.apply(model="chosen", provider="fakelab", reasoning="high")
        self.assertTrue(data["ok"], data)
        self.assertEqual(data["state"]["model"], "real-model")
        self.assertEqual(self.commands[-1], "/reasoning high")

    async def test_busy_precheck_does_not_dispatch_or_change_options(self):
        self.working.add("synthetic")
        data = await self.apply(model="fake-b", provider="fakelab", reasoning="high", fast=True)
        self.assertFalse(data["ok"], data)
        self.assertEqual(self.commands, [])

    async def test_rejection_does_not_apply_remaining_options(self):
        self.mode = "reject"
        data = await self.apply(model="fake-b", provider="fakelab", reasoning="high", fast=True)
        self.assertFalse(data["ok"], data)
        self.assertEqual(len(self.commands), 1)

    async def test_silent_noop_is_not_success(self):
        self.mode = "silent"
        data = await self.apply(model="fake-b", provider="fakelab")
        self.assertFalse(data["ok"], data)
        self.assertTrue(data["error"])

    async def test_success_is_read_back_from_separate_get(self):
        data = await self.apply(model="fake-b", provider="fakelab")
        self.assertTrue(data["ok"], data)
        state = await (await self.client.get("/v1/chats/synthetic/model")).json()
        self.assertEqual(state["model"], "fake-b")
        self.assertEqual(state["provider"], "fakelab")
        self.assertEqual(state["scope"], "chat")

    async def test_provider_mismatch_is_not_success(self):
        data = await self.apply(model="fake-b", provider="otherlab")
        self.assertFalse(data["ok"], data)

    async def test_global_success_checks_default(self):
        data = await self.apply(model="fake-b", provider="fakelab", scope="global")
        self.assertTrue(data["ok"], data)
        self.assertEqual(data["state"]["default"]["model"], "fake-b")

    async def test_failed_global_persistence_is_not_success(self):
        self.mode = "global-failed"
        data = await self.apply(model="fake-b", provider="fakelab", scope="global")
        self.assertFalse(data["ok"], data)

    async def test_confirmation_stops_remaining_options(self):
        self.mode = "confirm"
        data = await self.apply(model="fake-b", provider="fakelab", reasoning="high", fast=True)
        self.assertEqual(data["confirm"]["id"], "synthetic-confirm")
        self.assertEqual(data["confirm"]["message"], "⚠ Confirm switching provider")
        self.assertEqual(len(self.commands), 1)
        self.assertEqual(data["state"]["model"], "fake-a")

    async def test_stale_confirmation_cannot_mask_rejection(self):
        import time
        self.pending = {"command": "model", "created_at": time.time(), "confirm_id": "stale"}
        self.mode = "reject"
        data = await self.apply(model="fake-b", provider="fakelab")
        self.assertFalse(data["ok"], data)
        self.assertIsNone(data["confirm"])
        self.assertIn("wait or /stop", data["error"])

    async def test_stale_confirmation_is_not_returned_for_reasoning(self):
        import time
        self.pending = {"command": "model", "created_at": time.time(), "confirm_id": "stale"}
        data = await self.apply(reasoning="high")
        self.assertTrue(data["ok"], data)
        self.assertIsNone(data["confirm"])

    async def test_provider_error_is_preserved(self):
        self.mode = "error"
        data = await self.apply(model="fake-b", provider="fakelab")
        self.assertFalse(data["ok"], data)
        self.assertEqual(data["error"], "❌ Synthetic provider failure")

    async def test_fast_capability_without_catalog(self):
        self.default = {"model": "gpt-6.1-sol", "provider": "openai-codex"}
        state = await (await self.client.get("/v1/chats/synthetic/model")).json()
        self.assertTrue(state["caps"].get("fast"), state)

    async def test_fast_capability_respects_route(self):
        self.default = {"model": "gpt-6.1-sol", "provider": "openrouter"}
        state = self.ctl.chat_state("synthetic")
        self.assertFalse(state["caps"].get("fast"), state)

    async def test_fast_capability_rejects_proxy_override(self):
        self.override = {"model": "gpt-6.1-sol", "provider": "openai", "base_url": "https://proxy.example/v1"}
        self.assertFalse(self.ctl.chat_state("synthetic")["caps"]["fast"])

    async def test_catalog_missing_capabilities_is_enriched(self):
        self.default = {"model": "gpt-6.1-sol", "provider": "openai-codex"}
        rows = [{"slug": "openai-codex", "name": "Codex", "models": ["gpt-6.1-sol"]},
                {"slug": "openrouter", "name": "Router", "models": ["gpt-6.1-sol"]}]
        with patch("hermes_cli.model_switch_providers.list_picker_providers", return_value=rows):
            models = self.ctl._build_models(False)
        self.assertTrue(models["providers"][0]["capabilities"]["gpt-6.1-sol"]["fast"])
        self.assertFalse(models["providers"][1]["capabilities"]["gpt-6.1-sol"]["fast"])

    async def test_fast_same_state_rejection_is_not_success(self):
        self.mode = "reject"
        data = await self.apply(fast=False)
        self.assertFalse(data["ok"], data)

    async def test_fast_uses_supported_effective_runtime_not_default(self):
        self.runner._resolve_session_agent_runtime = lambda **kw: ("gpt-6.1-sol", {"provider": "openai-codex", "base_url": "https://chatgpt.com/backend-api/codex"})
        self.assertTrue(self.ctl.chat_state("synthetic")["caps"]["fast"])

    async def test_fast_uses_unsupported_effective_runtime_not_catalog(self):
        self.default = {"model": "gpt-6.1-sol", "provider": "openai-codex"}
        self.runner._resolve_session_agent_runtime = lambda **kw: ("gpt-6.1-sol", {"provider": "openrouter"})
        self.assertFalse(self.ctl.chat_state("synthetic")["caps"]["fast"])

    async def test_fast_default_scope_is_forwarded(self):
        self.runner._resolve_session_service_tier = lambda **kw: "priority"
        data = await self.apply(fast=True, scope="global")
        self.assertTrue(data["ok"], data)
        self.assertEqual(self.commands, ["/fast on --global"])

    async def test_fast_noop_is_not_success(self):
        self.mode = "fast-noop"
        data = await self.apply(fast=True)
        self.assertFalse(data["ok"], data)
        self.assertIn("Fast mode", data["error"])

    async def test_fast_success_readback(self):
        self.runner._resolve_session_service_tier = lambda **kw: "priority"
        data = await self.apply(fast=True)
        self.assertTrue(data["ok"], data)
        self.assertTrue(data["state"]["fast"])

    async def test_reasoning_only_still_dispatches(self):
        data = await self.apply(reasoning="high")
        self.assertTrue(data["ok"], data)
        self.assertEqual(self.commands, ["/reasoning high"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
