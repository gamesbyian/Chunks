#!/usr/bin/env python3
"""Prototype reference-faithful dotless i/j accent composites in five independent CFF OTFs.

The existing custom i and j contain detachable dots. Remove only those dots
when a native separable accent is available; below accents preserve the dot.
Pass all i/j compounds plus existing accent glyphs through the normal bounded
contextual kerning optimizer, leaving approved fonts and other outlines intact.
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
from build_reference_accent_studies import contours, glyph_record, bounds
from build_reference_accent_kerned import optimize, WEIGHTS, _append_kern
from spacing_optimizer import ink_gaps_at_size

GAP=28
MAX_TOP=1040
MIN_BOTTOM=-480


def build_dotless(source:Path,base:Path,dest:Path)->list[dict]:
    upstream=TTFont(source,recalcTimestamp=False)
    output=TTFont(base,recalcTimestamp=False)
    assert upstream.getBestCmap()==output.getBestCmap()
    cm=output.getBestCmap()
    oldset=upstream.getGlyphSet()
    inset=output.getGlyphSet()
    old_chars=output['CFF '].cff.topDictIndex[0].CharStrings
    built=[]
    # Crucial: native i/j source marks, not the original owner's dot, determine diacritic shape.
    for bare in ('i','j'):
        stem=glyph_record(inset,cm[ord(bare)])
        paths=contours(stem)
        bounded=[(part,bounds(part,inset)) for part in paths]
        if len(bounded)!=2 or any(b is None for _,b in bounded):
            raise AssertionError(f'{bare}: expected detachable reference stem and dot')
        bounded.sort(key=lambda x:x[1][1])
        stem_part,stem_box=bounded[0]
        dot_part,dot_box=bounded[1]
        if dot_box[1] < stem_box[3]+14 or dot_box[3]>950:
            raise AssertionError(f'{bare}: dot fails detachment or geometry gate')
        old_base=glyph_record(oldset,cm[ord(bare)])
        old_parts=[(part,bounds(part,oldset)) for part in contours(old_base)]
        old_stem_boxes=[b for _,b in old_parts if b and b[1]<500]
        if not old_stem_boxes:raise AssertionError('Missing source stem')
        old_x0=min(b[0] for b in old_stem_boxes)
        old_x1=max(b[2] for b in old_stem_boxes)
        xshift=round((stem_box[0]+stem_box[2]-old_x0-old_x1)/2)
        for cp,gn in sorted(cm.items()):
            ch=chr(cp)
            if not ud.name(ch,'').startswith('LATIN '):continue
            nfd=ud.normalize('NFD',ch)
            if len(nfd)!=2 or nfd[0]!=bare or ud.category(nfd[1])!='Mn':continue
            # Above marks replace the custom dot; below marks preserve it.
            # The old source dot is never mistaken for a new lower mark.
            mark_label=ud.name(nfd[1],'')
            is_below=mark_label in ('COMBINING DOT BELOW','COMBINING OGONEK')
            if ('BELOW' in mark_label or 'OGONEK' in mark_label) and not is_below:
                continue
            compound=glyph_record(oldset,gn)
            marks=[]
            for part in contours(compound):
                markbox=bounds(part,oldset)
                if markbox is None:continue
                if is_below and markbox[1]<-45:
                    marks.append((part,markbox))
                elif not is_below and markbox[1]>500 and markbox[3]>525:
                    marks.append((part,markbox))
            if not marks:continue
            # A single mark can consist of multiple disjoint contours, e.g. double grave.
            mark_min_y=min(b[1] for _,b in marks)
            if is_below:
                old_bottom=min(b[1] for b in old_stem_boxes)
                if 'OGONEK' in mark_label:
                    yshift=round(stem_box[1]-old_bottom)
                else:
                    yshift=round(stem_box[1]-GAP-max(b[3] for _,b in marks))
            else:
                yshift=round(max(0,stem_box[3]+GAP-mark_min_y))
            all_boxes=([stem_box,dot_box] if is_below else [stem_box])+[(b[0]+xshift,b[1]+yshift,b[2]+xshift,b[3]+yshift) for _,b in marks]
            bbox=(min(b[0] for b in all_boxes),min(b[1] for b in all_boxes),
                  max(b[2] for b in all_boxes),max(b[3] for b in all_boxes))
            if bbox[1]<MIN_BOTTOM or bbox[3]>MAX_TOP:continue
            pen=T2CharStringPen(width=0,glyphSet=inset)
            replayRecording(stem_part,pen)
            if is_below:replayRecording(dot_part,pen)
            tpen=TransformPen(pen,(1,0,0,1,xshift,yshift))
            for part,_ in marks:replayRecording(part,tpen)
            cs=pen.getCharString(private=output['CFF '].cff.topDictIndex[0].Private,
                                 globalSubrs=output['CFF '].cff.GlobalSubrs)
            cs.compile()
            if not cs.bytecode:raise AssertionError(f'{ch}: new CFF outline empty')
            old_chars[gn]=cs
            output['hmtx'][gn]=(output['hmtx'][cm[ord(bare)]][0],round(bbox[0]))
            built.append({'glyph':ch,'base':bare,'cp':f'U+{cp:04X}',
                          'x_shift':xshift,'y_shift':yshift,
                          'below_mark':is_below,'marks':len(marks),'bbox':[round(v,2) for v in bbox],
                          'source_dot_bounds':[round(v,2) for v in dot_box]})
    if len(built)<10:raise AssertionError(f'Only {len(built)} dotless accent compounds')
    output.recalcTimestamp=False
    dest.parent.mkdir(parents=True,exist_ok=True)
    output.save(dest)
    after=TTFont(dest)
    if after.getBestCmap()!=cm:raise AssertionError('Unicode coverage changed')
    for item in built:
        if after['hmtx'][cm[ord(item['glyph'])]][0]!=after['hmtx'][cm[ord(item['base'])]][0]:
            raise AssertionError('Accented advance differs from custom bare base')
    return built



def separate_tight_dotless_pairs(path:Path):
    """Close measured holes only, not a global all-pairs tracking hack.

    A dense pair of accent overhangs can protrude beyond inherited bare-base
    kerning at a particular size. Add a *bounded positive* GPOS correction
    for the precise pair and preserve other contexts and source glyph shapes.
    """
    glyphs='ìíîïĩīĭǐȉȋỉĵįị'
    required=[]
    for left,right in zip(glyphs,glyphs[1:]):
        gaps=[(size,min(ink_gaps_at_size(path,[left+right],px=size))) for size in (14,24,48,82)]
        if not gaps:continue
        missing=max(max(0,0.36-gap)*1000/size for size,gap in gaps)
        units=min(50,round(missing+1)) if missing>0 else 0
        if units>0:required.append((left,right,units,gaps))
    if required:
        f=TTFont(path,recalcTimestamp=False)
        cm=f.getBestCmap()
        tuples=[(cm[ord(a)],cm[ord(b)],n) for a,b,n,_ in required]
        _append_kern(f,tuples)
        f.recalcTimestamp=False
        f.save(path)
    after=[]
    for a,b,units,gaps in required:
        post=[(size,round(min(ink_gaps_at_size(path,[a+b],px=size)),4)) for size,_ in gaps]
        if min(gap for _,gap in post)<0.30:
            raise AssertionError(f'Uncorrected optical collision in {a+b}: {post}')
        after.append({'pair':a+b,'added_xadvance_units':units,
                      'original_gaps_px':gaps,'post_correction_gaps_px':post})
    return after


def run(source_dir:Path,base_dir:Path,out:Path,weights=None):
    manifest=json.loads((source_dir/'reference-accent-study-metrics.json').read_text())
    out.mkdir(parents=True,exist_ok=True)
    report={'status':'NONSHIPPING dotless-i-j reference glyph and contextual kerning prototype',
            'weights':{}}
    for weight,style in WEIGHTS.items():
        if weights and weight not in weights:continue
        source=source_dir/f'ChunksReferencePanel-{style}-AccentStudy.otf'
        reference=source
        with tempfile.TemporaryDirectory() as folder:
            raw=Path(folder)/f'{style}-dotless-raw.otf'
            edits=build_dotless(base_dir/f'Chunks-{style}.otf',reference,raw)
            dest=out/f'ChunksReferencePanel-{style}-DotlessKerned.otf'
            all_glyphs=manifest['weights'][style]['glyphs']+[{'glyph':g['glyph'],'base':g['base']} for g in edits]
            correction=optimize(raw,dest,all_glyphs)
        fine_pairs=separate_tight_dotless_pairs(dest)
        font=TTFont(dest)
        for nid,txt in ((1,'Chunks Reference Panel Dotless'),(16,'Chunks Reference Panel Dotless'),
                        (4,f'Chunks Reference Panel Dotless {style}'),
                        (6,f'ChunksReferencePanelDotless-{style}'),
                        (3,f'Version 0.310; ChunksReferencePanelDotless-{style}')):
            for platform,encoding,lang in ((3,1,0x409),(1,0,0)):
                font['name'].setName(txt,nid,platform,encoding,lang)
        font.recalcTimestamp=False
        font.save(dest)
        correction['output_sha256']=hashlib.sha256(dest.read_bytes()).hexdigest()
        correction.update({'base_font_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
                           'dotless_added':len(edits),'dotless_glyphs':edits,
                           'precision_pair_corrections':fine_pairs,
                           'unicode_count':len(font.getBestCmap()),
                           'file':dest.name})
        report['weights'][style]=correction
        print(style,len(edits),'dotless accents',correction['adjusted_pairs'],'bounded contextual pairs',flush=True)
    # Preserve previously generated styles only if both the source and output
    # bytes are exactly those recorded when that style was built.
    prior_file=out/'reference-dotless-kerned-metrics.json'
    if weights and prior_file.exists():
        prior=json.loads(prior_file.read_text()).get('weights',{})
        for style,entry in prior.items():
            if style in report['weights'] or style not in WEIGHTS.values():continue
            source=source_dir/f'ChunksReferencePanel-{style}-AccentStudy.otf'
            target=out/f'ChunksReferencePanel-{style}-DotlessKerned.otf'
            if source.exists() and target.exists() and (entry.get('base_font_sha256')==hashlib.sha256(source.read_bytes()).hexdigest()) and (entry.get('output_sha256')==hashlib.sha256(target.read_bytes()).hexdigest()):
                report['weights'][style]=entry
    report['weights']={style:report['weights'][style] for style in WEIGHTS.values() if style in report['weights']}
    report['total_dotless_added']=sum(x['dotless_added'] for x in report['weights'].values())
    (out/'reference-dotless-kerned-metrics.json').write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n')
    return report


if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--source',type=Path,default=Path('assets/fonts/experimental/accent-studies'))
    p.add_argument('--base',type=Path,default=Path('assets/fonts/chunks'))
    p.add_argument('--out',type=Path,default=Path('assets/fonts/experimental/dotless-kerned'))
    p.add_argument('--weights',type=int,nargs='*')
    a=p.parse_args();run(a.source,a.base,a.out,a.weights)
