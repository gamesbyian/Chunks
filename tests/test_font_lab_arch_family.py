#!/usr/bin/env python3
"""Source-only alpha arch/shoulder/hook weight study."""
import sys,unittest
from pathlib import Path
from xml.etree import ElementTree as ET
from fontTools.svgLib.path import parse_path
from fontTools.pens.recordingPen import RecordingPen
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from build_reference_panel_weight_studies import source_polygons,topology
SRC=ROOT/'assets/fonts/source/reference-alphabet'
class CurvedLowercase(unittest.TestCase):
    def test_six_curved_lowercase_per_weight(self):
        for ch in 'nhmrtf':
            original=source_polygons(SRC/f'U+{ord(ch):04X}.svg',3)
            self.assertEqual(topology(original),(1,0))
            area=[]
            for w in (400,500,600,700):
                with self.subTest(ch=ch,weight=w):
                    f=SRC/'weight-overrides'/str(w)/f'U+{ord(ch):04X}.svg'
                    new=source_polygons(f,3)
                    self.assertTrue(new.is_valid)
                    self.assertEqual(topology(new),(1,0))
                    self.assertTrue(all(abs(a-b)<=3 for a,b in zip(new.bounds,original.bounds)))
                    self.assertTrue(.3<=new.area/original.area<=1.2)
                    area.append(new.area)
                    d=next(x.attrib['d'] for x in ET.fromstring(f.read_text()).iter() if x.tag.endswith('path'))
                    pen=RecordingPen();parse_path(d,pen)
                    self.assertEqual(sum(x=='moveTo' for x,_ in pen.value),1)
                    self.assertGreaterEqual(sum(x in ('qCurveTo','curveTo') for x,_ in pen.value),6)
            self.assertEqual(area,sorted(area))
if __name__=='__main__':unittest.main()
