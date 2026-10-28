#!/usr/bin/env python3
"""Build five true CFF/OpenType fonts and optical Latin kerning from an OFL source.

Design-stage derivative: does not claim exact recovery of glyph curves from raster art.
No network required; source is a checked-in OFL variable TrueType master.
"""
from __future__ import annotations
import argparse
import json
import math
import os
import tempfile
import unicodedata
from collections import defaultdict
from pathlib import Path

import numpy as np
from PIL import ImageFont, features
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.qu2cuPen import Qu2CuPen
from fontTools.pens.t2CharStringPen import T2CharStringPen
from fontTools.ttLib.tables import otTables
from fontTools.ttLib.tables.otBase import ValueRecord
from brand_glyphs import apply_brand_glyphs
from spacing_optimizer import measured_presets

WEIGHTS = {400:'Regular',500:'Medium',600:'Semibold',700:'Bold',800:'ExtraBold'}
# Sample identical across weights for a fair trend comparison.
PROBES = ['Chunks', 'Torrent Details', 'Cleanup Wizard', 'Downloading 68%',
          'The.Wanderer.2024.mkv', 'Résumé – télévision', 'AÇÚ ÑØ ÅÉ çà ñü',
          'AVATAR To Wa Yo Ty f. fi ff 100%']
EXCEPTIONS = {'\u200b','\u200c','\u200d','\ufeff'}
CANDIDATE_ALPHABET = (
 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
 '.,;:!?-()[]{}&+/@#%$€£¥' +
 'ÀÁÂÃÄÅÆÇÈÉÊËÌÍÎÏÐÑÒÓÔÕÖØÙÚÛÜÝÞŸŽŠ'
 'àáâãäåæçèéêëìíîïðñòóôõöøùúûüýþÿžš'
)


def nice_name(font, style):
    names = font['name']
    def setname(nid, value):
        # replace, never leave source name IDs in any platform name record
        names.setName(value,nid,3,1,0x409)
        names.setName(value,nid,1,0,0)
    family='Chunks'; full=f'{family} {style}'
    # Keep OS/2 copyright of original upstream, and clearly identify derivative.
    setname(1,family); setname(2,style)
    setname(4,full); setname(6,'Chunks-'+style)
    setname(16,family); setname(17,style)
    setname(3, f'Chunks 0.1 {style}; OFL derivative')
    setname(5, 'Version 0.100; reconstructed family candidate')
    setname(10, 'Chunks brand reference approximation; derived from SIL OFL licensed Baloo 2')
    setname(13, 'SIL OPEN FONT LICENSE Version 1.1')
    setname(14, 'https://openfontlicense.org/')
    for rid in (1,2,4,6,16,17):
        # remove any old language records with the same ID, so font family queries
        # cannot accidentally return the source family in a different locale.
        names.names = [r for r in names.names if r.nameID != rid or
                       (r.platformID==3 and r.platEncID==1 and r.langID==0x409) or
                       (r.platformID==1 and r.platEncID==0 and r.langID==0)]


def optimize_vertical_metrics(font):
    """Make OS/2 and hhea metrics mutually consistent; never clip high accents."""
    upem=font['head'].unitsPerEm
    os2=font['OS/2']; hhea=font['hhea']
    # Preserve source headroom for accents and combining marks. Android renders
    # these metrics as font padding on some platforms; recommended line-height
    # below stays separate from safe static font metrics.
    top=max(int(1.03*upem), int(os2.sTypoAscender))
    bottom=max(int(.27*upem), int(-os2.sTypoDescender))
    # Geometry units: a default 1.30 em, with externally controlled 1.12/1.28/1.40.
    if top+bottom < round(upem*1.29):
        top += round(upem*1.29)-(top+bottom)
    os2.sTypoAscender=top
    os2.sTypoDescender=-bottom
    os2.sTypoLineGap=0
    hhea.ascent=top; hhea.descent=-bottom; hhea.lineGap=0
    os2.usWinAscent=max(os2.usWinAscent,top)
    os2.usWinDescent=max(os2.usWinDescent,bottom)
    os2.fsSelection |= (1<<7)  # USE_TYPO_METRICS
    return dict(ascender=top,descender=-bottom,lineGap=0,em=upem)


