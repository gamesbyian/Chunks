#!/usr/bin/env python3
"""Separately named, reversible mixed ABOVE + BELOW Latin mark CFF experiment.

Only replace stacked characters explicitly skipped by the previous above-only
study. Accept source contours wholly detached outside the ASCII stem and with
Unicode NFD combining classes for *both* above and below marks. Do not guess at
attached horns, cedillas, or integrated letter features; fail closed.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import tempfile
import unicodedata as ud
from collections import Counter
from pathlib import Path
from fontTools.ttLib import TTFont
from fontTools.pens.recordingPen import replayRecording
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.t2CharStringPen import T2CharStringPen
from build_reference_stacked_accents import (
    WEIGHTS, ASCII, TARGET_GAP, MAX_TOP, MIN_BOTTOM, MIN_TOP_GAP,
    stacked_candidates,
)
from build_reference_accent_studies import bounds, contours, glyph_record
from build_reference_accent_kerned import optimize

# Explicit, conservative canonical classes. Others are refused rather than
# incorrectly assigning linguistically unusual mark attachment directions.
ABOVE_CLASSES = {230}
BELOW_CLASSES = {202, 220}


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def directions(marks: str):
    values = [ud.combining(ch) for ch in marks]
    if not values or any(v not in ABOVE_CLASSES | BELOW_CLASSES for v in values):
        return None
    if not any(v in ABOVE_CLASSES for v in values):
        return None
    if not any(v in BELOW_CLASSES for v in values):
        return None
    return {'above': sum(v in ABOVE_CLASSES for v in values),
            'below': sum(v in BELOW_CLASSES for v in values)}


def compose(original_path: Path, stacked_path: Path, source_evidence: dict, output: Path):
    original = TTFont(original_path, recalcTimestamp=False)
    prior = TTFont(stacked_path, recalcTimestamp=False)
    if original.sfntVersion != 'OTTO' or prior.sfntVersion != 'OTTO':
        raise AssertionError('Only native CFF fonts permitted')
    cmap = original.getBestCmap()
    if len(cmap) != 856 or prior.getBestCmap() != cmap:
        raise AssertionError('Mismatched source and stacked Unicode maps')
    original_set = original.getGlyphSet()
    final_set = prior.getGlyphSet()
    charstrings = prior['CFF '].cff.topDictIndex[0].CharStrings
    skipped_previous = {r['glyph'] for r in source_evidence['skipped']}
    built = []
    skipped = []
    for char, base, glyph, marks_nfd in stacked_candidates(original):
        if char not in skipped_previous:
            continue
        allowed = directions(marks_nfd)
        if allowed is None:
            skipped.append({'glyph': char, 'reason': 'mixed_direction_not_supported'})
            continue
        oldbb = bounds(glyph_record(original_set, cmap[ord(base)]), original_set)
        newbase = glyph_record(final_set, cmap[ord(base)])
        newbb = bounds(newbase, final_set)
        if not oldbb or not newbb:
            skipped.append({'glyph': char, 'reason': 'empty_base'})
            continue
        grouped = {'above': [], 'below': []}
        for path in contours(glyph_record(original_set, glyph)):
            bb = bounds(path, original_set)
            if bb is None:
                continue
            if bb[1] > oldbb[3] + MIN_TOP_GAP:
                grouped['above'].append((path, bb))
            elif bb[3] < oldbb[1] - MIN_TOP_GAP:
                grouped['below'].append((path, bb))
        if any(len(grouped[d]) < allowed[d] for d in allowed):
            skipped.append({'glyph': char, 'reason': 'unseparated_directional_marks',
                            'detached_above': len(grouped['above']),
                            'detached_below': len(grouped['below'])})
            continue
        # The source glyph might have split ink islands (e.g., two dot paths).
        # Keep all islands in their original relative positions within each side.
        xshift = round((newbb[0] + newbb[2] - oldbb[0] - oldbb[2]) / 2)
        lowest_above = min(bb[1] for _, bb in grouped['above'])
        highest_below = max(bb[3] for _, bb in grouped['below'])
        up = round(max(0, newbb[3] + TARGET_GAP - lowest_above))
        down = round(min(0, newbb[1] - TARGET_GAP - highest_below))
        moved = [newbb]
        for side, dy in (('above', up), ('below', down)):
            for _, bb in grouped[side]:
                moved.append((bb[0] + xshift, bb[1] + dy,
                              bb[2] + xshift, bb[3] + dy))
        box = (min(v[0] for v in moved), min(v[1] for v in moved),
               max(v[2] for v in moved), max(v[3] for v in moved))
        if box[1] < MIN_BOTTOM or box[3] > MAX_TOP:
            skipped.append({'glyph': char, 'reason': 'vertical_limit',
                            'box': [round(v, 2) for v in box]})
            continue
        pen = T2CharStringPen(width=0, glyphSet=final_set)
        replayRecording(newbase, pen)
        for side, dy in (('above', up), ('below', down)):
            transform = TransformPen(pen, (1, 0, 0, 1, xshift, dy))
            for segment, _ in grouped[side]:
                replayRecording(segment, transform)
        top = prior['CFF '].cff.topDictIndex[0]
        newchar = pen.getCharString(private=top.Private,
                                     globalSubrs=prior['CFF '].cff.GlobalSubrs)
        newchar.compile()
        if not newchar.bytecode:
            raise AssertionError('Empty mixed-direction CFF path')
        charstrings[glyph] = newchar
        prior['hmtx'][glyph] = (prior['hmtx'][cmap[ord(base)]][0], round(box[0]))
        built.append({'glyph': char, 'base': base,
                      'unicode': f'U+{ord(char):04X}',
                      'nfd_marks': [f'U+{ord(m):04X}' for m in marks_nfd],
                      'separated_above': len(grouped['above']),
                      'separated_below': len(grouped['below']),
                      'x_shift': xshift, 'above_y_shift': up,
                      'below_y_shift': down,
                      'bbox': [round(v, 2) for v in box]})
    output.parent.mkdir(parents=True, exist_ok=True)
    prior.recalcTimestamp = False
    prior.save(output)
    verified = TTFont(output)
    if verified.getBestCmap() != cmap or not {'GPOS','GDEF','GSUB','CFF '}.issubset(verified.keys()):
        raise AssertionError('Mixed study lost shaping or Unicode')
    return {'previously_skipped': len(skipped_previous),
            'rebuilt_count': len(built), 'rebuilt': built,
            'skipped_count': len(skipped), 'skipped': skipped,
            'skip_reasons': dict(Counter(x['reason'] for x in skipped))}


def rename(path: Path, style: str):
    f = TTFont(path, recalcTimestamp=False)
    family = 'Chunks Reference Mixed Marks'
    postscript = f'ChunksReferenceMixedMarks-{style}'
    for nameid, value in ((1, family), (16, family), (2, style),
                          (17, style), (4, f'{family} {style}'),
                          (6, postscript), (3, f'Version 0.100; {postscript}'),
                          (5, 'Version 0.100; nonshipping bi-directional mark experiment')):
        for platform, encoding, lang in ((3, 1, 0x409), (1, 0, 0)):
            f['name'].setName(value, nameid, platform, encoding, lang)
    cff = f['CFF '].cff
    cff.fontNames = [postscript]
    cff.topDictIndex[0].FullName = f'{family} {style}'
    cff.topDictIndex[0].FamilyName = family
    cff.topDictIndex[0].Weight = style
    f.recalcTimestamp = False
    f.save(path)


def build(originals: Path, stacked: Path, out: Path, only=None):
    ledger = json.loads((stacked / 'reference-stacked-accent-metrics.json').read_text())
    chosen = only or list(WEIGHTS)
    if any(x not in WEIGHTS for x in chosen):
        raise ValueError('Unsupported weight')
    results = {}
    out.mkdir(parents=True, exist_ok=True)
    for weight in chosen:
        style = WEIGHTS[weight]
        original = originals / f'Chunks-{style}.otf'
        previous = stacked / f'ChunksReferencePanel-{style}-Stacked.otf'
        if digest(previous) != ledger['weights'][style]['output_sha256']:
            raise AssertionError('Stale source Stacked CFF')
        output = out / f'ChunksReferenceMixedMarks-{style}.otf'
        with tempfile.TemporaryDirectory() as folder:
            raw = Path(folder) / 'raw.otf'
            result = compose(original, previous, ledger['weights'][style], raw)
            # No kerning builder if no safe new contours for this weight.
            if result['rebuilt']:
                opt = optimize(raw, output, result['rebuilt'], max_delta=120)
            else:
                output.write_bytes(raw.read_bytes())
                opt = {'adjusted_pairs': 0, 'clipped_pair_count': 0}
        rename(output, style)
        if 'output_sha256' in opt:
            opt['pre_family_name_sha256'] = opt.pop('output_sha256')
        opt['output_sha256'] = digest(output)
        final = TTFont(output)
        if (final.getBestCmap() != TTFont(previous).getBestCmap() or
                final['OS/2'].usWeightClass != weight):
            raise AssertionError('Font output corrupted')
        results[style] = {**result, 'weight': weight,
                          'input_original_sha256': digest(original),
                          'input_stacked_sha256': digest(previous),
                          'output_sha256': digest(output),
                          'file': output.name, 'kerning': opt}
        print(style, result['rebuilt_count'], 'new mixed-direction glyphs; skipped',
              result['skipped_count'], flush=True)
    report = {'status': 'NONSHIPPING conservative mixed above/below accent CFF study',
              'stacked_source_metrics_sha256': digest(stacked / 'reference-stacked-accent-metrics.json'),
              'policy': 'NFD class 230 above, 202/220 below, source-only detached contours, ±font bbox',
              'weights': results}
    (out / 'reference-mixed-mark-metrics.json').write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + '\n')
    return report


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--originals', type=Path, default=Path('assets/fonts/chunks'))
    p.add_argument('--stacked', type=Path, default=Path('assets/fonts/experimental/stacked-studies'))
    p.add_argument('--out', type=Path, default=Path('assets/fonts/experimental/mixed-mark-studies'))
    p.add_argument('--weights', type=int, nargs='*')
    args = p.parse_args()
    build(args.originals, args.stacked, args.out, args.weights)
