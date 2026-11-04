"""Pure qualification-guard regressions. ZIP fixtures are not Android artifacts."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('qualify_play', Path(__file__).with_name('qualify_play.py'))
assert spec and spec.loader
qualify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qualify)


class TestExecutionTests(unittest.TestCase):
    def test_entirely_skipped_suite_is_rejected(self):
        with self.assertRaises(AssertionError):
            qualify.validate_test_counts({'tests': 3, 'skipped': 3, 'failures': 0, 'errors': 0})


build_spec = importlib.util.spec_from_file_location('build_play_candidate', Path(__file__).with_name('build_play_candidate.py'))
assert build_spec and build_spec.loader
build = importlib.util.module_from_spec(build_spec)
build_spec.loader.exec_module(build)


class BuildProvenanceTests(unittest.TestCase):
    def setUp(self):
        self.sources = {'source.kt': 'source-digest'}
        self.outputs = {'candidate.apk': 'artifact-digest', 'TEST-report.xml': 'test-digest', 'lint.xml': 'lint-digest'}
        self.record = {'format': 1, 'return_code': 0, 'command': build.BUILD_COMMAND.copy(), 'sources': self.sources.copy(), 'outputs': self.outputs.copy()}

    def test_matching_actual_build_snapshot_is_accepted(self):
        build.validate_provenance(self.record, self.sources, self.outputs)

    def test_missing_provenance_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'successful build provenance'):
            build.validate_provenance({}, self.sources, self.outputs)

    def test_changed_source_content_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'source snapshot differs'):
            build.validate_provenance(self.record, {'source.kt': 'different-content'}, self.outputs)

    def test_replaced_xml_or_artifact_is_rejected(self):
        for name in self.outputs:
            with self.subTest(output=name), self.assertRaisesRegex(AssertionError, 'artifact/report hashes differ'):
                build.validate_provenance(self.record, self.sources, {**self.outputs, name: 'unrelated-content'})

    def test_wrong_build_command_is_rejected(self):
        self.record['command'] = ['true']
        with self.assertRaisesRegex(AssertionError, 'build command'):
            build.validate_provenance(self.record, self.sources, self.outputs)


class PlayBoundaryReceiptTests(unittest.TestCase):
    """The receipt binds the merged-manifest service types and permissions the Play declarations describe."""
    good = {'permissions': ['android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED', 'android.permission.SCHEDULE_EXACT_ALARM'],
            'foreground_services': {'LiveService': 0x1, 'WakeService': 0x400, 'TalkService': 0x80, 'WakeListenService': 0x80}}

    def test_system_exempted_alarm_receipt_is_accepted(self):
        self.assertEqual(qualify.validate_play_boundary(self.good)['WakeService'], 'systemExempted')

    def test_media_playback_alarm_or_camera_is_rejected(self):
        for change in ({'foreground_services': {**self.good['foreground_services'], 'WakeService': 0x2}},
                       {'permissions': self.good['permissions'] + ['android.permission.CAMERA']},
                       {'permissions': self.good['permissions'] + ['android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK']},
                       {'permissions': ['android.permission.SCHEDULE_EXACT_ALARM']},
                       {'foreground_services': {**self.good['foreground_services'], 'Extra': 0x1}}):
            with self.subTest(change=change), self.assertRaises(AssertionError):
                qualify.validate_play_boundary({**self.good, **change})


class ApkIdentityTests(unittest.TestCase):
    def test_correct_candidate_is_accepted(self):
        qualify.validate_apk_identity("package: name='dev.avifenesh.hermes.play' versionCode='41' versionName='0.16.9'", 41, '0.16.9')

    def test_old_signed_package_version_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'version mismatch'):
            qualify.validate_apk_identity("package: name='dev.avifenesh.hermes.play' versionCode='1' versionName='0.0.1'", 41, '0.16.9')


class BundleParityTests(unittest.TestCase):
    def check(self, apk_extra=None, bundle_extra=None):
        with tempfile.TemporaryDirectory(prefix='bundle-parity-') as directory:
            apk, bundle = Path(directory) / 'candidate.apk', Path(directory) / 'candidate.aab'
            apk_rows = {'classes.dex': b'synthetic-dex', 'assets/policy.md': b'policy', 'lib/x86_64/test.so': b'synthetic-lib', 'assets/dexopt/baseline.prof': b'profile'}
            bundle_rows = {'base/dex/classes.dex': b'synthetic-dex', 'base/assets/policy.md': b'policy', 'base/lib/x86_64/test.so': b'synthetic-lib', 'BUNDLE-METADATA/com.android.tools.build.profiles/baseline.prof': b'profile'}
            apk_rows.update(apk_extra or {})
            bundle_rows.update(bundle_extra or {})
            for path, rows in ((apk, apk_rows), (bundle, bundle_rows)):
                with zipfile.ZipFile(path, 'w') as archive:
                    for name, data in rows.items():
                        archive.writestr(name, data)
            qualify.payload_parity(apk, bundle)

    def test_matching_payload_and_profile_mapping(self):
        self.check()

    def test_extra_bundle_asset_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'inventory mismatch'):
            self.check(bundle_extra={'base/assets/unreviewed.txt': b'extra'})

    def test_extra_apk_asset_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'inventory mismatch'):
            self.check(apk_extra={'assets/unreviewed.txt': b'extra'})

    def test_unreviewed_feature_module_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'Unexpected bundle module'):
            self.check(bundle_extra={'feature/dex/classes.dex': b'extra code'})

    def test_extra_base_dex_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'DEX inventory mismatch'):
            self.check(bundle_extra={'base/dex/classes2.dex': b'extra code'})

    def test_changed_code_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'code differs'):
            self.check(bundle_extra={'base/dex/classes.dex': b'changed code'})

    def test_changed_native_payload_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'content differs'):
            self.check(bundle_extra={'base/lib/x86_64/test.so': b'changed native'})



import os
import shutil
import subprocess
import sys

SCRIPTS = Path(__file__).resolve().parent
JAVA_BIN = Path(os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-21-openjdk-amd64')) / 'bin'


class OptimizedModeTests(unittest.TestCase):
    """python -O strips assert statements; the gates must still fail closed."""

    def run_optimized(self, code):
        return subprocess.run([sys.executable, '-O', '-c', f'import sys; sys.path.insert(0, {str(SCRIPTS)!r}); ' + code],
                              capture_output=True, text=True, timeout=60)

    def test_skipped_suite_still_rejected_under_optimize(self):
        result = self.run_optimized("import qualify_play as q; q.validate_test_counts({'tests': 3, 'skipped': 3, 'failures': 0, 'errors': 0})")
        self.assertNotEqual(result.returncode, 0, 'all-skipped suite passed under python -O')

    def test_provenance_mismatch_still_rejected_under_optimize(self):
        result = self.run_optimized("import build_play_candidate as b; b.validate_provenance({'format': 1, 'return_code': 0, 'command': ['x'], 'sources': {}, 'outputs': {}}, {}, {})")
        self.assertNotEqual(result.returncode, 0, 'wrong build command accepted under python -O')


@unittest.skipUnless((JAVA_BIN / 'jarsigner').is_file() and (JAVA_BIN / 'keytool').is_file(), 'JDK jarsigner/keytool not found')
class BundleSignatureTests(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp(prefix='qualify-sig-'))
        self.addCleanup(shutil.rmtree, self.dir)
        self.ks = self.dir / 'k.jks'
        subprocess.run([str(JAVA_BIN / 'keytool'), '-genkeypair', '-keystore', str(self.ks), '-storepass', 'fixture', '-keypass', 'fixture',
                        '-alias', 'k', '-keyalg', 'EC', '-dname', 'CN=fixture', '-validity', '2'], check=True, capture_output=True)

    def jar(self, name, extra_unsigned=False):
        path = self.dir / name
        with zipfile.ZipFile(path, 'w') as z:
            z.writestr('base/manifest/AndroidManifest.xml', b'manifest')
        subprocess.run([str(JAVA_BIN / 'jarsigner'), '-keystore', str(self.ks), '-storepass', 'fixture', str(path), 'k'], check=True, capture_output=True)
        if extra_unsigned:
            with zipfile.ZipFile(path, 'a') as z:
                z.writestr('base/dex/classes.dex', b'unsigned payload')
        return path

    def test_fully_signed_bundle_accepted(self):
        qualify.verify_bundle_signature(str(JAVA_BIN / 'jarsigner'), self.jar('ok.aab'), self.dir / 'ok.log')

    def test_partly_unsigned_bundle_rejected(self):
        with self.assertRaises((AssertionError, RuntimeError)):
            qualify.verify_bundle_signature(str(JAVA_BIN / 'jarsigner'), self.jar('partial.aab', extra_unsigned=True), self.dir / 'partial.log')


class BundleRegenerationTests(unittest.TestCase):
    def apk(self, entries):
        d = Path(tempfile.mkdtemp(prefix='qualify-regen-test-'))
        self.addCleanup(shutil.rmtree, d)
        path = d / 'x.apk'
        with zipfile.ZipFile(path, 'w') as z:
            for name, data in entries.items():
                z.writestr(name, data)
        return path

    def test_signature_files_ignored_payload_must_match(self):
        a = qualify.unsigned_payload(self.apk({'classes.dex': b'd', 'res/x': b'r', 'META-INF/A.SF': b'1'}))
        b = qualify.unsigned_payload(self.apk({'classes.dex': b'd', 'res/x': b'r', 'META-INF/B.RSA': b'2'}))
        qualify.same_payload(a, b)

    def test_changed_or_extra_entries_rejected(self):
        base = qualify.unsigned_payload(self.apk({'classes.dex': b'd', 'resources.arsc': b'r'}))
        for other in ({'classes.dex': b'd', 'resources.arsc': b'R'}, {'classes.dex': b'd', 'resources.arsc': b'r', 'lib/x.so': b's'}, {'classes.dex': b'd'}):
            with self.assertRaises(AssertionError):
                qualify.same_payload(base, qualify.unsigned_payload(self.apk(other)))

if __name__ == '__main__':
    unittest.main()
