#!/usr/bin/env python3
"""CPU-only wake source contracts, not native inference or device-quality evidence."""
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "android/app"
JAVA = APP / "src/main/java/dev/avifenesh/hermes"
CPP = APP / "src/main/cpp/microwakeword"
PINS = {
    "kissfft": ("7bce4153c6bc8aba2db0e889e576f9d00505cbe1", "7ad1124648a46977b16ddde03bf243bcd52fc452516c57016584ab4b4f2baadc"),
    "tflite_micro": ("2747abd5c82a95fb1624106a946fc671c31f16e8", "6ac0c8ef35f267cf3baf05dc094d4fd158864d4cc830d490b752994e58add8c8"),
    "flatbuffers": ("0100f6a5779831fa7a651e4b67ef389a8752bd9b", "85db3520acc4010b21984e2fb5ead3ec0d2c48df8009b614cb73562a82846554"),
    "gemmlowp": ("fda83bdc38b118cc6b56753bd540caa49e570745", "0f990732a0d541be514dfc5c1c45969626e9f349faa851baec01b9a9dcb4ae4b"),
    "ruy": ("54774a7a2cf85963777289193629d4bd42de4a59", "91993e7eb2aa56e62e9f4abc1158b2b46333574aa81f1a7431b06df80a42b7fa"),
}


def check():
    assets = APP / "src/main/assets/microwakeword"
    hashes = {
        "hey_jarvis.tflite": "21a7976add39ee24ec96c63d96b7aaa18e24d1d9824b963e451da8feb4b78b77",
        "hey_jarvis.json": "b153867d818675d8abcc9dace474afe7f83551ae0d5a9b1d71a98681320185af",
    }
    for name, expected in hashes.items():
        assert hashlib.sha256((assets / name).read_bytes()).hexdigest() == expected, name
    assert not list((APP / "src").rglob("*.onnx")), "Old ONNX assets remain"
    config = json.loads((assets / "hey_jarvis.json").read_text())["micro"]
    assert (config["probability_cutoff"], config["feature_step_size"], config["sliding_window_size"]) == (0.97, 10, 5)
    gradle = (APP / "build.gradle.kts").read_text()
    assert "onnxruntime" not in gradle
    assert 'ndkVersion = "29.0.14206865"' in gradle
    assert 'minSdk = 31' in gradle and 'targetSdk = 36' in gradle
    assert all(f'"{abi}"' in gradle for abi in ("arm64-v8a", "armeabi-v7a", "x86", "x86_64"))
    cmake = (CPP / "CMakeLists.txt").read_text()
    declarations = re.findall(r"FetchContent_Declare\(\s*(\w+)\s+URL\s+(\S+).*?URL_HASH SHA256=(\w+)\s*\)", cmake, re.S)
    assert len(declarations) == len(PINS)
    for name, url, digest in declarations:
        commit, expected = PINS[name]
        assert url.endswith(f"/{commit}.tar.gz") and digest == expected, name
    assert 'HERMES_WAKE_REFERENCE_TESTS AND CMAKE_BUILD_TYPE STREQUAL "Debug"' in cmake
    assert "max-page-size=16384" in cmake
    ref = APP / "src/androidTest/cpp/reference"
    for name in ("MicroFrontendWrapper.cpp", "MicroFrontendWrapper.h", "Logging.h"):
        assert (CPP / name).read_bytes() == (ref / name).read_bytes(), name
    wake = (JAVA / "WakeWord.kt").read_text()
    binding = (JAVA / "wake/MicroWakeWord.kt").read_text()
    assert "nativeReset" not in binding.split("private companion object")[1]
    assert "engine?.close()" in wake and "engine = createEngine()" in wake
    assert 'const val MODEL_ID = "mww_hey_jarvis_v2"' in wake
    assert 'const val DEFAULT_CUTOFF = 0.97f' in wake and 'const val WINDOW = 5' in wake
    assert "require(samples.size == 160)" in binding
    jni = (CPP / "MicroWakeWord_jni.cpp").read_text()
    assert 'FindClass("dev/avifenesh/hermes/wake/MicroWakeWord")' in jni
    assert "nativeReset" not in jni
    listen = (JAVA / "WakeListen.kt").read_text()
    assert "SystemClock.elapsedRealtime()" in listen and "now + 2_000L" in listen
    assert "origin != Api.epoch.capture()" in listen and "streamOrigin != origin" in listen
    assert "if (origin == Api.epoch.capture()) hit()" in listen
    assert "WakeListen.score" not in listen and "s >= Prefs.wakeThreshold" not in listen
    for path in (JAVA / "WakeWord.kt", JAVA / "WakeListen.kt", JAVA / "App.kt"):
        assert "oww/" not in path.read_text(), path
    prefs = (JAVA / "WakePreferences.kt").read_text()
    assert '.remove("wakethr")' in prefs and 'putBoolean("wakeword"' not in prefs
    print(json.dumps({"result": "PASS", "scope": "source contracts only", "model_hashes": hashes, "native_archive_pins": len(declarations)}, indent=2))


if __name__ == "__main__":
    check()
