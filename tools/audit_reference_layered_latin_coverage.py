#!/usr/bin/env python3
"""Exhaustive source-provenance ledger for all 495 mapped Latin letters.

Compares the actual compiled Consolidated, Stacked and Mixed CFF families. This
does not equate glyph availability or automatic geometry assembly with human
editorial approval, and does not touch installed Android font resources.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path
from fontTools.ttLib import TTFont
from audit_reference_latin_coverage import inventory, STYLES

ROOT = Path(__file__).resolve().parents[1]
STACK = ROOT / 'assets/fonts/experimental/stacked-studies'
MIXED = ROOT / 'assets/fonts/experimental/mixed-mark-studies'


def digest(path: Path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inspect():
    baseline = inventory()
    stack_path = STACK / 'reference-stacked-accent-metrics.json'
    mixed_path = MIXED / 'reference-mixed-mark-metrics.json'
    stacked = json.loads(stack_path.read_text())
    mixed = json.loads(mixed_path.read_text())
    if set(stacked['weights']) != set(STYLES) or set(mixed['weights']) != set(STYLES):
        raise AssertionError('Layered source evidence must include all five weights')
    evidence = {}
    for style in STYLES:
        a = stacked['weights'][style]
        b = mixed['weights'][style]
        source_stacked = STACK / f'ChunksReferencePanel-{style}-Stacked.otf'
        source_mixed = MIXED / f'ChunksReferenceMixedMarks-{style}.otf'
        if (a['output_sha256'] != digest(source_stacked) or
                b['input_stacked_sha256'] != digest(source_stacked) or
                b['output_sha256'] != digest(source_mixed)):
            raise AssertionError(f'{style}: stale stacked or mixed output font hashes')
        f = TTFont(source_mixed)
        if f.sfntVersion != 'OTTO' or 'CFF ' not in f or len(f.getBestCmap()) != 856:
            raise AssertionError(f'{style}: not a complete native CFF')
        if f['OS/2'].usWeightClass != 400 + 100 * STYLES.index(style):
            raise AssertionError(f'{style}: inconsistent font weight')
        a_set = {x['glyph'] for x in a['built']}
        b_set = {x['glyph'] for x in b['rebuilt']}
        if len(a_set) != a['built_count'] or len(b_set) != b['rebuilt_count']:
            raise AssertionError(f'{style}: duplicate source-derived characters')
        if a_set & b_set:
            raise AssertionError(f'{style}: overlapping stacked and mixed provenance')
        old_skip = {x['glyph'] for x in a['skipped']}
        if not b_set <= old_skip:
            raise AssertionError(f'{style}: mixed overlay edits a previously accepted glyph')
        evidence[style] = {
            'stacked_built': a_set, 'mixed_built': b_set,
            'stacked_sha256': digest(source_stacked), 'mixed_sha256': digest(source_mixed),
        }
    records = []
    summary = {}
    for original in baseline['latin_letters']:
        row = dict(original)
        layered = {}
        for style in STYLES:
            old = original['status_by_weight'][style]
            char = original['character']
            e = evidence[style]
            if char in e['stacked_built']:
                if old != 'unrebuilt_stacked_marks':
                    raise AssertionError(f'{style} {char}: unexpected stacked classification')
                status = 'stacked_above'
            elif char in e['mixed_built']:
                if old != 'unrebuilt_stacked_marks':
                    raise AssertionError(f'{style} {char}: unexpected mixed classification')
                status = 'mixed_above_below'
            else:
                status = old
            layered[style] = status
        row['layered_status_by_weight'] = layered
        row['layered_unresolved_weights'] = [
            style for style in STYLES if layered[style].startswith('unrebuilt_')]
        records.append(row)
    for style in STYLES:
        counts = Counter(row['layered_status_by_weight'][style] for row in records)
        initial = sum(r['status_by_weight'][style].startswith('unrebuilt_')
                      for r in records)
        after = sum(r['layered_status_by_weight'][style].startswith('unrebuilt_')
                    for r in records)
        if initial - after != (len(evidence[style]['stacked_built']) +
                              len(evidence[style]['mixed_built'])):
            raise AssertionError(f'{style}: unresolved delta cannot be reconciled')
        summary[style] = {
            'mapped_latin_letters': len(records),
            'unresolved_before': initial,
            'reconstructed_stacked_above': counts['stacked_above'],
            'reconstructed_mixed_above_below': counts['mixed_above_below'],
            'unresolved_after': after,
            'remaining_simple_mark': counts['unrebuilt_simple_mark'],
            'remaining_stacked_marks': counts['unrebuilt_stacked_marks'],
            'remaining_other_latin': counts['unrebuilt_other_latin'],
            'stacked_font_sha256': evidence[style]['stacked_sha256'],
            'mixed_font_sha256': evidence[style]['mixed_sha256'],
        }
    return {
        'status': 'NONSHIPPING source-provenance audit; not visual/editorial acceptance',
        'mapped_unicode': baseline['mapped_total_unicode'],
        'mapped_latin_letters': len(records),
        'baseline_source_status': 'Consolidated original + reference reconstructed characters',
        'baseline_manifest_sha256': digest(
            ROOT / 'assets/fonts/experimental/consolidated/reference-panel-consolidated-metrics.json'),
        'stacked_manifest_sha256': digest(stack_path),
        'mixed_manifest_sha256': digest(mixed_path),
        'weights': summary, 'latin_letters': records,
        'acceptance': 'Independent artwork, semantic mark placement and Android Compose/TalkBack required',
    }


def write(output: Path):
    r = inspect()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(r, indent=2, ensure_ascii=False) + '\n')
    return r


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--out', type=Path, default=Path('reference-layered-latin-coverage.json'))
    a = p.parse_args()
    report = write(a.out)
    for style, row in report['weights'].items():
        print(style, 'remaining', row['unresolved_after'], 'of',
              row['mapped_latin_letters'], 'Latin glyph designs')
