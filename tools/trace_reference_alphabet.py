#!/usr/bin/env python3
"""Extract opt-in reference-derived glyph silhouette masters from the canonical PNG.

No OCR, no fabricated glyphs. Row glyph identities and source coordinates are
explicit. The master remains a proposed glyph variant, NEVER overwrites the
approved six reference-traced wordmark contours or any shipping font by itself.
"""
from __future__ import annotations
import argparse, hashlib, json
from pathlib import Path
import cv2
import numpy as np

ROWS=(
    ('uppercase-A-M','ABCDEFGHIJKLM',298,337,30,520),
    ('uppercase-N-Z','NOPQRSTUVWXYZ',342,382,30,563),
    ('lowercase-a-m','abcdefghijklm',388,437,30,465),
    ('lowercase-n-z','nopqrstuvwxyz',439,480,30,488),
    ('digits','0123456789',487,526,30,378),
)
THRESH=170


def find_runs(mask,offset=0):
    projection=(mask!=0).sum(axis=0)
    runs=[];start=None
    for i,pixels in enumerate(projection):
        on=pixels >= 3
        if on and start is None:start=i
        if not on and start is not None:
            if i-start>=5:runs.append((offset+start,offset+i-1))
            start=None
    if start is not None and len(projection)-start>=5:runs.append((offset+start,offset+len(projection)-1))
    return runs


def glyph_contours(gray):
    # Fourfold scaling + small blur suppresses native JPEG/AI-image stairsteps.
    up=cv2.resize(gray,None,fx=4,fy=4,interpolation=cv2.INTER_CUBIC)
    up=cv2.GaussianBlur(up,(5,5),.9)
    _,binary=cv2.threshold(up,160,255,cv2.THRESH_BINARY)
    contours,hierarchy=cv2.findContours(binary,cv2.RETR_TREE,cv2.CHAIN_APPROX_SIMPLE)
    if hierarchy is None:raise AssertionError('No glyph ink')
    hierarchy=hierarchy[0]
    valid=[]
    for i,c in enumerate(contours):
        # Keep real counters, punctuation dots and stems; discard microscopic grit.
        if abs(cv2.contourArea(c))<4*4*.35:continue
        c=cv2.approxPolyDP(c,1.25,True)
        if len(c)<3:continue
        valid.append((i,c))
    if not valid:raise AssertionError('Only insignificant glyph contours')
    paths=[]
    for _,c in valid:
        p=c.reshape(-1,2).astype(float)/4
        # Rounded midpoint-quadratic path: visibly smoother than trace polygons.
        mids=(p+np.roll(p,-1,axis=0))/2
        parts=[f'M{mids[-1][0]:.2f},{mids[-1][1]:.2f}']
        for i,(x,y) in enumerate(p):
            ex,ey=mids[i]
            parts.append(f'Q{x:.2f},{y:.2f} {ex:.2f},{ey:.2f}')
        parts.append('Z')
        paths.append(' '.join(parts))
    return paths,len(valid)


def extract(source):
    original=Path(source).read_bytes()
    image=cv2.imdecode(np.frombuffer(original,np.uint8),cv2.IMREAD_COLOR)
    if image is None:
        raise AssertionError('Reference image cannot be decoded')
    original_height, original_width = image.shape[:2]
    if abs(original_width / original_height - 4/3) > .012:
        raise AssertionError('Source aspect ratio differs from 4:3 reference; fail closed')
    # The user's original illustration and the checked-in PNG may use different
    # pixel dimensions/compression. Normalize their geometry before extracting.
    if (original_height,original_width)!=(1152,1536):
        method=cv2.INTER_AREA if original_width>1536 else cv2.INTER_CUBIC
        image=cv2.resize(image,(1536,1152),interpolation=method)
    rgb=cv2.cvtColor(image,cv2.COLOR_BGR2RGB)
    catalog={}
    for row,characters,top,bottom,left,right in ROWS:
        region=rgb[top:bottom,left:right]
        maximum=region.max(axis=2);minimum=region.min(axis=2)
        white=np.where((minimum>THRESH)&(maximum-minimum<55),255,0).astype(np.uint8)
        runs=find_runs(white,left)
        if len(runs)!=len(characters):
            raise AssertionError(f'{row}: expected {len(characters)} glyphs, found {len(runs)}: {runs}')
        for char,(x0,x1) in zip(characters,runs):
            # Use established per-row geometry; within the assigned segment, keep every ink pixel.
            x0=max(left,x0-1);x1=min(right-1,x1+1)
            piece=white[:,x0-left:x1-left+1]
            ys,xs=np.where(piece>0)
            if not len(xs):raise AssertionError(f'{row} {char}: empty silhouette')
            ya=max(0,int(ys.min())-1);yb=min(piece.shape[0]-1,int(ys.max())+1)
            crop=piece[ya:yb+1,:]
            paths,n=glyph_contours(crop)
            x_source=x0; y_source=top+ya
            catalog[char]={
                'row':row,'source_bbox':[x_source,y_source,x_source+crop.shape[1],y_source+crop.shape[0]],
                'height':crop.shape[0],'width':crop.shape[1], 'contours':n,'paths':paths
            }
    assert len(catalog)==62, len(catalog)
    return catalog, hashlib.sha256(original).hexdigest()


def render_svg(char,data):
    v=data
    body='<path d="' + " ".join(v["paths"]) + '"/>'
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {v["width"]} {v["height"]}" '
            f'role="img" aria-label="Chunks reference glyph U+{ord(char):04X}">\n'
            f'<title>Reference-derived U+{ord(char):04X} glyph variant</title>\n'
            f'<g fill="#0B0F17" fill-rule="evenodd">{body}</g>\n</svg>\n')


def generate(source,output):
    catalog,digest=extract(source)
    output=Path(output);output.mkdir(parents=True,exist_ok=True)
    for ch,v in catalog.items():
        (output/f'U+{ord(ch):04X}.svg').write_text(render_svg(ch,v),encoding='utf8')
    manifest={
      'status':'reference-derived proposal; not shipping or approved contours',
      'canonical_image':'docs/design/reference-images/03-typography-and-wordmark.png',
      'source_sha256':digest,'mapped_glyph_count':len(catalog),'threshold':THRESH,
      'normalized_image_size':[1536,1152],
      'glyphs':{ch:{k:value for k,value in v.items() if k!='paths'} for ch,v in catalog.items()}
    }
    (output/'manifest.json').write_text(json.dumps(manifest,indent=2,ensure_ascii=False)+'\n',encoding='utf8')
    return manifest


if __name__=='__main__':
    ap=argparse.ArgumentParser()
    ap.add_argument('--source',type=Path,default=Path('docs/design/reference-images/03-typography-and-wordmark.png'))
    ap.add_argument('--out',type=Path,default=Path('assets/fonts/source/reference-alphabet'))
    args=ap.parse_args()
    result=generate(args.source,args.out)
    print(f'Reconstructed {result["mapped_glyph_count"]} individually editable reference glyph SVGs')