#!/usr/bin/env python3
"""Render draft Play artwork from the app's existing icon, colors and fonts.

Run with: uv run --with pillow --with cairosvg python scripts/build_play_artwork.py
These are artwork drafts, not screenshots or evidence of store approval.
"""
from __future__ import annotations

import argparse
import io
import json
from pathlib import Path
import xml.etree.ElementTree as ET

import cairosvg
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'


def app_icon(size: int) -> Image.Image:
    source = ROOT / 'android/app/src/main/res/drawable/ic_launcher_fg.xml'
    # Parse only this repository's reviewed static icon XML, never uploaded XML.
    vector = ET.parse(source).getroot()
    group = vector.find('group')
    assert group is not None
    paths = []
    for path in group.findall('path'):
        paths.append('<path d="{}" fill="none" stroke="{}" stroke-width="{}" stroke-linecap="{}"/>'.format(
            path.attrib[ANDROID + 'pathData'], path.attrib[ANDROID + 'strokeColor'],
            path.attrib[ANDROID + 'strokeWidth'], path.attrib[ANDROID + 'strokeLineCap']))
    svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><g transform="translate({} {}) scale({} {})">{}</g></svg>'.format(
        group.attrib[ANDROID + 'translateX'], group.attrib[ANDROID + 'translateY'],
        group.attrib[ANDROID + 'scaleX'], group.attrib[ANDROID + 'scaleY'], ''.join(paths))
    mark = Image.open(io.BytesIO(cairosvg.svg2png(bytestring=svg.encode(), output_width=size, output_height=size))).convert('RGBA')
    colors = {e.attrib['name']: e.text for e in ET.parse(ROOT / 'android/app/src/main/res/values/colors.xml').getroot()}
    image = Image.new('RGBA', (size, size), colors['lime'])
    image.alpha_composite(mark)
    return image.convert('RGB')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'docs/play/assets')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    icon = app_icon(512)
    icon.save(args.output / 'icon-512.png', optimize=True)
    image = Image.new('RGB', (1024, 500), '#0C0C0E')
    draw = ImageDraw.Draw(image)
    font_dir = ROOT / 'android/app/src/main/res/font'
    serif = ImageFont.truetype(str(font_dir / 'instrument_serif.ttf'), 73)
    sans = ImageFont.truetype(str(font_dir / 'geist.ttf'), 26)
    small = ImageFont.truetype(str(font_dir / 'geist.ttf'), 20)
    image.paste(app_icon(104), (64, 56))
    draw.text((64, 184), 'Hermes for Android', font=serif, fill='#F4F2ED')
    draw.text((67, 288), 'Your agent. Your gateway.', font=sans, fill='#D4F75C')
    draw.line((68, 357, 954, 357), fill='#343438', width=2)
    draw.text((67, 391), 'Chat  /  Approvals  /  Voice  /  Schedules', font=small, fill='#C8C6C1')
    image.save(args.output / 'feature-1024x500.png', optimize=True)
    for filename, size in [('icon-512.png', (512, 512)), ('feature-1024x500.png', (1024, 500))]:
        with Image.open(args.output / filename) as check:
            assert check.size == size and check.mode == 'RGB'
    print(json.dumps({'artwork': 'draft', 'icon': '512x512 RGB PNG', 'feature': '1024x500 RGB PNG',
                      'source': 'existing app icon, color tokens and bundled OFL fonts', 'output': str(args.output)}, indent=2))


if __name__ == '__main__':
    main()
