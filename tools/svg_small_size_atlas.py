#!/usr/bin/env python3
"""Browse Chunks SVGs at five native-icon pixel sizes. Review warnings, not approvals."""
import argparse,html,io,json,subprocess
from collections import Counter
from pathlib import Path
from PIL import Image
ROOT=Path(__file__).resolve().parents[1]
BASE=ROOT/'docs/design/svg'
CATS=set('navigation status system transfers storage files connectivity missing-glyph-icons launcher-concepts launcher-alternative android-specimens approved-derived'.split())
SIZES=(16,24,32,48,64)
def render(path,size):
    b=subprocess.run(['rsvg-convert','--format','png','--keep-aspect-ratio','--width',str(size),'--height',str(size),str(path)],check=True,capture_output=True,timeout=30).stdout
    im=Image.open(io.BytesIO(b)).convert('RGBA')
    if not 1<=im.width<=size or not 1<=im.height<=size:raise ValueError('Invalid image size '+str(path))
    return im
def main(out):
    entries=json.loads((BASE/'master-inventory.json').read_text())['assets']
    entries=sorted((a for a in entries if a['status']=='vector-draft' and a.get('category') in CATS),key=lambda a:(a['category'],a['id']))
    out.mkdir(parents=True,exist_ok=True)
    cards=[];results=[]
    for index,a in enumerate(entries):
        previews=[];frames={}
        for size in SIZES:
            im=render(ROOT/a['path'],size)
            p=out/'renders'/str(index)/(str(size)+'.png')
            p.parent.mkdir(parents=True,exist_ok=True);im.save(p)
            frames[size]=im
            previews.append('<figure><img loading="lazy" src="%s" width="%d" height="%d" alt="%s at %d px"><figcaption>%d px</figcaption></figure>'%(p.relative_to(out).as_posix(),im.width,im.height,html.escape(a['id'],quote=True),size,size))
        notes=[]
        if frames[16].getchannel('A').getbbox() is None:notes.append('Invisible at 16px')
        if frames[64].getchannel('A').getbbox() is None:notes.append('Invisible at 64px')
        if frames[64].getchannel('A').getbbox()==(0,0,frames[64].width,frames[64].height):notes.append('Full bleed: review mask')
        results.append({'id':a['id'],'category':a['category'],'flags':notes})
        cards.append('<article><strong>%s</strong><small>%s</small><div class="sizes">%s</div><p>%s</p></article>'%(html.escape(a['id']),html.escape(a['category']),''.join(previews),html.escape(', '.join(notes) or 'Five render sizes')))
    counts=dict(Counter(flag for item in results for flag in item['flags']))
    (out/'report.json').write_text(json.dumps({'scope':'SVG visual triage, not optical approval','count':len(entries),'sizes':SIZES,'flags':counts,'items':results},indent=2)+'\n')
    css='body{font:14px system-ui;background:#0B0F17;color:#F8FAFC;margin:12px auto;padding:15px;max-width:1150px}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:12px}article{background:#1A1F2B;border:1px solid #41516c;border-radius:12px;padding:12px;overflow-wrap:anywhere}strong,small{display:block;margin-bottom:7px}.sizes{display:flex;align-items:center;justify-content:space-around;background:white;color:#0B0F17;border-radius:8px;padding:9px;min-height:100px;overflow:auto}figure{margin:0;min-width:35px;text-align:center}figcaption{font-size:10px}p,small{color:#BEC9D8;line-height:1.4}'
    page='<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Chunks five-size icon review</title><style>'+css+'</style><h1>Chunks native-size icon review</h1><p>'+str(len(entries))+' distinct vector drafts at 16, 24, 32, 48 and 64 pixels. Render/visibility success does not certify contrast, accessibility or owner approval.</p><main class="grid">'+''.join(cards)+'</main></html>'
    (out/'index.html').write_text(page,encoding='utf8')
    print('SVG SMALL-SIZE PASS:',len(entries),'assets;',counts)
if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('--out',default='generated/svg-small-size')
    main(Path(ap.parse_args().out))
