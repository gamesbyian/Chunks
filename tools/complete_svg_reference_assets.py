#!/usr/bin/env python3
"""Make 12 missing source-preserving Chunks SVG drafts. No image embedding."""
from pathlib import Path
import argparse,json,re,xml.etree.ElementTree as ET
import cv2,numpy as np
from PIL import Image
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen

ROOT=Path(__file__).resolve().parents[1]
BASE=ROOT/'docs/design/svg'
DEST=BASE/'fidelity'
MAN=BASE/'master-inventory.json'
HTML=BASE/'gallery.html'
IMG=ROOT/'docs/design/reference-images/06-colour-illustrations-and-stickers.png'
FONT=ROOT/'assets/fonts/chunks/Chunks-ExtraBold.otf'
CASES={
'purple-download-organise-sticker-exact-hand-lettering':((315,735,465,852),(329,756,445,837),(173,90,245),'#B66CF2'),
'green-fast-private-sticker-exact-hand-lettering':((450,738,568,848),(461,757,561,832),(75,235,141),'#69ED98'),
'pink-movies-shows-sticker-exact-hand-lettering':((570,738,691,850),(590,754,670,837),(255,96,179),'#FF5EAA')}
SLOGANS={
'yellow-same-files-sticker':('SAME FILES.\nBRIGHTER DAYS.',False),
'yellow-simple-icons-sticker':('SIMPLE ICONS.\nBIG POSSIBILITIES.',False),
'white-same-vibes-sticker':('SAME VIBES.\nBUILD AWESOME.',True),
'yellow-your-torrents-sticker':('YOUR TORRENTS.\nYOUR WAY.',False),
'white-same-torrents-sticker':('SAME TORRENTS.\nMORE SPACE\nTO BREATHE.',True),
'black-outlined-same-torrents-sticker':('SAME TORRENTS.\nMORE SPACE\nTO BREATHE.',True)}
def wrap(name,inner,w,h):
    label=name.replace('-',' ')
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" role="img" aria-label="{label}"><title>{label}</title>{inner}</svg>\n'
def contour(binary,offset=(0,0)):
    cs,_=cv2.findContours(binary,cv2.RETR_LIST,cv2.CHAIN_APPROX_SIMPLE)
    paths=[]
    for c in cs:
        if abs(cv2.contourArea(c))<.65:continue
        p=cv2.approxPolyDP(c,.65,True).reshape(-1,2)
        if len(p)<3:continue
        paths.append('M'+' L'.join(f'{int(x)+offset[0]} {int(y)+offset[1]}' for x,y in p)+' Z')
    return ' '.join(paths)
def trace_colored(im,rect,letter,reference,fill):
    x0,y0,x1,y1=rect;tx,ty,ex,ey=letter
    patch=im[y0:y1,x0:x1]
    hsv=cv2.cvtColor(patch,cv2.COLOR_RGB2HSV)
    hue=int(cv2.cvtColor(np.uint8([[reference]]),cv2.COLOR_RGB2HSV)[0,0,0])
    delta=np.abs(hsv[:,:,0].astype(np.int16)-hue)
    mask=((np.minimum(delta,180-delta)<22)&(hsv[:,:,1]>75)&(hsv[:,:,2]>85)).astype('uint8')*255
    mask=cv2.morphologyEx(mask,cv2.MORPH_CLOSE,np.ones((5,5),np.uint8))
    n,labels,stats,_=cv2.connectedComponentsWithStats(mask,8)
    if n<2:raise RuntimeError('Missing coloured sticker blob')
    mask=np.where(labels==1+np.argmax(stats[1:,cv2.CC_STAT_AREA]),255,0).astype('uint8')
    bg=contour(mask)
    sub=im[ty:ey,tx:ex]
    threshold=((sub[:,:,0]<127)&(sub[:,:,1]<127)&(sub[:,:,2]<127)).astype('uint8')*255
    n,l,s,_=cv2.connectedComponentsWithStats(threshold,8)
    letters=np.zeros_like(threshold)
    for i in range(1,n):
        x,y,w,h,area=s[i]
        if 2<=area<600 and w<65 and h<35:letters[l==i]=255
    fg=contour(letters,(tx-x0,ty-y0))
    if not bg or not fg:raise RuntimeError('Missing source lettering contour')
    stroke=' stroke="#0B0F17" stroke-width="1"' if fill in ('#B66CF2','#FF5EAA') else ''
    return f'<path d="{bg}" fill="{fill}"/><path d="{fg}" fill="#0B0F17" fill-rule="evenodd"{stroke}/>',x1-x0,y1-y0
