#!/usr/bin/env python3
"""Optically refine only the 62 changed reference-panel letter and numeral pairs.

Keeps untouched script glyphs and source positioning exactly as previously built.
"""
from __future__ import annotations
import argparse,json,hashlib
from pathlib import Path
from fontTools.ttLib import TTFont
from build_chunks_fonts import optical_kerning,add_gpos_pairs
from spacing_optimizer import ink_gaps_at_size,measured_presets

GLYPHS='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
SAMPLES=['Chunks','TORRENTS, YOUR WAY.','Downloading 68%','0123456789','Résumé télévision']

def optimize(source,out,report):
    source=Path(source);out=Path(out);report=Path(report)
    font=TTFont(source,recalcBBoxes=True,recalcTimestamp=False)
    changed={font.getBestCmap()[ord(c)] for c in GLYPHS}
    before={str(sz):min(ink_gaps_at_size(source,SAMPLES,px=sz)) for sz in (14,24,48)}
    records,stat=optical_kerning(source,font,800)
    selected=[(a,b,d) for a,b,d in records if a in changed or b in changed]
    if len(selected)<500:raise AssertionError('Expected a meaningful set of optical corrections')
    add_gpos_pairs(font,selected)
    font.recalcTimestamp=False
    out.parent.mkdir(parents=True,exist_ok=True)
    font.save(out)
    check=TTFont(out)
    if check.getBestCmap()!=TTFont(source).getBestCmap():raise AssertionError('Unicode coverage changed')
    after={str(sz):min(ink_gaps_at_size(out,SAMPLES,px=sz)) for sz in (14,24,48)}
    if min(after.values())<.25:raise AssertionError(f'New collision in result {after}')
    track, lines, typography = measured_presets(out, check, 800)
    stats={'tracking_em':track,'line_height_em':lines,'tracking_audit':typography,
        'base_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
        'optimized_sha256':hashlib.sha256(out.read_bytes()).hexdigest(),
        'modified_glyphs':len(changed),'measured_pairs':stat['examined_pairs'],
        'additional_optical_pairs':len(selected),'minimum_ink_gaps_px_before':before,
        'minimum_ink_gaps_px_after':after,
        'condition':'experimental; native proof required; source non-Latin native shaping retained'}
    report.parent.mkdir(parents=True,exist_ok=True)
    report.write_text(json.dumps(stats,indent=2)+'\n')
    return stats
if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--in-font',dest='input',type=Path,default=Path('assets/fonts/experimental/ChunksReferencePanel-ExtraBold.otf'))
    p.add_argument('--out',type=Path,default=Path('assets/fonts/experimental/ChunksReferencePanel-ExtraBold-Optical.otf'))
    p.add_argument('--report',type=Path,default=Path('assets/fonts/experimental/reference-panel-spacing.json'))
    args=p.parse_args()
    print(json.dumps(optimize(args.input,args.out,args.report),indent=2))