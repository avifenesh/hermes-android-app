#!/usr/bin/env python3
"""Render the public Play policy from the same Markdown bundled into the APK.

uv run --with markdown python scripts/build_play_policy.py
The Worker serves these static files; no report text is interpolated into HTML.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import markdown

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    source = ROOT / 'docs/play/privacy-policy.md'
    text = source.read_text()
    assert text == (ROOT / 'android/app/src/play/assets/privacy/privacy-policy.md').read_text()
    body = markdown.markdown(text, extensions=['tables'])
    page = '<!doctype html>\n<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Hermes for Android: privacy</title><link rel="stylesheet" href="/style.css"></head><body><header>HERMES / ANDROID</header><main>' + body + '<p class="footer"><a href="/delete">Report-data deletion</a></p></main></body></html>\n'
    (ROOT / 'report-worker/privacy.html').write_text(page)
    print(json.dumps({'policy_markdown_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
                      'privacy_html_sha256': hashlib.sha256(page.encode()).hexdigest(),
                      'scope': 'full Play policy with voluntary reporting, not a store approval'}, indent=2))


if __name__ == '__main__':
    main()
