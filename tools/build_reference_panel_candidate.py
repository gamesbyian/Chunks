#!/usr/bin/env python3
"""Opt-in reference alphabet alternate: replace 62 glyphs, never the shipping fonts.

Uses the actual reference-derived vector SVGs as CFF cubic outline sources.
The six approved primary-wordmark glyphs remain in the shipping font untouched;
this generates a separate named font candidate for comparative editorial review.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import xml.etree.ElementTree as ET
from pathlib import Path
from fontTools.ttLib import TTFont
from fontTools.svgLib.path import parse_path
from fontTools.pens.t2CharStringPen import T2CharStringPen
from fontTools.pens.transformPen import TransformPen

BASELINES={'uppercase-A-M':336,'uppercase-N-Z':380,'lowercase-a-m':431,
           'lowercase-n-z':473,'digits':524}
SCALE=19.2


def build(baseline_font, reference_dir, out):
    baseline_font=Path(baseline_font);reference_dir=Path(reference_dir);out=Path(out)
    font=TTFont(baseline_font)
    if font.sfntVersion!='OTTO' or 'CFF ' not in font:raise ValueError('Expected original OTF/CFF master')
    manifest=json.loads((reference_dir/'manifest.json').read_text())
    expected=set('ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789')
    if set(manifest['glyphs'])!=expected:raise AssertionError('Incomplete reference glyph masters')
    cmap=font.getBestCmap()
    characters=font['CFF '].cff.topDictIndex[0].CharStrings
    widths={}
    for ch,v in manifest['glyphs'].items():
        glyph=cmap.get(ord(ch))
        if glyph is None:raise AssertionError(f'Missing source font character {ch}')
        path=reference_dir/f'U+{ord(ch):04X}.svg'
        root=ET.fromstring(path.read_text())
        shapes=[x.attrib['d'] for x in root.iter() if x.tag.endswith('path')]
        if len(shapes)!=1:raise AssertionError(f'{ch}: only a single evenodd compound path accepted')
        y0=v['source_bbox'][1]
        baseline=BASELINES[v['row']]
        pen=T2CharStringPen(width=0,glyphSet=font.getGlyphSet())
        transform=(SCALE,0,0,-SCALE,52,SCALE*(baseline-y0))
        parse_path(shapes[0],TransformPen(pen,transform))
        chars=pen.getCharString(private=font['CFF '].cff.topDictIndex[0].Private,globalSubrs=font['CFF '].cff.GlobalSubrs)
        chars.compile()
        if not chars.bytecode:raise AssertionError(f'{ch}: zero-length outline')
        characters[glyph]=chars
        # Preserve generous side bearings at all display sizes; optical re-kern
        # is intentionally a separate subsequent acceptance stage.
        advance=round((v['width']+6)*SCALE)
        font['hmtx'][glyph]=(advance,52)
        widths[ch]=advance
    font['name'].setName('Chunks Reference Panel',1,3,1,0x409)
    font['name'].setName('Chunks Reference Panel',16,3,1,0x409)
    font['name'].setName('ExtraBold',2,3,1,0x409)
    font['name'].setName('ExtraBold',17,3,1,0x409)
    font['name'].setName('Chunks Reference Panel ExtraBold',4,3,1,0x409)
    font['name'].setName('ChunksReferencePanel-ExtraBold',6,3,1,0x409)
    font['name'].setName('Reference-panel glyph draft; not shipping. Source font: Baloo 2, SIL OFL 1.1.',3,3,1,0x409)
    font.recalcTimestamp=False
    out.parent.mkdir(parents=True,exist_ok=True)
    font.save(out)
    verify=TTFont(out)
    if len(verify.getBestCmap())!=len(cmap):raise AssertionError('Coverage changed')
    if 'GPOS' not in verify or 'GDEF' not in verify or 'GSUB' not in verify:raise AssertionError('Shaping tables lost')
    return {'output':str(out),'sha256':hashlib.sha256(out.read_bytes()).hexdigest(),
            'glyphs_replaced':len(widths),'script_coverage':len(cmap),'char_widths':widths}

if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--base',type=Path,default=Path('assets/fonts/chunks/Chunks-ExtraBold.otf'))
    p.add_argument('--glyphs',type=Path,default=Path('assets/fonts/source/reference-alphabet'))
    p.add_argument('--out',type=Path,default=Path('assets/fonts/experimental/ChunksReferencePanel-ExtraBold.otf'))
    args=p.parse_args()
    print(json.dumps(build(args.base,args.glyphs,args.out),indent=2))