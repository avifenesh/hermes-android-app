#!/usr/bin/env python3
"""Qualify existing Play candidates. Does not build, upload, or approve a listing.

Uses source/artifact guards, real Gradle XML reports and bundletool validation.
Supply the expected upload certificate independently, never infer trust from an APK.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile


class QualificationError(AssertionError):
    """A failed gate. Raised explicitly so `python -O` cannot strip the check."""


def require(ok: object, message: str) -> None:
    if not ok:
        raise QualificationError(message)

ROOT = Path(__file__).resolve().parents[1]
ABIS = {'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'}


def digest(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def payload_parity(apk: Path, bundle: Path) -> None:
    """Single-module bundle, with bidirectional code/native/asset parity."""
    with zipfile.ZipFile(apk) as a, zipfile.ZipFile(bundle) as b:
        modules = {name.split('/')[0] for name in b.namelist() if '/' in name and not name.startswith(('BUNDLE-METADATA/', 'META-INF/'))}
        require(modules == {'base'}, 'Unexpected bundle module')
        dex = {name for name in a.namelist() if re.fullmatch(r'classes\d*\.dex', name)}
        bundle_dex = {name.removeprefix('base/dex/') for name in b.namelist() if re.fullmatch(r'base/dex/classes\d*\.dex', name)}
        require(dex and dex == bundle_dex, 'APK/bundle DEX inventory mismatch')
        for name in dex:
            require(a.read(name) == b.read('base/dex/' + name), 'APK/bundle code differs: ' + name)
        payload = {name for name in a.namelist() if name.startswith(('lib/', 'assets/')) and not name.endswith('/')}
        bundle_payload = {name.removeprefix('base/') for name in b.namelist() if name.startswith(('base/lib/', 'base/assets/')) and not name.endswith('/')}
        profiles = {'BUNDLE-METADATA/com.android.tools.build.profiles/' + name: 'assets/dexopt/' + name for name in ('baseline.prof', 'baseline.profm')}
        bundle_payload.update(value for name, value in profiles.items() if name in b.namelist())
        require(payload == bundle_payload, 'APK/bundle native/asset inventory mismatch')
        for name in payload:
            bundled = next((key for key, value in profiles.items() if value == name), 'base/' + name)
            require(a.read(name) == b.read(bundled), 'APK/bundle content differs: ' + name)


def validate_apk_identity(badging: str, code: int, version: str) -> None:
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.MULTILINE)
    require(package and package.groups() == ('dev.avifenesh.hermes.play', str(code), version), 'APK/source version mismatch')


def validate_test_counts(counts: dict[str, int]) -> None:
    require(counts['tests'] > counts['skipped'] >= 0 and counts['failures'] == counts['errors'] == 0, 'No executed successful tests')


def command(argv: list[str], output: Path) -> str:
    result = subprocess.run(argv, cwd=ROOT, capture_output=True, text=True, timeout=180)
    output.write_text(result.stdout + result.stderr)
    if result.returncode:
        raise RuntimeError(f'{Path(argv[0]).name} failed ({result.returncode}); see {output}')
    return result.stdout


def manifest_names(manifest: bytes) -> set[str]:
    """Entry names listed (with digests) in a JAR manifest; 72-byte lines continue with a leading space."""
    lines: list[str] = []
    for raw in manifest.decode('utf-8').replace('\r\n', '\n').split('\n'):
        if raw.startswith(' ') and lines:
            lines[-1] += raw[1:]
        else:
            lines.append(raw)
    return {line[len('Name: '):] for line in lines if line.startswith('Name: ')}


def verify_bundle_signature(jarsigner: str, bundle: Path, log: Path) -> None:
    """Every payload entry must be covered by the signed manifest.

    `jarsigner -verify` alone prints "jar verified." and exits 0 when some entries are unsigned. `-strict` is
    not usable here: Android upload certificates are self-signed and AAB manifests are not the first ZIP entry,
    so a correctly signed bundle fails it. Instead require the verification plus full manifest coverage.
    """
    signature = command([jarsigner, '-J-Duser.language=en', '-J-Duser.country=US', '-verify', str(bundle)], log)
    require('jar verified.' in signature, 'Bundle signature not verified')
    require('unsigned entries' not in signature, 'Bundle has unsigned entries')
    with zipfile.ZipFile(bundle) as archive:
        payload = {info.filename for info in archive.infolist()
                   if not info.is_dir() and not info.filename.upper().startswith('META-INF/')}
        listed = manifest_names(archive.read('META-INF/MANIFEST.MF'))
    missing = sorted(payload - listed)
    require(not missing, f'Bundle entries not covered by the signature: {missing[:5]}')


def native_alignment(apk: Path) -> dict:
    libraries = []
    with zipfile.ZipFile(apk) as archive:
        abis = {name.split('/')[1] for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')}
        require(abis == ABIS, f'Unexpected ABI set: {abis}')
        for name in archive.namelist():
            if not name.startswith('lib/') or not name.endswith('.so'):
                continue
            data = archive.read(name)
            require(data[:4] == b'\x7fELF' and data[5] == 1, 'qualify_play.py:75 check failed')
            if data[4] != 2:
                continue
            offset = struct.unpack_from('<Q', data, 32)[0]
            stride, count = struct.unpack_from('<HH', data, 54)
            alignments = []
            for index in range(count):
                start = offset + index * stride
                if struct.unpack_from('<I', data, start)[0] != 1:
                    continue
                file_offset, virtual_address = struct.unpack_from('<QQ', data, start + 8)
                alignment = struct.unpack_from('<Q', data, start + 48)[0]
                require(alignment >= 16384 and file_offset % 16384 == virtual_address % 16384, name)
                alignments.append(alignment)
            require(alignments, name)
            libraries.append({'library': name, 'load_alignments': alignments})
        policy = archive.read('assets/privacy/privacy-policy.md')
    expected_policy = ROOT / 'android/app/src/play/assets/privacy/privacy-policy.md'
    require(expected_policy.is_file(), 'Play-specific offline privacy policy is missing')
    require(policy == expected_policy.read_bytes(), 'Packaged Play privacy policy differs from source')
    return {'abis': sorted(abis), 'elf64_libraries': libraries, 'privacy_sha256': hashlib.sha256(policy).hexdigest()}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', required=True, type=Path)
    parser.add_argument('--bundle', required=True, type=Path)
    parser.add_argument('--provenance', required=True, type=Path, help='Receipt from build_play_candidate.py, bound to source and report/artifact hashes')
    parser.add_argument('--bundle-apk', required=True, type=Path, help='Publisher-signed universal APK generated from this bundle with bundletool')
    parser.add_argument('--expected-cert-sha256', required=True)
    parser.add_argument('--bundletool', required=True, type=Path)
    parser.add_argument('--build-tools', required=True, type=Path)
    parser.add_argument('--java', default='java')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    args.apk = args.apk.resolve()
    args.bundle = args.bundle.resolve()
    args.bundle_apk = args.bundle_apk.resolve()
    args.output.mkdir(parents=True, exist_ok=True)
    prior = args.output / 'verification.json'
    if prior.is_file():
        prior.rename(args.output / ('verification.previous-' + digest(prior)[:12] + '.json'))
    require(re.fullmatch('[0-9a-fA-F]{64}', args.expected_cert_sha256), 'qualify_play.py:117 check failed')
    from build_play_candidate import APK, BUNDLE, source_snapshot, output_snapshot, validate_provenance
    require(args.provenance.is_file(), 'Run scripts/build_play_candidate.py first')
    provenance = json.loads(args.provenance.read_text())
    bound_sources = source_snapshot(ROOT)
    validate_provenance(provenance, bound_sources, output_snapshot(ROOT))
    require(digest(args.apk) == provenance['outputs'][APK] and digest(args.bundle) == provenance['outputs'][BUNDLE], 'Supplied artifacts differ from proven build')
    source = (ROOT / 'android/app/build.gradle.kts').read_text()
    version_match = re.search(r'versionName = "([^"]+)"', source)
    code_match = re.search(r'versionCode = (\d+)', source)
    require(version_match and code_match, 'Missing source version')
    version = version_match.group(1)
    code = int(code_match.group(1))
    require(f'version: {version}\n' in (ROOT / 'server/mobile/plugin.yaml').read_text(), 'qualify_play.py:130 check failed')
    source_roots = [ROOT / 'android/app/src' / name for name in ['main', 'play', 'release', 'playRelease']]
    source_files = [path for base in source_roots for path in base.rglob('*') if path.is_file()]
    build_files = [ROOT / name for name in ['android/app/build.gradle.kts', 'android/build.gradle.kts', 'android/gradle.properties', 'android/settings.gradle.kts']]
    inputs = source_files + build_files
    latest_input = max(path.stat().st_mtime for path in inputs)
    require(min(path.stat().st_mtime for path in (args.apk, args.bundle, args.bundle_apk)) >= latest_input, 'Artifacts predate Android inputs; rebuild')
    distribution = json.loads(command([sys.executable, 'scripts/check_distribution.py', '--flavor', 'play', '--apk', str(args.apk),
        '--expected-cert-sha256', args.expected_cert_sha256], args.output / 'distribution.json'))
    command([sys.executable, 'licenses/check_legal.py', '--apk', str(args.apk)], args.output / 'legal.json')
    command([str(args.build_tools / 'zipalign'), '-c', '-P', '16', '4', str(args.apk)], args.output / 'zipalign.log')
    badging = command([str(args.build_tools / 'aapt2'), 'dump', 'badging', str(args.apk)], args.output / 'apk-badging.log')
    validate_apk_identity(badging, code, version)
    payload_parity(args.apk, args.bundle)
    command([args.java, '-jar', str(args.bundletool), 'validate', '--bundle', str(args.bundle)], args.output / 'bundle-validation.log')
    manifest = command([args.java, '-jar', str(args.bundletool), 'dump', 'manifest', '--bundle', str(args.bundle)], args.output / 'bundle-manifest.xml')
    # XML here is local build output, not an untrusted user document.
    from check_distribution import manifest_check
    manifest_check(manifest, 'play')
    node = ET.fromstring(manifest)
    ns = '{http://schemas.android.com/apk/res/android}'
    require(node.attrib['package'] == 'dev.avifenesh.hermes.play', 'qualify_play.py:151 check failed')
    require(node.attrib[ns + 'versionName'] == version and int(node.attrib[ns + 'versionCode']) == code, 'qualify_play.py:152 check failed')
    sdk = node.find('uses-sdk')
    require(sdk is not None, 'qualify_play.py:154 check failed')
    require(int(sdk.attrib[ns + 'targetSdkVersion']) >= 36, 'qualify_play.py:155 check failed')
    java_bin = Path(args.java).resolve().parent if Path(args.java).is_absolute() else None
    jarsigner = str(java_bin / 'jarsigner') if java_bin else 'jarsigner'
    keytool = str(java_bin / 'keytool') if java_bin else 'keytool'
    verify_bundle_signature(jarsigner, args.bundle, args.output / 'bundle-signature.log')
    certificate = command([keytool, '-J-Duser.language=en', '-J-Duser.country=US', '-printcert', '-jarfile', str(args.bundle)], args.output / 'bundle-certificate.log')
    certificates = re.findall(r'SHA256:\s*([A-Fa-f0-9:]+)', certificate)
    require(certificates and {item.replace(':', '').lower() for item in certificates} == {args.expected_cert_sha256.lower()}, 'Unexpected bundle signer')
    payload_parity(args.bundle_apk, args.bundle)
    command([sys.executable, 'scripts/check_distribution.py', '--flavor', 'play', '--apk', str(args.bundle_apk),
        '--expected-cert-sha256', args.expected_cert_sha256], args.output / 'bundle-apk-distribution.json')
    command([sys.executable, 'licenses/check_legal.py', '--apk', str(args.bundle_apk)], args.output / 'bundle-apk-legal.json')
    command([str(args.build_tools / 'zipalign'), '-c', '-P', '16', '4', str(args.bundle_apk)], args.output / 'bundle-apk-zipalign.log')
    bundled_badging = command([str(args.build_tools / 'aapt2'), 'dump', 'badging', str(args.bundle_apk)], args.output / 'bundle-apk-badging.log')
    validate_apk_identity(bundled_badging, code, version)
    generated_native = native_alignment(args.bundle_apk)
    reports = list((ROOT / 'android/app/build/test-results/testPlayDebugUnitTest').glob('TEST-*.xml'))
    require(reports, 'Run :app:testPlayDebugUnitTest first')
    test_roots = [ROOT / 'android/app/src' / name for name in ['main', 'play', 'debug', 'playDebug', 'test', 'testPlay']]
    test_inputs = [path for base in test_roots for path in base.rglob('*') if path.is_file() and path.suffix in ['.kt', '.java']]
    latest_test_input = max(path.stat().st_mtime for path in test_inputs + build_files)
    require(all(path.stat().st_mtime >= latest_test_input for path in reports), 'Unit reports predate test/compiler inputs; rerun tests')
    counts = {key: 0 for key in ['tests', 'failures', 'errors', 'skipped']}
    for path in reports:
        row = ET.parse(path).getroot()
        for key in counts:
            counts[key] += int(row.attrib.get(key, 0))
    validate_test_counts(counts)
    lint_file = ROOT / 'android/app/build/reports/lint-results-playRelease.xml'
    require(lint_file.is_file(), 'Run :app:lintPlayRelease first')
    lint_inputs = [path for path in source_files if path.suffix in ['.kt', '.java', '.xml']] + build_files
    require(lint_file.stat().st_mtime >= max(path.stat().st_mtime for path in lint_inputs), 'Lint report predates analyzed inputs; rerun lint')
    issues = ET.parse(lint_file).getroot().findall('issue')
    lint_errors = sum(item.attrib.get('severity') in ['Error', 'Fatal'] for item in issues)
    require(lint_errors == 0, 'qualify_play.py:190 check failed')
    native = native_alignment(args.apk)
    require(source_snapshot(ROOT) == bound_sources, 'Source changed during qualification')
    receipt = {'qualification': 'targeted artifact checks passed', 'store_approval': False, 'submitted': False,
        'version': version, 'version_code': code, 'package': node.attrib['package'],
        'commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        'working_tree_dirty': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True)),
        'apk': {'path': str(args.apk), 'sha256': digest(args.apk), 'bytes': args.apk.stat().st_size},
        'bundle': {'path': str(args.bundle), 'sha256': digest(args.bundle), 'bytes': args.bundle.stat().st_size},
        'bundle_generated_apk': {'path': str(args.bundle_apk), 'sha256': digest(args.bundle_apk), 'source_bundle_sha256': digest(args.bundle), 'bytes': args.bundle_apk.stat().st_size, 'native': generated_native},
        'build_provenance_sha256': digest(args.provenance),
        'expected_upload_certificate_sha256': args.expected_cert_sha256.lower(),
        'unit_tests': counts, 'lint': {'errors': lint_errors, 'warnings': len(issues)}, 'native': native,
        'scope_limits': ['No physical-phone qualification', 'No real FCM or microphone quality qualification',
            'No public reviewer-hosting availability', 'Play App Signing and listing declarations remain owner decisions']}
    (args.output / 'verification.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps({key: receipt[key] for key in ['qualification', 'version', 'package', 'unit_tests', 'lint', 'submitted']}, indent=2))


if __name__ == '__main__':
    main()
