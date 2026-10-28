#!/usr/bin/env python3
"""Show five actual CFF families before/after contextual kerning, at display sizes."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
STYLE=('Regular','Medium','Semibold','Bold','ExtraBold')
SAMPLES=('TŶr  Źn  ḍr  ÄV', 'Résumé télévision · Český přehled', 'Ŷoung  Übergröße  ñandú')

def render(root:Path,out:Path):
    face=root/'assets/fonts/experimental'
    width=1860;h=1240
    img=Image.new('RGB',(width,h),'#FAFBFD');d=ImageDraw.Draw(img)
    display=ImageFont.truetype('DejaVuSans.ttf',27)
    label=ImageFont.truetype('DejaVuSans.ttf',19)
    d.text((22,15),'Chunks Reference Panel • five weights • actual CFF kerning comparison',font=display,fill='#121722')
    d.text((22,55),'Top row = reference-panel accent source; bottom row = inherited-context GPOS.  Not yet approved for Android.',font=label,fill='#445266')
    for i,style in enumerate(STYLE):
        y=100+i*222
        d.rounded_rectangle((15,y,width-15,y+214),radius=15,fill=('#EEF5FF' if i%2==0 else '#F5F1FF'))
        d.text((26,y+8),f'{style}  /  48px actual font',font=label,fill='#394657')
        for j,(family,stem) in enumerate((('SOURCE','accent-studies'),('KERNED','accent-kerned'))):
            fontpath=face/stem/f'ChunksReferencePanel-{style}-{"AccentStudy" if j==0 else "AccentKerned"}.otf'
            f=ImageFont.truetype(str(fontpath),43,layout_engine=ImageFont.Layout.RAQM)
            yy=y+42+j*78
            d.text((27,yy+4),family,font=label,fill='#445266')
            x=164
            for s in SAMPLES:
                if x>1450:break
                limit=550 if s==SAMPLES[1] else 430
                d.text((x,yy),s,font=f,fill='#101521')
                x+=min(limit,int(f.getlength(s))+35)
    out.parent.mkdir(parents=True,exist_ok=True)
    img.save(out)
    return out

if __name__=='__main__':
 import argparse
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,default=Path('.'))
 p.add_argument('--out',type=Path,default=Path('reference-accent-kerned-comparison.png'))
 a=p.parse_args();render(a.root,a.out)