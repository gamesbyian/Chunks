#!/usr/bin/env python3
"""Real-CFF comparison for source-refused mixed above/below diacritic designs."""
from __future__ import annotations
import json
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
FONTS = ROOT / 'assets/fonts/experimental/mixed-mark-studies'
STYLES = ('Regular', 'Medium', 'Semibold', 'Bold', 'ExtraBold')
SIZE_PIXELS = (14, 24, 48, 82)


def render():
    data = json.loads((FONTS / 'reference-mixed-mark-metrics.json').read_text())
    rows = sorted({r['glyph'] for d in data['weights'].values()
                   for r in d['rebuilt'] + d['skipped']}, key=ord)
    if not rows:
        raise AssertionError('No mixed-mark research candidates')
    cell_height = 120
    width = 135 + 5 * 210
    # Keep every row visible, including unchanged cases, in each font size.
    for px in SIZE_PIXELS:
        image = Image.new('RGB', (width, 100 + len(rows) * cell_height), '#F8FAFD')
        draw = ImageDraw.Draw(image)
        title = ImageFont.load_default(size=25)
        lab = ImageFont.load_default(size=16)
        draw.text((20, 14), f'CHUNKS | MIXED ABOVE/BELOW | {px}px',
                  font=title, fill='#1C283C')
        draw.text((20, 52), 'B: rebuilt from separately detached marks | F: fallback source',
                  font=lab, fill='#52647D')
        faces = {style: ImageFont.truetype(
            str(FONTS / f'ChunksReferenceMixedMarks-{style}.otf'),
            px, layout_engine=ImageFont.Layout.RAQM) for style in STYLES}
        for j, style in enumerate(STYLES):
            draw.text((140+j*210, 80), style, font=lab, fill='#324258')
        for i, char in enumerate(rows):
            y = 100 + i * cell_height
            if i % 2 == 0:
                draw.rectangle((0, y, width, y + cell_height), fill='#FFFFFF')
            draw.text((12, y+9), f'U+{ord(char):04X}', font=lab, fill='#65748A')
            for j, style in enumerate(STYLES):
                built = {r['glyph'] for r in data['weights'][style]['rebuilt']}
                x = 130 + j*210
                draw.line((x+10, y+88, x+196, y+88), fill='#DCE4EF')
                draw.text((x+110, y+88), char, anchor='ms',
                          font=faces[style], fill='#102038')
                draw.text((x+10, y+8), 'B' if char in built else 'F',
                          font=lab, fill='#53657C')
        target = Path(f'reference-mixed-marks-{px}px.png')
        image.save(target)
        print(target.resolve())


if __name__ == '__main__':
    render()
