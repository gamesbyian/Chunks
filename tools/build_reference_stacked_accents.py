#!/usr/bin/env python3
"""Conservative nonshipping study of native stacked Latin accent compositions.

Builds from the existing *Consolidated* five-weight CFFs. Reuses unscaled
separable marks from the matching OFL source font only when they are entirely
above the original ASCII stem and consist of at least two independent paths.
Ambiguous, attached, mixed-side, i/j and too-tall forms remain original.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import tempfile
import unicodedata as ud
from pathlib import Path

from fontTools.ttLib import TTFont
from fontTools.pens.recordingPen import replayRecording
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.t2CharStringPen import T2CharStringPen

from build_reference_accent_studies import bounds, contours, glyph_record
from build_reference_accent_kerned import optimize, WEIGHTS

ASCII = set('ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghklmnopqrstuvwxyz')  # i/j excluded
MIN_TOP_GAP = 6
TARGET_GAP = 28
MAX_TOP = 1040
MIN_BOTTOM = -480


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def stacked_candidates(source: TTFont):
    cmap = source.getBestCmap()
    for cp, name in sorted(cmap.items()):
        char = chr(cp)
        if not ud.name(char, '').startswith('LATIN '):
            continue
        sequence = ud.normalize('NFD', char)
        if (len(sequence) < 3 or sequence[0] not in ASCII or
                not all(ud.category(mark) == 'Mn' for mark in sequence[1:])):
            continue
        yield char, sequence[0], name, ''.join(sequence[1:])


def create(source: Path, reference: Path, out: Path):
    original = TTFont(source, recalcTimestamp=False)
    final = TTFont(reference, recalcTimestamp=False)
    if (original.getBestCmap() != final.getBestCmap() or
            original.sfntVersion != 'OTTO' or final.sfntVersion != 'OTTO'):
        raise AssertionError('Expected cmap-identical original and experimental CFF fonts')
    cmap = original.getBestCmap()
    oldset = original.getGlyphSet()
    newset = final.getGlyphSet()
    charstrings = final['CFF '].cff.topDictIndex[0].CharStrings
    accepted, skipped = [], []
    candidates = list(stacked_candidates(original))
    for char, base, glyph, marks_nfd in candidates:
        old_base = glyph_record(oldset, cmap[ord(base)])
        new_base = glyph_record(newset, cmap[ord(base)])
        oldbb = bounds(old_base, oldset)
        newbb = bounds(new_base, newset)
        if not oldbb or not newbb:
            skipped.append({'glyph': char, 'reason': 'empty_base'})
            continue
        source_compound = glyph_record(oldset, glyph)
        marks = []
        for segment in contours(source_compound):
            bb = bounds(segment, oldset)
            if bb is not None and bb[1] > oldbb[3] + MIN_TOP_GAP:
                marks.append((segment, bb))
        # Each Unicode mark must yield distinct ink: do not guess which of
        # a single enclosed/attached contour was meant to be an accent.
        if len(marks) < len(marks_nfd):
            skipped.append({'glyph': char, 'reason': 'unseparated_marks'})
            continue
        xdelta = round((newbb[0] + newbb[2] - oldbb[0] - oldbb[2]) / 2)
        lower_mark = min(bb[1] for _, bb in marks)
        ydelta = round(max(0, newbb[3] + TARGET_GAP - lower_mark))
        shifted = [(bb[0] + xdelta, bb[1] + ydelta,
                    bb[2] + xdelta, bb[3] + ydelta) for _, bb in marks]
        allbb = [newbb] + shifted
        bbox = (min(bb[0] for bb in allbb), min(bb[1] for bb in allbb),
                max(bb[2] for bb in allbb), max(bb[3] for bb in allbb))
        if bbox[1] < MIN_BOTTOM or bbox[3] > MAX_TOP:
            skipped.append({'glyph': char, 'reason': 'vertical_limit'})
            continue
        # Verify we are not combining any mark path that actually intersects
        # the custom stem after translation. Group topology is preserved.
        if any(bb[1] < newbb[3] + TARGET_GAP - 1 for bb in shifted):
            skipped.append({'glyph': char, 'reason': 'mark_clearance'})
            continue
        pen = T2CharStringPen(width=0, glyphSet=newset)
        replayRecording(new_base, pen)
        tpen = TransformPen(pen, (1, 0, 0, 1, xdelta, ydelta))
        for contour, _ in marks:
            replayRecording(contour, tpen)
        top = final['CFF '].cff.topDictIndex[0]
        newchar = pen.getCharString(private=top.Private, globalSubrs=final['CFF '].cff.GlobalSubrs)
        newchar.compile()
        if not newchar.bytecode:
            raise AssertionError('Empty stacked CFF character')
        charstrings[glyph] = newchar
        final['hmtx'][glyph] = (final['hmtx'][cmap[ord(base)]][0], round(bbox[0]))
        accepted.append({'glyph': char, 'base': base, 'marks_nfd': [f'U+{ord(m):04X}' for m in marks_nfd],
                         'mark_contours': len(marks), 'x_shift': xdelta, 'y_shift': ydelta,
                         'bbox': [round(v, 3) for v in bbox]})
    if len(accepted) < 5:
        raise AssertionError(f'Only {len(accepted)} safely separable stacked accents')
    out.parent.mkdir(parents=True, exist_ok=True)
    final.recalcTimestamp = False
    final.save(out)
    after = TTFont(out)
    if after.getBestCmap() != cmap or not {'GDEF', 'GSUB', 'GPOS'}.issubset(after.keys()):
        raise AssertionError('CFF stacked study damaged Unicode/shaping')
    return {'eligible_stacked_non_ij': len(candidates), 'built': accepted,
            'skipped': skipped, 'built_count': len(accepted),
            'skipped_count': len(skipped)}


def rename(path: Path, style: str):
    f = TTFont(path, recalcTimestamp=False)
    family = 'Chunks Reference Panel Stacked'
    postscript = f'ChunksReferencePanelStacked-{style}'
    for nid, value in ((1, family), (16, family), (2, style), (17, style),
                       (4, f'{family} {style}'), (6, postscript),
                       (3, f'Version 0.500; {postscript}'),
                       (5, 'Version 0.500; nonshipping stacked accent experiment')):
        for platform, encoding, lang in ((3, 1, 0x409), (1, 0, 0)):
            f['name'].setName(value, nid, platform, encoding, lang)
    cff = f['CFF '].cff
    cff.fontNames = [postscript]
    cff.topDictIndex[0].FullName = f'{family} {style}'
    cff.topDictIndex[0].FamilyName = family
    cff.topDictIndex[0].Weight = style
    f.recalcTimestamp = False
    f.save(path)


def build(originals: Path, consolidated: Path, out: Path, weights=None):
    chosen = weights or list(WEIGHTS)
    if any(w not in WEIGHTS for w in chosen):
        raise ValueError('Unknown CFF weight')
    out.mkdir(parents=True, exist_ok=True)
    report = {'status': 'NONSHIPPING safe stacked-above Latin accent reconstruction study',
              'policy': 'Only distinct detached ABOVE mark contours, non-i/j ASCII base, no overflow',
              'weights': {}}
    for w in chosen:
        style = WEIGHTS[w]
        source = originals / f'Chunks-{style}.otf'
        base = consolidated / f'ChunksReferencePanel-{style}-Consolidated.otf'
        target = out / f'ChunksReferencePanel-{style}-Stacked.otf'
        with tempfile.TemporaryDirectory() as temp:
            raw = Path(temp) / 'stacked-raw.otf'
            result = create(source, base, raw)
            optimization = optimize(raw, target, result['built'], max_delta=120)
        rename(target, style)
        after = TTFont(target)
        if (after.getBestCmap() != TTFont(source).getBestCmap()
                or len(after.getBestCmap()) != 856 or after['OS/2'].usWeightClass != w):
            raise AssertionError('Stacked fonts have changed mapping or weight')
        report['weights'][style] = {
            **result, 'weight': w, 'file': target.name,
            'source_sha256': sha(source), 'input_consolidated_sha256': sha(base),
            'output_sha256': sha(target), 'kerning': optimization,
        }
        print(style, len(result['built']), 'stacked reconstructed of',
              result['eligible_stacked_non_ij'], 'eligible', flush=True)
    dest = out / 'reference-stacked-accent-metrics.json'
    dest.write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n')
    return report


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--originals', type=Path, default=Path('assets/fonts/chunks'))
    p.add_argument('--consolidated', type=Path, default=Path('assets/fonts/experimental/consolidated'))
    p.add_argument('--out', type=Path, default=Path('assets/fonts/experimental/stacked-studies'))
    p.add_argument('--weights', nargs='*', type=int)
    a = p.parse_args()
    build(a.originals, a.consolidated, a.out, a.weights)
