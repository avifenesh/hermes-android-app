"""Skills HTTP catalog regression against the installed Hermes API.

Run directly with Hermes' managed Python. Uses a disposable home and real
skill discovery; no gateway, provider calls or live skill state are touched.
"""
import atexit
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

SCRATCH = Path.home() / ".cache/mobile-skills-fix"
SCRATCH.mkdir(parents=True, exist_ok=True)
_HOME = tempfile.TemporaryDirectory(prefix="skills-", dir=SCRATCH)
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


class SkillsCatalogIntegration(unittest.IsolatedAsyncioTestCase):
    async def test_catalog_includes_enabled_and_disabled_skills(self):
        home = Path(_HOME.name)
        home.joinpath("config.yaml").write_text(
            "skills:\n  disabled: [fixture-disabled]\n", encoding="utf-8"
        )
        for name in ("fixture-enabled", "fixture-disabled"):
            folder = home / "skills" / "fixtures" / name
            folder.mkdir(parents=True)
            folder.joinpath("SKILL.md").write_text(
                f"---\nname: {name}\ndescription: Synthetic catalog fixture.\n---\n\n# Fixture\n",
                encoding="utf-8",
            )
        app = web.Application()
        C.routes(app.router, None, lambda data, status=200: web.json_response(data, status=status))
        async with TestClient(TestServer(app)) as client:
            response = await client.get("/v1/skills")
            self.assertEqual(response.status, 200, await response.text())
            body = await response.json()
            rows = {row["name"]: row for row in body["skills"]}
            self.assertTrue(rows["fixture-enabled"]["enabled"])
            self.assertFalse(rows["fixture-disabled"]["enabled"])
            self.assertEqual(rows["fixture-enabled"]["description"], "Synthetic catalog fixture.")
            for name in ("fixture-enabled", "fixture-disabled"):
                self.assertIn("usage", rows[name])
                self.assertIn("provenance", rows[name])


if __name__ == "__main__":
    unittest.main(verbosity=2)
