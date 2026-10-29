#!/usr/bin/env python3
"""Isolated Arabic numeral 1,2,3,4,5,7 weight masters."""
import sys, unittest
from pathlib import Path
from xml.etree import ElementTree as ET
from fontTools.pens.recordingPen import RecordingPen
from fontTools.svgLib.path import parse_path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from build_reference_panel_weight_studies import source_polygons,topology
SRC=ROOT/'assets/fonts/source/reference-alphabet'
class DigitShape(unittest.TestCase):
    def test_six_digits_all_four_weights(self):
        for ch in '123457':
            reference=source_polygons(SRC/f'U+{ord(ch):04X}.svg',3)
            self.assertEqual(topology(reference),(1,1) if ch=='4' else (1,0))
            areas=[]
            for w in (400,500,600,700):
                with self.subTest(char=ch,weight=w):
                    f=SRC/'weight-overrides'/str(w)/f'U+{ord(ch):04X}.svg'
                    g=source_polygons(f,3)
                    self.assertTrue(g.is_valid)
                    self.assertEqual(topology(g),topology(reference))
                    self.assertTrue(all(abs(a-b)<=3 for a,b in zip(g.bounds,reference.bounds)))
                    self.assertTrue(.30<=g.area/reference.area<=1.2)
                    areas.append(g.area)
                    d=next(x.attrib['d'] for x in ET.fromstring(f.read_text()).iter() if x.tag.endswith('path'))
                    pen=RecordingPen();parse_path(d,pen)
                    self.assertEqual(sum(op=='moveTo' for op,_ in pen.value),2 if ch=='4' else 1)
                    self.assertGreaterEqual(sum(op in ('curveTo','qCurveTo') for op,_ in pen.value),6)
            self.assertEqual(areas,sorted(areas))
if __name__=='__main__': unittest.main()
