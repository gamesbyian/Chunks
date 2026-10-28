#!/usr/bin/env python3
"""Render safe stacked-accent CFF prototypes and report skipped forms."""
from __future__ import annotations

import json
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'assets/fonts/experimental/stacked-studies'
STYLES=('Regular','Medium','Semibold','Bold','ExtraBold')


def main():
    data=json.loads((OUT/'reference-stacked-accent-metrics.json').read_text())
    image=Image.new('RGB',(1500,1220),'#F6F8FC')
    pen=ImageDraw.Draw(image)
    small=ImageFont.load_default(size=17)
    heading=ImageFont.load_default(size=27)
    pen.text((24,12),'CHUNKS REFERENCE | SAFE STACKED ACCENT EXPERIMENT',
             font=heading,fill='#16223A')
    pen.text((24,54),'Only unambiguously detached ABOVE marks; all others retain source glyphs',
             font=small,fill='#53657D')
    for index,style in enumerate(STYLES):
        row=data['weights'][style]
        y=100+index*218
        pen.rounded_rectangle((16,y,1480,y+205),radius=10,fill='#FFFFFF',outline='#D6DEEA')
        pen.text((30,y+14),f"{style}: {row['built_count']} reconstructed, {row['skipped_count']} left unchanged",
                 font=heading,fill='#1A2B40')
        chosen=row['built'][:24]
        font=ImageFont.truetype(str(OUT/f'ChunksReferencePanel-{style}-Stacked.otf'),33,
                                layout_engine=ImageFont.Layout.RAQM)
        for i,item in enumerate(chosen):
            x=35+(i%12)*120
            cy=y+70+(i//12)*67
            pen.text((x,cy),item['glyph'],font=font,fill='#152032')
            pen.text((x+45,cy+12),f'U+{ord(item["glyph"]):04X}',
                     font=small,fill='#617389')
    dest=Path('reference-stacked-five-weights.png')
    image.save(dest)
    print(dest.resolve())


if __name__=='__main__':
    main()
