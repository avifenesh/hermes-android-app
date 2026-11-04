"""Profile chats report one working span per busy period, kept alive through silent steps.

The phone (Play's run follow, the working dot) reads ``working:false`` as "the run ended". A profile child that is
silent for longer than the adapter's 14 s idle reaper (a slow tool or model step), or the gap between two queued
turns, must not produce it. Loads ``server/mobile/profiles.py`` without the gateway package; the child process is
a stub that sleeps silently and prints one stream-json result. Disposable HERMES_HOME; no gateway or live data.
"""
import asyncio
import importlib.util
import os
from pathlib import Path
import shutil
import sys
import tempfile
import time
import types
import unittest

ROOT = Path(__file__).resolve().parents[1]
HOME = tempfile.mkdtemp(prefix="profile-working-", dir=str(Path.home() / ".cache"))
os.environ["HERMES_HOME"] = HOME
assert Path(HOME).resolve() != (Path.home() / ".hermes").resolve()

package = types.ModuleType("mobile")
package.__path__ = [str(ROOT / "server/mobile")]
sys.modules.setdefault("mobile", package)


def _load(name):
    spec = importlib.util.spec_from_file_location(f"mobile.{name}", ROOT / f"server/mobile/{name}.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[f"mobile.{name}"] = module
    spec.loader.exec_module(module)
    return module


_load("store")
P = _load("profiles")

IDLE_S = 0.3  # the adapter reaper's 14 s idle limit, scaled down with the keepalive interval


class FakeStore:
    def __init__(self):
        self.seq = 0

    def add_message(self, cid, role, text, kind="text", meta=None):
        self.seq += 1
        return {"id": f"m{self.seq}", "chat_id": cid, "seq": self.seq, "role": role, "kind": kind, "content": text}

    def edit_message(self, mid, content=None, meta=None):
        self.seq += 1
        return {"id": mid, "seq": self.seq, "content": content}

    def get_chat(self, cid):
        return {"id": cid}


class FakeAdapter:
    def __init__(self):
        self._working = {}
        self.events = []
        self.store = FakeStore()

    def _emit(self, ev):
        if ev.get("type") == "working":
            self.events.append(("working", ev["working"], time.monotonic()))

    def _emit_msg(self, msg, push=None):
        pass

    def _push(self, *a):
        pass

    async def reap(self, stop):
        # adapter._reap_loop with the threshold scaled like the keepalive
        while not stop.is_set():
            await asyncio.sleep(IDLE_S / 6)
            now = time.time()
            for cid, w in list(self._working.items()):
                if now - w.get("last", w["since"]) > IDLE_S:
                    self._working.pop(cid, None)
                    self._emit({"type": "working", "chat_id": cid, "working": False})


def chats(ad, silent_s):
    pc = P.ProfileChats(ad)
    pc.KEEPALIVE_S = IDLE_S / 4
    child = f"import time, json; time.sleep({silent_s}); print(json.dumps({{'type': 'result', 'text': 'done'}}), flush=True)"
    pc._argv = lambda profile, text, resume, image=None: [sys.executable, "-c", child]
    pc._env = lambda profile: {"PATH": os.environ.get("PATH", ""), "HOME": HOME}
    pc._resume_id = lambda cid: ""
    pc.bind = lambda *a, **k: None
    return pc


async def drained(pc, cid):
    while pc.busy(cid):
        await asyncio.sleep(0.02)


class ProfileWorkingTests(unittest.TestCase):
    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(HOME, ignore_errors=True)

    def test_keepalive_is_well_inside_the_reaper_limit(self):
        self.assertLessEqual(P.ProfileChats.KEEPALIVE_S, 14 / 3)

    def test_silent_step_longer_than_the_idle_limit_stays_working(self):
        async def run():
            ad = FakeAdapter()
            stop = asyncio.Event()
            reaper = asyncio.create_task(ad.reap(stop))
            pc = chats(ad, silent_s=IDLE_S * 3)
            await pc.send("c1", "seller", "hello")
            await drained(pc, "c1")
            stop.set(); await reaper
            return ad.events
        events = asyncio.run(run())
        self.assertEqual([e[:2] for e in events], [("working", True), ("working", False)], events)

    def test_queued_turns_share_one_working_span(self):
        async def run():
            ad = FakeAdapter()
            stop = asyncio.Event()
            reaper = asyncio.create_task(ad.reap(stop))
            pc = chats(ad, silent_s=IDLE_S / 3)
            await pc.send("c1", "seller", "first")
            await pc.send("c1", "seller", "queued")
            await drained(pc, "c1")
            stop.set(); await reaper
            return ad.events
        events = asyncio.run(run())
        offs = [e for e in events if e[1] is False]
        self.assertEqual(len(offs), 1, events)
        self.assertEqual(events[-1][:2], ("working", False))


if __name__ == "__main__":
    unittest.main()
