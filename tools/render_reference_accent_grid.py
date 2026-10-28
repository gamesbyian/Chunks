#!/usr/bin/env python3
"""Render every reconstructed Chunks Latin accent from actual five-weight CFFs.

Produces paginated editorial sheets at 14/24/48/82px and a source-hashed
coverage matrix. A rendered glyph is not a hand-approved glyph: the legend
distinguishes rebuilt/dotless outlines from fallback shapes in each weight.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import unicodedata
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[1]
ACCENTS = ROOT / 'assets/fonts/experimental/accent-studies/reference-accent-study-metrics.json'
DOTLESS = ROOT / 'assets/fonts/experimental/dotless-kerned/reference-dotless-kerned-metrics.json'
FACES = ROOT / 'assets/fonts/experimental/consolidated'
STYLES = ('Regular', 'Medium', 'Semibold', 'Bold', 'ExtraBold')
SIZES = (14, 24, 48, 82)
ROWS_PER_PAGE = 72


def digest(path: Path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def font_files():
    return {style: FACES / f'ChunksReferencePanel-{style}-Consolidated.otf'
            for style in STYLES}


def coverage():
    accents = json.loads(ACCENTS.read_text())['weights']
    dots = json.loads(DOTLESS.read_text())['weights']
    if set(accents) != set(STYLES) or set(dots) != set(STYLES):
        raise AssertionError('Missing an experimental source accent weight')
    weight_sets = {}
    all_chars = set()
    for style in STYLES:
        base = {row['glyph'] for row in accents[style]['glyphs']}
        special = {row['glyph'] for row in dots[style]['dotless_glyphs']}
        if len(special) != 14 or base & special:
            raise AssertionError(f'{style}: missing or duplicate source accent forms')
        if len(base) < 240:
            raise AssertionError(f'{style}: unexpected limited accent provenance')
        weight_sets[style] = {'recomposed': base, 'dotless': special}
        all_chars |= base | special
    # Canonical sort groups related accented letters by base, then code point.
    def order(char):
        decomposed = unicodedata.normalize('NFD', char)
        key = decomposed[0] if decomposed else char
        return (key.casefold(), key != key.upper(), ord(char))
    characters = ''.join(sorted(all_chars, key=order))
    if len(characters) < 250:
        raise AssertionError('Insufficient reconstructed Latin character coverage')
    return characters, weight_sets


def verify_fonts(fonts, chars):
    expected = None
    result = {}
    for index, style in enumerate(STYLES):
        file = fonts[style]
        font = TTFont(file)
        cmap = font.getBestCmap()
        if font.sfntVersion != 'OTTO' or 'CFF ' not in font:
            raise AssertionError(f'{style} is not compiled CFF OpenType')
        if len(cmap) != 856 or any(ord(ch) not in cmap for ch in chars):
            raise AssertionError(f'{style} is missing a documented accent')
        if expected is None:
            expected = cmap
        elif expected != cmap:
            raise AssertionError('Five weight font Unicode maps differ')
        weight = 400 + index * 100
        if font['OS/2'].usWeightClass != weight:
            raise AssertionError(f'{style}: unexpected weight metadata')
        result[style] = {
            'file': file.relative_to(ROOT).as_posix(),
            'sha256': digest(file), 'weight': weight, 'codepoints': len(cmap),
        }
    return result


def draw_page(chars, weight_sets, fonts, size, page, out):
    nrows = len(chars)
    if not nrows or nrows > ROWS_PER_PAGE:
        raise ValueError('Page must contain 1..72 glyphs')
    width = max(1100, 130 + 5 * math.ceil(size * 1.7 + 100))
    col = (width - 150) // 5
    row_height = max(64, math.ceil(size * 2.25))
    header_height = 116
    height = header_height + nrows * row_height + 24
    image = Image.new('RGB', (width, height), '#F7F9FC')
    ink = ImageDraw.Draw(image)
    heading = ImageFont.load_default(size=24)
    small = ImageFont.load_default(size=15)
    ink.text((20, 15), f'CHUNKS | LATIN ACCENT GLYPHS | {size}px | page {page}',
             font=heading, fill='#182235')
    ink.text((21, 51), 'Native compiled CFFs  |  R = reference recomposition  |  D = custom dotless form',
             font=small, fill='#5B6B82')
    faces = {style: ImageFont.truetype(str(fonts[style]), size,
                                      layout_engine=ImageFont.Layout.RAQM)
             for style in STYLES}
    for i, style in enumerate(STYLES):
        ink.text((136 + i * col, 88), style, font=small, fill='#34425C')
    for j, char in enumerate(chars):
        y = header_height + j * row_height
        ink.rectangle((0, y, width, y + row_height),
                      fill=('#FFFFFF' if j % 2 == 0 else '#F0F4F9'))
        ink.text((14, y + 7), f'U+{ord(char):04X}', font=small, fill='#65748A')
        ink.text((15, y + 29), unicodedata.normalize('NFD', char)[0],
                 font=small, fill='#5B6B82')
        baseline = y + round(row_height * .79)
        for i, style in enumerate(STYLES):
            x = 125 + i * col
            if i:
                ink.line((x, y + 2, x, y + row_height - 2),
                         fill='#E0E5EE', width=1)
            ink.line((x + 6, baseline, x + col - 8, baseline),
                     fill='#DCE3ED', width=1)
            face = faces[style]
            ink.text((x + col // 2, baseline), char, anchor='ms',
                     font=face, fill='#162032')
            mode = ('D' if char in weight_sets[style]['dotless'] else
                    'R' if char in weight_sets[style]['recomposed'] else 'F')
            ink.text((x + 8, y + 8), mode, font=small, fill='#4A5D75')
    filename = f'reference-accent-grid-{size}px-p{page:02d}.png'
    target = out / filename
    image.save(target)
    return {'file': filename, 'sha256': digest(target),
            'size_px': [width, height], 'rows': nrows, 'weight_columns': 5,
            'characters': chars}


def generate(out=Path('.'), sizes=SIZES, selected=None):
    if not sizes or any(size not in SIZES for size in sizes) or len(set(sizes)) != len(sizes):
        raise ValueError('Sizes must be unique supported 14/24/48/82px values')
    characters, weight_sets = coverage()
    if selected is not None:
        if not selected or len(set(selected)) != len(selected):
            raise ValueError('Selection must be nonempty with unique characters')
        if any(ch not in characters for ch in selected):
            raise ValueError('Selection includes unrecognized reconstructed accents')
        characters = selected
    files = font_files()
    face_metrics = verify_fonts(files, characters)
    per_style = {style: {
        'recomposed': sum(ch in weight_sets[style]['recomposed'] for ch in characters),
        'dotless': sum(ch in weight_sets[style]['dotless'] for ch in characters),
        'fallback': sum(ch not in weight_sets[style]['recomposed'] |
                        weight_sets[style]['dotless'] for ch in characters),
    } for style in STYLES}
    out.mkdir(parents=True, exist_ok=True)
    pages = {}
    for size in sizes:
        rows = []
        for start in range(0, len(characters), ROWS_PER_PAGE):
            rows.append(draw_page(characters[start:start + ROWS_PER_PAGE],
                                  weight_sets, files, size, len(rows) + 1, out))
        pages[str(size)] = rows
    report = {
        'status': 'NONSHIPPING editor review; source-recomposed and dotless accent glyphs',
        'source_accent_metrics_sha256': digest(ACCENTS),
        'source_dotless_metrics_sha256': digest(DOTLESS),
        'full_scope': selected is None,
        'characters': characters, 'character_count': len(characters),
        'source_classes_by_style': per_style,
        'fonts': face_metrics, 'sizes': list(sizes), 'pages': pages,
        'approval': 'Visual, native Android 200% and TalkBack approval still required',
    }
    (out / 'reference-accent-grid-metrics.json').write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + '\n')
    return report


if __name__ == '__main__':
    cli = argparse.ArgumentParser()
    cli.add_argument('--out', type=Path, default=Path('.'))
    cli.add_argument('--sizes', type=int, nargs='*', default=list(SIZES))
    arg = cli.parse_args()
    report = generate(arg.out, arg.sizes)
    print(report['character_count'], 'accents', sum(map(len, report['pages'].values())),
          'real-font pages')
