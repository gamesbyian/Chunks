#!/usr/bin/env python3
"""Non-shipping audit: compare accented glyphs against their bases in the same font."""
from __future__ import annotations
import argparse, json, unicodedata as ud, hashlib
from pathlib import Path
from PIL import ImageFont

WEIGHTS=('Regular','Medium','Semibold','Bold','ExtraBold')
CONTEXTS=('{}','A{}','{}V','T{}r','{}n','R{}s','{}r','W{}')

def root_font(root, style):
    return root/'assets/fonts/experimental/accent-studies'/f'ChunksReferencePanel-{style}-AccentStudy.otf'

def analyze(root):
    results={'status':'NONSHIPPING pair-context diagnostic; no font mutation','fonts':{},'summary':{}}
    for style in WEIGHTS:
        path=root_font(root,style)
        font=ImageFont.truetype(str(path),48,layout_engine=ImageFont.Layout.RAQM)
        metrics=json.loads((root/'assets/fonts/experimental/accent-studies/reference-accent-study-metrics.json').read_text())
        chars=metrics['weights'][style]['glyphs']
        rows=[]
        for glyph in chars:
            ch=glyph['glyph'];base=glyph['base']
            if len(ch)!=1 or len(base)!=1:continue
            solo=font.getlength(ch)-font.getlength(base)
            deviations=[]
            for context in CONTEXTS:
                a=font.getlength(context.format(ch));b=font.getlength(context.format(base))
                deviations.append(round(a-b,4))
            rows.append({'glyph':ch,'base':base,'standalone_advance_delta_px':round(solo,4),
                         'max_context_delta_px':max(abs(v) for v in deviations),
                         'context_deltas_px':deviations})
        threshold=1.0
        irregular=sorted((r for r in rows if r['max_context_delta_px']>threshold),key=lambda r:r['max_context_delta_px'],reverse=True)
        results['fonts'][style]={'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
            'accented_glyphs':len(rows),'contexts':list(CONTEXTS),
            'context_outliers_above_1px':len(irregular),'top_outliers':irregular[:30],
            'worst_context_delta_px':max((r['max_context_delta_px'] for r in rows),default=0),
            'standalone_delta_max_px':max((abs(r['standalone_advance_delta_px']) for r in rows),default=0)}
    results['summary']={'font_count':len(results['fonts']),'all_sampled_accents':sum(v['accented_glyphs'] for v in results['fonts'].values()),
        'total_context_outliers_above_1px':sum(v['context_outliers_above_1px'] for v in results['fonts'].values())}
    return results

def main():
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,default=Path('.'));p.add_argument('--out',type=Path,default=Path('chunks-accent-context-audit.json'));a=p.parse_args()
    result=analyze(a.root);a.out.parent.mkdir(parents=True,exist_ok=True);a.out.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print('Audited',result['summary']['all_sampled_accents'],'accented glyphs and',len(CONTEXTS),'contexts per glyph')
if __name__=='__main__':main()