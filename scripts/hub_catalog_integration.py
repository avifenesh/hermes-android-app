"""Isolated Hub route regression checks. No gateway, live data or network calls.

Run with Hermes' Python: python scripts/hub_catalog_integration.py
Optional --market-source PATH loads an old market.py for regression proof.
Only external catalog/search/preview providers are substituted. Inventory,
profile resolution, HubLockFile and aiohttp route handlers are real.
This is not request-signature, installer, plugin or physical-phone coverage.
"""
import atexit
import os
from pathlib import Path
import shutil
import sys
import tempfile

if "hermes_constants" in sys.modules or "hermes_bootstrap" in sys.modules:
    raise RuntimeError("Run directly in a fresh Hermes Python process")
SCRATCH = Path.home() / ".cache/hub-discovery-fix"
SCRATCH.mkdir(parents=True, exist_ok=True)
_HOME = tempfile.TemporaryDirectory(prefix="hub-integration-", dir=SCRATCH)
atexit.register(shutil.rmtree, _HOME.name, ignore_errors=True)
os.environ["HERMES_HOME"] = _HOME.name  # FORCE, never inherit a gateway home
os.environ["HERMES_ENABLE_PROJECT_PLUGINS"] = "false"
os.environ["HERMES_DISABLE_LAZY_INSTALLS"] = "1"
assert Path(_HOME.name).resolve().is_relative_to(SCRATCH.resolve())
assert Path(_HOME.name).resolve() != (Path.home() / ".hermes").resolve()
sys.path.insert(0, str(Path.home() / ".hermes/hermes-agent"))
import hermes_bootstrap  # noqa: E402,F401
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))

import argparse
import asyncio
import importlib.util
import unittest
from unittest.mock import patch
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from mobile import market as M
from tools.skills_hub import HubLockFile
from tools.skills_hub_models import SkillMeta, SkillBundle

parser = argparse.ArgumentParser(add_help=False)
parser.add_argument("--market-source")
args, remaining = parser.parse_known_args()
if args.market_source:
    spec = importlib.util.spec_from_file_location("mobile.market_regression", args.market_source)
    assert spec is not None and spec.loader is not None
    M = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(M)


def meta(ident, source="official"):
    return SkillMeta(name=ident.rsplit("/", 1)[-1], description="Synthetic catalog fixture",
                     source=source, identifier=ident, trust_level="builtin" if source == "official" else "community")


