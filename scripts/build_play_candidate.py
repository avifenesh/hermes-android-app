#!/usr/bin/env python3
"""Build Play artifacts and bind actual Gradle outputs to a content-hashed source tree."""
from __future__ import annotations

from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BUILD_COMMAND = ['./gradlew', '--offline', '--max-workers=2', ':app:testPlayDebugUnitTest', ':app:lintPlayRelease', ':app:assemblePlayRelease', ':app:bundlePlayRelease']
APK = 'android/app/build/outputs/apk/play/release/app-play-release.apk'
BUNDLE = 'android/app/build/outputs/bundle/playRelease/app-play-release.aab'
LINT = 'android/app/build/reports/lint-results-playRelease.xml'
PROVENANCE = ROOT / 'dist/build-provenance-play.json'


def digest(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def source_snapshot(root: Path) -> dict[str, str]:
    """All tracked and nonignored source files, including dirty/new files, not secrets/builds."""
    raw = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=root)
    paths = sorted({os.fsdecode(name) for name in raw.split(b'\0') if name})
    return {name: digest(root / name) for name in paths if (root / name).is_file()}


def output_snapshot(root: Path) -> dict[str, str]:
    reports = sorted((root / 'android/app/build/test-results/testPlayDebugUnitTest').glob('TEST-*.xml'))
    assert reports, 'Missing Play unit reports'
    paths = [root / name for name in (APK, BUNDLE, LINT)] + reports
    assert all(path.is_file() for path in paths), 'Missing build artifact or report'
    return {str(path.relative_to(root)): digest(path) for path in paths}


def validate_provenance(record: dict, sources: dict[str, str], outputs: dict[str, str]) -> None:
    assert record.get('format') == 1 and record.get('return_code') == 0, 'Missing successful build provenance'
    assert record.get('command') == BUILD_COMMAND, 'Unexpected provenance build command'
    assert record.get('sources') == sources, 'Build provenance source snapshot differs; rerun build'
    assert record.get('outputs') == outputs, 'Build provenance artifact/report hashes differ; rerun build'


def main() -> None:
    PROVENANCE.parent.mkdir(parents=True, exist_ok=True)
    if PROVENANCE.is_file():
        PROVENANCE.rename(PROVENANCE.with_name('build-provenance-play.previous-' + digest(PROVENANCE)[:12] + '.json'))
    before = source_snapshot(ROOT)
    started = datetime.now(timezone.utc).isoformat()
    env = dict(os.environ)
    assert env.get('JAVA_HOME'), 'Set JAVA_HOME to a supported JDK 21 installation'
    try:
        with (ROOT / 'dist/build-play-gradle.log').open('w') as log:
            result = subprocess.run(BUILD_COMMAND, cwd=ROOT / 'android', env=env, stdout=log, stderr=subprocess.STDOUT, timeout=540)
        assert result.returncode == 0, 'Gradle failed; see dist/build-play-gradle.log'
        after = source_snapshot(ROOT)
        assert before == after, 'Source changed during build; rerun after changes finish'
        record = {'format': 1, 'command': BUILD_COMMAND, 'return_code': result.returncode,
                  'started_at': started, 'finished_at': datetime.now(timezone.utc).isoformat(),
                  'build_head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                  'sources': after, 'outputs': output_snapshot(ROOT)}
        validate_provenance(record, after, output_snapshot(ROOT))
        PROVENANCE.write_text(json.dumps(record, indent=2, sort_keys=True) + '\n')
        print(json.dumps({'build_provenance': str(PROVENANCE), 'source_files': len(after), 'outputs': len(record['outputs'])}))
    finally:
        stopped = subprocess.run(['./gradlew', '--stop'], cwd=ROOT / 'android', env=env, capture_output=True, text=True, timeout=60)
        (ROOT / 'dist/build-play-gradle-stop.log').write_text(stopped.stdout + stopped.stderr)
        assert stopped.returncode == 0, 'Gradle daemon shutdown failed'


if __name__ == '__main__':
    main()
