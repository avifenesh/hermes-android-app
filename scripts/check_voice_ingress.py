#!/usr/bin/env python3
"""No-build wiring regression check (not a substitute for the debug device probe).

python3 scripts/check_voice_ingress.py
python3 scripts/check_voice_ingress.py --ref 245758a  # must FAIL on vulnerable source

Device probe, AFTER the parent builds/installs debug on an isolated, unlocked,
paired emulator with wake word OFF:
adb shell am instrument -w -e isolated yes dev.avifenesh.hermes/.VoiceIngressProbe
Expect VOICE INGRESS PASS and INSTRUMENTATION_CODE: -1. It tests authorization
without requesting speech capture. Check real wake detection, notification taps,
and cancel/success of secure unlock separately on device.
"""
from pathlib import Path
import argparse
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = "android/app/src/main/java/dev/avifenesh/hermes/"


def check(ref=None):
    def source(path):
        if ref:
            result = subprocess.run(["git", "show", f"{ref}:{path}"], cwd=ROOT, text=True, capture_output=True)
            return result.stdout if result.returncode == 0 else ""
        file = ROOT / path
        return file.read_text() if file.exists() else ""

    app = source(KOTLIN + "App.kt")
    wake = source(KOTLIN + "WakeListen.kt")
    voice = source(KOTLIN + "VoiceLaunch.kt")
    talk = source(KOTLIN + "Talk.kt")
    # Only our version-controlled manifest is parsed; this probe accepts no external XML input.
    manifest = ET.fromstring(source("android/app/src/main/AndroidManifest.xml"))
    android = "{http://schemas.android.com/apk/res/android}"
    activities = {a.get(android + "name"): a for a in manifest.findall("application/activity")}
    entry = activities.get(".VoiceLaunchActivity")
    checks = {
        "exported listen extra is not read": not re.search(r'getBooleanExtra\(\s*"listen"', app),
        "exported debug live-mic extra removed": 'getStringExtra("wwlive")' not in app,
        "every incoming intent clears stale voice state": bool(re.search(r'private fun handle\(.*?VoiceLaunch.consume\(i\).*?cancelVoiceEntry\(\).*?i \?: return', app, re.S)),
        "voice revocation never disposes unrelated Root/drafts": "disposeComposition" not in app and "createComposition" not in app and "VoiceSession.revoke()" in app,
        "scoped revocation stops queued start/send/recognition/TTS": all(s in talk for s in ["VoiceSession.observe { cancelWork() }", "startJob?.cancel()", "sendJob?.cancel()", "talker.afterSpeak = null; talker.stopListening(); talker.stopSpeaking()"]),
        "trusted launch requires capability, opt-in, unlock": "pendingVoiceStart = trustedVoice && VoiceLaunch.enabled() && VoiceLaunch.unlocked(this)" in app,
        "trusted launch waits for resumed focus": "!pendingVoiceStart || !resumed || !hasWindowFocus()" in app,
        "backgrounding revokes wake capture": "if (pendingVoiceStart || VoiceSession.gate.current?.wake == true) cancelVoiceEntry()" in app,
        "nonexported activity, no intent filters": entry is not None and entry.get(android + "exported") == "false" and not entry.findall("intent-filter"),
        "wake detector uses private entry": "val i = VoiceLaunch.wakeIntent(this)" in wake and '.putExtra("listen", true)' not in wake,
        "notification remains immutable and one-shot": "PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE" in wake,
        "notification has Android 15 creator BAL opt-in": "setPendingIntentCreatorBackgroundActivityStartMode" in wake,
        "unlock callback gates handoff": "requestDismissKeyguard" in voice and "VoiceLaunch.unlocked(this)" in voice and "generation == VoiceLaunch.gate.generation" in voice,
        "capability is random, epoch-bound, one-use": all(s in voice for s in ["UUID.randomUUID()", "epoch == current", "invalidate() //", "removeExtra(TOKEN)"]),
        "no arbitrary handoff/notification authorization timeout": "15_000" not in voice and "setTimeoutAfter" not in wake,
        "private unlock retains original machine identity": "epoch == Api.epoch.capture()" in voice and "VoiceLaunch.handoff(this, epoch)" in voice,
        "pending start rechecks its original session ticket": "VoiceSession.valid(pendingVoiceTicket)" in app and "VoiceSession.begin(wake = true, epoch = origin)" in app,
        "screen-off revokes wake authority even with showWhenLocked": "Intent.ACTION_SCREEN_OFF" in voice and "VoiceSession.revokeWake()" in voice,
        "wake focus loss revokes without disrupting manual Talk": "!hasFocus && VoiceSession.gate.current?.wake == true" in app,
        "session tickets retain generation and epoch": "VoiceTicket(val generation: Long, val epoch: Long, val wake: Boolean)" in voice and "ticket == current && ticket.epoch == epoch" in voice,
        "opt-out revokes rather than temporarily suppressing": 'key == "wakeword" && !Prefs.wakeWord' in voice and "VoiceSession.revokeWake()" in voice,
        "machine preference/reset changes stop active voice": '"base", "device", "token", "e_token"' in voice and "Wake.generation.collect" in talk,
        "queued mic handoff cannot mint fresh authority": "continueListening(queued)" in talk and "t != ticket || !valid(t)" in talk,
        "permission callback bound to its original ticket": "requested == ticket && valid(requested)" in talk and "if (permissionTicket != null) return" in talk,
        "afterSpeak continues only its original ticket": "speakingTicket == ticket && valid(speakingTicket)" in talk and "continueListening(speakingTicket)" in talk,
        "recognizer fallback and callbacks have operation fence": "main.post { if (current()) listen() }" in talk and "generation == listenGeneration && allowed()" in talk and "listenGeneration++" in talk,
        "TTS completion and network playback have operation fence": "speechCurrent(id)" in talk and "generation != speechGeneration || !allowed()" in talk and "speechGeneration++" in talk,
        "async send checks identity after creating chat": bool(re.search(r'val id = cid \?: Hub.newChat\("Talk"\).*?if \(!valid\(origin\)\) return@launch.*?Hub.send', talk, re.S)),
        "explicit unlocked UI tap can begin manual Talk": "ticket = VoiceSession.begin(wake = false)" in talk and "!VoiceLaunch.unlocked(ctx) || !act.hasWindowFocus()" in talk,
        "queued foreground-service effects require active authority": 'val active = valid(ticket) && state in setOf(' in talk,
        "detector result is rechecked on main thread": "post { if (origin == Api.epoch.capture()) hit() }" in wake,

    }
    for name, ok in checks.items():
        print(f"{'PASS' if ok else 'FAIL'} {name}")
    return all(checks.values())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", help="Read source from a git ref instead of the worktree")
    args = parser.parse_args()
    sys.exit(0 if check(args.ref) else 1)
