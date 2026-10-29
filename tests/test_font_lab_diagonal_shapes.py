#!/usr/bin/env python3
"""Local-only W/w X/x Y/y Z/z native stroke construction proof."""
import sys,unittest
from pathlib import Path
from fontTools.pens.recordingPen import RecordingPen
from fontTools.svgLib.path import parse_path
from xml.etree import ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from build_reference_panel_weight_studies import source_polygons,topology
SRC=ROOT/'assets/fonts/source/reference-alphabet'
class JoinedDiagonalFamily(unittest.TestCase):
    def test_edited_stroke_shape_progression(self):
        for ch in 'WwXxYyZz':
            before=source_polygons(SRC/f'U+{ord(ch):04X}.svg',3)
            self.assertEqual(topology(before),(1,0))
            areas=[]
            for w in (400,500,600,700):
                with self.subTest(ch=ch,weight=w):
                    f=SRC/'weight-overrides'/str(w)/f'U+{ord(ch):04X}.svg'
                    after=source_polygons(f,3)
                    self.assertTrue(after.is_valid)
                    self.assertEqual(topology(after),(1,0))
                    self.assertTrue(all(abs(a-b)<=3 for a,b in zip(after.bounds,before.bounds)))
                    self.assertTrue(.30<=after.area/before.area<=1.20)
                    areas.append(after.area)
                    d=next(p.attrib['d'] for p in ET.fromstring(f.read_text()).iter() if p.tag.endswith('path'))
                    pen=RecordingPen();parse_path(d,pen)
                    self.assertEqual(sum(op=='moveTo' for op,_ in pen.value),1)
                    self.assertGreaterEqual(sum(op=='qCurveTo' for op,_ in pen.value),5)
            self.assertEqual(areas,sorted(areas))
if __name__=='__main__':unittest.main()
