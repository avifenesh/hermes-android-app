#!/usr/bin/env python3
"""Render the Play store icon and feature graphic from the Play edition's launcher icon, colors and fonts.

Run with: uv run --with pillow --with cairosvg python scripts/build_play_artwork.py
The icon is the Herms launcher icon (src/play adaptive icon), so the store and the phone show the same mark.
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


def play_icon(size: int, crop: float = 76) -> Image.Image:
    """The Play edition's adaptive launcher icon, so the store icon is the icon on the phone.

    Reads src/play's adaptive-icon XML for its background colour and foreground vector, renders the foreground's filled
    paths, and shows the centred `crop` dp of the 108 dp canvas (launchers show about 72 dp; Play rounds the corners).
    """
    res = ROOT / 'android/app/src/play/res'
    adaptive = ET.parse(res / 'mipmap-anydpi-v26/ic_launcher.xml').getroot()
    background = adaptive.find('background').attrib[ANDROID + 'drawable']
    foreground = adaptive.find('foreground').attrib[ANDROID + 'drawable']
    assert background.startswith('@color/') and foreground.startswith('@drawable/'), (background, foreground)
    colors = {e.attrib['name']: e.text for e in ET.parse(ROOT / 'android/app/src/main/res/values/colors.xml').getroot()}
    # Parse only this repository's reviewed static icon XML, never uploaded XML.
    vector = ET.parse(res / 'drawable' / (foreground.split('/', 1)[1] + '.xml')).getroot()
    assert vector.attrib[ANDROID + 'viewportWidth'] == '108' and vector.find('group') is None
    paths = ''.join('<path d="{}" fill="{}" fill-opacity="{}" fill-rule="{}"/>'.format(
        p.attrib[ANDROID + 'pathData'], p.attrib[ANDROID + 'fillColor'], p.attrib.get(ANDROID + 'fillAlpha', '1'),
        'evenodd' if p.attrib.get(ANDROID + 'fillType') == 'evenOdd' else 'nonzero') for p in vector.findall('path'))
    off = (108 - crop) / 2
    svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="{0} {0} {1} {1}"><rect x="0" y="0" width="108" height="108" fill="{2}"/>{3}</svg>'.format(
        off, crop, colors[background.split('/', 1)[1]], paths)
    return Image.open(io.BytesIO(cairosvg.svg2png(bytestring=svg.encode(), output_width=size, output_height=size))).convert('RGB')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'docs/play/assets')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    icon = play_icon(512)
    icon.save(args.output / 'icon-512.png', optimize=True)
    image = Image.new('RGB', (1024, 500), '#0C0C0E')
    draw = ImageDraw.Draw(image)
    font_dir = ROOT / 'android/app/src/main/res/font'
    serif = ImageFont.truetype(str(font_dir / 'instrument_serif.ttf'), 73)
    sans = ImageFont.truetype(str(font_dir / 'geist.ttf'), 26)
    small = ImageFont.truetype(str(font_dir / 'geist.ttf'), 20)
    image.paste(play_icon(112, crop=60), (60, 52))  # the seal fills its slot; the ink background blends in
    draw.text((64, 184), 'Herms', font=serif, fill='#F4F2ED')
    draw.text((67, 288), 'For Hermes Agent. Your agent, your gateway.', font=sans, fill='#FF8A66')  # the seal's coral
    draw.line((68, 357, 954, 357), fill='#343438', width=2)
    draw.text((67, 391), 'Chat  /  Approvals  /  Voice  /  Schedules', font=small, fill='#C8C6C1')
    image.save(args.output / 'feature-1024x500.png', optimize=True)
    for filename, size in [('icon-512.png', (512, 512)), ('feature-1024x500.png', (1024, 500))]:
        with Image.open(args.output / filename) as check:
            assert check.size == size and check.mode == 'RGB'
    print(json.dumps({'artwork': 'draft', 'icon': '512x512 RGB PNG', 'feature': '1024x500 RGB PNG',
                      'source': 'Play adaptive launcher icon, color tokens and bundled OFL fonts', 'output': str(args.output)}, indent=2))


if __name__ == '__main__':
    main()
