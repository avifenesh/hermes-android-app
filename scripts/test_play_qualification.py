"""Pure ZIP-parity regression tests. Fixtures are not Android build artifacts."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('qualify_play', Path(__file__).with_name('qualify_play.py'))
assert spec and spec.loader
qualify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qualify)


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
