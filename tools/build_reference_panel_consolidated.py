#!/usr/bin/env python3
"""Assemble five nonshipping reference fonts with traced punctuation.

Input: the already-proven DotlessKerned faces (62 reference glyphs, reconstructed
Latin accents, i/j compounds, contextual pair positioning). This layer replaces
only 28 traced punctuation outlines, then remeasures pairs that contain changed
punctuation on those *final* CFF curves. Earlier experiments remain byte intact.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import tempfile
import unicodedata
from pathlib import Path

import numpy as np
from PIL import ImageFont, features
from fontTools.ttLib import TTFont
from fontTools.pens.t2CharStringPen import T2CharStringPen
from fontTools.pens.transformPen import TransformPen

from build_chunks_fonts import CANDIDATE_ALPHABET, get_mask
from build_reference_accent_kerned import WEIGHTS, _append_kern
from build_reference_panel_weight_studies import (
    SCALE, draw_geometry, source_polygons, subtract_stroke, topology,
)
from spacing_optimizer import ink_gaps_at_size, measured_presets

PUNCT_BASELINE = 568  # normalized source-image row, matching manifest
LEFT_OFFSET = 52
EXPECTED = '!?.,:;“”()[]{}@#$%&*+-=<>/\\"'
SUPPLEMENTAL = "'‘’…"
ALL_PUNCTUATION = EXPECTED + SUPPLEMENTAL
# Supplemental glyphs are explicitly derived from reference contours, not
# presented as separately traced originals. Other punctuation remains inherited.
SAMPLES = (
    'Chunks! Files (156)', 'Downloading 68%', 'TORRENTS, YOUR WAY.',
    'A/B\\C: 2026-10-27', 'Torrents [1] {2}', '“Résumé” & Music',
    'What? 100% complete.', 'Seeding 8/12 @ 4.2 MB/s',
    "It's 'Chunks'… and we're downloading.", '“They’re here…” she said.',
)
SIZES = (14, 24, 48, 82)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def apply_punctuation(source: Path, masters: Path, out: Path, weight: int,
                      profile: dict) -> dict:
    """Safely overlay 28 SVG shapes; never mutate the input OTF or SVG sources."""
    manifest = json.loads((masters / 'manifest.json').read_text())
    source_glyphs = manifest['glyphs']
    if set(source_glyphs) != set(EXPECTED) or len(source_glyphs) != 28:
        raise AssertionError('Expected exactly 28 source punctuation mappings')
    supplemental_dir = masters / 'supplemental'
    supplements = json.loads((supplemental_dir / 'manifest.json').read_text())
    if set(supplements['glyphs']) != set(SUPPLEMENTAL):
        raise AssertionError('Missing four independently editable derivative glyphs')
    if supplements['source_original_manifest_sha256'] != digest(masters / 'manifest.json'):
        raise AssertionError('Derived glyphs were generated against a different reference manifest')
    source_glyphs = {**source_glyphs, **supplements['glyphs']}
    font = TTFont(source, recalcBBoxes=True, recalcTimestamp=False)
    cmap = font.getBestCmap()
    if font.sfntVersion != 'OTTO' or 'CFF ' not in font:
        raise AssertionError('Only the native CFF reference candidates are accepted')
    missing = [c for c in ALL_PUNCTUATION if ord(c) not in cmap]
    if missing:
        raise AssertionError(f'Punctuation absent from the source cmap: {missing!r}')
    characters = font['CFF '].cff.topDictIndex[0].CharStrings
    geom_report = {}
    for char, item in source_glyphs.items():
        svg_dir = supplemental_dir if char in SUPPLEMENTAL else masters
        svg = svg_dir / f'U+{ord(char):04X}.svg'
        original = source_polygons(svg, min_area=0.15)
        # Punctuation contains many naturally fragile strokes. Back off the
        # source's letter-eroding profile and preserve all components/counters.
        offset = profile['per_weight_erosion_in_reference_pixels'][str(weight)] * 0.42
        if offset:
            edited, clamps = subtract_stroke(original, offset, 0.08)
        else:
            edited, clamps = original, []
        if topology(original) != topology(edited) or edited.is_empty:
            raise AssertionError(f'Punctuation topology damaged: {char!r}')
        y0 = item['source_bbox'][1]
        pen = T2CharStringPen(width=0, glyphSet=font.getGlyphSet())
        transform = (SCALE, 0, 0, -SCALE, LEFT_OFFSET,
                     SCALE * (PUNCT_BASELINE - y0))
        draw_geometry(edited, TransformPen(pen, transform))
        cff = font['CFF '].cff.topDictIndex[0]
        charstring = pen.getCharString(private=cff.Private,
                                      globalSubrs=font['CFF '].cff.GlobalSubrs)
        charstring.compile()
        if not charstring.bytecode:
            raise AssertionError(f'Empty compiled glyph {char!r}')
        glyph = cmap[ord(char)]
        characters[glyph] = charstring
        advance = round((item['width'] + 6) * SCALE)
        lsb = round(LEFT_OFFSET + edited.bounds[0] * SCALE)
        font['hmtx'][glyph] = (advance, lsb)
        geom_report[f'U+{ord(char):04X}'] = {
            'source_svg_sha256': digest(svg),
            'source_bbox': item['source_bbox'],
            'topology': topology(edited),
            'ink_area_ratio': round(edited.area / original.area, 5),
            'erosion_reference_px': round(offset, 3),
            'backoff_factors': clamps,
            'advance_units': advance, 'lsb_units': lsb,
        }
    font.recalcTimestamp = False
    out.parent.mkdir(parents=True, exist_ok=True)
    font.save(out)
    check = TTFont(out)
    if check.getBestCmap() != cmap or not {'GDEF', 'GSUB', 'GPOS'}.issubset(check.keys()):
        raise AssertionError('Unicode or native shaping tables changed')
    return geom_report


def punctuation_kern(raw: Path, output: Path, weight: int) -> dict:
    """Bounded additive GPOS corrections for changed punctuation pairs only."""
    if not features.check_feature('raqm'):
        raise RuntimeError('Pillow with HarfBuzz/libraqm required')
    font = TTFont(raw, recalcTimestamp=False)
    cmap = font.getBestCmap()
    punct = set(ALL_PUNCTUATION)
    # Include source punctuation, Latin base and existing common accented forms,
    # leaving unrelated scripts and bare letter-to-letter kerning untouched.
    chars = list(dict.fromkeys(CANDIDATE_ALPHABET + ALL_PUNCTUATION))
    chars = [c for c in chars if ord(c) in cmap]
    pil = ImageFont.truetype(str(raw), 126, layout_engine=ImageFont.Layout.RAQM)
    masks = {c: get_mask(pil, c) for c in chars}
    base_advances = {c: pil.getlength(c, features=['-kern']) for c in chars}
    target = 126 * (0.115 - 0.030 * (weight - 400) / 400)
    minimum = 126 * 0.022
    records = []
    sampled = 0
    clipped = 0
    clipped_pairs = []
    for first in chars:
        if not masks[first]:
            continue
        for second in chars:
            if first not in punct and second not in punct:
                continue
            if not masks[second]:
                continue
            useful = [y for y in masks[first].keys() & masks[second].keys()
                      if -110 <= y <= 30]
            if len(useful) < 8:
                continue
            distances = [base_advances[first] + masks[second][y][0]
                         - masks[first][y][1] for y in useful]
            raw_gap = float(np.percentile(distances, 18))
            shaping = (pil.getlength(first + second) -
                       pil.getlength(first) - pil.getlength(second))
            shaped_gap = raw_gap + shaping
            change = target - shaped_gap
            if change < 0:
                change = max(change, minimum - shaped_gap)
            bounded = max(-126 * 0.072, min(126 * 0.072, change))
            was_clipped = abs(change - bounded) > 0.001
            clipped += was_clipped
            units = round(bounded * font['head'].unitsPerEm / 126)
            if was_clipped:
                requested = change * font['head'].unitsPerEm / 126
                clipped_pairs.append({
                    'pair': first + second,
                    'left_codepoint': f'U+{ord(first):04X}',
                    'right_codepoint': f'U+{ord(second):04X}',
                    'requested_xadvance_units': round(requested, 3),
                    'applied_xadvance_units': units,
                    'unapplied_units': round(requested - units, 3),
                    'before_ink_gap_126px': round(shaped_gap, 4),
                    'estimated_after_ink_gap_126px': round(shaped_gap + bounded, 4),
                    'target_ink_gap_126px': round(target, 4),
                })
            sampled += 1
            if abs(units) >= 13:
                records.append((cmap[ord(first)], cmap[ord(second)], units))
    if not records:
        raise AssertionError('No punctuation-context optical adjustments measured')
    lookup_count = _append_kern(font, records)
    style = WEIGHTS[weight]
    family = 'Chunks Reference Panel Consolidated'
    for name_id, name in (
        (1, family), (16, family), (2, style), (17, style),
        (4, f'{family} {style}'), (6, f'ChunksReferencePanelConsolidated-{style}'),
        (3, f'Version 0.400; ChunksReferencePanelConsolidated-{style}'),
        (5, 'Version 0.400; nonshipping punctuation/Latin design experiment'),
    ):
        for plat, encoding, lang in ((3, 1, 0x409), (1, 0, 0)):
            font['name'].setName(name, name_id, plat, encoding, lang)
    # The internal CFF Name INDEX and Top DICT must agree with OpenType name
    # IDs or installing this alternate beside DotlessKerned may collide.
    cff = font['CFF '].cff
    postscript = f'ChunksReferencePanelConsolidated-{style}'
    cff.fontNames = [postscript]
    cff.topDictIndex[0].FamilyName = family
    cff.topDictIndex[0].FullName = f'{family} {style}'
    cff.topDictIndex[0].Weight = style
    os2 = font['OS/2']
    os2.fsSelection = (os2.fsSelection & ~((1 << 5) | (1 << 6))) | (
        (1 << 5) if weight >= 700 else (1 << 6))
    font['head'].macStyle = (font['head'].macStyle & ~1) | (weight >= 700)
    font.recalcTimestamp = False
    font.save(output)
    final = TTFont(output)
    if final.getBestCmap() != cmap:
        raise AssertionError('Unicode changed during kerning')
    if final['hmtx'].metrics != font['hmtx'].metrics:
        raise AssertionError('Kerning accidentally changed advance metrics')
    clipped_pairs.sort(key=lambda x: (-abs(x['unapplied_units']), x['pair']))
    return ({'sampled_punctuation_pairs': sampled,
             'adjusted_punctuation_pairs': len(records),
             'clipped_at_72_units': clipped,
             'worst_clipped_pair_examples': clipped_pairs[:12],
             'bounded_pairpos_subtables': lookup_count}, clipped_pairs)


def run(source_dir: Path, masters: Path, profile_file: Path, out: Path,
        weights: list[int] | None = None) -> dict:
    profile = json.loads(profile_file.read_text())
    master_manifest = masters / 'manifest.json'
    if not master_manifest.is_file():
        raise FileNotFoundError(master_manifest)
    selected = weights or list(WEIGHTS)
    if any(w not in WEIGHTS for w in selected):
        raise ValueError('Unsupported font weight')
    out.mkdir(parents=True, exist_ok=True)
    report = {'status': 'NONSHIPPING reference-panel consolidated candidate; editorial review pending',
              'punctuation_count': len(ALL_PUNCTUATION),
              'original_reference_punctuation_count': len(EXPECTED),
              'supplemental_derived_punctuation_count': len(SUPPLEMENTAL),
              'supplemental_manifest_sha256': digest(masters / 'supplemental' / 'manifest.json'),
              'source_manifest_sha256': digest(master_manifest),
              'weight_profile_sha256': digest(profile_file),
              'intentional_source_fallbacks': ['untraced dashes and punctuation'],
              'weights': {}}
    for weight in selected:
        style = WEIGHTS[weight]
        source = source_dir / f'ChunksReferencePanel-{style}-DotlessKerned.otf'
        output = out / f'ChunksReferencePanel-{style}-Consolidated.otf'
        with tempfile.TemporaryDirectory() as temp:
            raw = Path(temp) / 'punctuation-raw.otf'
            geometry = apply_punctuation(source, masters, raw, weight, profile)
            kern, clipped_pairs = punctuation_kern(raw, output, weight)
        # Preserve every out-of-bounds desired delta in a deterministic
        # compressed sidecar, rather than silently dropping raw measurements.
        audit_path = out / f'reference-punctuation-clipped-{style}.json.gz'
        audit = {'schema': 1, 'weight': weight, 'style': style,
                 'source_font_sha256': digest(source),
                 'punctuation_references': len(ALL_PUNCTUATION),
                 'unit': 'font units per 1000 em; optical gaps sampled at 126px',
                 'pairs': clipped_pairs}
        audit_data = json.dumps(audit, ensure_ascii=False, sort_keys=True,
                                separators=(',', ':')).encode('utf-8')
        audit_path.write_bytes(gzip.compress(audit_data, compresslevel=9, mtime=0))
        kern['clipped_pair_report'] = {'file': audit_path.name,
                                       'sha256': digest(audit_path),
                                       'count': len(clipped_pairs),
                                       'uncompressed_bytes': len(audit_data)}
        final = TTFont(output)
        first = TTFont(source)
        if final.getBestCmap() != first.getBestCmap():
            raise AssertionError('Lost Unicode mappings')
        if len(final.getBestCmap()) != 856 or final['OS/2'].usWeightClass != weight:
            raise AssertionError('Font repertoire or weight changed')
        tracking, leading, raw_metrics = measured_presets(output, final, weight)
        gaps = {str(s): round(min(ink_gaps_at_size(output, SAMPLES, px=s)), 4)
                for s in SIZES}
        report['weights'][style] = {
            'font': output.name, 'weight': weight, 'input_sha256': digest(source),
            'output_sha256': digest(output), 'unicode_count': len(final.getBestCmap()),
            'preserved_source_shaping': True, 'punctuation': geometry,
            'kerning': kern, 'tracking_em': tracking, 'leading_em': leading,
            'tracking_measurements': raw_metrics, 'small_size_minimum_gaps_px': gaps,
        }
        print(style, len(geometry), 'punctuation glyphs', kern['adjusted_punctuation_pairs'],
              'new kern pairs', gaps, flush=True)
    metrics = out / 'reference-panel-consolidated-metrics.json'
    # For incremental debugging, retain earlier rows only when their exact
    # generated OTFs and matching source input checksums are still present.
    if len(selected) < len(WEIGHTS) and metrics.exists():
        prior = json.loads(metrics.read_text())
        if (prior.get('source_manifest_sha256') == report['source_manifest_sha256']
                and prior.get('weight_profile_sha256') == report['weight_profile_sha256']):
            for style, item in prior.get('weights', {}).items():
                if style in report['weights'] or style not in WEIGHTS.values():
                    continue
                upstream = source_dir / f'ChunksReferencePanel-{style}-DotlessKerned.otf'
                candidate = out / f'ChunksReferencePanel-{style}-Consolidated.otf'
                artifact = item.get('kerning', {}).get('clipped_pair_report', {})
                audit_path = out / artifact.get('file', 'missing')
                if (upstream.exists() and candidate.exists() and audit_path.is_file()
                        and item['input_sha256'] == digest(upstream)
                        and item['output_sha256'] == digest(candidate)
                        and artifact.get('sha256') == digest(audit_path)):
                    report['weights'][style] = item
    report['weights'] = {style: report['weights'][style]
                         for style in WEIGHTS.values() if style in report['weights']}
    metrics.write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--source-dir', type=Path,
                        default=Path('assets/fonts/experimental/dotless-kerned'))
    parser.add_argument('--masters', type=Path,
                        default=Path('assets/fonts/source/reference-punctuation'))
    parser.add_argument('--profile', type=Path, default=Path('tools/reference_weight_profiles.json'))
    parser.add_argument('--out', type=Path,
                        default=Path('assets/fonts/experimental/consolidated'))
    parser.add_argument('--weights', type=int, nargs='*')
    args = parser.parse_args()
    run(args.source_dir, args.masters, args.profile, args.out, args.weights)
