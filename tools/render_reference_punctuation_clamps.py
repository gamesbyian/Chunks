#!/usr/bin/env python3
"""Render worst bounded punctuation pair specimens from real CFF files.

Shows the glyph pair with its originally requested optical XAdvance and the
safe applied value. This is an editorial review atlas, not auto-approval.
"""
from __future__ import annotations

import gzip
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'assets/fonts/experimental/consolidated'
STYLES = ('Regular', 'Medium', 'Semibold', 'Bold', 'ExtraBold')


def draw():
    width = 1510
    per_weight = 11
    panel_height = 85 + per_weight * 39
    image = Image.new('RGB', (width, 110 + panel_height * len(STYLES)), '#F8F9FC')
    ink = ImageDraw.Draw(image)
    label = ImageFont.load_default(size=17)
    header = ImageFont.load_default(size=26)
    ink.text((38, 27), 'CHUNKS | REFERENCE PUNCTUATION OPTICAL CLAMPS',
             fill='#131A28', font=header)
    ink.text((38, 64),
             'Real compiled CFFs | sorted by greatest unapplied correction | not shipping',
             fill='#516075', font=label)
    y = 110
    for style in STYLES:
        archive = SOURCE / f'reference-punctuation-clipped-{style}.json.gz'
        report = json.loads(gzip.decompress(archive.read_bytes()))
        font = ImageFont.truetype(
            str(SOURCE / f'ChunksReferencePanel-{style}-Consolidated.otf'),
            34, layout_engine=ImageFont.Layout.RAQM)
        ink.rounded_rectangle((25, y, width - 25, y + panel_height - 12),
                              radius=12, fill='#FFFFFF', outline='#D8DEE9')
        ink.text((43, y + 14), f'{style}: highest requested optical corrections',
                 fill='#243249', font=header)
        ink.text((740, y + 21), 'Requested / applied / remaining (font units)',
                 fill='#64748B', font=label)
        for index, item in enumerate(report['pairs'][:per_weight]):
            iy = y + 61 + 39 * index
            if index % 2:
                ink.rectangle((36, iy - 3, width - 36, iy + 35),
                              fill='#F5F7FA')
            ink.text((50, iy + 7),
                     f"{item['left_codepoint']} + {item['right_codepoint']}",
                     fill='#687488', font=label)
            ink.text((325, iy - 3), item['pair'], fill='#171C2A', font=font)
            ink.text((740, iy + 7),
                     f"{item['requested_xadvance_units']:+.1f}  / "
                     f"{item['applied_xadvance_units']:+d}  / "
                     f"{item['unapplied_units']:+.1f}",
                     fill='#27344B', font=label)
            ink.text((1180, iy + 7),
                     f"gap {item['before_ink_gap_126px']:+.2f}px",
                     fill='#677489', font=label)
        y += panel_height
    target = Path('reference-punctuation-clamp-review.png')
    image.save(target)
    print(target.resolve())


if __name__ == '__main__':
    draw()
