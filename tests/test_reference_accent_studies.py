#!/usr/bin/env python3
"""Contract tests for non-shipping reference-font accent graft experiments."""
from __future__ import annotations
import hashlib,json,sys,tempfile,unittest
from pathlib import Path
from fontTools.pens.recordingPen import RecordingPen
from fontTools.ttLib import TTFont
from PIL import ImageFont
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from build_reference_accent_studies import WEIGHTS,STEMS,create_variant
SRC=ROOT/'assets/fonts/chunks'
STUDIES=ROOT/'assets/fonts/experimental/weight-studies'
EXTRA=ROOT/'assets/fonts/experimental/ChunksReferencePanel-ExtraBold-Optical.otf'
OUT=ROOT/'assets/fonts/experimental/accent-studies'


def native_outline(font,ch):
    pen=RecordingPen(); font.getGlyphSet()[font.getBestCmap()[ord(ch)]].draw(pen)
    return pen.value


class AccentStudies(unittest.TestCase):
    def test_preserves_unicode_and_nonlatin_shaping(self):
        for weight,style in WEIGHTS.items():
            with self.subTest(style=style):
                accent=OUT/f'ChunksReferencePanel-{style}-AccentStudy.otf'
                ref=EXTRA if weight==800 else STUDIES/f'ChunksReferencePanel-{style}-Study.otf'
                self.assertTrue(accent.is_file())
                original=TTFont(ref); new=TTFont(accent)
                self.assertEqual(original.getBestCmap(),new.getBestCmap())
                self.assertEqual(len(new.getBestCmap()),856)
                self.assertEqual(new.sfntVersion,'OTTO')
                self.assertTrue({'GPOS','GSUB','GDEF','CFF '}.issubset(new.keys()))
                self.assertEqual(new['OS/2'].usWeightClass,weight)
                for ch in 'Chunks 1234567890 ßðøıĳ':
                    if ch==' ' or ord(ch) not in new.getBestCmap():continue
                    self.assertEqual(native_outline(original,ch),native_outline(new,ch), (style,ch))
                for text in ('हिन्दी','देवनागरी','Downloading 68%'):
                    a=ImageFont.truetype(str(accent),24,layout_engine=ImageFont.Layout.RAQM)
                    b=ImageFont.truetype(str(ref),24,layout_engine=ImageFont.Layout.RAQM)
                    self.assertAlmostEqual(a.getlength(text),b.getlength(text),delta=.01)

    def test_source_grafted_mark_invariants(self):
        report=json.loads((OUT/'reference-accent-study-metrics.json').read_text())
        self.assertEqual(set(report['weights']),set(WEIGHTS.values()))
        for style,info in report['weights'].items():
            with self.subTest(style=style):
                self.assertGreaterEqual(info['built'],220)
                self.assertLessEqual(info['built'],260)
                self.assertEqual(len(info['glyphs']),info['built'])
                self.assertEqual(len({x['unicode'] for x in info['glyphs']}),info['built'])
                path=OUT/info['font']
                self.assertEqual(info['output_sha256'],hashlib.sha256(path.read_bytes()).hexdigest())
                self.assertTrue({'Á','Ä','Ö','Ñ','é','ü','č','å'}.issubset({g['glyph'] for g in info['glyphs']}))
                self.assertTrue(all(g['base'] in STEMS for g in info['glyphs']))
                self.assertTrue(all(g['direction'] in ('above','below') for g in info['glyphs']))
                for g in info['glyphs']:
                    self.assertGreaterEqual(g['bounds'][1],-480)
                    self.assertLessEqual(g['bounds'][3],1040)
                face=TTFont(path)
                for g in info['glyphs']:
                    base=face.getBestCmap()[ord(g['base'])]
                    mark=face.getBestCmap()[ord(g['glyph'])]
                    self.assertEqual(face['hmtx'][base][0],face['hmtx'][mark][0])
                for ch in 'íĵçą':
                    if ch in info['skipped_chars']:
                        self.assertNotIn(ch,{g['glyph'] for g in info['glyphs']})

    def test_one_build_is_deterministic(self):
        with tempfile.TemporaryDirectory() as d:
            ref=STUDIES/'ChunksReferencePanel-Bold-Study.otf'
            old=SRC/'Chunks-Bold.otf'
            a=Path(d)/'first.otf';b=Path(d)/'second.otf'
            info1=create_variant(old,ref,a)
            info2=create_variant(old,ref,b)
            self.assertEqual(a.read_bytes(),b.read_bytes())
            self.assertEqual(info1['built'],info2['built'])
            self.assertEqual(info1['glyphs'],info2['glyphs'])

if __name__=='__main__':unittest.main()