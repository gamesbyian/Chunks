#!/usr/bin/env python3
"""Acceptance gates for optional Chunks dotless-i/j accented CFF candidates."""
from __future__ import annotations
import hashlib,json,sys,tempfile,unittest
from pathlib import Path
from fontTools.ttLib import TTFont
from fontTools.pens.recordingPen import RecordingPen
from PIL import ImageFont

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from build_reference_dotless_accents import WEIGHTS,build_dotless
from build_reference_accent_studies import glyph_record,contours
from spacing_optimizer import ink_gaps_at_size

SRC=ROOT/'assets/fonts/experimental/accent-studies'
OUT=ROOT/'assets/fonts/experimental/dotless-kerned'
BASE=ROOT/'assets/fonts/chunks'
EXPECTED=set('ìíîïĩīĭǐȉȋỉĵ')
EXCLUDED='įị'
CONTEXTS=('{}','A{}','{}V','T{}r','{}n','R{}s','{}r','W{}')

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()

class DotlessAccentTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.m=json.loads((OUT/'reference-dotless-kerned-metrics.json').read_text())

    def test_real_five_weight_cff_and_provenance(self):
        self.assertEqual(set(self.m['weights']),set(WEIGHTS.values()))
        self.assertEqual(self.m['total_dotless_added'],60)
        for weight,style in WEIGHTS.items():
            with self.subTest(style=style):
                src=SRC/f'ChunksReferencePanel-{style}-AccentStudy.otf'
                path=OUT/f'ChunksReferencePanel-{style}-DotlessKerned.otf'
                f=TTFont(path);original=TTFont(src)
                self.assertEqual(f.sfntVersion,'OTTO')
                self.assertIn('CFF ',f);self.assertNotIn('glyf',f)
                self.assertTrue({'GPOS','GDEF','GSUB'}.issubset(f.keys()))
                self.assertEqual(f.getBestCmap(),original.getBestCmap())
                self.assertEqual(len(f.getBestCmap()),856)
                self.assertEqual(f['OS/2'].usWeightClass,weight)
                self.assertEqual(f['name'].getDebugName(1),'Chunks Reference Panel Dotless')
                self.assertEqual(f['name'].getDebugName(6),f'ChunksReferencePanelDotless-{style}')
                row=self.m['weights'][style]
                self.assertEqual(row['file'],path.name)
                self.assertEqual(row['dotless_added'],12)
                self.assertEqual(set(g['glyph'] for g in row['dotless_glyphs']),EXPECTED)
                self.assertEqual(row['unicode_count'],856)
                self.assertGreater(row['adjusted_pairs'],90000)
                self.assertEqual(sha(path),row['output_sha256'])
                self.assertEqual(sha(src),row['base_font_sha256'])
                cmap=f.getBestCmap()
                for g in row['dotless_glyphs']:
                    self.assertEqual(f['hmtx'][cmap[ord(g['glyph'])]][0],f['hmtx'][cmap[ord(g['base'])]][0])
                    self.assertGreaterEqual(g['bbox'][3],650)
                for ch in 'įị':
                    name=cmap.get(ord(ch))
                    if name:
                        a=RecordingPen();b=RecordingPen()
                        f.getGlyphSet()[name].draw(a);original.getGlyphSet()[name].draw(b)
                        self.assertEqual(a.value,b.value,(style,ch))

    def test_ref_stems_replace_original_latin_i_j_accent_shapes(self):
        for style in WEIGHTS.values():
            final=TTFont(OUT/f'ChunksReferencePanel-{style}-DotlessKerned.otf')
            raw=TTFont(SRC/f'ChunksReferencePanel-{style}-AccentStudy.otf')
            cmap=final.getBestCmap()
            for ch in EXPECTED:
                a=RecordingPen();b=RecordingPen()
                final.getGlyphSet()[cmap[ord(ch)]].draw(a)
                raw.getGlyphSet()[cmap[ord(ch)]].draw(b)
                self.assertNotEqual(a.value,b.value,(style,ch))
            for ch in 'Chunk 0123hजू':
                if ord(ch) not in cmap:continue
                a=RecordingPen();b=RecordingPen()
                final.getGlyphSet()[cmap[ord(ch)]].draw(a)
                raw.getGlyphSet()[cmap[ord(ch)]].draw(b)
                self.assertEqual(a.value,b.value,(style,ch))

    def test_full_contextual_kerning_inheritance(self):
        for style in WEIGHTS.values():
            f=ImageFont.truetype(str(OUT/f'ChunksReferencePanel-{style}-DotlessKerned.otf'),48,layout_engine=ImageFont.Layout.RAQM)
            src=ImageFont.truetype(str(SRC/f'ChunksReferencePanel-{style}-AccentStudy.otf'),48,layout_engine=ImageFont.Layout.RAQM)
            for ch in EXPECTED:
                bare='j' if ch=='ĵ' else 'i'
                self.assertAlmostEqual(f.getlength(ch),f.getlength(bare),delta=.02,msg=str((style,ch)))
                for ctx in CONTEXTS:
                    self.assertLess(abs(f.getlength(ctx.format(ch))-f.getlength(ctx.format(bare))),1.0,(style,ch,ctx))
            for sample in ('Chunks','12345 68%','हिन्दी','देवनागरी','The.Wanderer.2024.mkv'):
                self.assertEqual(f.getlength(sample),src.getlength(sample),(style,sample))

    def test_real_size_collision_sample(self):
        samples=['ìíîïĩīĭǐȉȋỉĵ','Írish Ĵourney','Chüňks! Downloading 68%','Résumé télévision']
        for style in WEIGHTS.values():
            path=OUT/f'ChunksReferencePanel-{style}-DotlessKerned.otf'
            for size in (14,24,48,82):
                native=min(ink_gaps_at_size(path,samples,px=size))
                self.assertGreater(native,0.25,(style,size,native))

    def test_raw_dotless_step_deterministic(self):
        with tempfile.TemporaryDirectory() as tmp:
            source=SRC/'ChunksReferencePanel-Regular-AccentStudy.otf'
            base=BASE/'Chunks-Regular.otf'
            first=Path(tmp)/'a.otf';second=Path(tmp)/'b.otf'
            shapes_a=build_dotless(base,source,first)
            shapes_b=build_dotless(base,source,second)
            self.assertEqual(shapes_a,shapes_b)
            self.assertEqual(first.read_bytes(),second.read_bytes())
            self.assertEqual(len(shapes_a),12)

if __name__=='__main__':unittest.main()
