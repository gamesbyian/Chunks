#!/usr/bin/env python3
"""Source-only preflight: independent O/o/0 and C/c cubic-weight candidates.

Does not require rebuilt OTFs or dispatch remote CI.
"""
import sys
import unittest
from pathlib import Path
from xml.etree import ElementTree as ET
from fontTools.pens.recordingPen import RecordingPen
from fontTools.svgLib.path import parse_path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from build_reference_panel_weight_studies import source_polygons,topology
SOURCE=ROOT/'assets/fonts/source/reference-alphabet'
WEIGHTS=(400,500,600,700)

class BowlAndCrescentSourceTest(unittest.TestCase):
    def test_independently_drawn_five_glyphs_four_weights(self):
        for ch in ('O','o','0','C','c'):
            original=source_polygons(SOURCE/f'U+{ord(ch):04X}.svg',3)
            self.assertEqual(topology(original),(1,1) if ch in 'Oo0' else (1,0))
            previous=0
            for weight in WEIGHTS:
                with self.subTest(glyph=ch,weight=weight):
                    path=SOURCE/'weight-overrides'/str(weight)/f'U+{ord(ch):04X}.svg'
                    self.assertTrue(path.is_file())
                    candidate=source_polygons(path,3)
                    self.assertTrue(candidate.is_valid)
                    self.assertEqual(topology(candidate),topology(original))
                    self.assertGreater(candidate.area,previous)
                    previous=candidate.area
                    self.assertTrue(.30<=candidate.area/original.area<=1.20)
                    self.assertTrue(all(abs(a-b)<=3.0 for a,b in
                                        zip(candidate.bounds,original.bounds)))
                    root=ET.fromstring(path.read_text())
                    outlines=[x for x in root.iter() if x.tag.endswith('path')]
                    self.assertEqual(len(outlines),1)
                    pen=RecordingPen()
                    parse_path(outlines[0].attrib['d'],pen)
                    self.assertEqual(sum(op=='moveTo' for op,_ in pen.value),
                                     2 if ch in 'Oo0' else 1)
                    self.assertGreaterEqual(sum(op=='curveTo' for op,_ in pen.value),4)
                    self.assertTrue(all(op!='lineTo' for op,_ in pen.value))
            self.assertLess(previous,original.area,
                            f'{ch} Bold lab candidate should not exceed source 800 ink')
if __name__=='__main__':unittest.main()
