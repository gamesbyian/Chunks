#!/usr/bin/env python3
"""Review true native CFF stacked accents by weight, size, and source status.

Compares the original nonshipping Consolidated family against the reversible
Stacked family. No edits to fonts, OpenType tables or Android resources.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
from fontTools.ttLib import TTFont
from audit_reference_latin_coverage import inventory

ROOT=Path(__file__).resolve().parents[1]
FONT_DIR=ROOT/'assets/fonts/experimental/stacked-studies'
STYLES=('Regular','Medium','Semibold','Bold','ExtraBold')
SIZES=(14,24,48,82)
ROWS_PER_PAGE=60


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_report():
    source=FONT_DIR/'reference-stacked-accent-metrics.json'
    meta=json.loads(source.read_text())
    baseline=inventory()
    if set(meta['weights']) != set(STYLES):
        raise AssertionError('Five stacked source manifests required')
    old={r['character']:r for r in baseline['latin_letters']}
    characters=sorted({r['glyph'] for v in meta['weights'].values()
                       for r in v['built']+v['skipped']},key=ord)
    if len(characters)<100:
        raise AssertionError('Insufficient native stacked candidates')
    rows=[]
    counts={}
    for style in STYLES:
        record=meta['weights'][style]
        face=FONT_DIR/f'ChunksReferencePanel-{style}-Stacked.otf'
        font=TTFont(face)
        expected=400+100*STYLES.index(style)
        if (digest(face)!=record['output_sha256'] or font.sfntVersion!='OTTO'
                or 'CFF ' not in font or len(font.getBestCmap())!=856
                or font['OS/2'].usWeightClass!=expected):
            raise AssertionError(f'{style}: invalid or stale compiled CFF')
        built={r['glyph'] for r in record['built']}
        skipped={r['glyph'] for r in record['skipped']}
        if built & skipped or len(built)!=record['built_count']:
            raise AssertionError('Overlapping original/derived evidence')
        if built|skipped!=set(characters):
            raise AssertionError('Different per-weight stacked candidate repertoire')
        if any(old[ch]['status_by_weight'][style]!='unrebuilt_stacked_marks' for ch in built):
            raise AssertionError(f'{style}: unexpected nonstacked overwrite')
        old_count=len([r for r in baseline['latin_letters']
                       if r['status_by_weight'][style].startswith('unrebuilt_')])
        counts[style]={
            'baseline_unresolved':old_count,
            'new_stacked':len(built),
            'retained_stacked':len(skipped),
            'remaining_unresolved':old_count-len(built),
            'native_font_sha256':digest(face),
        }
    for ch in characters:
        oldrow=old[ch]
        styles={}
        for style in STYLES:
            master=meta['weights'][style]
            matched=next((x for x in master['built'] if x['glyph']==ch),None)
            failure=next((x for x in master['skipped'] if x['glyph']==ch),None)
            styles[style]=('rebuilt' if matched else 'retained')
            if not matched and not failure:
                raise AssertionError(f'{style} {ch}: no source decision')
        rows.append({'unicode':f'U+{ord(ch):04X}','char':ch,'name':oldrow['name'],
                     'by_style':styles})
    return {
        'status':'NONSHIPPING five native-CFF stacked accent design review',
        'source_stacked_metrics_sha256':digest(source),
        'source_latin_inventory_font_hashes':baseline['source_fonts'],
        'characters':rows,'candidate_count':len(rows),'counts':counts,
    }


def review_page(report,size,index,subset,out):
    if not subset or len(subset)>ROWS_PER_PAGE or size not in SIZES:
        raise ValueError('Invalid rendered stacked review group')
    left=140
    col=max(180,round(size*2+95))
    width=left+5*col+30
    rowheight=max(68,math.ceil(2.5*size))
    top=115
    im=Image.new('RGB',(width,top+len(subset)*rowheight+20),'#F6F8FC')
    d=ImageDraw.Draw(im)
    small=ImageFont.load_default(size=15)
    title=ImageFont.load_default(size=25)
    d.text((20,12),f'CHUNKS | STACKED LATIN | {size}px | page {index}',
           font=title,fill='#142035')
    d.text((20,47),'B: rebuilt from detached original marks; F: prior CFF fallback',
           font=small,fill='#536177')
    fonts={style:ImageFont.truetype(str(FONT_DIR/f'ChunksReferencePanel-{style}-Stacked.otf'),
             size,layout_engine=ImageFont.Layout.RAQM) for style in STYLES}
    for i,style in enumerate(STYLES):
        d.text((left+i*col+9,86),style,font=small,fill='#27374E')
    for j,row in enumerate(subset):
        y=top+j*rowheight
        d.rectangle((0,y,width,y+rowheight),
                    fill=('#FFFFFF' if j%2==0 else '#EFF3F8'))
        d.text((12,y+8),row['unicode'],font=small,fill='#5E7089')
        baseline=y+round(rowheight*.81)
        for i,style in enumerate(STYLES):
            x=left+i*col
            if i:
                d.line((x,y+1,x,y+rowheight-1),fill='#DDE5EE')
            d.line((x+8,baseline,x+col-10,baseline),fill='#DCE5EF')
            d.text((x+col//2,baseline),row['char'],font=fonts[style],
                   anchor='ms',fill='#10192B')
            flag='B' if row['by_style'][style]=='rebuilt' else 'F'
            d.text((x+10,y+8),flag,font=small,fill='#50647D')
    filename=f'reference-stacked-grid-{size}px-p{index:02d}.png'
    file=out/filename
    im.save(file)
    return {'file':filename,'sha256':digest(file),'pixel_dimensions':[width,im.height],
            'rows':len(subset),'size_px':size,'page':index}


def generate(out=Path('.'),sizes=SIZES,selection=None):
    if not sizes or len(set(sizes))!=len(sizes) or any(s not in SIZES for s in sizes):
        raise ValueError('Unsupported/repeated review size')
    report=source_report()
    if selection is not None:
        if not selection or len(set(selection))!=len(selection):
            raise ValueError('Duplicate or empty selected glyphs')
        rows=[r for r in report['characters'] if r['char'] in selection]
        if len(rows)!=len(selection):
            raise ValueError('Selection contains untracked stacked glyph')
    else:
        rows=report['characters']
    out.mkdir(parents=True,exist_ok=True)
    pages={}
    for size in sizes:
        pages[str(size)]=[review_page(report,size,i+1,
                          rows[p:p+ROWS_PER_PAGE],out)
                          for i,p in enumerate(range(0,len(rows),ROWS_PER_PAGE))]
    report.update({'rendered_characters':len(rows),'full_scope':selection is None,
                   'pages':pages,'sizes':list(sizes),
                   'approval':'Native Android 200% and manual contour review pending'})
    (out/'reference-stacked-grid-metrics.json').write_text(
        json.dumps(report,indent=2,ensure_ascii=False)+'\n')
    return report


if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--out',type=Path,default=Path('.'))
    p.add_argument('--sizes',type=int,nargs='*',default=list(SIZES))
    a=p.parse_args()
    r=generate(a.out,a.sizes)
    print(r['candidate_count'],'candidate glyphs,',
          sum(len(v) for v in r['pages'].values()),'rendered CFF sheets')
    for style,counts in r['counts'].items():
        print(style,counts)
