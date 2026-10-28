"""Source-based and output-based font build tests; no raster reference OCR."""
from __future__ import annotations
import sys
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from brand_glyphs import STROKE,REFERENCE_INSET,filled_reference_outline,apply_brand_glyphs,source_paths,stroke_skeleton
from audit_chunks_fonts import analyze
from fontTools.ttLib import TTFont

class BrandMastersTest(unittest.TestCase):
    def setUp(self):
        self.master=ROOT/'assets/fonts/source/reference-traced-wordmark.svg'
        self.stroke_master=ROOT/'assets/fonts/source/wordmark-stroke-masters.svg'

    def test_all_six_letters_have_distinct_paths(self):
        paths=source_paths(self.master)
        self.assertEqual(set(paths),set('Chunks'))
        self.assertEqual(len(set(paths.values())),6)

    def test_reference_traced_filled_shapes_remain_valid_when_thinned(self):
        for ch,d in source_paths(self.master).items():
            shape=filled_reference_outline(d)
            self.assertGreater(shape.area,100,(ch,shape.area))
            previous=0
            for weight in STROKE:
                thinner=shape.buffer(-REFERENCE_INSET[weight]) if REFERENCE_INSET[weight] else shape
                self.assertFalse(thinner.is_empty,(ch,weight))
                self.assertGreater(thinner.area,previous,(ch,weight))
                previous=thinner.area

    def test_strokes_are_valid_and_thicken_by_weight(self):
        for ch,d in source_paths(self.stroke_master).items():
            previous=0
            for weight in STROKE:
                shape=stroke_skeleton(d,STROKE[weight]*(0.7 if ch=='s' else 1.0))
                self.assertTrue(shape.is_valid, (ch,weight))
                self.assertGreater(shape.area,previous,(ch,weight))
                previous=shape.area

    def test_svg_to_cff_is_deterministic(self):
        p=ROOT/'assets/fonts/chunks/Chunks-Bold.otf'
        f=TTFont(p);g=TTFont(p)
        a=apply_brand_glyphs(f,700,self.master)
        b=apply_brand_glyphs(g,700,self.master)
        self.assertEqual(a,b)
        for ch in 'Chunks':
            name=f.getBestCmap()[ord(ch)]
            cs1=f['CFF '].cff.topDictIndex[0].CharStrings[name]
            cs2=g['CFF '].cff.topDictIndex[0].CharStrings[name]
            cs1.compile();cs2.compile()
            self.assertEqual(cs1.bytecode,cs2.bytecode)

class OutputTest(unittest.TestCase):
    def test_all_five_otfs_pass_audit(self):
        measurements=analyze(ROOT/'assets/fonts/chunks')
        self.assertEqual(set(measurements),{'Regular','Medium','Semibold','Bold','ExtraBold'})
        self.assertTrue(all(v['unicode_codepoints']>=850 for v in measurements.values()))

    def test_ink_density_monotonic_for_wordmark(self):
        from PIL import ImageFont
        areas=[]
        for style in ('Regular','Medium','Semibold','Bold','ExtraBold'):
            p=ROOT/'assets/fonts/chunks'/f'Chunks-{style}.otf'
            m=ImageFont.truetype(str(p),82).getmask('Chunks',mode='L')
            areas.append(sum(m)/255)
        self.assertTrue(all(b>a for a,b in zip(areas,areas[1:])),areas)

if __name__=='__main__':unittest.main()