def get_mask(font, ch, px=126):
    mask, pos=font.getmask2(ch,mode='L',anchor='ls')
    img=np.asarray(mask, dtype=np.uint8).reshape((mask.size[1], mask.size[0]))
    if img.ndim !=2 or not img.size:
        return None
    ys,xs=np.where(img >= 115)
    if not len(xs): return None
    # A tight, downsampled optical silhouette at the source baseline.
    byrow={}
    for y in np.unique(ys):
        sel=xs[ys==y]
        byrow[int(pos[1]+y)]=(int(pos[0]+sel.min()),int(pos[0]+sel.max()+1))
    return byrow


def optical_kerning(fontpath, font, weight):
    """Calibrate Latin pair spacing to an optical reference, retaining native kern.

    Raster profiles are only an optical measuring tool; the font outlines remain
    native smooth vector curves. Round glyphs get naturally less forceful pairs.
    """
    if not features.check_feature('raqm'):
        raise RuntimeError('Pillow must have libraqm/HarfBuzz to measure native kerning')
    px=126; pil=ImageFont.truetype(str(fontpath),px,layout_engine=ImageFont.Layout.RAQM)
    upem=font['head'].unitsPerEm
    chars=list(dict.fromkeys(ch for ch in CANDIDATE_ALPHABET if ord(ch) in font.getBestCmap()))
    # Balanced basic Latin plus accents; only glyphs whose outlines actually intersect
    # in vertical space generate a kerning recommendation.
    masks={ch:get_mask(pil,ch,px) for ch in chars}
    advance={ch:pil.getlength(ch,features=['-kern']) for ch in chars}
    # Continuous weight-dependent design target, not raw minimum gap (which makes
    # 'To' or rounded forms look too loose).
    reference_gap = px * (0.115 - .030*(weight-400)/400)
    min_gap = px*.022
    records=[]; residuals=[]; altered=[]
    for a in chars:
        left=masks[a]
        if not left: continue
        for b in chars:
            right=masks[b]
            if not right: continue
            common=left.keys() & right.keys()
            # Diacritics far above ascender should not dominate spacing analysis.
            useful=[y for y in common if -110<=y<=30]
            if len(useful)<8: continue
            sep=np.array([advance[a]+right[y][0]-left[y][1] for y in useful])
            # Low quantile captures tight part; mitigates a single antialiased pixel.
            raw=float(np.percentile(sep,18))
            existing=float(pil.getlength(a+b)-pil.getlength(a)-pil.getlength(b))
            # Source kerning is already present in shaping. Correct the *shaped*
            # clearance, not unkerned clearance, to avoid double-applying base kern.
            shaped=raw+existing
            delta=(reference_gap-shaped)
            # Prevent overly mechanical tightening; never push two ink bodies together.
            if delta<0: delta=max(delta,min_gap-shaped)
            # Preserve designer's source kerning unless the pair has a real anomaly.
            # New changes no more than 0.075em in either direction.
            delta=max(-px*.072,min(px*.072,delta))
            units=int(round(delta*upem/px))
            if abs(units) >= 13:
                records.append((font.getBestCmap()[ord(a)],font.getBestCmap()[ord(b)],units))
                altered.append(abs(units))
                residuals.append(round((shaped+units*px/upem-reference_gap)*upem/px,2))
    # Accented glyphs often share similar edges; all direct pairs are measured,
    # rather than relying on incomplete accent substitution tables.
    return records, dict(measured_glyphs=len(chars), examined_pairs=len(chars)**2,
                         adjusted_pairs=len(records), median_absolute_adjustment=round(float(np.median(altered)) if altered else 0,2),
                         residual_90pct=round(float(np.percentile(np.abs(residuals),90)) if residuals else 0,2))


