"""Focused guard regression tests; manifest/DEX inputs below are synthetic fixtures."""
import importlib.util
from pathlib import Path
import struct
import unittest

spec = importlib.util.spec_from_file_location("distribution", Path(__file__).with_name("check_distribution.py"))
assert spec is not None and spec.loader is not None
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


def manifest(flavor="play", extra="", permission=""):
    package = check.PACKAGE + (".play" if flavor == "play" else "")
    perms = ('<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />' if flavor == "play" else
             ''.join(f'<uses-permission android:name="{p}" />' for p in check.FULL_ONLY_PERMISSIONS))
    phone = '' if flavor == "play" else '<service android:name=".PhoneService" />'
    activities = ''.join(f'<activity android:name=".{n}" />' for n in ("MainActivity", "VoiceLaunchActivity", "ApproveActivity"))
    services = ''.join(f'<service android:name=".{n}" />' for n in ("TalkService", "WakeService", "WakeListenService"))
    return f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}">
    <uses-sdk android:targetSdkVersion="36" />{perms}{permission}<application>{phone}{activities}{services}
    <activity android:name=".WakeActivity" android:taskAffinity="{package}.wake" />
    <provider android:name="androidx.core.content.FileProvider" android:authorities="{package}.files" />
    {extra}</application></manifest>'''


class DistributionGuardTests(unittest.TestCase):
    def test_current_source_boundary(self):
        self.assertEqual(check.source_check()["source"], "PASS")

    def test_both_manifest_contracts(self):
        for flavor in ("full", "play"):
            check.manifest_check(manifest(flavor), flavor)

    def test_play_rejects_full_permissions(self):
        for permission in check.FULL_ONLY_PERMISSIONS:
            with self.subTest(permission=permission), self.assertRaises(ValueError):
                check.manifest_check(manifest(permission=f'<uses-permission android:name="{permission}" />'), "play")

    def test_play_rejects_accessibility_service_even_renamed(self):
        with self.assertRaises(ValueError):
            check.manifest_check(manifest(extra='<service android:name=".Renamed" android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE" />'), "play")

    def test_play_rejects_capped_exact_permission(self):
        xml = manifest().replace('android.permission.SCHEDULE_EXACT_ALARM"', 'android.permission.SCHEDULE_EXACT_ALARM" android:maxSdkVersion="32"')
        with self.assertRaises(ValueError):
            check.manifest_check(xml, "play")

    def test_release_rejects_debug_component_and_cross_package_provider(self):
        for xml in (manifest(extra='<activity android:name=".PhoneProbeActivity" />'),
                    manifest().replace('dev.avifenesh.hermes.play.files', 'dev.avifenesh.hermes.files')):
            with self.assertRaises(ValueError):
                check.manifest_check(xml, "play")

    def test_dex_play_contract_and_negative_markers(self):
        inert = {"Ldev/avifenesh/hermes/UnsupportedPhoneControl;"}
        check.dex_check(set(), inert, "play")
        for name in check.FULL_CLASSES + ("PhoneProbeActivity",):
            with self.subTest(name=name), self.assertRaises(ValueError):
                check.dex_check(set(), inert | {f"Ldev/avifenesh/hermes/{name};"}, "play")
        for marker in check.FORBIDDEN_DEX_STRINGS:
            with self.subTest(marker=marker), self.assertRaises(ValueError):
                check.dex_check({marker}, inert, "play")

    def test_dex_full_contract(self):
        classes = {f"Ldev/avifenesh/hermes/{name};" for name in check.FULL_CLASSES}
        check.dex_check(set(), classes, "full")
        with self.assertRaises(ValueError):
            check.dex_check(set(), set(), "full")

    def test_dex_table_reader_with_minimal_synthetic_fixture(self):
        # Header + one string id, type id, class_def and ASCII string_data_item.
        descriptor = b"Ldev/avifenesh/hermes/PhoneService;"
        data = bytearray(152 + 1 + len(descriptor) + 1)
        data[:8] = b"dex\n035\0"
        for offset, value in ((56, 1), (60, 112), (64, 1), (68, 116), (96, 1), (100, 120), (112, 152)):
            struct.pack_into("<I", data, offset, value)
        data[152] = len(descriptor)
        data[153:153 + len(descriptor)] = descriptor
        strings, classes = check.dex_tables(bytes(data))
        self.assertEqual(strings, {descriptor.decode()})
        self.assertEqual(classes, strings)
        with self.assertRaises(ValueError):
            check.dex_tables(b"not a dex")

    def test_release_artifact_requires_approved_signer_before_tools(self):
        with self.assertRaisesRegex(ValueError, "expected-cert-sha256"):
            check.artifact_check(Path("not-used.apk"), "play", None, False)


if __name__ == "__main__":
    unittest.main()
