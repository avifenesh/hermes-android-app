#!/usr/bin/env python3
"""Offline source contract / hash inventory verifier; optional real APK/upstream checks.

No Gradle, Android SDK, third-party Python packages, credentials or server access.
Never regenerates inventory or changes files. Run from any working directory.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import urllib.request
import zipfile

REQUIRED = {'Android-NDK-r29-NOTICE-toolchain.txt',
 'Android-NDK-r29-NOTICE.txt',
 'Apache-2.0.txt',
 'Google-ML-Kit-NOTICE.txt',
 'Hermes-MIT.txt',
 'Home-Assistant-Android-LICENSE.txt',
 'KissFFT-BSD-3-Clause.txt',
 'KissFFT-COPYING.txt',
 'ML-Kit-code-scanner-16.1.0.pom',
 'MODEL-PROVENANCE.txt',
 'NATIVE-DEPENDENCIES.txt',
 'NOTICE.txt',
 'OFL-1.1-Instrument.txt',
 'OFL-1.1.txt',
 'TFLite-Micro-AUTHORS.txt',
 'gemmlowp-AUTHORS.txt',
 'microWakeWord-models-LICENSE.txt',
 'microWakeWord-models-NOTICE.txt',
 'microWakeWord-models-README.md'}
PINNED = {'Android-NDK-r29-NOTICE-toolchain.txt': '789714a9076571c580cf531bdcebec776a4d889c12c3c1108adafc6e4338710a',
 'Android-NDK-r29-NOTICE.txt': 'faf0dc52cf6ce9ab09a04b0c074a378dd2565ee1ed9bc1179d83793bcdba012e',
 'Apache-2.0.txt': 'cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30',
 'Home-Assistant-Android-LICENSE.txt': 'c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4',
 'KissFFT-BSD-3-Clause.txt': '4d6bcab46f27d6d0c878e8305d4bdcfe040d52bf6656b9771f04c9fb9902c0ed',
 'KissFFT-COPYING.txt': 'a2840585f8411be8e6826a31ef15ae65c950bd74a2437a73b013398a934ad0c6',
 'ML-Kit-code-scanner-16.1.0.pom': 'bc2ed0ddc8f2e3a27275d0bea19236fa726054ac1deb66714d13fd11e1530416',
 'OFL-1.1-Instrument.txt': '129ed7618959716959f2941fdd5b49e0ad6e6c1d78726761786a00253d865521',
 'TFLite-Micro-AUTHORS.txt': 'd10975e78ed80f3c138e0b4734db20bbcb0679f43176d28531c542bc2e6be534',
 'gemmlowp-AUTHORS.txt': '916234caa03bbb2769b278e165515a8ca9fa9d8f60b7b57a5dd6a4f026208ce2',
 'microWakeWord-models-LICENSE.txt': 'c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4',
 'microWakeWord-models-NOTICE.txt': 'bf6c416d88749af557faecbaf874861c6a808c5d45f4c28a2e25f9f613ce91ad',
 'microWakeWord-models-README.md': 'bee9ce31bfae0e24aaa1ef71eec099300aaa3486be5e125d32a07831191a674a'}
MODELS = {'hey_jarvis.json': 'b153867d818675d8abcc9dace474afe7f83551ae0d5a9b1d71a98681320185af',
 'hey_jarvis.tflite': '21a7976add39ee24ec96c63d96b7aaa18e24d1d9824b963e451da8feb4b78b77'}
NATIVE_PINS = {'flatbuffers': ('0100f6a5779831fa7a651e4b67ef389a8752bd9b',
                 '85db3520acc4010b21984e2fb5ead3ec0d2c48df8009b614cb73562a82846554'),
 'gemmlowp': ('fda83bdc38b118cc6b56753bd540caa49e570745',
              '0f990732a0d541be514dfc5c1c45969626e9f349faa851baec01b9a9dcb4ae4b'),
 'kissfft': ('7bce4153c6bc8aba2db0e889e576f9d00505cbe1',
             '7ad1124648a46977b16ddde03bf243bcd52fc452516c57016584ab4b4f2baadc'),
 'ruy': ('54774a7a2cf85963777289193629d4bd42de4a59',
         '91993e7eb2aa56e62e9f4abc1158b2b46333574aa81f1a7431b06df80a42b7fa'),
 'tflite_micro': ('2747abd5c82a95fb1624106a946fc671c31f16e8',
                  '6ac0c8ef35f267cf3baf05dc094d4fd158864d4cc830d490b752994e58add8c8')}
MODEL_COMMIT = '40ff33f57f8fc6ad71a75ef085abbf742495b225'
ENGINE_COMMIT = '110809fb146429a613cabeaa0eb2165be57939b9'
OLD_MODEL_HASHES = {
    '94a13cfe60075b132f6a472e7e462e8123ee70861bc3fb58434a73712ee0d2cb',
    '70d164290c1d095d1d4ee149bc5e00543250a7316b59f31d056cff7bd3075c1f',
    'ba2b0e0f8b7b875369a2c89cb13360ff53bac436f2895cced9f479fa65eb176f',
}


def obsolete_asset(name, data):
    return (name.lower().endswith(('.onnx', '.ort')) or '/oww/' in name.lower()
            or 'onnxruntime' in name.lower() or digest(data) in OLD_MODEL_HASHES)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--apk', type=Path, help='Inspect this exact existing APK ZIP; does not build')
    parser.add_argument('--ndk', type=Path, help='Compare notices to the installed pinned NDK; does not install/build')
    parser.add_argument('--verify-upstream', action='store_true', help='Download public source URLs and verify original source hashes (network required)')
    args = parser.parse_args()
    root = args.root.resolve()
    asset_dir = root / 'android/app/src/main/assets/legal'
    errors = []

    def check(ok, message):
        if not ok:
            errors.append(message)

    def read(path):
        try:
            return path.read_bytes()
        except OSError as exc:
            errors.append(f'{path.relative_to(root) if path.is_relative_to(root) else path}: {exc.__class__.__name__}')
            return b''

    def safe_path(relative):
        path = (root / relative).resolve()
        if not path.is_relative_to(root):
            raise ValueError('Inventory path escapes repository')
        return path

    def verify(data, entry, label):
        check(len(data) == entry['bytes'], f'{label}: byte count mismatch')
        check(digest(data) == entry['sha256'], f'{label}: SHA-256 mismatch')

    inventory_bytes = read(root / 'licenses/inventory.json')
    inventory = json.loads(inventory_bytes)
    check(inventory.get('schema_version') == 1, 'Unknown inventory schema')
    check(read(asset_dir / 'inventory.json') == inventory_bytes, 'Source and packaged inventory differ')
    docs = inventory['documents']
    names = [entry['file'] for entry in docs]
    check(len(names) == len(set(names)), 'Duplicate document entries')
    check(set(names) == REQUIRED, 'Required document set changed or incomplete')
    check({p.name for p in asset_dir.iterdir()} == REQUIRED | {'inventory.json'}, 'Unlisted or missing legal asset files')
    texts = {}
    expected_apk = {'assets/legal/inventory.json': inventory_bytes}
    for entry in docs:
        name = entry['file']
        if not re.fullmatch(r'[A-Za-z0-9_.-]+', name):
            raise ValueError('Unsafe document filename')
        data = read(safe_path(entry['repo_path']))
        verify(data, entry, entry['repo_path'])
        mirror = read(asset_dir / name)
        check(mirror == data, f'{name}: canonical text and Android asset differ')
        texts[name] = data.decode('utf-8')
        expected_apk['assets/legal/' + name] = data
        check(bool(entry['title'].strip()), f'{name}: missing reader title')
        if name in PINNED:
            check(digest(data) == PINNED[name], f'{name}: does not match verified authoritative text')
        if entry.get('upstream_exact'):
            check(entry.get('upstream_sha256') == entry['sha256'], f'{name}: upstream-exact assertion inconsistent')

    notice = texts.get('NOTICE.txt', '')
    provenance = texts.get('MODEL-PROVENANCE.txt', '')
    for token in ['explicit weight grant', 'Training provenance remains unresolved', 'not a claim that all training inputs', 'not part of the replacement shipping inventory', 'BSD-3-Clause', 'ML Kit Terms of Service']:
        check(token in notice, 'NOTICE missing required distinction: ' + token)
    for name, author in [('OFL-1.1.txt', 'Copyright 2024 The Geist Project Authors'), ('OFL-1.1-Instrument.txt', 'Copyright 2022 The Instrument Serif Project Authors')]:
        check(texts.get(name, '').startswith(author), name + ': original author credit missing')
        check('SIL OPEN FONT LICENSE Version 1.1' in texts.get(name, ''), name + ': full OFL missing')
    check('trained model weights (.tflite) and their manifests (.json)' in texts.get('microWakeWord-models-NOTICE.txt', ''), 'Publisher per-weight grant missing')
    check('Mark Borgerding' in texts.get('KissFFT-COPYING.txt', ''), 'KissFFT copyright missing')
    check('3. Neither the name' in texts.get('KissFFT-BSD-3-Clause.txt', ''), 'Full KissFFT BSD terms missing')
    check('ML Kit Terms of Service' in texts.get('ML-Kit-code-scanner-16.1.0.pom', ''), 'Official Google terms declaration missing')

    models = inventory['models']
    check(len(models) == len(MODELS), 'Wrong model inventory count')
    check({e['file'] for e in models} == set(MODELS), 'Wrong model inventory set')
    model_dir = root / 'android/app/src/main/assets/microwakeword'
    check({p.name for p in model_dir.glob('*') if p.is_file()} == set(MODELS), 'Replacement model asset set changed')
    # Scan every source set, not only main, and catch old weights renamed to .bin.
    for pattern in ['*/assets/**/*', '*/jniLibs/**/*']:
        for path in (root / 'android/app/src').glob(pattern):
            if path.is_file() and '/legal/' not in path.as_posix():
                check(not obsolete_asset(path.as_posix(), read(path)), 'Obsolete ONNX/ORT source asset: ' + str(path.relative_to(root)))
    for entry in models:
        data = read(safe_path(entry['repo_path']))
        verify(data, entry, entry['repo_path'])
        check(entry['sha256'] == MODELS.get(entry['file']), 'Model differs from audited microWakeWord source: ' + entry['file'])
        check(entry['repo_path'] == 'android/app/src/main/assets/microwakeword/' + entry['file'] and entry['apk_path'] == 'assets/microwakeword/' + entry['file'], 'Model asset path changed')
        check(entry.get('commit') == MODEL_COMMIT and entry.get('model_id') == 'mww_hey_jarvis_v2', 'Model provenance pin changed')
        check(entry['license_status'] == 'Apache-2.0', 'Model weight/manifest license misidentified')
        check(entry['sha256'] in provenance and entry['source_url'] in provenance, 'Human-readable provenance missing: ' + entry['file'])
        expected_apk[entry['apk_path']] = data
    native_dependencies = inventory['native_dependencies']
    check(len(native_dependencies) == len(NATIVE_PINS) and
          {e['name']: (e['commit'], e['archive_sha256']) for e in native_dependencies} == NATIVE_PINS,
          'Native dependency inventory pins changed')
    check(inventory['engine']['commit'] == ENGINE_COMMIT, 'Engine provenance pin changed')
    cmake = read(safe_path(inventory['engine']['cmake_repo_path'])).decode()
    declared = re.findall(r'FetchContent_Declare\(\s+(\w+)\s+URL (\S+).*?URL_HASH SHA256=(\w+)', cmake, re.S)
    check({name: (url, sha) for name, url, sha in declared} ==
          {e['name']: (e['archive_url'], e['archive_sha256']) for e in native_dependencies},
          'Native CMake dependency pins changed; refresh authoritative notices')
    for entry in native_dependencies:
        doc = next((d for d in docs if d['file'] == entry['license_document']), {})
        check(doc.get('sha256') == entry['license_sha256'], 'Native license text mismatch: ' + entry['name'])
        check(entry['commit'] in texts.get('NATIVE-DEPENDENCIES.txt', '') and
              entry['archive_sha256'] in texts.get('NATIVE-DEPENDENCIES.txt', ''),
              'Native human-readable provenance missing: ' + entry['name'])
    fonts = inventory['fonts']
    font_paths = {str(p.relative_to(root)) for p in (root / 'android/app/src/main/res/font').glob('*.ttf')}
    check(len(fonts) == 4 and {e['repo_path'] for e in fonts} == font_paths, 'Font inventory incomplete')
    for entry in fonts:
        data = read(safe_path(entry['repo_path']))
        verify(data, entry, entry['repo_path'])
        expected_apk[entry['apk_path']] = data

    kotlin = root / 'android/app/src/main/java/dev/avifenesh/hermes'
    ui = read(kotlin / 'Ui.kt').decode()
    me = read(kotlin / 'Screens2.kt').decode()
    legal = read(kotlin / 'Legal.kt').decode()
    check('data class LegalR(val document: String? = null) : Route' in ui, 'Legal route missing')
    check('is Route.LegalR -> LicensesScreen(r.document)' in ui, 'Legal route dispatch missing')
    check('"Licenses", "Offline notices, model restrictions and full license texts") { Ui.push(Route.LegalR()) }' in me, 'Me > Licenses entry missing')
    for token in ['context.assets.open("legal/inventory.json")', 'entries.getJSONObject(i)', 'context.assets.open("legal/${it.file}")', 'reader.readText()', 'SelectionContainer', 'LazyColumn', 'chunked(4000)', 'Text(current.paragraphs[i]']:
        check(token in legal, 'Offline reader source contract missing: ' + token)
    # Source contract, not Kotlin compilation or an emulator/rendering assertion.
    for forbidden in ['Api.get(', 'Api.post(', 'WebView', 'ACTION_VIEW', 'maxLines =', 'TextOverflow.Ellipsis']:
        check(forbidden not in legal, 'Reader unexpectedly networks or truncates: ' + forbidden)
    check('Training provenance remains unresolved' in legal and 'explicit Apache-2.0 grant' in legal, 'Reader lacks current model grant/provenance distinction')
    check('Hey Jarvis is CC BY-NC-SA' not in legal, 'Reader retains obsolete classifier warning')
    gradle = read(root / 'android/app/build.gradle.kts').decode()
    check('onnxruntime' not in gradle.lower(), 'Obsolete ONNX Runtime Gradle dependency')
    check(inventory.get('ndk', {}).get('revision') == '29.0.14206865', 'NDK inventory pin changed')
    check('ndkVersion = "29.0.14206865"' in gradle, 'NDK build pin changed; refresh runtime notices')
    if args.ndk:
        check(digest(read(args.ndk / 'source.properties')) == inventory['ndk']['source_properties_sha256'], 'Installed NDK revision differs from audited source')
        for entry in docs:
            if entry.get('ndk_path'):
                check(read(args.ndk / entry['ndk_path']) == read(safe_path(entry['repo_path'])), 'Installed NDK notice differs: ' + entry['file'])
    for coordinate, version in inventory['dependency_contract'].items():
        check(f'implementation("{coordinate}:{version}")' in gradle, 'Dependency changed; refresh authoritative notices: ' + coordinate)

    upstream_verified = []
    if args.verify_upstream:
        remote_entries = [(e['file'], e['source_url'], e['upstream_sha256']) for e in docs if e.get('upstream_sha256') and not e.get('source_archive_member')]
        remote_entries += [(e['file'], e['source_url'], e['sha256']) for e in models]
        remote_entries += [(e['name'] + ' LICENSE', e['license_source_url'], e['license_sha256']) for e in native_dependencies]
        for name, url, sha in remote_entries:
            try:
                with urllib.request.urlopen(url, timeout=90) as response:
                    data = response.read()
                if digest(data) == sha:
                    upstream_verified.append(name)
                else:
                    errors.append('Upstream hash changed: ' + name)
            except Exception as exc:
                errors.append(f'Upstream verification blocked for {name}: {exc.__class__.__name__}: {exc}')

    apk_report = None
    if args.apk:
        with zipfile.ZipFile(args.apk) as apk:
            listed = apk.namelist()
            check(len(listed) == len(set(listed)), 'APK contains duplicate ZIP entry names')
            packaged_legal = {n for n in listed if n.startswith('assets/legal/') and not n.endswith('/')}
            check(packaged_legal == {'assets/legal/' + n for n in REQUIRED | {'inventory.json'}}, 'APK legal asset set mismatch')
            font_resource_paths = {entry['apk_path'] for entry in fonts}
            packaged_fonts = {}
            for name, expected in expected_apk.items():
                if name in font_resource_paths and name not in listed:
                    # AAPT2 shortens resource paths in release APKs. Verify actual res/
                    # bytes, never assume source paths survive resource optimization.
                    matches = [info.filename for info in apk.infolist()
                               if info.filename.startswith('res/') and info.file_size == len(expected)
                               and apk.read(info) == expected]
                    check(bool(matches), 'APK missing font bytes: ' + name)
                    packaged_fonts[name] = matches
                else:
                    check(name in listed, 'APK missing ' + name)
                    if name in listed:
                        check(apk.read(name) == expected, 'APK byte mismatch: ' + name)
            for name in listed:
                check(not obsolete_asset(name, apk.read(name) if name.startswith('assets/') and not name.startswith('assets/legal/') else b''),
                      'APK contains obsolete ONNX/ORT content: ' + name)
            natives = sorted(n for n in listed if n.endswith('/libmicrowakeword.so'))
            check(bool(natives), 'APK missing native microWakeWord engine')
            dex = b''.join(apk.read(n) for n in listed if re.fullmatch(r'classes\d*\.dex', n))
            check(b'ai/onnxruntime/' not in dex and b'ai.onnxruntime.' not in dex, 'APK contains obsolete ONNX Runtime Java classes')
            for token in [b'legal/inventory.json', b'Licenses']:
                check(token in dex, 'APK DEX lacks reader marker: ' + token.decode())
            apk_report = {'path': str(args.apk.resolve()), 'sha256': digest(args.apk.read_bytes()), 'checked_entries': len(expected_apk), 'font_resource_paths': packaged_fonts, 'microwakeword_native_entries': natives, 'old_onnx_ort_absent': not any('obsolete ONNX' in e for e in errors)}

    result = {
        'ok': not errors,
        'scope': 'Source/hash contract' + (' + exact APK byte/reader-marker inspection' if args.apk else '; no APK inspected') + (' + public upstream downloads' if args.verify_upstream else ''),
        'documents': len(docs), 'models': len(models), 'fonts': len(fonts), 'native_dependency_pins': len(native_dependencies),
        'inventory_sha256': digest(inventory_bytes),
        'asset_hashes': {e['file']: e['sha256'] for e in docs},
        'upstream_verified': upstream_verified, 'apk': apk_report,
        'ndk_checked': str(args.ndk.resolve()) if args.ndk else None,
        'upstream_archive_members': [e['file'] for e in docs if e.get('source_archive_member')],
        'remaining_license_blockers': inventory['remaining_blockers'],
        'not_verified': ['Kotlin compilation', 'Device rendering / navigation / accessibility', 'Legal compliance or commercial permission', 'Complete Android native constituent / dependency SBOM'],
        'errors': errors,
    }
    print(json.dumps(result, indent=2))
    return 0 if not errors else 1


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, StopIteration, zipfile.BadZipFile) as exc:
        print(json.dumps({'ok': False, 'error': f'{exc.__class__.__name__}: {exc}'}), file=sys.stderr)
        sys.exit(1)
