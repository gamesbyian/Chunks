#!/usr/bin/env python3
"""Render exact candidate CFF OTFs; no image-generator approximation."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import argparse

LABELS=('Regular','Medium','Semibold','Bold','ExtraBold')
ROWS=('ABCDEFGHIJKLMNOPQRSTUVWXYZ',
      'abcdefghijklmnopqrstuvwxyz',
      '0123456789  Äéöüñß ₤ € % &',
      'Chunks  Torrent Details  Downloading 68%')

def render(folder,heavy,destination):
    folder=Path(folder);heavy=Path(heavy);destination=Path(destination)
    width=1800;h=5*310+60
    img=Image.new('RGB',(width,h),'#FBFAF5');d=ImageDraw.Draw(img)
    try:small=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',20)
    except OSError:small=ImageFont.load_default()
    for i,style in enumerate(LABELS):
        y=15+i*310
        bg=['#FFF0F7','#F1EEFF','#E7F6FF','#FFF6DB','#E4F9EF'][i]
        d.rounded_rectangle((16,y,1784,y+294),radius=16,fill=bg)
        file=heavy if style=='ExtraBold' else folder/f'ChunksReferencePanel-{style}-Study.otf'
        font=ImageFont.truetype(str(file),43,layout_engine=ImageFont.Layout.RAQM)
        d.text((32,y+6),f'CHUNKS REFERENCE PANEL STUDY    {style} { [400,500,600,700,800][i]}    |    {file.stat().st_size:,} bytes',font=small,fill='#4B415B')
        for n,line in enumerate(ROWS):
            d.text((32,y+44+62*n),line,font=font,fill='#12101D')
    destination.parent.mkdir(exist_ok=True,parents=True)
    img.save(destination)
    return destination
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--folder',type=Path,default=Path('assets/fonts/experimental/weight-studies'))
    p.add_argument('--heavy',type=Path,default=Path('assets/fonts/experimental/ChunksReferencePanel-ExtraBold-Optical.otf'))
    p.add_argument('--output',type=Path,default=Path('reference-panel-five-weights.png'))
    a=p.parse_args();print(render(a.folder,a.heavy,a.output))