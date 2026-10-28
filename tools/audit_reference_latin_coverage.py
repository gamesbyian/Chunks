#!/usr/bin/env python3
"""Audit all mapped Latin characters for reference-glyph design completeness.

Uses real five-weight CFF cmap plus source reconstruction manifests. Distinguishes
Unicode *coverage* from custom-outline provenance, and records canonical
composition complexity without claiming that NFD alone can create good design.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import unicodedata as ud
from collections import Counter
from pathlib import Path

from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[1]
FACES = ROOT / 'assets/fonts/experimental/consolidated'
ACCENT_SOURCE = ROOT / 'assets/fonts/experimental/accent-studies/reference-accent-study-metrics.json'
DOTLESS_SOURCE = ROOT / 'assets/fonts/experimental/dotless-kerned/reference-dotless-kerned-metrics.json'
STYLES = ('Regular', 'Medium', 'Semibold', 'Bold', 'ExtraBold')
ASCII_STEMS = set('ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz')
ORDER = ('unrebuilt_simple_mark', 'unrebuilt_stacked_marks',
         'unrebuilt_other_latin', 'recomposed', 'dotless_rebuilt',
         'reference_base')


def sha(path: Path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def classify(char, reconstructed, dotless):
    if char in dotless:
        return 'dotless_rebuilt'
    if char in reconstructed:
        return 'recomposed'
    if char in ASCII_STEMS:
        return 'reference_base'
    seq = ud.normalize('NFD', char)
    if seq and seq[0] in ASCII_STEMS and len(seq) > 1:
        rest = seq[1:]
        if all(ud.category(mark) == 'Mn' for mark in rest):
            if len(rest) == 1:
                return 'unrebuilt_simple_mark'
            return 'unrebuilt_stacked_marks'
    return 'unrebuilt_other_latin'


def inventory():
    accents = json.loads(ACCENT_SOURCE.read_text())['weights']
    dots = json.loads(DOTLESS_SOURCE.read_text())['weights']
    if set(accents) != set(STYLES) or set(dots) != set(STYLES):
        raise AssertionError('Missing complete source evidence for five weights')
    codepoints = None
    per_style = {}
    chars_by_style = {}
    files = {}
    for weight, style in zip(range(400, 801, 100), STYLES):
        path = FACES / f'ChunksReferencePanel-{style}-Consolidated.otf'
        font = TTFont(path)
        cmap = font.getBestCmap()
        if codepoints is None:
            codepoints = cmap
        elif cmap != codepoints:
            raise AssertionError(f'{style}: Unicode repertoire not identical')
        if (font.sfntVersion != 'OTTO' or 'CFF ' not in font or
                font['OS/2'].usWeightClass != weight or len(cmap) != 856):
            raise AssertionError(f'{style}: non-CFF, missing Unicode or invalid weight')
        rebuilt = {item['glyph'] for item in accents[style]['glyphs']}
        special = {item['glyph'] for item in dots[style]['dotless_glyphs']}
        if rebuilt & special or len(rebuilt) < 240 or len(special) != 14:
            raise AssertionError(f'{style}: incomplete or overlapping accent construction')
        if not all(ord(ch) in cmap for ch in rebuilt | special):
            raise AssertionError(f'{style}: source claims absent mapped glyphs')
        chars_by_style[style] = (rebuilt, special)
        files[style] = {'file': path.relative_to(ROOT).as_posix(),
                        'sha256': sha(path), 'weight': weight}
    latin = [(cp, chr(cp)) for cp in sorted(codepoints)
             if ud.name(chr(cp), '').startswith('LATIN ')
             and ud.category(chr(cp)).startswith('L')]
    if len(latin) < 300:
        raise AssertionError('Unexpectedly sparse Latin repertoire')
    records = []
    counts = {style: Counter() for style in STYLES}
    for cp, char in latin:
        nfd = ud.normalize('NFD', char)
        labels = {}
        for style in STYLES:
            rebuilt, dots = chars_by_style[style]
            labels[style] = classify(char, rebuilt, dots)
            counts[style][labels[style]] += 1
        records.append({'unicode': f'U+{cp:04X}', 'character': char,
                        'name': ud.name(char, 'UNKNOWN'),
                        'canonical_nfd': [f'U+{ord(ch):04X}' for ch in nfd],
                        'base_ascii': nfd[0] if nfd and nfd[0] in ASCII_STEMS else None,
                        'status_by_weight': labels,
                        'unresolved_weights': [style for style in STYLES
                             if labels[style].startswith('unrebuilt_')]})
    summary = {style: {key: counts[style][key] for key in ORDER}
               for style in STYLES}
    unresolved = [x for x in records if x['unresolved_weights']]
    by_bucket = {bucket: [x['unicode'] for x in unresolved
                          if bucket in x['status_by_weight'].values()]
                 for bucket in ORDER[:3]}
    result = {
        'status': 'NONSHIPPING provenance-based Latin design coverage audit',
        'unicode_database_version': ud.unidata_version,
        'source_accent_sha256': sha(ACCENT_SOURCE),
        'source_dotless_sha256': sha(DOTLESS_SOURCE),
        'mapped_total_unicode': len(codepoints),
        'mapped_latin_letter_count': len(latin),
        'source_fonts': files,
        'status_counts': summary,
        'unresolved_any_weight': len(unresolved),
        'unresolved_buckets': by_bucket,
        'latin_letters': records,
        'caveat': 'Manifest-based contour provenance, not a visual or Android acceptance result',
    }
    for style in STYLES:
        if sum(summary[style].values()) != len(latin):
            raise AssertionError('Not all mapped Latin glyphs have a single classification')
    return result


def write(out: Path):
    out.parent.mkdir(parents=True, exist_ok=True)
    report = inventory()
    out.write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--out', type=Path, default=Path('reference-latin-coverage-ledger.json'))
    args = parser.parse_args()
    report = write(args.out)
    print(report['mapped_latin_letter_count'], 'mapped Latin letters,',
          report['unresolved_any_weight'], 'unresolved in at least one face')
    for style, counts in report['status_counts'].items():
        print(style, counts)
