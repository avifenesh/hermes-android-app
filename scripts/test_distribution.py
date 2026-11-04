"""Focused guard regression tests; manifest/DEX inputs below are synthetic fixtures."""
import importlib.util
from pathlib import Path
import struct
import unittest

spec = importlib.util.spec_from_file_location("distribution", Path(__file__).with_name("check_distribution.py"))
assert spec is not None and spec.loader is not None
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


def manifest(flavor="play", extra="", permission="", wake=None):
    package = check.PACKAGE + (".play" if flavor == "play" else "")
    perms = (''.join(f'<uses-permission android:name="{p}" />' for p in sorted(check.PLAY_ONLY_PERMISSIONS | {"android.permission.SCHEDULE_EXACT_ALARM"}))
             if flavor == "play" else ''.join(f'<uses-permission android:name="{p}" />' for p in sorted(check.FULL_ONLY_PERMISSIONS)))
    phone = '' if flavor == "play" else '<service android:name=".PhoneService" />'
    activities = ''.join(f'<activity android:name=".{n}" />' for n in ("MainActivity", "VoiceLaunchActivity", "ApproveActivity"))
    types = dict(check.FGS_TYPES[flavor])
    if wake is not None:
        types["WakeService"] = wake
    # apkanalyzer prints flag attributes as hex; the fixture uses the same form.
    services = ''.join(f'<service android:name=".{n}" android:foregroundServiceType="{hex(check.fgs_bits(t))}" />' for n, t in types.items())
    return f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}">
    <uses-sdk android:targetSdkVersion="36" />{perms}{permission}<application>{phone}{activities}{services}
    <service android:name="androidx.work.impl.foreground.SystemForegroundService" />
    <activity android:name=".WakeActivity" android:taskAffinity="{package}.wake" />
    <provider android:name="androidx.core.content.FileProvider" android:authorities="{package}.files" />
    {extra}</application></manifest>'''


class DistributionGuardTests(unittest.TestCase):
    def test_publisher_reporting_test_components_never_ship(self):
        for name in ('PublisherReportProbeActivity', 'PublisherReportsTest', 'PublisherReportFenceTest'):
            with self.subTest(name=name):
                xml = manifest().replace('</application>', f'<activity android:name="dev.avifenesh.hermes.{name}" /></application>')
                with self.assertRaises(ValueError):
                    check.manifest_check(xml, 'play')

    def test_voice_probe_never_ships(self):
        for flavor in ("full", "play"):
            with self.subTest(flavor=flavor):
                check.manifest_check(manifest(flavor), flavor)
                xml = manifest(flavor).replace('</application>', '<activity android:name="dev.avifenesh.hermes.VoiceProbeActivity" /></application>')
                with self.assertRaises(ValueError):
                    check.manifest_check(xml, flavor)
        with self.assertRaises(ValueError):
            check.dex_check(set(), {f"Ldev/avifenesh/hermes/{n};" for n in check.FULL_CLASSES} | {"Ldev/avifenesh/hermes/VoiceProbeActivity;"}, "full")
        with self.assertRaises(ValueError):
            check.payload_check(["classes.dex", "assets/probe/voice-fixture.ogg"])
        check.payload_check(["classes.dex", "assets/probe/voice-fixture.ogg"], debug=True)
        check.payload_check(["classes.dex", "assets/legal/NOTICE.txt"])

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
        notice = set(check.PLAY_DEX_STRINGS)
        check.dex_check(notice, inert, "play")
        for name in check.FULL_CLASSES + ("PhoneProbeActivity",):
            with self.subTest(name=name), self.assertRaises(ValueError):
                check.dex_check(notice, inert | {f"Ldev/avifenesh/hermes/{name};"}, "play")
        for marker in check.FORBIDDEN_DEX_STRINGS:
            with self.subTest(marker=marker), self.assertRaises(ValueError):
                check.dex_check(notice | {marker}, inert, "play")

    def test_play_dex_keeps_always_on_connection_out_and_ai_notice_in(self):
        inert = {"Ldev/avifenesh/hermes/UnsupportedPhoneControl;"}
        self.assertIn("Stay connected in background", check.FORBIDDEN_DEX_STRINGS)
        with self.assertRaisesRegex(ValueError, "AI-content notice"):
            check.dex_check(set(), inert, "play")

    def test_fgs_type_bits_from_names_and_hex(self):
        self.assertEqual(check.fgs_bits("systemExempted"), 0x400)
        self.assertEqual(check.fgs_bits("0x400"), 0x400)
        self.assertEqual(check.fgs_bits("dataSync|mediaPlayback"), 0x3)
        self.assertEqual(check.fgs_bits("1024"), 0x400)
        with self.assertRaises(ValueError):
            check.fgs_bits("notAType")

    def test_play_alarm_service_is_system_exempted(self):
        self.assertEqual(check.FGS_TYPES["play"]["WakeService"], "systemExempted")
        self.assertEqual(check.FGS_TYPES["full"]["WakeService"], "mediaPlayback")
        for bad in ("mediaPlayback", "systemExempted|mediaPlayback", "specialUse"):
            with self.subTest(wake=bad), self.assertRaisesRegex(ValueError, "foreground service type"):
                check.manifest_check(manifest(wake=bad), "play")
        with self.assertRaisesRegex(ValueError, "foreground service type"):
            check.manifest_check(manifest("full", wake="systemExempted"), "full")

    def test_play_requires_system_exempted_permission(self):
        xml = manifest().replace('<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED" />', '')
        with self.assertRaisesRegex(ValueError, "FOREGROUND_SERVICE_SYSTEM_EXEMPTED"):
            check.manifest_check(xml, "play")
        with self.assertRaises(ValueError):
            check.manifest_check(manifest("full", permission='<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED" />'), "full")

    def test_play_has_no_camera_or_media_playback_permission(self):
        for permission in ("android.permission.CAMERA", "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"):
            self.assertIn(permission, check.FULL_ONLY_PERMISSIONS)
            with self.subTest(permission=permission), self.assertRaises(ValueError):
                check.manifest_check(manifest(permission=f'<uses-permission android:name="{permission}" />'), "play")

    def test_unreviewed_typed_service_is_rejected(self):
        for flavor in ("full", "play"):
            with self.subTest(flavor=flavor), self.assertRaisesRegex(ValueError, "foreground service type"):
                check.manifest_check(manifest(flavor, extra='<service android:name="com.example.Sync" android:foregroundServiceType="0x1" />'), flavor)

    def test_manifest_report_lists_foreground_service_types(self):
        report = check.manifest_check(manifest(), "play")
        self.assertEqual(report["foreground_services"],
                         {f"{check.NAMESPACE}.{n}": check.fgs_bits(t) for n, t in check.FGS_TYPES["play"].items()})

    def test_typed_services_are_matched_by_full_component_name(self):
        # A library service that shares a short name must not stand in for, or hide behind, the app's own.
        for extra in ('<service android:name="com.vendor.LiveService" android:foregroundServiceType="0x1" />',
                      '<service android:name="com.vendor.WakeService" android:foregroundServiceType="0x400" />'):
            for flavor in ("full", "play"):
                with self.subTest(extra=extra, flavor=flavor), self.assertRaisesRegex(ValueError, "foreground service"):
                    check.manifest_check(manifest(flavor, extra=extra), flavor)
        absolute = manifest().replace('android:name=".LiveService"', f'android:name="{check.NAMESPACE}.LiveService"')
        self.assertIn(f"{check.NAMESPACE}.LiveService", check.manifest_check(absolute, "play")["foreground_services"])

    def test_duplicate_service_declarations_are_rejected(self):
        twice = f'<service android:name="{check.NAMESPACE}.LiveService" android:foregroundServiceType="0x1" />'
        with self.assertRaisesRegex(ValueError, "duplicate"):
            check.manifest_check(manifest(extra=twice), "play")

    def test_dex_full_contract(self):
        classes = {f"Ldev/avifenesh/hermes/{name};" for name in check.FULL_CLASSES}
        check.dex_check(set(), classes, "full")
        with self.assertRaisesRegex(ValueError, "AI-content notice"):
            check.dex_check(set(check.PLAY_DEX_STRINGS), classes, "full")
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