def add_gpos_pairs(font, records):
    """Append PairPos to an existing GPOS, without discarding base mark/ligature features."""
    if not records:return
    if 'GPOS' not in font:
        raise RuntimeError('Expected a source with GPOS; do not silently discard positioning')
    table=font['GPOS'].table
    gp=defaultdict(dict)
    for g1,g2,delta in records:
        if g1!=g2 or delta:gp[g1][g2]=delta
    order={g:i for i,g in enumerate(font.getGlyphOrder())}
    subt=otTables.PairPos(); subt.Format=1
    subt.Coverage=otTables.Coverage()
    subt.Coverage.glyphs=sorted(gp,key=lambda k:order[k])
    subt.ValueFormat1=4 # xAdvance; 0x0004
    subt.ValueFormat2=0
    subt.PairSet=[]
    for first in subt.Coverage.glyphs:
        pset=otTables.PairSet()
        pset.PairValueRecord=[]
        for second,delta in sorted(gp[first].items(),key=lambda kv:order[kv[0]]):
            pair=otTables.PairValueRecord()
            pair.SecondGlyph=second
            pair.Value1=ValueRecord();pair.Value1.XAdvance=delta
            pset.PairValueRecord.append(pair)
        pset.PairValueCount=len(pset.PairValueRecord)
        subt.PairSet.append(pset)
    subt.PairSetCount=len(subt.PairSet)
    look=otTables.Lookup();look.LookupType=2;look.LookupFlag=0
    look.SubTable=[subt];look.SubTableCount=1
    list_=table.LookupList
    idx=len(list_.Lookup);list_.Lookup.append(look);list_.LookupCount=len(list_.Lookup)
    # Append to existing 'kern' feature, rather than erasing existing feature references.
    featureidx=[]
    for i, rec in enumerate(table.FeatureList.FeatureRecord):
        if rec.FeatureTag=='kern':
            rec.Feature.LookupListIndex.append(idx)
            rec.Feature.LookupCount=len(rec.Feature.LookupListIndex)
            featureidx.append(i)
    if not featureidx:
        rec=otTables.FeatureRecord();rec.FeatureTag='kern'
        feature=otTables.Feature();feature.FeatureParams=None
        feature.LookupListIndex=[idx];feature.LookupCount=1;rec.Feature=feature
        # Append to feature list, and wire to relevant scripts.
        featureidx=[len(table.FeatureList.FeatureRecord)]
        table.FeatureList.FeatureRecord.append(rec)
        table.FeatureList.FeatureCount=len(table.FeatureList.FeatureRecord)
        for sr in table.ScriptList.ScriptRecord:
            for lang in ([sr.Script.DefaultLangSys] if sr.Script.DefaultLangSys else [])+[l.LangSys for l in sr.Script.LangSysRecord]:
                lang.FeatureIndex.append(featureidx[0]);lang.FeatureCount=len(lang.FeatureIndex)
    return len(records)


def to_cff(font, fontname):
    """Convert actual quadratic contours to cubic CFF, retaining OT layout tables."""
    upem=font['head'].unitsPerEm
    glyph_names=font.getGlyphOrder()
    glyph_set=font.getGlyphSet()
    charstrings={}
    for name in glyph_names:
        pen=T2CharStringPen(width=0,glyphSet=glyph_set)
        convert=Qu2CuPen(pen,max_err=1.0,all_cubic=True)
        glyph_set[name].draw(convert)
        charstrings[name]=pen.getCharString(private=None,globalSubrs=None)
    fb=FontBuilder(upem,isTTF=False)
    fb.setupGlyphOrder(glyph_names)
    info={'FullName':fontname,'FamilyName':'Chunks','Weight':fontname.split()[-1],
          'version':'0.100','Notice':'OFL 1.1; based on Baloo 2 by Ek Type; brand reconstruction candidate'}
    fb.setupCFF(fontname.replace(' ','-'),info,charstrings,privateDict={})
    font['CFF ']=fb.font['CFF ']
    for bad in ('glyf','loca','cvt ','fpgm','prep','gasp','DSIG','fvar','gvar','avar','HVAR','MVAR','STAT'):
        if bad in font:del font[bad]
    font.sfntVersion='OTTO'
    font['head'].glyphDataFormat=0
    font['maxp'].tableVersion=0x5000
    if hasattr(font['maxp'],'maxZones'):
        for name in ('maxZones','maxTwilightPoints','maxStorage','maxFunctionDefs','maxInstructionDefs','maxStackElements','maxSizeOfInstructions','maxComponentElements','maxComponentDepth','maxPoints','maxContours','maxCompositePoints','maxCompositeContours'):
            if hasattr(font['maxp'],name):delattr(font['maxp'],name)
    return font


