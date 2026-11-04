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
        self.texts = []

    def add_message(self, cid, role, text, kind="text", meta=None):
        self.seq += 1
        self.texts.append(text)
        return {"id": f"m{self.seq}", "chat_id": cid, "seq": self.seq, "role": role, "kind": kind, "content": text}

    def edit_message(self, mid, content=None, meta=None):
        self.seq += 1
        self.texts.append(content)
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


def chats(ad, silent_s, child=None):
    pc = P.ProfileChats(ad)
    pc.KEEPALIVE_S = IDLE_S / 4
    child = child or f"import time, json; time.sleep({silent_s}); print(json.dumps({{'type': 'result', 'text': 'done'}}), flush=True)"
    pc._argv = lambda profile, text, resume, image=None: [sys.executable, "-c", child]
    pc._env = lambda profile: {"PATH": os.environ.get("PATH", ""), "HOME": HOME}
    pc._resume_id = lambda cid: ""
    pc.bind = lambda *a, **k: None
    return pc


async def drained(pc, cid):
    while pc.busy(cid):
        await asyncio.sleep(0.02)


def profile_config(name, config, env=None):
    home = Path(HOME) / "profiles" / name
    home.mkdir(parents=True, exist_ok=True)
    (home / "config.yaml").write_text(config)
    if env is not None:
        (home / ".env").write_text(env)


class launch_timeout:
    """The gateway process's own HERMES_AGENT_TIMEOUT, bridged from the launch profile's config."""
    def __init__(self, value):
        self.value = value

    def __enter__(self):
        self.previous = os.environ.get("HERMES_AGENT_TIMEOUT")
        os.environ["HERMES_AGENT_TIMEOUT"] = self.value

    def __exit__(self, *exc):
        if self.previous is None:
            os.environ.pop("HERMES_AGENT_TIMEOUT", None)
        else:
            os.environ["HERMES_AGENT_TIMEOUT"] = self.previous


def alive(pid):
    try:
        state = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()[0]
    except (OSError, IndexError):
        return False
    return state != "Z"


async def run_child(child, profile, grace=None):
    ad = FakeAdapter()
    stop = asyncio.Event()
    reaper = asyncio.create_task(ad.reap(stop))
    pc = chats(ad, silent_s=0, child=child)
    if grace is not None:
        pc.TERM_GRACE_S = grace
    await pc.send("c1", profile, "hello")
    try:
        await asyncio.wait_for(drained(pc, "c1"), timeout=8)
    finally:
        for proc in list(pc.procs.values()):
            if proc.returncode is None:
                proc.kill()
        stop.set()
        await reaper
    return ad, pc


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

    def test_a_hung_child_ends_after_its_profiles_inactivity_limit(self):
        # Core stops a gateway turn after agent.gateway_timeout without activity; a profile child that prints nothing
        # for its own profile's limit is stopped the same way, so the chat cannot show working forever. The gateway's
        # HERMES_AGENT_TIMEOUT belongs to the launch profile and must not decide it.
        profile_config("hung", "agent:\n  gateway_timeout: %s\n" % (IDLE_S * 3))
        hung = "import time, json; print(json.dumps({'type': 'text', 'text': 'thinking'}), flush=True); time.sleep(120)"
        with launch_timeout("600"):
            ad, pc = asyncio.run(run_child(hung, "hung"))
        self.assertEqual([e[:2] for e in ad.events], [("working", True), ("working", False)], ad.events)
        self.assertNotIn("c1", ad._working)
        self.assertFalse(pc.procs)
        self.assertTrue(any("no output" in (t or "") for t in ad.store.texts), ad.store.texts)

    def test_an_open_tool_counts_as_activity(self):
        # Core heartbeats while a tool runs; stream-json only says tool_use at the start and tool_result at the end.
        profile_config("tools", "agent:\n  gateway_timeout: %s\n" % (IDLE_S * 2))
        child = ("import time, json\n"
                 "say = lambda **e: print(json.dumps(e), flush=True)\n"
                 "say(type='tool_use', name='delegate_task', tool_call_id='t1')\n"
                 "time.sleep(%s)\n"
                 "say(type='tool_result', name='delegate_task', tool_call_id='t1', output='ok')\n"
                 "say(type='result', text='done')\n") % (IDLE_S * 5)
        with launch_timeout(str(IDLE_S * 2)):
            ad, pc = asyncio.run(run_child(child, "tools"))
        self.assertEqual([e[:2] for e in ad.events], [("working", True), ("working", False)], ad.events)
        self.assertFalse(any("no output" in (t or "") for t in ad.store.texts), ad.store.texts)
        self.assertIn("done", ad.store.texts)

    def test_a_hung_child_is_stopped_with_its_tool_processes(self):
        profile_config("group", "agent:\n  gateway_timeout: %s\n" % (IDLE_S * 2))
        pid_file_reset = Path(HOME) / "grandchild.pid"
        pid_file_reset.unlink(missing_ok=True)
        pid_file = Path(HOME) / "grandchild.pid"
        child = ("import json, signal, subprocess, sys, time\n"
                 "g = subprocess.Popen([sys.executable, '-c', 'import signal, time; signal.signal(signal.SIGTERM, signal.SIG_IGN); time.sleep(120)'])\n"
                 "open(%r, 'w').write(str(g.pid))\n"
                 "print(json.dumps({'type': 'tool_use', 'name': 'terminal', 'tool_call_id': 't1'}), flush=True)\n"
                 "print(json.dumps({'type': 'tool_result', 'name': 'terminal', 'tool_call_id': 't1'}), flush=True)\n"
                 "time.sleep(120)\n") % str(pid_file)
        grandchild = None
        try:
            ad, pc = asyncio.run(run_child(child, "group", grace=IDLE_S))
            grandchild = int(pid_file.read_text())
            deadline = time.monotonic() + 5
            while alive(grandchild) and time.monotonic() < deadline:
                time.sleep(0.05)
            self.assertFalse(alive(grandchild), "a tool process the child started outlived the stop")
            self.assertFalse(pc.procs)
        finally:
            if grandchild is None and pid_file.exists():
                grandchild = int(pid_file.read_text())
            if grandchild and alive(grandchild):
                os.kill(grandchild, 9)

    def test_inactivity_limit_is_the_target_profiles(self):
        profile_config("cfg", "agent:\n  gateway_timeout: 600\n")
        profile_config("zero", "agent:\n  gateway_timeout: 0\n")
        profile_config("envonly", "model: x\n", env="HERMES_AGENT_TIMEOUT=120\n")
        profile_config("plain", "model: x\n")
        profile_config("junk", "agent:\n  gateway_timeout: soon\n")
        pc = P.ProfileChats(FakeAdapter())
        with launch_timeout("5"):
            for profile, expected in (("cfg", 600.0), ("zero", None), ("envonly", 120.0), ("plain", 1800.0), ("junk", 1800.0)):
                with self.subTest(profile=profile):
                    self.assertEqual(pc._idle_limit(profile), expected)

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