def outlines(message,font):
    face=font.getGlyphSet(); cmap=font.getBestCmap();hmtx=font['hmtx'].metrics
    scale=51/font['head'].unitsPerEm
    lines=[]
    for line in message.split('\n'):
        advance=0;g=[]
        for c in line:
            glyph=cmap.get(ord(c))
            if not glyph:raise ValueError(f'Font has no glyph for {c}')
            pen=SVGPathPen(face);face[glyph].draw(pen)
            d=pen.getCommands()
            if d:g.append(f'<path transform="translate({advance} 0)" d="{d}"/>')
            advance+=hmtx[glyph][0]
        lines.append((advance*scale,''.join(g)))
    w=min(540,max(260,int(max(width for width,_ in lines)+40)))
    h=len(lines)*72+30
    result=[]
    for i,(width,content) in enumerate(lines):
        fit=min(1,(w-32)/max(width,1))
        x=(w-width*fit)/2
        result.append(f'<g transform="translate({x:.2f} {(i+1)*72}) scale({scale*fit:.7f} {-scale*fit:.7f})">{content}</g>')
    return ''.join(result),w,h
def make_slogan(name,words,font,white):
    letters,w,h=outlines(words,font)
    bg='#F8FAFC' if white else '#FFD93D'
    border=' stroke="#0B0F17" stroke-width="4"' if white else ''
    bubble=f'<path d="M20 32 Q70 1 {w//2} 16 Q{w-36} -10 {w-16} 50 Q{w+3} 94 {w-20} {h-24} Q{w//2} {h+5} 19 {h-20} Q-2 {h//2} 20 32 Z" fill="{bg}"{border}/>'
    return wrap(name,bubble+'<g fill="#0B0F17" transform="translate(0 6)">'+letters+'</g>',w,h)
def nest(file,x,y,w,h):
    raw=file.read_text(encoding='utf8').strip()
    head=re.match(r'<svg[^>]*viewBox="([^"]+)"[^>]*>',raw)
    if not head:raise ValueError(f'Missing viewBox in {file}')
    return f'<svg x="{x}" y="{y}" width="{w}" height="{h}" viewBox="{head.group(1)}">{raw[head.end():-6]}</svg>'
def scene(name):
    cream=name!='splash-screen-decorative-composition'
    bg='#FFF9EA' if cream else '#F8FAFC'
    art=f'<rect width="1200" height="780" fill="{bg}"/>'
    art+='<path d="M0 0 H260 Q215 87 314 133 Q188 180 172 237 Q12 209 0 112 Z" fill="#FF4EC4"/>'
    art+='<path d="M1025 0 Q994 124 1200 159 V0 Z" fill="#FFD93D"/>'
    art+='<path d="M0 677 Q157 591 350 780 H0 Z" fill="#22D97A"/>'
    art+='<path d="M941 780 Q1014 613 1200 650 V780 Z" fill="#00D4FF"/>'
    art+=nest(ROOT/'docs/design/approved/chunks-app-icon-master.svg',445,77,590,585)
    art+=nest(BASE/'brand/reference-traced/primary-lockup-source-traced.svg',175,472,850,290)
    if name=='onboarding-brand-hero-composition':
        art+='<rect x="438" y="722" width="324" height="37" rx="18" fill="#A56BFF"/>'
    if name=='splash-screen-decorative-composition':
        art+='<path d="M185 753 Q530 708 944 748" stroke="#FFD93D" stroke-width="12" fill="none"/>'
    return wrap(name,art,1200,780)
