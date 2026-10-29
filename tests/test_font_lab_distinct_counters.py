#!/usr/bin/env python3
"""Isolated source preflight for the distinctive counters: B/8/6/9/Q/a."""
import sys,unittest
from pathlib import Path
from xml.etree import ElementTree as ET
from fontTools.pens.recordingPen import RecordingPen
from fontTools.svgLib.path import parse_path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from build_reference_panel_weight_studies import source_polygons,topology
DIR=ROOT/'assets/fonts/source/reference-alphabet'
class LabCounterProof(unittest.TestCase):
    def test_all_native_counters_have_reference_topology_and_safe_weights(self):
        for char in 'B869Qa':
            original=source_polygons(DIR/f'U+{ord(char):04X}.svg',3)
            self.assertEqual(topology(original),(1,2) if char in 'B8' else (1,1))
            areas=[]
            for weight in (400,500,600,700):
                with self.subTest(char=char,weight=weight):
                    source=DIR/'weight-overrides'/str(weight)/f'U+{ord(char):04X}.svg'
                    shape=source_polygons(source,3)
                    self.assertTrue(shape.is_valid)
                    self.assertEqual(topology(shape),topology(original))
                    self.assertTrue(all(abs(x-y)<=3 for x,y in zip(shape.bounds,original.bounds)))
                    self.assertTrue(.30<=shape.area/original.area<=1.20)
                    areas.append(shape.area)
                    root=ET.fromstring(source.read_text())
                    p=next(x.attrib['d'] for x in root.iter() if x.tag.endswith('path'))
                    rec=RecordingPen();parse_path(p,rec)
                    self.assertEqual(sum(c=='moveTo' for c,_ in rec.value),3 if char in 'B8' else 2)
                    self.assertGreaterEqual(sum(c=='curveTo' for c,_ in rec.value),6)
            self.assertEqual(areas,sorted(areas))
if __name__=='__main__':unittest.main()
