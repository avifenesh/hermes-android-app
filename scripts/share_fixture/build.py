"""Build an emulator-only, separate-UID share sender without adding an app module.

Usage: JAVA_HOME=/path/to/jdk21 python3 scripts/share_fixture/build.py OUTPUT_DIR
Requires installed Android SDK build-tools/platforms. No server or device is touched.
"""
import os
import subprocess
import sys
import zipfile
from pathlib import Path

source = Path(__file__).resolve().parent
out = Path(sys.argv[1]).resolve()
out.mkdir(parents=True, exist_ok=True)
sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Android/Sdk"))
java = Path(os.environ["JAVA_HOME"]) / "bin"
tools = max((sdk / "build-tools").iterdir(), key=lambda p: tuple(int(x) for x in p.name.split(".") if x.isdigit()))
platforms = [p for p in (sdk / "platforms").glob("android-*/android.jar")
             if p.parent.name.removeprefix("android-").replace(".", "").isdigit()]
platform = max(platforms, key=lambda p: tuple(map(int, p.parent.name.removeprefix("android-").split("."))))
classes, dex = out / "classes", out / "dex"
classes.mkdir(exist_ok=True)
dex.mkdir(exist_ok=True)

def run(*args):
    subprocess.run([str(a) for a in args], check=True, timeout=180)

run(java / "javac", "--release", "8", "-classpath", platform, "-d", classes, *source.glob("*.java"))
run(tools / "d8", "--lib", platform, "--min-api", "31", "--output", dex, *classes.rglob("*.class"))
unsigned, aligned, apk = out / "unsigned.apk", out / "aligned.apk", out / "share-fixture.apk"
run(tools / "aapt2", "link", "-I", platform, "--manifest", source / "AndroidManifest.xml", "-o", unsigned)
with zipfile.ZipFile(unsigned, "a") as archive:
    for f in dex.glob("*.dex"):
        archive.write(f, f.name)
run(tools / "zipalign", "-f", "4", unsigned, aligned)
key = out / "fixture.jks"
if not key.exists():
    # Public test-only password; this ephemeral key authenticates no user or server.
    run(java / "keytool", "-genkeypair", "-keystore", key, "-storepass", "android", "-keypass", "android",
        "-alias", "fixture", "-dname", "CN=Hermes Share Fixture", "-keyalg", "RSA", "-noprompt")
run(tools / "apksigner", "sign", "--ks", key, "--ks-pass", "pass:android", "--out", apk, aligned)
run(tools / "apksigner", "verify", apk)
print(apk)
