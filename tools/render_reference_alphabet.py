#!/usr/bin/env python3
"""Render a vector-native specimen sheet for unapproved Chunks alphabet alternatives."""
from __future__ import annotations
import argparse
import io
import json
from pathlib import Path
import cairosvg
from PIL import Image, ImageDraw, ImageFont

ROWS=(
 ('UPPERCASE A–M','ABCDEFGHIJKLM'),
 ('UPPERCASE N–Z','NOPQRSTUVWXYZ'),
 ('LOWERCASE A–M','abcdefghijklm'),
 ('LOWERCASE N–Z','nopqrstuvwxyz'),
 ('NUMERALS','0123456789')
)

def render(directory, output):
    directory=Path(directory);output=Path(output)
    manifest=json.loads((directory/'manifest.json').read_text())
    assert len(manifest['glyphs'])==62
    width,height=1550,900
    im=Image.new('RGB',(width,height),'#F7F3F8');draw=ImageDraw.Draw(im)
    title=ImageFont.load_default(size=22);caption=ImageFont.load_default(size=15)
    for ri,(label,chars) in enumerate(ROWS):
        y=38+ri*172
        draw.rounded_rectangle((20,y,1530,y+162),radius=12,fill=['#FDE5F4','#E8D9FE','#E3F4FF','#DCFBE7','#FFF1C0'][ri])
        draw.text((35,y+10),label,font=title,fill='#16101B')
        for ci,ch in enumerate(chars):
            x=36+ci*115
            svg=(directory/f'U+{ord(ch):04X}.svg').read_bytes()
            metadata=manifest['glyphs'][ch]
            scale=min(82/metadata['width'],91/metadata['height'])
            png=cairosvg.svg2png(bytestring=svg,output_width=max(1,round(metadata['width']*scale)),
                                  output_height=max(1,round(metadata['height']*scale)))
            glyph=Image.open(io.BytesIO(png)).convert('RGBA')
            im.paste(glyph,(int(x+(88-glyph.width)//2),y+48),glyph)
            draw.text((x+33,y+140),ch,font=caption,fill='#413C46')
    output.parent.mkdir(parents=True,exist_ok=True)
    im.save(output)
    return output

if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--glyphs',default='assets/fonts/source/reference-alphabet',type=Path)
    p.add_argument('--out',default='chunks-reference-alphabet-atlas.png',type=Path)
    a=p.parse_args()
    print('Rendered',render(a.glyphs,a.out))