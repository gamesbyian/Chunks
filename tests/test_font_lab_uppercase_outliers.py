#!/usr/bin/env python3
"""Source-only shape check for custom uppercase A/G/J/K/M/N/R/S masters."""
import sys,unittest
from pathlib import Path
from xml.etree import ElementTree as ET
from fontTools.pens.recordingPen import RecordingPen
from fontTools.svgLib.path import parse_path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from build_reference_panel_weight_studies import source_polygons,topology
SRC=ROOT/'assets/fonts/source/reference-alphabet'
class UppercaseNative(unittest.TestCase):
    def test_independent_uppercase_families_have_native_contours(self):
        for ch in 'AGJKMNRS':
            original=source_polygons(SRC/f'U+{ord(ch):04X}.svg',3)
            self.assertEqual(topology(original),(1,1) if ch in 'AR' else (1,0))
            areas=[]
            for w in (400,500,600,700):
                with self.subTest(ch=ch,w=w):
                    f=SRC/'weight-overrides'/str(w)/f'U+{ord(ch):04X}.svg'
                    g=source_polygons(f,3)
                    self.assertTrue(g.is_valid)
                    self.assertEqual(topology(g),topology(original))
                    self.assertTrue(all(abs(a-b)<=3 for a,b in zip(g.bounds,original.bounds)))
                    self.assertTrue(.30<=g.area/original.area<=1.20)
                    areas.append(g.area)
                    d=next(x.attrib['d'] for x in ET.fromstring(f.read_text()).iter() if x.tag.endswith('path'))
                    pen=RecordingPen();parse_path(d,pen)
                    self.assertEqual(sum(op=='moveTo' for op,_ in pen.value),2 if ch in 'AR' else 1)
                    self.assertGreaterEqual(sum(op in ('qCurveTo','curveTo') for op,_ in pen.value),5)
            self.assertEqual(areas,sorted(areas))
if __name__=='__main__':unittest.main()
