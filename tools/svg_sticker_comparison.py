#!/usr/bin/env python3
"""Side-by-side visual review of source-extracted Chunks sticker variants."""
from __future__ import annotations
import argparse, html, io, json, subprocess
from pathlib import Path
from PIL import Image, ImageDraw

ROOT=Path(__file__).resolve().parents[1]
REFERENCE=ROOT/'docs/design/reference-images/06-colour-illustrations-and-stickers.png'
VECTOR=ROOT/'docs/design/svg/fidelity'
CASES={
  'purple-download-organise-sticker-exact-hand-lettering':((315,735,465,852),(329,756,445,837)),
  'green-fast-private-sticker-exact-hand-lettering':((450,738,568,848),(461,757,561,832)),
  'pink-movies-shows-sticker-exact-hand-lettering':((570,738,691,850),(590,754,670,837)),
}
def source():
    im=Image.open(REFERENCE).convert('RGB')
    if im.size==(1448,1086):
        im=im.resize((1536,1152),Image.Resampling.BICUBIC)
    if im.size!=(1536,1152):
        raise ValueError('Unexpected reference image size '+str(im.size))
    return im
def main(dest):
    dest.mkdir(parents=True,exist_ok=True)
    original=source()
    rows=[]
    for name,(bbox,letters) in CASES.items():
        x0,y0,x1,y1=bbox
        w,h=x1-x0,y1-y0
        cmd=['rsvg-convert','-w',str(w),'-h',str(h),'-a',str(VECTOR/(name+'.svg'))]
        svg_bytes=subprocess.run(cmd,check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=30).stdout
        candidate=Image.open(io.BytesIO(svg_bytes)).convert('RGBA')
        if candidate.size!=(w,h):
            raise ValueError(f'Render size mismatch: {name} {candidate.size}')
        expected=original.crop(bbox).convert('RGBA')
        white=Image.new('RGBA',(w,h),'white')
        composed=Image.alpha_composite(white,candidate)
        overlay=Image.blend(expected,composed,.5)
        # Indicative diagnostic IoU, restricted to lettering's source ROI.
        xx0,yy0,xx1,yy1=letters
        xc0,yc0,xc1,yc1=xx0-x0,yy0-y0,xx1-x0,yy1-y0
        src=expected.crop((xc0,yc0,xc1,yc1)).convert('RGB')
        render=composed.crop((xc0,yc0,xc1,yc1)).convert('RGB')
        m1=[max(p)<127 for p in src.getdata()]
        m2=[max(p)<127 for p in render.getdata()]
        intersection=sum(a and b for a,b in zip(m1,m2))
        union=sum(a or b for a,b in zip(m1,m2))
        iou=intersection/union if union else 0.0
        # Upscale for human inspection while keeping source pixels unmodified.
        scale=3
        panes=[img.resize((w*scale,h*scale),Image.Resampling.NEAREST)
               for img in (expected,composed,overlay)]
        out=Image.new('RGB',(w*scale*3+40,h*scale+70),'#F8FAFC')
        draw=ImageDraw.Draw(out)
        for i,(title,pane) in enumerate(zip(('Reference PNG','Rendered SVG','50% overlay'),panes)):
            px=10+i*(w*scale+10)
            out.paste(pane.convert('RGB'),(px,55))
            draw.text((px,15),title,fill='#0B0F17')
        file=name+'.png'
        out.save(dest/file)
        rows.append({'id':name,'file':file,'letteringIoU':round(iou,4),
            'note':'Diagnostic only. Text shape overlap is not a visual approval or proof of exact fidelity.'})
        print(f'{name}: diagnostic ink IoU {iou:.4f}')
    cards=''.join('<article><h2>'+html.escape(row['id'])+'</h2><img width="100%" alt="Original, vector render and blend" src="'+html.escape(row['file'])+'"/><p>Indicative text mask IoU: '+str(row['letteringIoU'])+'</p></article>' for row in rows)
    doc='<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Chunks sticker review</title><style>body{font:16px system-ui;background:#0B0F17;color:#F8FAFC;max-width:1150px;margin:auto;padding:24px}article{padding:12px;background:#1A1F2B;border-radius:14px;margin:20px 0}img{object-fit:contain;background:white}h2{font-size:17px;overflow-wrap:anywhere}p{line-height:1.4}</style><h1>Chunks SVG sticker reference comparisons</h1><p>Technical PNG versus vector overlays, not optical acceptance. Inspect the letter holes, cropped lower lines and wobbly contours; all original references remain unchanged.</p>'+cards+'</html>'
    (dest/'index.html').write_text(doc,encoding='utf8')
    (dest/'report.json').write_text(json.dumps(rows,indent=2)+'\n',encoding='utf8')
    print('SVG sticker comparison artifact PASS: 3 source-matched previews')
if __name__=='__main__':
    arg=argparse.ArgumentParser()
    arg.add_argument('--out',default='generated/svg-sticker-comparison')
    main(Path(arg.parse_args().out))
