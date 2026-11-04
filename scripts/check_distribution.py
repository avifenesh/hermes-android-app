#!/usr/bin/env python3
"""Fail-closed flavor boundary checks. No build, network, install or key access.

Source: python3 scripts/check_distribution.py
Artifact: python3 scripts/check_distribution.py --apk PATH --flavor play \
  --expected-cert-sha256 APPROVED_CERT_SHA256
Run on full and Play release APKs, including bundletool-generated universal APKs.
APK verification needs SDK apkanalyzer/apksigner on PATH or under ANDROID_HOME.
This is a focused boundary guard, not a policy, license or runtime certification.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
# Trusted local source XML and XML emitted by the installed SDK analyzer only.
# No network XML or DTD/entity resolution is used.
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "android/app/src"
A = "{http://schemas.android.com/apk/res/android}"
T = "{http://schemas.android.com/tools}"
PACKAGE = "dev.avifenesh.hermes"
# Kotlin namespace of the app's components in both flavors (the Play application ID adds ".play", the classes do not).
NAMESPACE = "dev.avifenesh.hermes"
FULL_ONLY_PERMISSIONS = {
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
    "android.permission.USE_EXACT_ALARM",
    # Play captures through the camera app (ACTION_IMAGE_CAPTURE), which needs no grant when CAMERA is undeclared.
    "android.permission.CAMERA",
    # Play's alarm sound runs as systemExempted instead of mediaPlayback.
    "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
}
PLAY_ONLY_PERMISSIONS = {"android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED"}
# android:foregroundServiceType flag values (android.content.pm.ServiceInfo). apkanalyzer prints them as hex.
FGS_BITS = {
    "dataSync": 0x1, "mediaPlayback": 0x2, "phoneCall": 0x4, "location": 0x8, "connectedDevice": 0x10,
    "mediaProjection": 0x20, "camera": 0x40, "microphone": 0x80, "health": 0x100, "remoteMessaging": 0x200,
    "systemExempted": 0x400, "shortService": 0x800, "fileManagement": 0x1000, "mediaProcessing": 0x2000,
    "specialUse": 0x40000000,
}
# Every typed foreground service in the merged manifest, by class name under NAMESPACE. Any other typed service,
# including a library class with the same short name, fails.
FGS_TYPES = {
    "full": {"LiveService": "dataSync", "WakeService": "mediaPlayback", "TalkService": "microphone", "WakeListenService": "microphone"},
    "play": {"LiveService": "dataSync", "WakeService": "systemExempted", "TalkService": "microphone", "WakeListenService": "microphone"},
}
FORBIDDEN_SOURCE = (
    "import android.accessibilityservice.", "/v1/phone/", "PhoneControlPanel",
    "ACTION_ACCESSIBILITY_SETTINGS", "ACTION_MANAGE_UNKNOWN_APP_SOURCES",
    "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "canRequestPackageInstalls",
    "PackageInstaller", "dispatchGesture(",
    # The always-on background connection preference and the camera grant live in full sources only.
    "Prefs.live", "Manifest.permission.CAMERA",
)
FORBIDDEN_DEX_STRINGS = (
    "/v1/phone/", "android.settings.ACCESSIBILITY_SETTINGS",
    "android.settings.MANAGE_UNKNOWN_APP_SOURCES",
    "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
    "Allow agent control", "Allow phone control", "PhoneControlPanel",
    "Landroid/accessibilityservice/AccessibilityService;",
    "Landroid/accessibilityservice/GestureDescription;",
    "Stay connected in background",
)
# Play-only safeguard: the first-run AI-content notice, a Play string resource.
PLAY_RESOURCE_TEXT = ("About AI replies",)
# Phone-control and always-on connection UI text lives in full-only string resources; Play's resource table must
# not carry it either.
FORBIDDEN_RESOURCE_TEXT = ("Allow agent control", "Allow phone control", "Stay connected in background")
FULL_CLASSES = ("PhoneService", "PhoneStopReceiver", "PhoneNotice", "PhoneControlKt")
DEBUG_CLASSES = ("PhoneProbeActivity", "UiAuditActivity", "VoiceIngressProbe",
                 "MarkdownProbeActivity", "FleetProbeActivity", "ReleaseLinkageProbe",
                 "ImageMemoryTest", "TerminalUnicodeWidthTest",
                 "BatteryPollingActivity", "BatteryPollingProbe",
                 "WakeIsolationProbeActivity", "MachineStorageProbeActivity",
                 "DownloadIntegrityProbeActivity", "RedirectIsolationProbeActivity",
                 "PublisherReportProbeActivity", "PublisherReportsTest", "PublisherReportFenceTest",
                 "VoiceProbeActivity", "RoomProbeActivity")
# Debug-only fixture payload (the voice probe's bundled clip) lives under this asset folder.
DEBUG_ASSET_DIR = "assets/probe/"


def require(ok: bool, message: str) -> None:
    if not ok:
        raise ValueError(message)


def fgs_bits(value: str) -> int:
    """foregroundServiceType as written in source XML (names joined by |) or printed by apkanalyzer (hex/decimal)."""
    value = value.strip()
    if re.fullmatch(r"0x[0-9a-fA-F]+", value):
        return int(value, 16)
    if value.isdigit():
        return int(value)
    bits = 0
    for name in value.split("|"):
        require(name in FGS_BITS, f"unknown foreground service type: {name}")
        bits |= FGS_BITS[name]
    return bits


def component(name: str) -> str:
    """Fully qualified component name; source manifests may use the ".Class" shorthand."""
    return NAMESPACE + name if name.startswith(".") else name


def foreground_services(app: ET.Element) -> dict:
    names = [component(s.get(A + "name", "")) for s in app.findall("service")]
    duplicates = sorted({n for n in names if names.count(n) > 1})
    require(not duplicates, f"duplicate service declarations: {duplicates}")
    found = {}
    for service in app.findall("service"):
        kind = service.get(A + "foregroundServiceType")
        if kind is not None:
            found[component(service.get(A + "name", ""))] = fgs_bits(kind)
    return found


def expected_foreground_services(flavor: str) -> dict:
    return {f"{NAMESPACE}.{name}": fgs_bits(kind) for name, kind in FGS_TYPES[flavor].items()}


def source_check(root: Path = ROOT) -> dict:
    src = root / "android/app/src"
    gradle = (root / "android/app/build.gradle.kts").read_text()
    require('create("full")' in gradle and 'create("play")' in gradle, "missing product flavors")
    require('applicationId = "dev.avifenesh.hermes"' in gradle, "full identity changed")
    require('applicationIdSuffix = ".play"' in gradle, "Play coexistence suffix missing")
    require('dependsOn(verifyReleaseSigning)' in gradle and 'check(hasReleaseKey)' in gradle,
            "release signing gate missing")
    require('else null' in gradle and 'signingConfigs.getByName("debug")' not in gradle,
            "release must not fall back to debug signing")
    # Standard AGP source sets only; remapping must be reviewed and this checker updated.
    require('sourceSets' not in gradle, "custom sourceSets need explicit boundary review")
    count = 0
    for source_set in ("main", "play", "debug", "playDebug", "release", "playRelease"):
        for p in (src / source_set).rglob("*.kt"):
            text = p.read_text()
            for token in FORBIDDEN_SOURCE:
                require(token not in text, f"Play source violation: {p.relative_to(root)}: {token}")
            count += 1
        for p in (src / source_set / "res").rglob("*"):
            if p.is_file() and p.suffix == ".xml":
                text = p.read_text()
                require("phone_service" not in text and "phone_control_" not in text, f"full-only resource in Play: {p}")
    for source_set in ("main", "full", "play", "release", "fullRelease", "playRelease"):
        require(not (src / source_set / DEBUG_ASSET_DIR).exists(), f"debug fixture assets outside debug: {source_set}")
    for name in ("PhoneService.kt", "PhoneControl.kt"):
        require((src / "full/java/dev/avifenesh/hermes" / name).is_file(), f"full implementation lost: {name}")
    require((src / "full/res/xml/phone_service.xml").is_file(), "full service resource lost")
    require((src / "fullDebug/java/dev/avifenesh/hermes/PhoneProbeActivity.kt").is_file(), "full debug fixture lost")
    play = ET.parse(src / "play/AndroidManifest.xml").getroot()
    permissions = {p.get(A + "name", ""): p for p in play.findall("uses-permission")}
    exact = permissions.get("android.permission.SCHEDULE_EXACT_ALARM")
    require(exact is not None and exact.get(A + "maxSdkVersion") is None,
            "Play exact-alarm special access must cover every supported API")
    for permission in FULL_ONLY_PERMISSIONS:
        require(permission in permissions and permissions[permission].get(T + "node") == "remove",
                f"missing Play defense-in-depth permission removal: {permission}")
    for permission in PLAY_ONLY_PERMISSIONS:
        require(permission in permissions and permissions[permission].get(T + "node") is None,
                f"missing Play permission: {permission}")
    wake = [n for n in play.iter("service") if n.get(A + "name") == ".WakeService"]
    require(len(wake) == 1 and wake[0].get(A + "foregroundServiceType") == FGS_TYPES["play"]["WakeService"]
            and wake[0].get(T + "replace") == "android:foregroundServiceType",
            "Play overlay must replace the WakeService foreground service type with systemExempted")
    main = (src / "main/AndroidManifest.xml").read_text()
    require('android:authorities="${applicationId}.files"' in main, "provider identity not flavor-safe")
    require('android:taskAffinity="${applicationId}.wake"' in main, "Wake task affinity not flavor-safe")
    require('android:targetPackage="${applicationId}"' in (src / "debug/AndroidManifest.xml").read_text(),
            "debug instrumentation target not flavor-safe")
    for flavor, resource_set in (("full", "main"), ("play", "play")):
        shortcuts = ET.parse(src / resource_set / "res/xml/shortcuts.xml").getroot()
        expected = PACKAGE + (".play" if flavor == "play" else "")
        require(all(i.get(A + "targetPackage") == expected for i in shortcuts.iter("intent")),
                f"{flavor} shortcuts target another application")
    return {"source": "PASS", "checked_kotlin_files": count}


def manifest_check(xml: str, flavor: str, debug: bool = False) -> dict:
    root = ET.fromstring(xml)
    expected = PACKAGE + (".play" if flavor == "play" else "")
    require(root.get("package") == expected, "wrong artifact package")
    app = root.find("application")
    if app is None:
        raise ValueError("no application manifest")
    require((app.get(A + "debuggable") == "true") == debug, "debuggable state differs from requested build type")
    sdk = root.find("uses-sdk")
    require(sdk is not None and int(sdk.get(A + "targetSdkVersion", "0")) >= 36, "target SDK below 36")
    permissions = {p.get(A + "name", ""): p for p in root.findall("uses-permission")}
    names = {n.get(A + "name", "") for n in app}
    has_phone = any(n.endswith(".PhoneService") for n in names)
    require(has_phone == (flavor == "full"), "wrong PhoneService manifest boundary")
    if flavor == "play":
        require(not (FULL_ONLY_PERMISSIONS & permissions.keys()), "Play includes full-only permissions")
        require("android.permission.BIND_ACCESSIBILITY_SERVICE" not in xml and
                "android.accessibilityservice" not in xml, "Play accessibility component")
        require("PhoneStopReceiver" not in xml and "PhoneProbeActivity" not in xml, "Play phone component")
        exact = permissions.get("android.permission.SCHEDULE_EXACT_ALARM")
        require(exact is not None and exact.get(A + "maxSdkVersion") is None, "Play exact alarm access capped/missing")
        for permission in PLAY_ONLY_PERMISSIONS:
            require(permission in permissions, f"Play is missing {permission}")
    else:
        require(FULL_ONLY_PERMISSIONS <= permissions.keys(), "full permissions lost")
        require(not (PLAY_ONLY_PERMISSIONS & permissions.keys()), "full includes Play-only permissions")
    services = foreground_services(app)
    expected_services = expected_foreground_services(flavor)
    require(services == expected_services, f"wrong foreground service type set: {services} != {expected_services}")
    provider = [p for p in app.findall("provider") if p.get(A + "name") == "androidx.core.content.FileProvider"]
    require(len(provider) == 1 and provider[0].get(A + "authorities") == expected + ".files", "wrong FileProvider authority")
    wake = [p for p in app.findall("activity") if p.get(A + "name", "").endswith(".WakeActivity")]
    require(len(wake) == 1 and wake[0].get(A + "taskAffinity") == expected + ".wake", "wrong Wake affinity")
    for activity in ("MainActivity", "VoiceLaunchActivity", "ApproveActivity", "WakeActivity"):
        require(any(n.endswith("." + activity) for n in names), f"retained activity missing: {activity}")
    for service in ("TalkService", "WakeService", "WakeListenService"):
        require(any(n.endswith("." + service) for n in names), f"retained service missing: {service}")
    if debug:
        require(all(i.get(A + "targetPackage") == expected for i in root.findall("instrumentation")), "wrong instrumentation target")
    else:
        require(not root.findall("instrumentation"), "instrumentation leaked into release")
        require(not any(n.endswith("." + d) for n in names for d in DEBUG_CLASSES), "debug component in release")
    return {"package": expected, "permissions": sorted(permissions), "foreground_services": services}


def dex_tables(data: bytes) -> tuple[set[str], set[str]]:
    """Read actual DEX strings and defined class descriptors, not ZIP/string guesses."""
    require(data.startswith(b"dex\n"), "unsupported or malformed DEX")
    def u32(offset: int) -> int:
        return struct.unpack_from("<I", data, offset)[0]
    strings = []
    for i in range(u32(56)):
        offset = u32(u32(60) + 4 * i)
        while data[offset] & 128:  # skip utf16 length ULEB128
            offset += 1
        offset += 1
        end = data.index(b"\0", offset)
        strings.append(data[offset:end].decode("utf-8", errors="replace"))
    types = [strings[u32(u32(68) + 4 * i)] for i in range(u32(64))]
    classes = {types[u32(u32(100) + 32 * i)] for i in range(u32(96))}
    return set(strings), classes


def dex_check(strings: set[str], classes: set[str], flavor: str, debug: bool = False) -> None:
    def has(name: str) -> bool:
        return any(c == f"Ldev/avifenesh/hermes/{name};" or
                   c.startswith(f"Ldev/avifenesh/hermes/{name}$") for c in classes)
    if flavor == "play":
        require(not any(has(n) for n in FULL_CLASSES + ("PhoneProbeActivity",)), "full-only implementation in Play DEX")
        for token in FORBIDDEN_DEX_STRINGS:
            require(not any(token in s for s in strings), f"full-only DEX marker: {token}")
        require(has("UnsupportedPhoneControl"), "missing inert Play contract")
    else:
        require(all(has(n) for n in FULL_CLASSES), "full implementation missing from DEX")
    if not debug:
        require(not any(has(n) for n in DEBUG_CLASSES), "debug fixture in release DEX")


def resource_text_check(pool: str, flavor: str) -> None:
    """`aapt2 dump strings` output: the compiled resource string pool."""
    if flavor == "play":
        for token in FORBIDDEN_RESOURCE_TEXT:
            require(token not in pool, f"full-only resource text in Play: {token}")
        require(all(token in pool for token in PLAY_RESOURCE_TEXT), "missing Play AI-content notice")
    else:
        require(not any(token in pool for token in PLAY_RESOURCE_TEXT), "Play AI-content notice in full resources")


def payload_check(names: list[str], debug: bool = False) -> None:
    if not debug:
        require(not any(n.startswith(DEBUG_ASSET_DIR) for n in names), "debug fixture asset in release")


def sdk_tool(name: str) -> str:
    if found := shutil.which(name):
        return found
    sdk = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", str(Path.home() / "Android/Sdk"))))
    candidates = list(sdk.glob(f"cmdline-tools/*/bin/{name}")) + list(sdk.glob(f"build-tools/*/{name}"))
    require(bool(candidates), f"SDK tool not found: {name}; set ANDROID_HOME or PATH")
    return str(sorted(candidates)[-1])


def artifact_check(apk: Path, flavor: str, cert: str | None, debug: bool) -> dict:
    require(debug or bool(cert), "release verification requires --expected-cert-sha256 (approved signer, not a discovered default)")
    manifest = subprocess.check_output([sdk_tool("apkanalyzer"), "manifest", "print", str(apk)], text=True)
    report = manifest_check(manifest, flavor, debug)
    strings, classes = set(), set()
    with zipfile.ZipFile(apk) as archive:
        dex = [n for n in archive.namelist() if re.fullmatch(r"classes(?:\d+)?\.dex", n)]
        require(bool(dex), "APK contains no DEX")
        payload_check(archive.namelist(), debug)
        for name in dex:
            s, c = dex_tables(archive.read(name)); strings.update(s); classes.update(c)
        # AAPT2 shortens ZIP resource paths (for example res/wA.xml). Inspect
        # the compiled resource table instead of assuming source filenames survive.
        resources = subprocess.check_output([sdk_tool("aapt2"), "dump", "resources", str(apk)], text=True)
        phone_xml = bool(re.search(r"(?m)^\s*resource\s+0x[0-9a-fA-F]+\s+(?:[^\s:]+:)?xml/phone_service\s*$", resources))
        require(phone_xml == (flavor == "full"), "wrong phone_service.xml resource boundary")
        resource_text_check(subprocess.check_output([sdk_tool("aapt2"), "dump", "strings", str(apk)], text=True), flavor)
    dex_check(strings, classes, flavor, debug)
    signature = subprocess.check_output([sdk_tool("apksigner"), "verify", "--print-certs", str(apk)], text=True)
    fingerprints = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)", signature)
    require(bool(fingerprints), "no verified APK signer")
    if cert:
        expected = cert.replace(":", "").lower()
        require(bool(re.fullmatch(r"[a-f0-9]{64}", expected)), "expected certificate SHA-256 must have 64 hex digits")
        require({f.lower() for f in fingerprints} == {expected}, "APK signer differs from approved identity")
    report.update(artifact="PASS", apk=str(apk), sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
                  signer_sha256=fingerprints, dex_files=len(dex), defined_classes=len(classes))
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--flavor", choices=("full", "play"))
    parser.add_argument("--expected-cert-sha256")
    parser.add_argument("--debug", action="store_true", help="explicitly check a debug artifact instead of a distribution release")
    args = parser.parse_args()
    try:
        report = source_check()
        if args.apk:
            require(bool(args.flavor), "--apk requires --flavor")
            report.update(artifact_check(args.apk, args.flavor, args.expected_cert_sha256, args.debug))
        print(json.dumps(report, indent=2))
    except (ValueError, OSError, ET.ParseError, subprocess.CalledProcessError, zipfile.BadZipFile) as exc:
        parser.exit(1, f"FAIL: {exc}\n")


if __name__ == "__main__":
    main()