class HubCatalogIntegration(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="case-", dir=_HOME.name)
        self.addCleanup(self.tmp.cleanup)
        self.home = Path(self.tmp.name)
        os.environ["HERMES_HOME"] = str(self.home)
        self.other = self.home / "profiles/other"
        self.other.mkdir(parents=True)
        (self.other / "config.yaml").write_text("model: {}\n")
        (self.home / "config.yaml").write_text(
            "skills:\n  disabled: [bundled]\n  create_dir: authored\n  external_dirs: [imported]\n")
        self.skill("skills/research/bundled", "bundled")
        self.skill("skills/manual", "manual")
        self.skill("authored/created", "created")
        self.skill("imported/imported", "imported")
        self.skill("skills/research/bundled/references/not-a-skill", "support")
        self.skill("skills/.archive/archived", "archived")
        self.skill("skills/.hub/quarantine/hidden", "hidden")
        self.skill("skills/managed", "managed")
        self.skill("skills/collision", "collision")
        self.skill("skills/other-only", "other-only", home=self.other)
        lock = HubLockFile(self.home / "skills/.hub/lock.json")
        for name, ident in (("managed", "owner/repo/skills/managed"),
                            ("collision", "owner/first/skills/collision")):
            lock.record_install(name=name, source="github", identifier=ident, trust_level="community",
                                scan_verdict="safe", skill_hash="fixture", install_path=name, files=["SKILL.md"])
        self.catalog = [meta("official/research/bundled"), meta("official/general/manual"),
                        meta("official/general/created"), meta("official/general/imported"),
                        meta("official/general/absent"), meta("official/general/other-only"),
                        meta("official/general/collision"), meta("owner/repo/skills/managed", "github"),
                        meta("owner/second/skills/collision", "github"),
                        meta("someone/else/skills/manual", "github")]
        catalog = self.catalog

        class Index:
            is_available = True

            def source_id(self):
                return "hermes-index"

            def search(self, query, limit=12):
                return catalog[:limit]

        def resolve(ident, router):
            m = next((m for m in catalog if m.identifier == ident), None)
            if m is None:
                return None, None, None
            return m, SkillBundle(name=m.name, files={"SKILL.md": "# Synthetic fixture"},
                                  source=m.source, identifier=m.identifier, trust_level=m.trust_level), None

        self.search_calls = []

        def search(router, **kwargs):
            self.search_calls.append(kwargs)
            source = kwargs["source_filter"]
            rows = [m for m in catalog if source == "all" or m.source == source]
            return rows, {"fixture": len(rows)}, []

        for target, value in (
            ("tools.skills_hub_official.OptionalSkillSource.list_local", lambda _: [m for m in catalog if m.source == "official"]),
            ("tools.skills_hub_search.create_source_router", lambda: [Index()]),
            ("tools.skills_hub_search.parallel_search_sources", search),
            ("hermes_cli.skills_hub._resolve_source_meta_and_bundle", resolve),
        ):
            p = patch(target, value)
            p.start()
            self.addCleanup(p.stop)
        app = web.Application()
        self.jobs = M.routes(app.router, lambda data, status=200: web.json_response(data, status=status))
        self.client = TestClient(TestServer(app))
        await self.client.start_server()
        self.addAsyncCleanup(self.client.close)

    def skill(self, rel, name, home=None):
        md = (home or self.home) / rel / "SKILL.md"
        md.parent.mkdir(parents=True, exist_ok=True)
        md.write_text(f"---\nname: {name}\ndescription: Synthetic local fixture\n---\n# Fixture\n")

    async def get(self, route="browse", **params):
        response = await self.client.get("/v1/hub/skills/" + route, params=params)
        data = await response.json()
        self.assertEqual(response.status, 200, data)
        return data

    async def test_browse_recognizes_unmanaged_and_disabled(self):
        data = await self.get()
        rows = {r["name"]: r for r in data["official"]}
        for name in ("bundled", "manual", "created", "imported"):
            with self.subTest(name=name):
                self.assertTrue(rows[name]["installed"])
        self.assertFalse(rows["absent"]["installed"])
        self.assertFalse(rows["other-only"]["installed"])
        self.assertFalse(rows["collision"]["installed"])

    async def test_inventory_is_separate_and_prunes_support_and_cache(self):
        data = await self.get()
        local = {r["name"]: r for r in data.get("local", [])}
        self.assertEqual(set(local), {"bundled", "manual", "created", "imported", "managed", "collision"})
        self.assertFalse(local["manual"]["managed"])
        self.assertIsNone(local["manual"].get("identifier"))
        self.assertTrue(local["managed"]["managed"])
        self.assertEqual(local["managed"]["identifier"], "owner/repo/skills/managed")
        self.assertEqual(set(data["installed"]), {"owner/repo/skills/managed", "owner/first/skills/collision"})

    async def test_shadowed_inventory_has_unique_identity(self):
        self.skill("imported/manual", "manual")
        data = await self.get()
        copies = [row for row in data["local"] if row["directory_name"] == "manual"]
        self.assertEqual(len(copies), 2)
        self.assertEqual(len({row["inventory_id"] for row in copies}), 2)
        again = await self.get()
        self.assertEqual([row["inventory_id"] for row in data["local"]],
                         [row["inventory_id"] for row in again["local"]])

    async def test_featured_and_search_share_installed_detection(self):
        browse = await self.get()
        search = await self.get("search", q="fixture", source="all")
        for rows in (browse["featured"], search["results"]):
            by_id = {r["identifier"]: r for r in rows}
            self.assertTrue(by_id["official/research/bundled"]["installed"])
            self.assertTrue(by_id["owner/repo/skills/managed"]["installed"])
            self.assertFalse(by_id["owner/second/skills/collision"]["installed"])
            self.assertFalse(by_id["someone/else/skills/manual"]["installed"])

    async def test_preview_unmanaged_blocks_duplicate_not_management(self):
        for ident in ("official/research/bundled", "official/general/manual"):
            with self.subTest(ident=ident):
                data = await self.get("preview", identifier=ident)
                self.assertTrue(data.get("installed_local"))
                self.assertIsNone(data["installed"])
                self.assertEqual(data["installed_name"], ident.rsplit("/", 1)[-1])

    async def test_preview_preserves_lock_object_and_no_community_basename_match(self):
        data = await self.get("preview", identifier="owner/repo/skills/managed")
        self.assertEqual(data["installed"]["name"], "managed")
        self.assertTrue(data.get("installed_local"))
        for ident in ("owner/second/skills/collision", "someone/else/skills/manual", "official/general/collision"):
            with self.subTest(ident=ident):
                data = await self.get("preview", identifier=ident)
                self.assertIsNone(data["installed"])
                self.assertFalse(data.get("installed_local"))

    async def test_selected_profile_isolation(self):
        data = await self.get(profile="other")
        rows = {r["name"]: r for r in data["official"]}
        self.assertTrue(rows["other-only"]["installed"])
        self.assertFalse(rows["bundled"]["installed"])
        self.assertFalse(data["installed"])
        self.assertEqual({r["name"] for r in data.get("local", [])}, {"other-only"})
        result = await self.get("search", profile="other", q="fixture")
        self.assertFalse(next(r for r in result["results"] if r["name"] == "managed")["installed"])
        preview = await self.get("preview", profile="other", identifier="official/research/bundled")
        self.assertFalse(preview.get("installed_local"))
        preview = await self.get("preview", profile="other", identifier="official/general/other-only")
        self.assertTrue(preview.get("installed_local"))

    async def test_named_gateway_can_read_default_and_own_profile(self):
        os.environ["HERMES_HOME"] = str(self.other)
        own = await self.get()
        default = await self.get(profile="default")
        self.assertEqual({r["name"] for r in own.get("local", [])}, {"other-only"})
        self.assertIn("manual", {r["name"] for r in default.get("local", [])})
        self.assertIn("owner/repo/skills/managed", default["installed"])
        self.assertEqual(os.environ["HERMES_HOME"], str(self.other))

    async def test_concurrent_profile_reads_do_not_mutate_home(self):
        a, b = await asyncio.gather(self.get(), self.get(profile="other"))
        self.assertIn("manual", {r["name"] for r in a.get("local", [])})
        self.assertNotIn("manual", {r["name"] for r in b.get("local", [])})
        self.assertEqual(os.environ["HERMES_HOME"], str(self.home))

    async def test_installed_endpoint_and_empty_search_expose_inventory(self):
        installed = await self.get("installed")
        empty = await self.get("search", q="")
        self.assertTrue(installed.get("local"))
        self.assertEqual(installed["local"], empty.get("local"))
        self.assertEqual(installed["installed"], empty["installed"])
        self.assertEqual(empty["results"], [])
        self.assertEqual(self.search_calls, [])

    async def test_unmanaged_update_remove_stay_rejected(self):
        for action in ("update", "uninstall"):
            response = await self.client.post("/v1/hub/skills/" + action, json={"name": "manual"})
            self.assertEqual(response.status, 404, await response.text())
        self.assertFalse(self.jobs.jobs)
        self.assertTrue((self.home / "skills/manual/SKILL.md").is_file())

    async def test_catalog_reads_do_not_write_inventory(self):
        def snapshot():
            return {p.relative_to(self.home).as_posix(): p.read_bytes()
                    for p in self.home.rglob("*") if p.is_file()}
        before = snapshot()
        await self.get()
        await self.get("search", q="fixture")
        await self.get("preview", identifier="official/research/bundled")
        await self.get("installed", profile="other")
        self.assertEqual(snapshot(), before)

    async def test_source_and_limit_forwarding_unknown_profile_and_preview(self):
        result = await self.get("search", q="fixture", source="github", limit="1")
        self.assertEqual(len(result["results"]), 1)
        self.assertEqual(result["results"][0]["source"], "github")
        self.assertEqual(self.search_calls[-1]["source_filter"], "github")
        response = await self.client.get("/v1/hub/skills/browse", params={"profile": "missing"})
        self.assertEqual(response.status, 404)
        response = await self.client.get("/v1/hub/skills/preview", params={"identifier": "official/no-such-skill"})
        self.assertEqual(response.status, 404)


if __name__ == "__main__":
    unittest.main(argv=[sys.argv[0], *remaining], verbosity=2)
