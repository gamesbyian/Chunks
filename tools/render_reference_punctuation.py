#!/usr/bin/env python3
"""Evidence atlas of exact extracted reference punctuation SVGs, no substitutions."""
from __future__ import annotations
import argparse,io,json
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import cairosvg


def render(source:Path,out:Path):
    manifest=json.loads((source/'manifest.json').read_text())
    glyphs=manifest['glyphs']
    width=7*180;height=4*165+48
    canvas=Image.new('RGB',(width,height),'#F8FAFD')
    d=ImageDraw.Draw(canvas)
    label=ImageFont.truetype('DejaVuSans.ttf',15)
    head=ImageFont.truetype('DejaVuSans.ttf',20)
    d.text((16,10),'CHUNKS / 28 PUNCTUATION GLYPH MASTERS / FROM THE ORIGINAL TYPE SHEET',font=head,fill='#192134')
    for i,(char,meta) in enumerate(glyphs.items()):
        x=(i%7)*180;y=48+(i//7)*165
        if (i//7)%2==0:d.rectangle((x,y,x+180,y+165),fill='#EAF3FC')
        p=source/f'U+{ord(char):04X}.svg'
        svg=p.read_bytes()
        image=Image.open(io.BytesIO(cairosvg.svg2png(bytestring=svg,output_width=int(meta['width']*3),output_height=int(meta['height']*3)))).convert('RGBA')
        image.thumbnail((130,117),Image.Resampling.LANCZOS)
        canvas.paste(image,(x+(180-image.width)//2,y+14),image)
        d.text((x+12,y+127),f'U+{ord(char):04X}  {char}',font=label,fill='#14283E')
    out.parent.mkdir(parents=True,exist_ok=True);canvas.save(out,optimize=True)
    return out

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--source',type=Path,default=Path('assets/fonts/source/reference-punctuation'))
    p.add_argument('--out',type=Path,default=Path('reference-punctuation-atlas.png'))
    a=p.parse_args();render(a.source,a.out)
