#!/usr/bin/env python3
"""Five-weight actual-OTF specimen, including precomposed Latin accents."""
from pathlib import Path
import json,argparse
from PIL import Image,ImageDraw,ImageFont
from build_reference_accent_studies import WEIGHTS

LINES=[
 'ÀÁÂÃÄÅ  ÈÉÊË  ÌÍÎÏ  ÑÒÓÔÕÖ  ÚÛÜ',
 'àáâãäå èéêë ñòóôõö ùúûü',
 'Český přehled • Žlutý kůň • 68%',
 'Résumé, télévision, Österreichstraße',
 'Chunks • DOWNLOADS • 0123456789',
]

def main():
 p=argparse.ArgumentParser();p.add_argument('--fonts',type=Path,default=Path('assets/fonts/experimental/accent-studies'));p.add_argument('--out',type=Path,default=Path('reference-accent-five-weights.png'));a=p.parse_args()
 rep=json.loads((a.fonts/'reference-accent-study-metrics.json').read_text())['weights']
 width=1650;block=330
 image=Image.new('RGB',(width,5*block+35),'white');d=ImageDraw.Draw(image)
 info=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',20)
 backgrounds=['#FFF4FA','#F3EEFF','#E8F7FF','#FFF6D9','#E9FAF1']
 for i,(w,style) in enumerate(WEIGHTS.items()):
  y=i*block
  d.rounded_rectangle((12,y+8,width-12,y+block-2),radius=20,fill=backgrounds[i])
  count=rep[style]['built']
  d.text((30,y+20),f'CHUNKS REFERENCE PANEL • {style.upper()} {w}    |    {count} recomposed accents',font=info,fill='#47415A')
  face=ImageFont.truetype(str(a.fonts/f'ChunksReferencePanel-{style}-AccentStudy.otf'),43)
  for j,line in enumerate(LINES):
   d.text((30,y+56+j*49),line,font=face,fill='#090C17')
 a.out.parent.mkdir(parents=True,exist_ok=True);image.save(a.out)
 print('Rendered',a.out)
if __name__=='__main__':main()