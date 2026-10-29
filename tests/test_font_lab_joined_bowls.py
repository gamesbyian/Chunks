#!/usr/bin/env python3
"""Source-only validation of newly edited D P b d p q weight masters."""
import sys,unittest
from pathlib import Path
from xml.etree import ElementTree as ET
from fontTools.svgLib.path import parse_path
from fontTools.pens.recordingPen import RecordingPen
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from build_reference_panel_weight_studies import source_polygons,topology
FOLDER=ROOT/'assets/fonts/source/reference-alphabet'

class JoinedBowlWeights(unittest.TestCase):
    def test_six_edited_counter_and_stem_shapes_at_four_weights(self):
        for ch in "DPbdpq":
            original=source_polygons(FOLDER/f'U+{ord(ch):04X}.svg',3)
            self.assertEqual(topology(original),(1,1))
            previous=0
            for weight in (400,500,600,700):
                with self.subTest(letter=ch,weight=weight):
                    file=FOLDER/'weight-overrides'/str(weight)/f'U+{ord(ch):04X}.svg'
                    new=source_polygons(file,3)
                    self.assertTrue(new.is_valid)
                    self.assertEqual(topology(new),(1,1))
                    self.assertTrue(.30<=new.area/original.area<=1.2)
                    self.assertGreater(new.area,previous)
                    previous=new.area
                    self.assertTrue(all(abs(a-b)<=3 for a,b in zip(new.bounds,original.bounds)))
                    svg=ET.fromstring(file.read_text())
                    p=next(x for x in svg.iter() if x.tag.endswith('path')).attrib['d']
                    pen=RecordingPen();parse_path(p,pen)
                    self.assertEqual(sum(op=='moveTo' for op,_ in pen.value),2)
                    self.assertGreaterEqual(sum(op=='curveTo' for op,_ in pen.value),
                                            6 if ch in 'DP' else 7)
                    self.assertGreaterEqual(sum(op=='qCurveTo' for op,_ in pen.value),
                                            2 if ch == 'D' else 3)
if __name__=='__main__':unittest.main()