def expected():
    image=Image.open(IMG).convert('RGB')
    if image.size not in ((1448,1086),(1536,1152)):raise ValueError(f'Source image size changed: {image.size}')
    if image.size!=(1536,1152):image=image.resize((1536,1152),Image.Resampling.BICUBIC)
    raster=np.asarray(image); font=TTFont(FONT)
    result={}
    for name,(rect,letters,rgb,fill) in CASES.items():
        data,w,h=trace_colored(raster,rect,letters,rgb,fill)
        result[name]=(wrap(name,data,w,h),'source-contour-draft')
    for name,(phrase,white) in SLOGANS.items():
        result[name]=(make_slogan(name,phrase,font,white),'bundled-otf-outlined-interpretation')
    for name in ('cream-launch-screen-frame','splash-screen-decorative-composition','onboarding-brand-hero-composition'):
        result[name]=(scene(name),'approved-vector-recomposition')
    assert len(result)==12
    return result
def main(write):
    data=expected()
    manifest=json.loads(MAN.read_text(encoding='utf8'))
    for slug,(svg,method) in data.items():
        target=[x for x in manifest['assets'] if x['id'].split('/')[-1]==slug and x['status'] in ('planned','vector-draft')]
        if len(target)!=1:raise RuntimeError('Missing unique target '+slug)
        entry=target[0];path=DEST/(slug+'.svg')
        entry.update(status='vector-draft',path='docs/design/svg/fidelity/'+slug+'.svg',
            fidelity=method,source=entry['source']+'; reproducible original vector; optical approval pending')
        ET.fromstring(svg)
        if write:path.parent.mkdir(parents=True,exist_ok=True);path.write_text(svg,encoding='utf8')
        elif not path.exists() or path.read_text(encoding='utf8')!=svg:raise RuntimeError(f'Stale SVG {path}')
    manifest['totals']['planned']=0
    manifest['totals']['vectorDraft']=sum(a['status']=='vector-draft' for a in manifest['assets'])
    if write:MAN.write_text(json.dumps(manifest,indent=2,ensure_ascii=False)+'\n',encoding='utf8')
    elif json.loads(MAN.read_text(encoding='utf8'))['totals']!=manifest['totals']:raise RuntimeError('Counts mismatch')
    html=HTML.read_text(encoding='utf8')
    rx=r'(<script type="application/json" id="svg-catalog">)(.*?)(</script>)'
    match=re.search(rx,html,re.S)
    if not match:raise RuntimeError('Gallery catalog missing')
    gallery=json.loads(match.group(2));known={item['id'] for item in gallery}
    for entry in manifest['assets']:
        if entry['status']=='vector-draft' and entry['id'] not in known:
            gallery.append({'id':entry['id'],'category':entry['category'],'label':entry.get('label',entry['id']),
                'src':entry['path'].removeprefix('docs/design/svg/'),'source':entry.get('source','')})
            known.add(entry['id'])
    gallery.sort(key=lambda row:row['id'])
    body=json.dumps(gallery,ensure_ascii=False,separators=(',',':')).replace('<','\\u003c').replace('>','\\u003e').replace('&','\\u0026')
    updated=html[:match.start(2)]+body+html[match.end(2):]
    updated=re.sub(r'<strong>\d+ draft SVGs</strong>','<strong>%d draft SVGs</strong>'%manifest['totals']['vectorDraft'],updated)
    if write:HTML.write_text(updated,encoding='utf8')
    elif HTML.read_text(encoding='utf8')!=updated:raise RuntimeError('Gallery stale')
    provenance={'schema':1,'sources':['docs/design/reference-images/06-colour-illustrations-and-stickers.png',
      'docs/design/approved/chunks-app-icon-master.svg','assets/fonts/chunks/Chunks-ExtraBold.otf'],
      'approval':'Technical vector drafts, not pixel-exact optical acceptance',
      'imageEmbedding':False,'approvedLauncherModified':False,
      'assets':{x:method for x,(_,method) in sorted(data.items())}}
    content=json.dumps(provenance,indent=2)+'\n'; p=DEST/'provenance.json'
    if write:p.write_text(content,encoding='utf8')
    elif not p.exists() or p.read_text(encoding='utf8')!=content:raise RuntimeError('Provenance stale')
    print('PASS: 12 sticker SVG candidates verified; total %d library SVGs, none raster-embedded'%manifest['totals']['vectorDraft'])
if __name__=='__main__':
    arg=argparse.ArgumentParser();arg.add_argument('--write',action='store_true')
    main(arg.parse_args().write)
