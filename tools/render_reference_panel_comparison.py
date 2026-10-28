#!/usr/bin/env python3
"""Native-font-rendered, reproducible candidate-vs-shipping comparison sheet."""
from __future__ import annotations
import argparse
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont


def render(base, candidate, output):
    output=Path(output)
    canvas=Image.new('RGB',(1500,720),'#F6F1F8')
    draw=ImageDraw.Draw(canvas)
    for n,(name,path,bg) in enumerate((
        ('CURRENT CHUNKS EXTRABOLD: SIX WORDMARK MASTERS',Path(base),'#E9D7FF'),
        ('EXPERIMENTAL: 62 REFERENCE-PANEL GLYPHS',Path(candidate),'#FFEDC0'))):
        y=36+n*350
        draw.rounded_rectangle((18,y-12,1482,y+324),radius=18,fill=bg)
        draw.text((35,y),name,font=ImageFont.load_default(size=24),fill='#1D1530')
        display=ImageFont.truetype(str(path),82)
        draw.text((35,y+47),'Chunks 0123456789',font=display,fill='#0B0F17')
        smaller=ImageFont.truetype(str(path),36)
        draw.text((35,y+178),'TORRENTS, YOUR WAY.  Downloads 68%',font=smaller,fill='#0B0F17')
        draw.text((35,y+238),'ABCDEFGHIJKLMNOPQRSTUVWXYZ',font=ImageFont.truetype(str(path),35),fill='#0B0F17')
    output.parent.mkdir(parents=True,exist_ok=True)
    canvas.save(output)
    return output

if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--base',type=Path,default=Path('assets/fonts/chunks/Chunks-ExtraBold.otf'))
    p.add_argument('--candidate',type=Path,default=Path('assets/fonts/experimental/ChunksReferencePanel-ExtraBold.otf'))
    p.add_argument('--out',type=Path,default=Path('chunks-reference-panel-otf-comparison.png'))
    args=p.parse_args()
    print('Rendered',render(args.base,args.candidate,args.out))