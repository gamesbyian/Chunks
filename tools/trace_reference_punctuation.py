#!/usr/bin/env python3
"""Extract 28 reference punctuation variants from the owner-approved typography PNG.

These are optional vector glyph source masters, not replacements for approved
Chunks fonts or Android resources. Segmentation identities are pinned explicitly
and fail closed if the reference changes.
"""
from __future__ import annotations
import argparse,hashlib,json
from pathlib import Path
import cv2,numpy as np
from trace_reference_alphabet import find_runs,glyph_contours,render_svg,THRESH

# 29 vertical ink runs; two separate double-quotes each have two components.
SEQUENCE=[
 ('!',(0,)),('?',(1,)),('.',(2,)),(',',(3,)),(':',(4,)),(';',(5,)),
 ('“',(6,7)),('”',(8,9)),('(',(10,)),(')',(11,)),('[',(12,)),(']',(13,)),
 ('{',(14,)),('}',(15,)),('@',(16,)),('#',(17,)),('$',(18,)),('%',(19,)),
 ('&',(20,)),('*',(21,)),('+',(22,)),('-',(23,)),('=',(24,)),
 ('<',(25,)),('>',(26,)),('/',(27,)),('\\',(28,)),
]
# Straight ASCII quote shares the first source curly-quote variant, available
# independently alongside the two curly shapes as a reversible design choice.
DUPLICATES={'"':'“'}
ROW=(528,568,30,553)
EXPECTED=29


def extract(source:Path):
    raw=source.read_bytes()
    img=cv2.imdecode(np.frombuffer(raw,np.uint8),cv2.IMREAD_COLOR)
    if img is None:raise AssertionError('Missing source image')
    height,width=img.shape[:2]
    if abs(width/height-4/3)>.012:raise AssertionError('Source ratio must be the 4:3 type sheet')
    if (height,width)!=(1152,1536):
        img=cv2.resize(img,(1536,1152),interpolation=cv2.INTER_AREA if width>1536 else cv2.INTER_CUBIC)
    rgb=cv2.cvtColor(img,cv2.COLOR_BGR2RGB)
    top,bottom,left,right=ROW
    region=rgb[top:bottom,left:right]
    pale=np.where((region.min(axis=2)>THRESH)&((region.max(axis=2)-region.min(axis=2))<55),255,0).astype(np.uint8)
    runs=find_runs(pale,left)
    if len(runs)!=EXPECTED:raise AssertionError(f'Expected 29 punctuation ink segments, found {len(runs)}: {runs}')
    if sorted(i for _,group in SEQUENCE for i in group)!=list(range(EXPECTED)):
        raise AssertionError('Every source segment must be assigned once')
    catalog={}
    for char,indices in SEQUENCE:
        x0=max(left,min(runs[i][0] for i in indices)-1)
        x1=min(right-1,max(runs[i][1] for i in indices)+1)
        piece=pale[:,x0-left:x1-left+1]
        ys,xs=np.where(piece>0)
        if not len(xs):raise AssertionError(f'Empty punctuation {char!r}')
        y0=max(0,int(ys.min())-1);y1=min(piece.shape[0]-1,int(ys.max())+1)
        crop=piece[y0:y1+1,:]
        outlines,n=glyph_contours(crop)
        catalog[char]={'row':'punctuation','source_bbox':[x0,top+y0,x0+crop.shape[1],top+y0+crop.shape[0]],
                       'height':crop.shape[0],'width':crop.shape[1],
                       'contours':n,'paths':outlines,'runs':list(indices)}
    for duplicate,source_char in DUPLICATES.items():
        copy=dict(catalog[source_char]);copy['derived_variant_of']=source_char
        catalog[duplicate]=copy
    if len(catalog)!=28:raise AssertionError(f'Expected 28 variants; got {len(catalog)}')
    return catalog,hashlib.sha256(raw).hexdigest()


def generate(source:Path,out:Path):
    out.mkdir(parents=True,exist_ok=True)
    glyphs,digest=extract(source)
    for char,item in glyphs.items():
        (out/f'U+{ord(char):04X}.svg').write_text(render_svg(char,item))
    manifest={'status':'NONSHIPPING original reference punctuation contours',
       'source_image':'docs/design/reference-images/03-typography-and-wordmark.png',
       'source_sha256':digest,'normalized_image_size':[1536,1152],
       'glyph_count':len(glyphs),'source_ink_segments':EXPECTED,
       'glyphs':{c:{k:v for k,v in obj.items() if k!='paths'} for c,obj in glyphs.items()}}
    (out/'manifest.json').write_text(json.dumps(manifest,indent=2,ensure_ascii=False)+'\n')
    return manifest

if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--source',type=Path,default=Path('docs/design/reference-images/03-typography-and-wordmark.png'))
    p.add_argument('--out',type=Path,default=Path('assets/fonts/source/reference-punctuation'))
    a=p.parse_args();x=generate(a.source,a.out)
    print(f'Reconstructed {x["glyph_count"]} punctuation SVG variants from source {x["source_sha256"]}')
