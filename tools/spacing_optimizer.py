"""Measurement-driven tracking/leading presets for Chunks OpenType families.

Kerning lives in GPOS; tracking and line height remain application-level text styles.
No character-specific shaping feature is overwritten by a display typography preset.
"""
from __future__ import annotations
import math
from pathlib import Path

import numpy as np
from PIL import ImageFont
from fontTools.pens.boundsPen import BoundsPen

LABELS={
 'display':('Chunks','BIG FILES','BRIGHTER DAYS'),
 'heading':('Torrent Details','Cleanup Wizard','Downloads','Storage','Settings'),
 'body':('The.Wanderer.2024.mkv','Downloading 68%','Résumé télévision','1.8 TB free'),
 'small_labels':('Local','Remote','42 peers','Files (156)','Seeding'),
}

def get_mask(pil, ch):
    mask, pos=pil.getmask2(ch,mode='L',anchor='ls')
    img=np.asarray(mask,dtype=np.uint8).reshape((mask.size[1],mask.size[0]))
    if img.ndim!=2 or not img.size:return None
    ys,xs=np.where(img>=115)
    if not len(xs):return None
    return {int(pos[1]+y):(int(pos[0]+xs[ys==y].min()),int(pos[0]+xs[ys==y].max()+1)) for y in np.unique(ys)}


def ink_gaps_at_size(fontpath: Path, samples, px=126):
    """Measure shaped clearance between adjacent glyphs via baseline-aligned masks.

    Collapsing pairs with no ink overlap in Y would bias the spacing estimator,
    so those pairs are excluded (but remain shaped by the native GPOS feature).
    """
    
    pil=ImageFont.truetype(str(fontpath),px,layout_engine=ImageFont.Layout.RAQM)
    chars={ch for s in samples for ch in s if not ch.isspace()}
    masks={ch:get_mask(pil,ch) for ch in chars}
    unkerned={ch:float(pil.getlength(ch,features=['-kern'])) for ch in chars}
    gaps=[]
    for s in samples:
        for a,b in zip(s,s[1:]):
            if a.isspace() or b.isspace(): continue
            left=masks.get(a);right=masks.get(b)
            if not left or not right:continue
            common=left.keys() & right.keys()
            if len(common)<6:continue
            sep=np.array([unkerned[a]+right[y][0]-left[y][1] for y in common])
            existing=float(pil.getlength(a+b)-pil.getlength(a)-pil.getlength(b))
            gaps.append(float(np.percentile(sep,18))+existing)
    return gaps


def safe_line_height(font):
    """Return minimum safe multiline height for the actual ALL-script glyph extents.

    This checks all glyphs, including complex-script marks. An explicitly
    Latin-only text style may use a smaller preferred line-height.
    """
    glyphset=font.getGlyphSet()
    low=math.inf;high=-math.inf
    for name in font.getGlyphOrder():
        p=BoundsPen(glyphset);glyphset[name].draw(p)
        if p.bounds:
            low=min(low,p.bounds[1]);high=max(high,p.bounds[3])
    em=font['head'].unitsPerEm
    return dict(low=round(low,2),high=round(high,2),
                minimum_multiscript_line_height_em=round(math.ceil(((high-low)/em+.06)*100)/100,2))


def measured_presets(fontpath, font, weight):
    """Conservative bounded tracking correction from visible UI string samples.

    The 20th-percentile space is an optical lower-tail measure. Native kerning
    already runs first. Presets are validated at multiple sizes by the audit.
    """
    goals={'display':.078, 'heading':.100, 'body':.122, 'small_labels':.138}
    limits={'display':(-.022,.010),'heading':(-.015,.014),
            'body':(-.004,.018),'small_labels':(0,.024)}
    track={}; details={}
    for role, strings in LABELS.items():
        gaps=ink_gaps_at_size(fontpath,strings)
        q20=float(np.percentile(gaps,20)) / 126 if gaps else goals[role]
        lo,hi=limits[role]
        delta=max(lo,min(hi,goals[role]-q20))
        track[role]=round(delta,4)
        details[role]={'observed_20pct_ink_gap_em':round(q20,4),
                       'target_ink_gap_em':goals[role],
                       'sampled_pairs':len(gaps),'lower_limit_em':lo,'upper_limit_em':hi}
    allscripts=safe_line_height(font)
    # Density thresholds for Latin-first UI; labels involving complex scripts use
    # the measured multi-script lower bound, not these tighter Latin presets.
    lines={'display_latin':1.14,'heading_latin':1.22,'body_latin':1.36,
           'small_labels_latin':1.42,
           'body_multiscript':max(1.60,allscripts['minimum_multiscript_line_height_em']),
           'small_labels_multiscript':max(1.65,allscripts['minimum_multiscript_line_height_em'])}
    return track,lines,{'ink_gap_measurements':details,'all_glyph_vertical_extents':allscripts}