#!/usr/bin/env python3
"""Create reversible, kerning-inherited Chunks Reference Panel accent OTFs.

This does not touch any shipping font, accepted display variant, or source SVG.
Only pairs containing explicitly constructed precomposed accented Latin glyphs
are modified; glyph outlines, cmap, advances and original features are retained.
The pair target is the same-font NFD bare-base context, measured with HarfBuzz.
Pillow shaping disables ligatures for this one-pair comparison.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from collections import defaultdict
from functools import lru_cache
from pathlib import Path
from fontTools.ttLib import TTFont
from fontTools.ttLib.tables import otTables
from fontTools.ttLib.tables.otBase import ValueRecord
from PIL import ImageFont

WEIGHTS={400:'Regular',500:'Medium',600:'Semibold',700:'Bold',800:'ExtraBold'}
PARTNERS='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
SIZE=1000  # source fonts use 1,000 units/em
LAYOUT_FEATURES=['-liga','-clig','-dlig']
SAMPLES=('Résumé télévision','Český přehled','Ärger Übergröße','ñandú chü','Downloading 68%','Torrents, your way.','Ŷoung Źebra','ÁV ŔA ĀY','TŶr Źn','ḍandelion')
MAX_CORRECTION_UNITS=180
MIN_CORRECTION_UNITS=2
FIRST_GLYPH_CHUNK=12  # keeps every PairPos subtable safely below 16-bit offset limits


def _pair_width(font,first,second):
    return font.getlength(first+second,features=LAYOUT_FEATURES)


def _subtable(glyphorder,records):
    """Build a bounded PairPos Format 1 subtable; each row has unique first glyph."""
    sub=otTables.PairPos();sub.Format=1
    order={g:i for i,g in enumerate(glyphorder)}
    sub.Coverage=otTables.Coverage()
    sub.Coverage.glyphs=sorted(records,key=order.get)
    sub.ValueFormat1=4;sub.ValueFormat2=0
    sub.PairSet=[]
    for first in sub.Coverage.glyphs:
        ps=otTables.PairSet();ps.PairValueRecord=[]
        for second,val in sorted(records[first].items(),key=lambda p:order[p[0]]):
            pv=otTables.PairValueRecord();pv.SecondGlyph=second
            pv.Value1=ValueRecord();pv.Value1.XAdvance=val
            ps.PairValueRecord.append(pv)
        ps.PairValueCount=len(ps.PairValueRecord)
        sub.PairSet.append(ps)
    sub.PairSetCount=len(sub.PairSet)
    return sub


def _append_kern(font,pairs):
    order=font.getGlyphOrder()
    rows=defaultdict(dict)
    for first,second,delta in pairs:
        if delta and first!=second:rows[first][second]=delta
        elif delta: rows[first][second]=delta
    if not rows:raise AssertionError('No accent pair corrections')
    left=sorted(rows,key=font.getGlyphID)
    subtables=[_subtable(order,{g:rows[g] for g in left[i:i+FIRST_GLYPH_CHUNK]})
        for i in range(0,len(left),FIRST_GLYPH_CHUNK)]
    # Each subtable covers disjoint first glyphs, so placement is unambiguous.
    lookup=otTables.Lookup();lookup.LookupType=2;lookup.LookupFlag=0
    lookup.SubTable=subtables;lookup.SubTableCount=len(subtables)
    table=font['GPOS'].table
    index=len(table.LookupList.Lookup)
    table.LookupList.Lookup.append(lookup)
    table.LookupList.LookupCount=len(table.LookupList.Lookup)
    seen=0
    for feature in table.FeatureList.FeatureRecord:
        if feature.FeatureTag=='kern':
            feature.Feature.LookupListIndex.append(index)
            feature.Feature.LookupCount=len(feature.Feature.LookupListIndex)
            seen+=1
    if not seen:raise AssertionError('No existing kern feature; do not mutate other scripts')
    return len(subtables)


def optimize(source:Path,output:Path,manifest_glyphs,*,max_delta=MAX_CORRECTION_UNITS):
    font=TTFont(source,recalcTimestamp=False)
    if font.sfntVersion!='OTTO' or 'CFF ' not in font:raise AssertionError('Requires CFF font')
    cmap=font.getBestCmap()
    mapping={g['glyph']:g['base'] for g in manifest_glyphs}
    if len(mapping)!=len(manifest_glyphs):raise AssertionError('Ambiguous accent manifest')
    if any(ord(a) not in cmap or ord(b) not in cmap for a,b in mapping.items()):
        raise AssertionError('Missing glyph mapping')
    # Actual output pairs include every constructed glyph. Native glyph shapes and
    # advance widths for nonconstructed Latin characters remain untouched.
    probe=ImageFont.truetype(str(source),SIZE,layout_engine=ImageFont.Layout.RAQM)
    if font['head'].unitsPerEm!=SIZE:raise AssertionError('Unexpected em calibration')
    for accent,base in mapping.items():
        if font['hmtx'][cmap[ord(accent)]][0]!=font['hmtx'][cmap[ord(base)]][0]:
            raise AssertionError(f'{accent} changes original base advance')
    letters=list(PARTNERS)+list(mapping)
    measured_pair=lru_cache(maxsize=131072)(lambda a,b: _pair_width(probe,a,b))
    desired={}
    clipped=[]
    count_target=0
    count_changed=0
    correction_absolute=[]
    # The full candidate space covers A-first/accent-second and accent-first/
    # A-or-accent-second. This covers accented-accented pairs exactly once.
    for first in letters:
        if first not in mapping and first not in PARTNERS:continue
        first_base=mapping.get(first,first)
        for second in letters:
            if first not in mapping and second not in mapping:continue
            if first==second and first not in mapping:continue
            second_base=mapping.get(second,second)
            observed=measured_pair(first,second)
            # No change to glyph own advance; this isolates pair adjustment.
            target=measured_pair(first_base,second_base)
            count_target+=1
            delta=int(round(target-observed))
            if abs(delta)<MIN_CORRECTION_UNITS:continue
            actual=max(-max_delta,min(max_delta,delta))
            if actual!=delta:clipped.append({'first':first,'second':second,'desired_units':delta,'applied_units':actual})
            desired[cmap[ord(first)],cmap[ord(second)]]=actual
            correction_absolute.append(abs(actual));count_changed+=1
    records=[(a,b,d) for (a,b),d in desired.items()]
    lookup_count=_append_kern(font,records)
    # This file is independently installable alongside both the accepted face
    # and the raw accent study: its family, full and PostScript names differ.
    weight=font['OS/2'].usWeightClass
    style=WEIGHTS.get(weight)
    if style is None:raise AssertionError('Unsupported weight metadata')
    identity=((1,'Chunks Reference Panel Kerned'),(16,'Chunks Reference Panel Kerned'),
              (2,style),(17,style),(4,f'Chunks Reference Panel Kerned {style}'),
              (6,f'ChunksReferencePanelKerned-{style}'),
              (3,f'Version 0.200; ChunksReferencePanelKerned-{style}'))
    for nid,value in identity:
        for platform,encoding,language in ((3,1,0x409),(1,0,0)):
            font['name'].setName(value,nid,platform,encoding,language)
    # Repair metadata inherited from the source ExtraBold experiment, which
    # erroneously set both REGULAR and BOLD bits. A font must never be both.
    os2=font['OS/2']
    os2.fsSelection=(os2.fsSelection & ~((1<<5)|(1<<6))) | ((1<<5) if weight>=700 else (1<<6))
    font['head'].macStyle=(font['head'].macStyle & ~1) | (1 if weight>=700 else 0)
    font.recalcTimestamp=False
    output.parent.mkdir(parents=True,exist_ok=True)
    font.save(output)
    result=TTFont(output)
    if result.getBestCmap()!=cmap:raise AssertionError('Unicode changed')
    if not {'GPOS','GSUB','GDEF','CFF '}.issubset(result.keys()):raise AssertionError('Shaping tables dropped')
    if result['hmtx'].metrics!=TTFont(source)['hmtx'].metrics:raise AssertionError('Glyph advances mutated')
    return {'input_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
            'output_sha256':hashlib.sha256(output.read_bytes()).hexdigest(),
            'file':output.name,'reference_accent_glyphs':len(mapping),
            'candidate_pairs':count_target,'adjusted_pairs':count_changed,
            'bounded_subtables':lookup_count,'max_correction_units':max_delta,
            'clipped_pair_count':len(clipped),'clipped_pair_examples':clipped[:20],
            'mean_abs_correction_units':round(sum(correction_absolute)/max(1,len(correction_absolute)),2),
            'source_scripts_unchanged':True}


def run(font_dir:Path,out:Path,only=None):
    manifest=json.loads((font_dir/'reference-accent-study-metrics.json').read_text())
    results={}
    for weight,style in WEIGHTS.items():
        if only and weight not in only:continue
        src=font_dir/f'ChunksReferencePanel-{style}-AccentStudy.otf'
        dest=out/f'ChunksReferencePanel-{style}-AccentKerned.otf'
        results[style]=optimize(src,dest,manifest['weights'][style]['glyphs'])
        print(style,results[style]['adjusted_pairs'],'pairs',results[style]['bounded_subtables'],'subtables',flush=True)
    out.mkdir(parents=True,exist_ok=True)
    manifest_file=out/'reference-accent-kerned-metrics.json'
    if only and manifest_file.is_file():
        prior=json.loads(manifest_file.read_text()).get('weights',{})
        for style,entry in prior.items():
            if style in results or style not in WEIGHTS.values():continue
            source=font_dir/f'ChunksReferencePanel-{style}-AccentStudy.otf'
            target=out/f'ChunksReferencePanel-{style}-AccentKerned.otf'
            if source.is_file() and target.is_file() and (entry.get('input_sha256')==hashlib.sha256(source.read_bytes()).hexdigest()) and (entry.get('output_sha256')==hashlib.sha256(target.read_bytes()).hexdigest()):
                results[style]=entry
    report={'status':'NONSHIPPING bounded accent-context kerning inheritance; base family unchanged',
            'weights':{style:results[style] for style in WEIGHTS.values() if style in results}}
    manifest_file.write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n')
    return report

if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--in-dir',type=Path,default=Path('assets/fonts/experimental/accent-studies'))
    p.add_argument('--out',type=Path,default=Path('assets/fonts/experimental/accent-kerned'))
    p.add_argument('--weights',type=int,nargs='*',default=None)
    a=p.parse_args();run(a.in_dir,a.out,a.weights)