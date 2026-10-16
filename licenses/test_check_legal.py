#!/usr/bin/env python3
"""Offline negative regressions, including synthetic APK ZIP contract fixtures.

ZIP fixtures contain test marker bytes, not executable DEX/ELF or real APKs.
They prove the checker rejects violations, not Android runtime behavior.
"""
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CHECKER = ROOT / 'licenses/check_legal.py'


class LegalContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='hermes-legal-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for directory in ['licenses', 'android/app/src/main/assets', 'android/app/src/main/res/font',
                          'android/app/src/main/cpp/microwakeword']:
            shutil.copytree(ROOT / directory, self.root / directory,
                            ignore=shutil.ignore_patterns('__pycache__'))
        for path in ['NOTICE', 'LICENSE', 'android/app/build.gradle.kts'] + [
            f'android/app/src/main/java/dev/avifenesh/hermes/{name}'
            for name in ['Ui.kt', 'Screens2.kt', 'Legal.kt']
        ]:
            target = self.root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / path, target)

    def run_check(self, apk=None):
        command = [sys.executable, str(CHECKER), '--root', str(self.root)]
        if apk:
            command += ['--apk', str(apk)]
        result = subprocess.run(command, capture_output=True, text=True, timeout=30)
        return result.returncode, json.loads(result.stdout or result.stderr)

    def expect_failure(self, token, apk=None):
        code, report = self.run_check(apk)
        self.assertEqual(code, 1, report)
        self.assertFalse(report['ok'])
        self.assertIn(token, '\n'.join(report.get('errors', [report.get('error', '')])))

    def fixture_apk(self, extra=None, omit=(), corrupt=None, rename_fonts=False):
        inventory = json.loads((self.root / 'licenses/inventory.json').read_text())
        files = {'assets/legal/' + p.name: p.read_bytes()
                 for p in (self.root / 'android/app/src/main/assets/legal').iterdir()}
        for entry in inventory['models'] + inventory['fonts']:
            name = entry['apk_path']
            if rename_fonts and name.startswith('res/font/'):
                name = 'res/short_' + str(len(files)) + '.ttf'
            files[name] = (self.root / entry['repo_path']).read_bytes()
        files['lib/arm64-v8a/libmicrowakeword.so'] = b'SYNTHETIC ELF test marker only'
        files['classes.dex'] = b'SYNTHETIC DEX test marker: legal/inventory.json Licenses'
        files.update(extra or {})
        for name in omit:
            files.pop(name)
        for name in corrupt or ():
            files[name] += b'corrupt'
        apk = self.root / 'synthetic-contract.zip'
        with zipfile.ZipFile(apk, 'w') as archive:
            for name, data in files.items():
                archive.writestr(name, data)
        return apk

    def test_unchanged_source_contract(self):
        code, report = self.run_check()
        self.assertEqual(code, 0, report)
        self.assertTrue(report['ok'])
        self.assertEqual((report['models'], report['fonts'], report['native_dependency_pins']), (2, 4, 5))
        self.assertIsNone(report['apk'])

    def test_stale_packaged_notice_rejected(self):
        (self.root / 'android/app/src/main/assets/legal/NOTICE.txt').write_text('incomplete notice')
        self.expect_failure('canonical text and Android asset differ')

    def test_changed_classifier_rejected(self):
        path = self.root / 'android/app/src/main/assets/microwakeword/hey_jarvis.tflite'
        data = bytearray(path.read_bytes())
        data[-1] ^= 1
        path.write_bytes(data)
        self.expect_failure('SHA-256 mismatch')

    def test_changed_manifest_rejected(self):
        path = self.root / 'android/app/src/main/assets/microwakeword/hey_jarvis.json'
        path.write_bytes(path.read_bytes() + b'\n')
        self.expect_failure('SHA-256 mismatch')

    def test_missing_full_kissfft_terms_rejected(self):
        (self.root / 'android/app/src/main/assets/legal/KissFFT-BSD-3-Clause.txt').unlink()
        self.expect_failure('Unlisted or missing legal asset files')

    def test_missing_publisher_notice_rejected(self):
        (self.root / 'android/app/src/main/assets/legal/microWakeWord-models-NOTICE.txt').unlink()
        self.expect_failure('Unlisted or missing legal asset files')

    def test_mismatched_catalog_rejected(self):
        path = self.root / 'android/app/src/main/assets/legal/inventory.json'
        path.write_bytes(path.read_bytes() + b'\n')
        self.expect_failure('Source and packaged inventory differ')

    def test_reintroduced_ort_dependency_rejected(self):
        path = self.root / 'android/app/build.gradle.kts'
        path.write_text(path.read_text() + '\nimplementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")')
        self.expect_failure('Obsolete ONNX Runtime Gradle dependency')

    def test_stale_scanner_version_rejected(self):
        path = self.root / 'android/app/build.gradle.kts'
        path.write_text(path.read_text().replace('play-services-code-scanner:16.1.0', 'play-services-code-scanner:0.0.0'))
        self.expect_failure('Dependency changed; refresh authoritative notices')

    def test_native_pin_drift_rejected(self):
        path = self.root / 'android/app/src/main/cpp/microwakeword/CMakeLists.txt'
        path.write_text(path.read_text().replace('2747abd5c82a95fb1624106a946fc671c31f16e8', '0' * 40))
        self.expect_failure('Native CMake dependency pins changed')

    def test_old_model_in_other_flavor_rejected(self):
        path = self.root / 'android/app/src/full/assets/oww/hey_jarvis_v0.1.onnx'
        path.parent.mkdir(parents=True)
        path.write_bytes(b'old ONNX fixture')
        self.expect_failure('Obsolete ONNX/ORT source asset')

    def test_ndk_pin_drift_rejected(self):
        path = self.root / 'android/app/build.gradle.kts'
        path.write_text(path.read_text().replace('ndkVersion = "29.0.14206865"', 'ndkVersion = "0.0.0"'))
        self.expect_failure('NDK build pin changed')

    def test_old_ort_native_in_other_flavor_rejected(self):
        path = self.root / 'android/app/src/full/jniLibs/arm64-v8a/libonnxruntime.so'
        path.parent.mkdir(parents=True)
        path.write_bytes(b'old ORT fixture')
        self.expect_failure('Obsolete ONNX/ORT source asset')

    def test_missing_route_rejected(self):
        path = self.root / 'android/app/src/main/java/dev/avifenesh/hermes/Ui.kt'
        path.write_text(path.read_text().replace('is Route.LegalR -> LicensesScreen(r.document)', 'is Route.LegalR -> Unit'))
        self.expect_failure('Legal route dispatch missing')

    def test_apk_with_aapt2_shortened_font_paths(self):
        code, report = self.run_check(self.fixture_apk(rename_fonts=True))
        self.assertEqual(code, 0, report)
        self.assertEqual(len(report['apk']['font_resource_paths']), 4)
        self.assertTrue(report['apk']['old_onnx_ort_absent'])

    def test_apk_changed_font_bytes_rejected(self):
        apk = self.fixture_apk(corrupt=['res/font/geist.ttf'])
        self.expect_failure('APK byte mismatch: res/font/geist.ttf', apk)

    def test_apk_corrupt_replacement_rejected(self):
        apk = self.fixture_apk(corrupt=['assets/microwakeword/hey_jarvis.tflite'])
        self.expect_failure('APK byte mismatch: assets/microwakeword/hey_jarvis.tflite', apk)

    def test_apk_missing_manifest_rejected(self):
        apk = self.fixture_apk(omit=['assets/microwakeword/hey_jarvis.json'])
        self.expect_failure('APK missing assets/microwakeword/hey_jarvis.json', apk)

    def test_apk_old_onnx_rejected(self):
        for name in ['hey_jarvis_v0.1.onnx', 'embedding_model.onnx', 'melspectrogram.onnx']:
            with self.subTest(name=name):
                apk = self.fixture_apk(extra={'assets/oww/' + name: b'old ONNX fixture'})
                self.expect_failure('APK contains obsolete ONNX/ORT content', apk)

    def test_apk_ort_native_rejected(self):
        for name in ['libonnxruntime.so', 'libonnxruntime4j_jni.so']:
            with self.subTest(name=name):
                apk = self.fixture_apk(extra={'lib/arm64-v8a/' + name: b'old ORT fixture'})
                self.expect_failure('APK contains obsolete ONNX/ORT content', apk)

    def test_apk_ort_java_rejected(self):
        apk = self.fixture_apk(extra={'classes2.dex': b'Lai/onnxruntime/OrtEnvironment;'})
        self.expect_failure('APK contains obsolete ONNX Runtime Java classes', apk)

    def test_apk_missing_native_engine_rejected(self):
        apk = self.fixture_apk(omit=['lib/arm64-v8a/libmicrowakeword.so'])
        self.expect_failure('APK missing native microWakeWord engine', apk)


if __name__ == '__main__':
    unittest.main(verbosity=2)
