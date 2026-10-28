#!/usr/bin/env python3
"""Compute brand palette contrast for both competing reference systems (stdlib)."""
from __future__ import annotations
import argparse,json
from pathlib import Path

BASE=Path(__file__).resolve().parents[1]/'docs/design/svg/palettes'
DARK='#0B0F17'
LIGHT='#F8FAFC'
def luminance(hex_color):
    values=[int(hex_color[i:i+2],16)/255 for i in (1,3,5)]
    linear=[v/12.92 if v<=0.04045 else ((v+.055)/1.055)**2.4 for v in values]
    return sum(x*y for x,y in zip(linear,(.2126,.7152,.0722)))
def contrast(a,b):
    bright,dim=sorted((luminance(a),luminance(b)),reverse=True)
    return (bright+.05)/(dim+.05)
def generate():
    source=json.loads((BASE/'palette-tokens.json').read_text(encoding='utf8'))
    report={'schema':1,'scope':'reference colour pairs only; no theme changes',
            'surfaces':{'dark':DARK,'light':LIGHT},'thresholds':{'normalText':4.5,'largeTextOrIcon':3.0},'variants':[]}
    lines=['# Chunks palette contrast audit','',
           'Reference colours remain separate and editable. This is automated WCAG 2.x pairwise contrast evidence, not optical approval or a chosen app palette. Dark text means Brand Essence near-black and light text means Brand Essence off-white.','',
           'AA normal text: 4.5:1. Large text and relevant non-text icon boundaries: 3:1. A higher-contrast text suggestion is evidence only; no reference colour is overwritten.','']
    for variant in source['variants']:
        rows=[]
        for key,hex_color in variant['colors'].items():
            dark=contrast(hex_color,DARK);light=contrast(hex_color,LIGHT)
            preferred='near-black' if dark>=light else 'off-white'
            best=max(dark,light)
            rows.append({'token':key,'hex':hex_color,'darkText':f'{dark:.2f}',
                         'lightText':f'{light:.2f}','candidate':preferred,
                         'candidateRatio':f'{best:.2f}','normalTextAA':best>=4.5,
                         'iconOnDark':dark>=3.0,'iconOnLight':light>=3.0})
        report['variants'].append({'key':variant['key'],'certainty':variant['certainty'],
                                   'description':variant['description'],'rows':rows})
        lines.extend(['## '+variant['key'],'',variant['description']+' ('+variant['certainty']+')','',
                      '| Token | Value | Dark text | Light text | Suggested text | Normal AA | Icon on dark | Icon on light |',
                      '| --- | --- | ---: | ---: | --- | --- | --- | --- |'])
        for row in rows:
            lines.append('| '+row['token']+' | '+row['hex']+' | '+row['darkText']+':1 | '+row['lightText']+
                         ':1 | '+row['candidate']+' ('+row['candidateRatio']+':1) | '+
                         ('Pass' if row['normalTextAA'] else 'Fail')+' | '+
                         ('Pass' if row['iconOnDark'] else 'Fail')+' | '+
                         ('Pass' if row['iconOnLight'] else 'Fail')+' |')
        lines.append('')
    lines.extend(['## Interpretation','',
                  'Contrast applies to a particular foreground/background pair. A bright icon may pass on near-black but fail on off-white. 3:1 is not sufficient for ordinary text, and colour alone cannot communicate transfer state. Preserve descriptive icons, status text and TalkBack semantics. Both supplied palette systems and dimensional material shades remain available for mixing and matching.','',
                  'This report does not certify gradients, focus rings, disabled states, 200 percent font scaling, final Android Compose colours, or any physical device. Source colour labels from the Typography sheet remain explicitly provisional.',''])
    return json.dumps(report,indent=2,ensure_ascii=False)+'\n','\n'.join(lines)
def main(check):
    report,markdown=generate()
    files=[(BASE/'contrast-audit.json',report),(BASE/'contrast-audit.md',markdown)]
    for target,content in files:
        if check:
            if not target.exists() or target.read_text(encoding='utf8')!=content:
                raise SystemExit('Stale palette audit: '+str(target))
        else:
            target.write_text(content,encoding='utf8')
    print('PASS: all three reference palettes audited; no source colours changed')
if __name__=='__main__':
    args=argparse.ArgumentParser()
    args.add_argument('--check',action='store_true')
    main(args.parse_args().check)