def build(source, outdir, only=None, brand_svg=None):
    source=Path(source);outdir=Path(outdir);outdir.mkdir(parents=True,exist_ok=True)
    brand_svg=Path(brand_svg or (Path(__file__).resolve().parent.parent/'assets/fonts/source/reference-traced-wordmark.svg'))
    if not brand_svg.is_file(): raise FileNotFoundError(f'Missing editable wordmark glyph master: {brand_svg}')
    result={'source':source.name, 'version':'0.1.0', 'purpose':'draft reference-based family; not exact original vector data', 'weights':{}}
    for weight,style in WEIGHTS.items():
        if only and weight not in only:continue
        font=TTFont(source,recalcBBoxes=True,recalcTimestamp=False)
        font.recalcTimestamp=False
        if 'fvar' in font:
            font=instantiateVariableFont(font,{'wght':weight},inplace=True,updateFontNames=False)
        font['OS/2'].usWeightClass=weight
        font['head'].macStyle=int(weight>=700)
        # Set italic bit off, bold status accurate.
        if weight>=700:font['OS/2'].fsSelection |= 1<<5
        else:font['OS/2'].fsSelection &= ~(1<<5)
        nice_name(font,style)
        metrics=optimize_vertical_metrics(font)
        cff=to_cff(font,'Chunks '+style)
        glyphs=apply_brand_glyphs(cff,weight,brand_svg)
        with tempfile.TemporaryDirectory() as scratch:
            interim=Path(scratch)/'spacing.otf';cff.save(interim)
            records, optical=optical_kerning(interim,cff,weight)
            add_gpos_pairs(cff,records)
        path=outdir/f'Chunks-{style}.otf'
        cff.save(path,reorderTables=True)
        # Reopening is an important acceptance check. No TTF masquerading as .otf.
        check=TTFont(path)
        assert check.sfntVersion=='OTTO' and 'CFF ' in check and 'glyf' not in check
        assert check['OS/2'].usWeightClass==weight
        assert len(check.getBestCmap())>=150
        track,lines,tracking_audit=measured_presets(path,check,weight)
        stats={'file':path.name,'bytes':path.stat().st_size,'glyphs':len(check.getGlyphOrder()),
               'mapped_codepoints':len(check.getBestCmap()),'vertical_metrics':metrics,
               'optical_kerning':optical,'wordmark_glyphs':glyphs,'tracking_em':track,
               'line_height_em':lines,'spacing_audit':tracking_audit}
        result['weights'][style]=stats
        print(f"{style}: {stats['mapped_codepoints']} chars, {stats['optical_kerning']['adjusted_pairs']} adjusted kern pairs, {stats['bytes']/1024:.0f} KiB")
    (outdir/'chunks-font-metrics.json').write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n',encoding='utf8')
    return result

if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--source',required=True,type=Path)
    p.add_argument('--out',default='assets/fonts/chunks',type=Path)
    p.add_argument('--only',nargs='*',type=int,choices=list(WEIGHTS))
    p.add_argument('--brand-svg',type=Path,help='Editable brand wordmark skeleton SVG, default tracked under assets/fonts/source')
    a=p.parse_args();build(a.source,a.out,set(a.only) if a.only else None, brand_svg=a.brand_svg)