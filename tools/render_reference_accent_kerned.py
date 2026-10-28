#!/usr/bin/env python3
"""Visually compare five real CFF font pairs without hiding collisions/overflows."""
from __future__ import annotations
import argparse
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
STYLES=('Regular','Medium','Semibold','Bold','ExtraBold')
PHRASES=('TŶr  Źn  ḍr  ÄV  ÁV  ŔA','Résumé télévision · Český přehled')
INK='#0B101C';BORDER='#D7DAE5';LABEL='#36415B'


def render(root:Path,out:Path):
    family=root/'assets/fonts/experimental'
    width=1850;panel_h=253
    img=Image.new('RGB',(width,98+len(STYLES)*panel_h+10),'#FAFBFD')
    draw=ImageDraw.Draw(img)
    display=ImageFont.truetype('DejaVuSans.ttf',28)
    label=ImageFont.truetype('DejaVuSans.ttf',19)
    label_small=ImageFont.truetype('DejaVuSans.ttf',16)
    draw.text((25,13),'Chunks Reference Panel · five font weights · contextual kerning',font=display,fill=INK)
    draw.text((25,55),'The same compiled letterforms before (SOURCE) and after (KERNED) bounded GPOS changes.',font=label,fill=LABEL)
    for index,style in enumerate(STYLES):
        top=96+index*panel_h
        draw.rounded_rectangle((15,top,width-15,top+panel_h-7),radius=16,
                               fill='#EAF2FD' if index%2==0 else '#F3EDFF',outline=BORDER,width=1)
        draw.text((27,top+8),f'{style} · 40px genuine OpenType/CFF',font=label,fill=LABEL)
        for row,(kind,part) in enumerate((('SOURCE','accent-studies'),('KERNED','accent-kerned'))):
            path=family/part/f'ChunksReferencePanel-{style}-{"AccentStudy" if row==0 else "AccentKerned"}.otf'
            otf=ImageFont.truetype(str(path),40,layout_engine=ImageFont.Layout.RAQM)
            for col,phrase in enumerate(PHRASES):
                yy=top+40+row*100+col*47
                draw.text((30,yy+11),kind,font=label_small,fill=LABEL)
                left=155
                draw.text((left,yy),phrase,font=otf,fill=INK)
                # Show genuine overshoot rather than resizing the font to fit.
                advance=otf.getlength(phrase)
                if left+advance>width-30:
                    draw.text((width-205,yy+10),'REVIEW WIDTH',font=label_small,fill='#AE2343')
        draw.line((28,top+139,width-28,top+139),fill='#D4D7E0',width=1)
    out.parent.mkdir(parents=True,exist_ok=True)
    img.save(out,optimize=True)
    return out

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,default=Path('.'))
    p.add_argument('--out',type=Path,default=Path('reference-accent-kerned-comparison.png'))
    args=p.parse_args();render(args.root,args.out)