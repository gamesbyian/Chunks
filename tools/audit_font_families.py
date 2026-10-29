#!/usr/bin/env python3
"""Read-only, cross-family Chunks OTF integrity and typography acceptance audit.

Never writes OTF binaries. Runs after the independent source generators have
created their distinct main/experimental candidate families.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import re
from pathlib import Path

from fontTools.ttLib import TTFont
from PIL import ImageFont

WEIGHTS = {"Regular": 400, "Medium": 500, "Semibold": 600, "Bold": 700, "ExtraBold": 800}
SAMPLE_TEXT = ("Chunks", "Downloading 68%", "The.Wanderer.2024.mkv", "Résumé télévision", "Österreich", "přehled", "0123456789", "हिन्दी")
NON_LATIN_SAMPLES = ("हिन्दी", "देवनागरी")


def _sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _font(path: Path):
    font = TTFont(path)
    if font.sfntVersion != 'OTTO' or 'CFF ' not in font or 'glyf' in font:
        raise AssertionError(f"{path}: not a CFF OpenType font")
    if not {'GPOS', 'GSUB', 'GDEF', 'OS/2', 'hhea', 'cmap', 'name'}.issubset(font.keys()):
        raise AssertionError(f"{path}: layout or metrics tables missing")
    return font


def _metric_close(a: TTFont, b: TTFont, path: Path):
    for metric in ('ascent', 'descent', 'lineGap'):
        if getattr(a['hhea'], metric) != getattr(b['hhea'], metric):
            raise AssertionError(f"{path}: {metric} drift")
    for metric in ('sTypoAscender', 'sTypoDescender', 'sTypoLineGap'):
        if getattr(a['OS/2'], metric) != getattr(b['OS/2'], metric):
            raise AssertionError(f"{path}: {metric} drift")


def audit(root: Path):
    fontroot = root/'assets/fonts'
    base_path = fontroot/'chunks'
    study_path = fontroot/'experimental/weight-studies'
    accent_path = fontroot/'experimental/accent-studies'
    experiments = fontroot/'experimental'
    source_license = fontroot/'source/OFL.txt'
    android_font = root/'app/src/main/res/font/chunks_extrabold.otf'
    android_license = root/'app/src/main/assets/licenses/Baloo2-OFL.txt'
    if not source_license.read_bytes() == android_license.read_bytes():
        raise AssertionError('Android OFL license differs from source')
    if _sha(android_font) != _sha(experiments/'consolidated/ChunksReferencePanel-ExtraBold-Consolidated.otf'):
        raise AssertionError('Android installed ExtraBold differs from the owner-approved reference-panel consolidated master')

    accent_metrics = json.loads((accent_path/'reference-accent-study-metrics.json').read_text())
    kerned_path = experiments/'accent-kerned'
    kerned_metrics = json.loads((kerned_path/'reference-accent-kerned-metrics.json').read_text())
    weight_metrics = json.loads((study_path/'reference-panel-weight-study-metrics.json').read_text())
    summary = {'status':'approved reference-panel ExtraBold 800 packaged in Android; 400–700 studies isolated',
               'families':{},'audit_scope':'20 total CFF family/weight binaries; 5 accepted + 15 experiments', 'android':{'font_sha256':_sha(android_font),'license_sha256':_sha(source_license)}}
    original_cm = None
    originals = {}
    for style, weight in WEIGHTS.items():
        path = base_path/f'Chunks-{style}.otf'
        f = _font(path)
        if f['OS/2'].usWeightClass != weight:
            raise AssertionError(f'{path}: weight mismatch')
        cm = f.getBestCmap()
        if original_cm is None:
            original_cm = cm
        elif cm != original_cm:
            raise AssertionError(f'{path}: cmap differs across base weights')
        originals[style] = f
        summary['families'][f'base/{style}']={'weight':weight,'sha256':_sha(path),'mapped':len(cm)}

    if len(original_cm) < 850:
        raise AssertionError('Unexpected loss of mapped Unicode coverage')
    for style, weight in WEIGHTS.items():
        reference = originals[style]
        candidates = []
        if style != 'ExtraBold':
            candidates.append(('weight-study',study_path/f'ChunksReferencePanel-{style}-Study.otf'))
        else:
            candidates.append(('reference-optical',experiments/'ChunksReferencePanel-ExtraBold-Optical.otf'))
        candidates.append(('accent-study',accent_path/f'ChunksReferencePanel-{style}-AccentStudy.otf'))
        candidates.append(('accent-kerned',kerned_path/f'ChunksReferencePanel-{style}-AccentKerned.otf'))
        for family,path in candidates:
            candidate = _font(path)
            if candidate['OS/2'].usWeightClass != weight:
                raise AssertionError(f'{path}: incorrect weight')
            if candidate.getBestCmap() != original_cm:
                raise AssertionError(f'{path}: Unicode coverage or names drift')
            _metric_close(candidate,reference,path)
            if _sha(path) == _sha(base_path/f'Chunks-{style}.otf'):
                raise AssertionError(f'{path}: suspicious identical base font')
            for sample in NON_LATIN_SAMPLES:
                base_r = ImageFont.truetype(str(base_path/f'Chunks-{style}.otf'),32,layout_engine=ImageFont.Layout.RAQM)
                cand_r = ImageFont.truetype(str(path),32,layout_engine=ImageFont.Layout.RAQM)
                if abs(base_r.getlength(sample)-cand_r.getlength(sample))>.04:
                    raise AssertionError(f'{path}: Hindi shaping advance drift: {sample}')
            info={'sha256':_sha(path),'weight':weight,'mapped':len(original_cm),
                  'non_latin_advance_samples':len(NON_LATIN_SAMPLES)}
            if family=='accent-study':
                m=accent_metrics['weights'][style]
                if m['output_sha256'] != info['sha256']:
                    raise AssertionError(f'{path}: accent metrics refer to stale font')
                if not 220 <= m['built'] <= 350:
                    raise AssertionError(f'{path}: unexpected accent graft count {m["built"]}')
                info['accent_grafts']=m['built']
            if family=='accent-kerned':
                audit=kerned_metrics['weights'][style]
                original_accent=accent_path/f'ChunksReferencePanel-{style}-AccentStudy.otf'
                if audit['output_sha256']!=info['sha256']:
                    raise AssertionError(f'{path}: kerned manifest references stale OTF')
                if audit['input_sha256']!=_sha(original_accent):
                    raise AssertionError(f'{path}: source accent study hash mismatch')
                if audit['reference_accent_glyphs']!=accent_metrics['weights'][style]['built']:
                    raise AssertionError(f'{path}: reconstructed glyph coverage drift')
                if audit['adjusted_pairs']<75000 or audit['bounded_subtables']<10:
                    raise AssertionError(f'{path}: missing compact accent pair calibration')
                if candidate['hmtx'].metrics!=_font(original_accent)['hmtx'].metrics:
                    raise AssertionError(f'{path}: altered glyph horizontal metrics')
                if candidate['name'].getDebugName(1)!='Chunks Reference Panel Kerned':
                    raise AssertionError(f'{path}: family name must be independently installable')
                if bool(candidate['OS/2'].fsSelection & 32) and bool(candidate['OS/2'].fsSelection & 64):
                    raise AssertionError(f'{path}: both Bold and Regular flags set')
                info['constructed_accent_glyphs']=audit['reference_accent_glyphs']
                info['new_bounded_pair_rules']=audit['adjusted_pairs']
            if family=='weight-study':
                m=weight_metrics['weights'][style]
                if m['sha256'] != info['sha256']:
                    raise AssertionError(f'{path}: weight study metrics refer to stale font')
                if m['glyphs_changed']!=62:
                    raise AssertionError(f'{path}: 62 primary glyphs missing')
                info['reference_primary_glyphs']=62
            summary['families'][f'{family}/{style}']=info
    summary['family_count']=len(summary['families'])
    summary['source_license_sha256']=_sha(source_license)
    return summary


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root',type=Path,default=Path('.'))
    p.add_argument('--output',type=Path,default=Path('chunks-font-cross-family-audit.json'))
    args=p.parse_args()
    result=audit(args.root)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n')
    print(f"Verified {result['family_count']} family/weight binaries, Android license/master isolation and Hindi shaping")