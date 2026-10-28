#!/usr/bin/env python3
"""Deterministic source-provenance and shape gates for 28 punct SVGs."""
from __future__ import annotations
import hashlib,json,sys,tempfile,unittest,xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from trace_reference_punctuation import generate,SEQUENCE,DUPLICATES,EXPECTED
SOURCE=ROOT/'docs/design/reference-images/03-typography-and-wordmark.png'
OUT=ROOT/'assets/fonts/source/reference-punctuation'

class ReferencePunctuationTests(unittest.TestCase):
    def test_reconstruction_is_exact_and_deterministic(self):
        stored=json.loads((OUT/'manifest.json').read_text())
        self.assertEqual(stored['glyph_count'],28)
        self.assertEqual(stored['source_ink_segments'],29)
        self.assertEqual(stored['source_sha256'],hashlib.sha256(SOURCE.read_bytes()).hexdigest())
        self.assertEqual(set(stored['glyphs']),{char for char,_ in SEQUENCE}|set(DUPLICATES))
        with tempfile.TemporaryDirectory() as d:
            fresh=Path(d)
            regenerate=generate(SOURCE,fresh)
            self.assertEqual(regenerate,stored)
            for c in stored['glyphs']:
                name=f'U+{ord(c):04X}.svg'
                self.assertEqual((OUT/name).read_bytes(),(fresh/name).read_bytes(),name)

    def test_svg_shapes_and_quote_pair_are_independent(self):
        manifest=json.loads((OUT/'manifest.json').read_text())['glyphs']
        for char,item in manifest.items():
            with self.subTest(glyph=char):
                p=OUT/f'U+{ord(char):04X}.svg'
                self.assertTrue(p.is_file(),p)
                svg=ET.fromstring(p.read_text())
                self.assertTrue(svg.tag.endswith('svg'))
                path=[x for x in svg.iter() if x.tag.endswith('path')]
                self.assertEqual(len(path),1)
                self.assertTrue(path[0].attrib['d'].startswith('M'))
                self.assertGreater(item['width'],4)
                self.assertGreater(item['height'],4)
                self.assertGreater(item['contours'],0)
                self.assertTrue(all(0<=k<EXPECTED for k in item['runs']))
        self.assertEqual(manifest['"']['derived_variant_of'],'“')
        self.assertEqual((OUT/'U+0022.svg').read_text().split('<g ',1)[1],
                         (OUT/'U+201C.svg').read_text().split('<g ',1)[1])
        self.assertNotEqual((OUT/'U+201C.svg').read_bytes(),(OUT/'U+201D.svg').read_bytes())

if __name__=='__main__':unittest.main()
