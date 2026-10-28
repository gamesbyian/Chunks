#!/usr/bin/env python3
"""Render all 62 Chunks reference glyphs from compiled CFF fonts, at four sizes.

These are nonshipping editorial grids. No glyph outline is inferred from an
image and no Android resource is altered. Actual CFF shaping is used.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[1]
STUDIES = ROOT / 'assets/fonts/experimental/weight-studies'
HEAVY = ROOT / 'assets/fonts/experimental/ChunksReferencePanel-ExtraBold-Optical.otf'
GLYPHS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
STYLES = ('Regular', 'Medium', 'Semibold', 'Bold', 'ExtraBold')
SIZES = (14, 24, 48, 82)


def font_paths() -> dict[str, Path]:
    result = {style: STUDIES / f'ChunksReferencePanel-{style}-Study.otf'
              for style in STYLES[:-1]}
    result['ExtraBold'] = HEAVY
    return result


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_fonts(paths: dict[str, Path]):
    font_meta = {}
    expected_cmap = None
    for style in STYLES:
        path = paths[style]
        if not path.exists():
            raise FileNotFoundError(path)
        font = TTFont(path)
        cmap = font.getBestCmap()
        if font.sfntVersion != 'OTTO' or 'CFF ' not in font:
            raise AssertionError(f'{style}: genuine CFF/OpenType required')
        if len(cmap) != 856 or any(ord(ch) not in cmap for ch in GLYPHS):
            raise AssertionError(f'{style}: incomplete source repertoire')
        if expected_cmap is None:
            expected_cmap = cmap
        elif expected_cmap != cmap:
            raise AssertionError(f'{style}: Unicode mapping differs across weights')
        expected_weight = (STYLES.index(style) + 4) * 100
        if font['OS/2'].usWeightClass != expected_weight:
            raise AssertionError(f'{style}: expected weight {expected_weight}')
        font_meta[style] = {'file': path.relative_to(ROOT).as_posix(),
                            'sha256': sha(path), 'weight': expected_weight,
                            'codepoints': len(cmap)}
    return font_meta


def draw_one(paths: dict[str, Path], px: int, out: Path) -> dict:
    if px not in SIZES:
        raise ValueError('Only the approved 14/24/48/82px review sizes are supported')
    left = 102
    col_width = max(186, math.ceil(px * 2.35 + 35))
    row_height = max(67, math.ceil(px * 1.90))
    header = 92
    footer = 20
    width = left + col_width * len(STYLES) + 18
    height = header + row_height * len(GLYPHS) + footer
    image = Image.new('RGB', (width, height), '#F8F9FC')
    d = ImageDraw.Draw(image)
    lab = ImageFont.load_default(size=15)
    top = ImageFont.load_default(size=24)
    d.text((22, 12), f'CHUNKS REFERENCE PANEL | ALL 62 GLYPHS | {px}px',
           font=top, fill='#141E2D')
    d.text((23, 48), 'Real CFF outlines • Regular to ExtraBold • nonshipping review only',
           font=lab, fill='#526279')
    faces = {style: ImageFont.truetype(str(paths[style]), px,
                                      layout_engine=ImageFont.Layout.RAQM)
             for style in STYLES}
    for index, style in enumerate(STYLES):
        x = left + index * col_width
        d.text((x + 12, 67), style, font=lab, fill='#314256')
    for row, char in enumerate(GLYPHS):
        top_y = header + row * row_height
        d.rectangle((0, top_y, width, top_y + row_height),
                    fill=('#FFFFFF' if row % 2 == 0 else '#F1F4F8'))
        d.text((16, top_y + 7), f'U+{ord(char):04X}', font=lab, fill='#607086')
        d.text((37, top_y + 31), char, font=lab, fill='#24344B')
        baseline = top_y + int(row_height * .76)
        for i, style in enumerate(STYLES):
            x = left + i * col_width
            if i:
                d.line((x, top_y + 3, x, top_y + row_height - 3),
                       fill='#DCE3EC', width=1)
            d.line((x + 9, baseline, x + col_width - 9, baseline),
                   fill='#E2E7EE', width=1)
            face = faces[style]
            bounds = face.getbbox(char, anchor='ls')
            if bounds and (bounds[1] < -int(row_height * .76) or
                           bounds[3] > row_height - int(row_height * .76)):
                raise AssertionError(f'{style} {char} clipped at {px}px: {bounds}')
            d.text((x + col_width // 2, baseline), char, anchor='ms',
                   font=face, fill='#111A28')
    filename = f'reference-panel-glyph-grid-{px}px.png'
    target = out / filename
    image.save(target)
    return {'file': filename, 'sha256': sha(target),
            'size_px': [width, height], 'point_size_px': px,
            'glyph_rows': len(GLYPHS), 'weight_columns': len(STYLES)}


def generate(out: Path = Path('.'), sizes=SIZES):
    if any(s not in SIZES for s in sizes) or len(set(sizes)) != len(sizes):
        raise ValueError('Repeated or unsupported grid sizes')
    paths = font_paths()
    meta = verify_fonts(paths)
    out.mkdir(parents=True, exist_ok=True)
    pages = {str(px): draw_one(paths, px, out) for px in sizes}
    report = {'status': 'NONSHIPPING native-CFF glyph-by-glyph editorial review',
              'glyphs': GLYPHS, 'glyph_count': len(GLYPHS), 'styles': meta,
              'pages': pages,
              'review_required': ('Independent contour/counter/spacing approval at all sizes; '
                                  'Android 200% scaling and TalkBack remain untested')}
    (out / 'reference-panel-glyph-grid-metrics.json').write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + '\n')
    return report


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--out', type=Path, default=Path('.'))
    p.add_argument('--sizes', type=int, nargs='*', default=list(SIZES))
    a = p.parse_args()
    report = generate(a.out, a.sizes)
    for size, page in report['pages'].items():
        print(size, page['file'], page['size_px'], page['sha256'])
