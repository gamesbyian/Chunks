#!/usr/bin/env python3
"""Visual evidence from actual 15 Chunks CFF fonts at four UI sizes.

Every sample is drawn at its stated pixel size without automatic scaling.
The report distinguishes physical clipping from subjective letterform quality.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

WEIGHTS=('Regular','Medium','Semibold','Bold','ExtraBold')
SIZES=(14,24,48,82)
EXAMPLES=(('wordmark','Chunks'),('accent','Résumé ÖÄ Ç ñ'),('numbers','68% 42 MB/s'),('script','हिन्दी'))
COLS=(540,575,670,440)
LABEL=350
PADDING=20
WIDTH=LABEL+sum(COLS)+PADDING*3
BGCOL=(249,250,252)
INK=(17,26,40)


def font_sets(root):
    p=root/'assets/fonts'
    for style in WEIGHTS:
        yield style,'base',p/'chunks'/f'Chunks-{style}.otf'
        if style!='ExtraBold':
            yield style,'panel',p/'experimental/weight-studies'/f'ChunksReferencePanel-{style}-Study.otf'
        else:
            yield style,'panel',p/'experimental/ChunksReferencePanel-ExtraBold-Optical.otf'
        yield style,'accent',p/'experimental/accent-studies'/f'ChunksReferencePanel-{style}-AccentStudy.otf'


def create(root:Path, out:Path):
    out.mkdir(parents=True,exist_ok=True)
    report={'status':'non-shipping visual review aid; no design acceptance implied','samples':{},'images':{}}
    for size in SIZES:
        row_h=max(89, int(size*1.65)+30)
        height=90+15*row_h+38
        canvas=Image.new('RGB',(WIDTH,height),BGCOL)
        draw=ImageDraw.Draw(canvas)
        heading=ImageFont.truetype('DejaVuSans.ttf',26)
        labels=ImageFont.truetype('DejaVuSans.ttf',17)
        draw.text((20,14),f'Chunks / font variants / actual {size}px glyphs',font=heading,fill=INK)
        draw.text((20,53),'Base  |  reference-panel weight study  |  accent-fusion study     •     CFF/OpenType / HarfBuzz',font=labels,fill=(68,78,92))
        xstarts=[]
        x=LABEL
        for w in COLS:
            xstarts.append(x);x+=w
        for ix,(style,family,path) in enumerate(font_sets(root)):
            if not path.is_file():raise FileNotFoundError(path)
            font=ImageFont.truetype(str(path),size,layout_engine=ImageFont.Layout.RAQM)
            y=90+ix*row_h
            if ix%3==0:draw.rectangle((0,y,WIDTH,y+row_h),fill=(242,247,251))
            draw.line((0,y,WIDTH,y),fill=(223,229,235),width=1)
            draw.text((20,y+22),f'{style:9s} · {family}',font=labels,fill=INK)
            key=f'{style}/{family}'
            info={'file':path.relative_to(root).as_posix(),'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
                  'pixel_size':size,'samples':{}}
            for (sample_name,text),left,width in zip(EXAMPLES,xstarts,COLS):
                bounds=draw.textbbox((0,0),text,font=font,anchor='lt')
                text_width=bounds[2]-bounds[0]
                text_height=bounds[3]-bounds[1]
                # This is an actual fit finding, not a shrink-to-fit operation.
                overflow=text_width>width-24 or text_height>row_h-18
                if overflow:draw.rectangle((left+4,y+6,left+width-8,y+row_h-6),outline=(198,66,77),width=2)
                draw.text((left+8,y+max(5,(row_h-text_height)//2)),text,font=font,anchor='lt',fill=INK)
                info['samples'][sample_name]={'text':text,'ink_width_px':text_width,'ink_height_px':text_height,
                    'available_width_px':width-24,'overflows_review_cell':overflow}
            report['samples'][key+f'/{size}']=info
        png=out/f'chunks-font-variants-{size}px.png'
        canvas.save(png,optimize=True)
        report['images'][str(size)]=png.name
    # Width drift is a font-specific signal, unlike the arbitrary comparison-cell edge.
    # Record it for every sample at every weight/size, without false pass/fail claims.
    report['relative_width_review']=[]
    for size in SIZES:
        for style in WEIGHTS:
            baseline=report['samples'][f'{style}/base/{size}']['samples']
            for family in ('panel','accent'):
                candidate=report['samples'][f'{style}/{family}/{size}']['samples']
                for sample_name,_ in EXAMPLES:
                    source=baseline[sample_name]['ink_width_px']
                    target=candidate[sample_name]['ink_width_px']
                    ratio=round(target/max(1,source),4)
                    candidate[sample_name]['width_vs_base_ratio']=ratio
                    if ratio>=1.15:
                        report['relative_width_review'].append({
                            'weight':style,'family':family,'size_px':size,
                            'sample':sample_name,'width_vs_base_ratio':ratio,
                            'base_width_px':source,'candidate_width_px':target})
    report['relative_width_review_count']=len(report['relative_width_review'])
    # Overflow is intentionally surfaced rather than hidden by reducing font size.
    report['overflow_count']=sum(int(s['overflows_review_cell']) for info in report['samples'].values() for s in info['samples'].values())
    dst=out/'chunks-font-visual-stress.json'
    dst.write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n')
    print('Rendered',len(report['samples']),'real-font sample rows across 4 sizes; review cells overflowing:',report['overflow_count'])
    return report

if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--root',type=Path,default=Path('.'))
    p.add_argument('--out',type=Path,default=Path('font-stress-artifacts'))
    a=p.parse_args()
    create(a.root,a.out)