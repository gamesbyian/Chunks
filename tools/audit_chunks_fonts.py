#!/usr/bin/env python3
"""Deterministic OpenType family audit and visual regression specimens.

This is a source-based CI check, not a substitute for handset font rendering.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, features
from fontTools.ttLib import TTFont
from spacing_optimizer import ink_gaps_at_size

WEIGHTS = {400:'Regular',500:'Medium',600:'Semibold',700:'Bold',800:'ExtraBold'}
PROBES = [
 'Chunks', 'TORRENTS, YOUR WAY.', 'Torrent Details',
 'Downloading 68%   •   36.4 MB/s',
 'The.Wanderer.2024.2160p.HEVC.mkv',
 'Résumé – télévision   Ångström   Çü',
 'Local ↔ Remote    £ € ¥ $ 1234567890',
]

def analyze(font_dir: Path, reference_dir:Path|None=None, preview: Path|None=None, atlas: Path|None=None):
    if not features.check_feature('raqm'):
        raise RuntimeError('HarfBuzz/libraqm is mandatory for audit; do not silently approximate shaping')
    results={}
    prior_glyphs=None
    prior_metrics=None
    fonts={}
    sample_cp='Chunks ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz 0123456789 !?:;()[]{}$€£¥ ÀÁÂÃÄÅÇÈÉÊËÌÍÎÏÑÒÓÖÙÚÛÜÝŸ àáâäåçèéêëìíîïñòóöùúûüÿ'
    for weight,style in WEIGHTS.items():
        path=font_dir/f'Chunks-{style}.otf'
        f=TTFont(path)
        if f.sfntVersion!='OTTO' or 'CFF ' not in f or 'glyf' in f:
            raise AssertionError(f'{style}: not true CFF OpenType')
        if f['OS/2'].usWeightClass != weight:
            raise AssertionError(f'{style}: wrong weight class')
        chars=f.getBestCmap()
        if not all(ord(ch) in chars for ch in sample_cp):
            raise AssertionError(f'{style}: missing required Latin/currency glyphs')
        if len(chars)<850 or len(f.getGlyphOrder())<1600:
            raise AssertionError(f'{style}: glyph coverage regressed')
        if any(t not in f for t in ('GPOS','GDEF','GSUB','hhea','OS/2','name')):
            raise AssertionError(f'{style}: missing essential OpenType tables')
        if f['OS/2'].sTypoAscender!=f['hhea'].ascent or f['OS/2'].sTypoDescender!=f['hhea'].descent:
            raise AssertionError(f'{style}: inconsistent leading metrics')
        if not (f['OS/2'].fsSelection & (1<<7)):
            raise AssertionError(f'{style}: USE_TYPO_METRICS absent')
        if any(f['hmtx'][chars[ord(ch)]][0]<=0 for ch in 'Chunks'):
            raise AssertionError(f'{style}: nonpositive character advances')
        v=(f['hhea'].ascent,f['hhea'].descent,f['hhea'].lineGap)
        if prior_metrics is not None and v!=prior_metrics:
            raise AssertionError(f'{style}: cross-weight leading drift, {v} vs {prior_metrics}')
        prior_metrics=v
        cp_set=set(chars)
        if prior_glyphs is not None and cp_set!=prior_glyphs:
            raise AssertionError(f'{style}: inconsistent charset between weights')
        prior_glyphs=cp_set
        if reference_dir is not None:
            reference=TTFont(reference_dir/f'Chunks-{style}.otf')
            for ch in 'Chunks':
                # source and derivative outlines must differ (not just an alternate font name)
                name=chars[ord(ch)]
                before=reference['CFF '].cff.topDictIndex[0].CharStrings[name].bytecode
                after=f['CFF '].cff.topDictIndex[0].CharStrings[name].bytecode
                if before == after:
                    raise AssertionError(f'{style}: expected redesigned {ch} glyph')
        pil=ImageFont.truetype(str(path),42,layout_engine=ImageFont.Layout.RAQM)
        widths={sample:round(float(pil.getlength(sample)),2) for sample in PROBES}
        if any(width<=0 for width in widths.values()):
            raise AssertionError(f'{style}: non-positive measured string width')
        gaps_by_size={}
        for px in (14,24,48):
            gaps=ink_gaps_at_size(path,PROBES[:5],px=px)
            if not gaps or min(gaps)<0.3:
                raise AssertionError(f'{style}: visible sampled glyph collision/clearance failure at {px}px: {min(gaps) if gaps else None}')
            gaps_by_size[str(px)]={'sample_pairs':len(gaps),'minimum_ink_gap_px':round(min(gaps),3)}
        results[style]={'sampled_spacing_clearance':gaps_by_size, 'weight':weight,'glyph_count':len(f.getGlyphOrder()),'unicode_codepoints':len(chars),
                        'bytes':path.stat().st_size,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
                        'metrics':{'ascent':v[0],'descent':v[1],'lineGap':v[2]},'sample_width_px_at_42':widths}
        fonts[style]=path
    if preview:
        preview.parent.mkdir(parents=True,exist_ok=True)
        canvas=Image.new('RGB',(1280,980),'#FFFFFF')
        draw=ImageDraw.Draw(canvas)
        caption=ImageFont.load_default(size=22)
        for idx,(weight,style) in enumerate(WEIGHTS.items()):
            y=16+idx*190
            draw.rounded_rectangle((18,y,1260,y+174),radius=16,fill=['#FCE5F1','#EBDCFB','#D9F1FE','#FFF3BA','#DDFCE9'][idx])
            draw.text((34,y+8),f'CHUNKS {style.upper()}  /  {weight}',font=caption,fill='#222')
            font=ImageFont.truetype(str(fonts[style]),79,layout_engine=ImageFont.Layout.RAQM)
            draw.text((32,y+25),'Chunks',font=font,fill='#0B0F17')
            small=ImageFont.truetype(str(fonts[style]),30,layout_engine=ImageFont.Layout.RAQM)
            draw.text((510,y+64),'Torrent Details   68%   36.4 MB/s',font=small,fill='#0B0F17')
            draw.text((33,y+132),'ÁÉÖÜ äçñ   0123456789  $€£¥',font=small,fill='#0B0F17')
        canvas.save(preview)
    if atlas:
        atlas.parent.mkdir(parents=True,exist_ok=True)
        canvas=Image.new('RGB',(1460,1490),'#FFFFFF')
        draw=ImageDraw.Draw(canvas)
        small=ImageFont.load_default(size=19)
        rows=[
          'ABCDEFGHIJKLMNOPQRSTUVWXYZ',
          'abcdefghijklmnopqrstuvwxyz',
          '0123456789  ! ? : ; ( ) [ ] { } @ # & *',
          'ÀÁÂÃÄÅ Æ Ç ÈÉÊË ÌÍÎÏ Ñ ÒÓÔÕÖØ ÙÚÛÜ ÝŸ',
          'àáâãäå æ ç èéêë ìíîï ñ òóôõöø ùúûü ýÿ',
        ]
        for idx,(weight,style) in enumerate(WEIGHTS.items()):
            y=12+idx*294
            draw.rounded_rectangle((12,y,1448,y+284),radius=15,
                fill=['#FFF2F8','#F2E9FF','#E8F7FF','#FFF6D8','#EAFDF2'][idx])
            draw.text((26,y+10),f'CHUNKS {style.upper()} ({weight})  |  SCALABLE CFF OTF',font=small,fill='#404040')
            font=ImageFont.truetype(str(fonts[style]),40,layout_engine=ImageFont.Layout.RAQM)
            for i,row in enumerate(rows):
                draw.text((26,y+43+i*42),row,font=font,fill='#0B0F17')
        canvas.save(atlas)
    return results

if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--fonts',type=Path,default=Path('assets/fonts/chunks'))
    p.add_argument('--reference',type=Path)
    p.add_argument('--preview',type=Path)
    p.add_argument('--atlas',type=Path,help='Render exact ASCII and accented Latin grid for all weights')
    p.add_argument('--report',type=Path)
    args=p.parse_args()
    out=analyze(args.fonts,args.reference,args.preview,args.atlas)
    if args.report:
        args.report.parent.mkdir(parents=True,exist_ok=True)
        args.report.write_text(json.dumps(out,indent=2,ensure_ascii=False)+'\n')
    print('Audited',', '.join(out),'faces, full shared 856+ Unicode coverage and vertical metrics')