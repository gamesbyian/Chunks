#!/usr/bin/env python3
"""Render comparative OTF ink, never a vector approximation or unlicensed font."""
from __future__ import annotations
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont

ROOT=Path('.')
SAMPLES=('ìíîïĩīĭǐȉȋỉĵ','Tìme to Ĵump!','Torrents, your way.','68% · Résumé 2024.mkv')
FAMILIES=(('AccentStudy','accent-studies','AccentStudy'),
          ('AccentKerned','accent-kerned','AccentKerned'),
          ('DotlessKerned','dotless-kerned','DotlessKerned'))
WEIGHTS=('Regular','Medium','Semibold','Bold','ExtraBold')


def create(root:Path,out:Path):
    width=1770;row_height=248
    canvas=Image.new('RGB',(width,60+len(WEIGHTS)*row_height),'#FBFBFE')
    draw=ImageDraw.Draw(canvas)
    label=ImageFont.truetype('DejaVuSans.ttf',19)
    heading=ImageFont.truetype('DejaVuSans.ttf',24)
    draw.text((24,10),'CHUNKS / REFERENCE DOTLESS i,j / THREE VERSIONS x FIVE WEIGHTS',font=heading,fill='#152036')
    for j,style in enumerate(WEIGHTS):
        y=56+j*row_height
        if j%2==0:draw.rectangle((0,y,width,y+row_height),fill='#F0F4FA')
        for i,(label_name,subdir,suffix) in enumerate(FAMILIES):
            x=20+i*590
            path=root/'assets/fonts/experimental'/subdir/f'ChunksReferencePanel-{style}-{suffix}.otf'
            font=ImageFont.truetype(str(path),31,layout_engine=ImageFont.Layout.RAQM)
            draw.text((x,y+4),f'{style} / {label_name}',font=label,fill='#516075')
            for k,sample in enumerate(SAMPLES):draw.text((x,y+38+k*44),sample,font=font,fill='#121825')
    out.parent.mkdir(parents=True,exist_ok=True)
    canvas.save(out,optimize=True)
    return out

if __name__=='__main__':create(ROOT,Path('reference-dotless-five-weights.png'))
