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


if __name__ == '__main__':
    unittest.main()
