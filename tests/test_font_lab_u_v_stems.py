#!/usr/bin/env python3
"""Local source-only reference-family U/u/V/v contour and weight proof."""
import sys,unittest
from pathlib import Path
from xml.etree import ElementTree as ET
from fontTools.svgLib.path import parse_path
from fontTools.pens.recordingPen import RecordingPen
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from build_reference_panel_weight_studies import source_polygons,topology
FOLDER=ROOT/'assets/fonts/source/reference-alphabet'
class JoinedStem(unittest.TestCase):
    def test_twelve_source_origin_invariants_and_native_curves(self):
        for ch in 'UuVv':
            source=source_polygons(FOLDER/f'U+{ord(ch):04X}.svg',3)
            self.assertEqual(topology(source),(1,0))
            areas=[]
            for weight in (400,500,600,700):
                with self.subTest(char=ch,weight=weight):
                    f=FOLDER/'weight-overrides'/str(weight)/f'U+{ord(ch):04X}.svg'
                    shape=source_polygons(f,3)
                    self.assertTrue(shape.is_valid)
                    self.assertEqual(topology(shape),(1,0))
                    self.assertTrue(all(abs(x-y)<=3 for x,y in zip(shape.bounds,source.bounds)))
                    self.assertTrue(.30<=shape.area/source.area<=1.20)
                    areas.append(shape.area)
                    path=next(x.attrib['d'] for x in ET.fromstring(f.read_text()).iter() if x.tag.endswith('path'))
                    rec=RecordingPen();parse_path(path,rec)
                    self.assertEqual(sum(x=='moveTo' for x,_ in rec.value),1)
                    self.assertGreaterEqual(sum(x=='qCurveTo' for x,_ in rec.value),4)
            self.assertEqual(areas,sorted(areas))
if __name__=='__main__':unittest.main()
