#!/usr/bin/env python3
"""Quantify real CFF-rendered counter visibility at actual small pixel sizes.

Unlike geometry-only source counters, this measures the rendered hole islands
at 14, 24, 48 and 82 px from four *compiled* weight-study OTFs.

The 24px minimum (>=2 high-contrast interior pixels per true counter)
is a nonregression gate. 14px is diagnostic because the original reference
is a very heavy display face and some 14px counters are subpixel.
"""
from __future__ import annotations
import argparse
import json
from collections import deque
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parents[1]
DIR=ROOT/'assets/fonts/experimental/weight-studies'
STYLES=('Regular','Medium','Semibold','Bold')
SIZES=(14,24,48,82)

def visible_holes(font: ImageFont.FreeTypeFont, ch:str, threshold:int=127) -> list[int]:
    x0,y0,x1,y1=font.getbbox(ch)
    pad=5
    width=max(1,x1-x0+2*pad)
    height=max(1,y1-y0+2*pad)
    im=Image.new('L',(width,height),0)
    ImageDraw.Draw(im).text((pad-x0,pad-y0),ch,font=font,fill=255)
    ink=[v>threshold for v in im.getdata()]
    visited=bytearray(width*height)
    holes=[]
    # A 4-neighbour background flood fill is consistent with the existing
    # image-based contour gate and catches tiny disconnected white islands.
    for start in range(width*height):
        if ink[start] or visited[start]:
            continue
        q=deque([start]);visited[start]=1;count=0;edge=False
        while q:
            at=q.popleft();count+=1
            x=at%width;y=at//width
            if x==0 or x==width-1 or y==0 or y==height-1:edge=True
            for nxt in (at-1 if x else -1,
                        at+1 if x<width-1 else -1,
                        at-width if y else -1,
                        at+width if y<height-1 else -1):
                if nxt>=0 and not ink[nxt] and not visited[nxt]:
                    visited[nxt]=1;q.append(nxt)
        if not edge:holes.append(count)
    return sorted(holes,reverse=True)

def audit():
    metric=json.loads((DIR/'reference-panel-weight-study-metrics.json').read_text())
    results={}
    failures=[]
    for style in STYLES:
        glyphs=metric['weights'][style]['glyph_geometry']
        chars={ch:info['counters'] for ch,info in glyphs.items()
               if info['counters']>0}
        for px in SIZES:
            font=ImageFont.truetype(str(DIR/f'ChunksReferencePanel-{style}-Study.otf'),px)
            measurements={}
            for ch,expected in chars.items():
                areas=visible_holes(font,ch)
                visible=sum(a>=2 for a in areas)
                measurements[ch]={'expected_counters':expected,
                                  'interior_pixel_areas':areas,
                                  'visible_counters_at_2px':visible}
                if px==24 and visible<expected:
                    failures.append(f'{style} {ch!r} @24px: '
                                    f'expected {expected} >=2px holes, got {areas}')
            results[f'{style}-{px}px']=measurements
    return {'status':'nonshipping raster counter audit',
            'threshold':'128/255 grayscale (ink >127), 4-neighbour hole connectivity',
            'strict_24px_failure_count':len(failures),
            'strict_24px_failures':failures,
            'measurements':results}

if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--output',type=Path,
                   default=Path('font-lab-raster-counters.json'))
    p.add_argument('--strict',action='store_true',
                   help='fail if any real 24px counter has <2 pixels')
    args=p.parse_args()
    data=audit()
    args.output.write_text(json.dumps(data,indent=2,ensure_ascii=False)+'\n')
    for key,rows in data['measurements'].items():
        missing=[ch for ch,v in rows.items()
                 if v['visible_counters_at_2px']<v['expected_counters']]
        print(f'{key}: {len(rows)} counter glyphs, {len(missing)} subpixel warnings: '
              + ''.join(missing))
    if args.strict and data['strict_24px_failures']:
        p.exit(1,'\n'.join(data['strict_24px_failures'])+'\n')
