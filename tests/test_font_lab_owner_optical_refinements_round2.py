#!/usr/bin/env python3
"""Owner's second optical pass: regression proofs for j/G/g/9, 400–700.

Source-based checks are deliberately small and cheap. The frozen acceptance
workflow separately tests actual compiled CFF rasterization and family integrity.
"""
from __future__ import annotations
import sys, unittest
from pathlib import Path
from xml.etree import ElementTree as ET
from fontTools.pens.recordingPen import RecordingPen
from fontTools.svgLib.path import parse_path

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from build_reference_panel_weight_studies import source_polygons, topology

SRC=ROOT/'assets/fonts/source/reference-alphabet'
WEIGHTS=(400,500,600,700)


def glyph(ch,weight):
    path=SRC/'weight-overrides'/str(weight)/f'U+{ord(ch):04X}.svg'
    root=ET.fromstring(path.read_text())
    outline=next(node.attrib['d'] for node in root.iter()
        if node.tag.endswith('path'))
    pen=RecordingPen()
    parse_path(outline,pen)
    return path,pen.value


class OwnerOpticalSecondPass(unittest.TestCase):
    def test_j_top_left_is_straight_with_no_triangular_spur(self):
        for weight in WEIGHTS:
            with self.subTest(weight=weight):
                _,ops=glyph('j',weight)
                self.assertEqual(ops[0][0],'moveTo')
                x,y=ops[0][1][0]
                end=next(i for i,(op,_) in enumerate(ops) if op=='closePath')
                self.assertEqual(ops[end-1][0],'lineTo')
                self.assertAlmostEqual(ops[end-1][1][0][0],x,places=2)
                self.assertAlmostEqual(ops[end-1][1][0][1],y,places=2)
                # First contour now has a single unbroken vertical left
                # side on its shaft; no thin pointed wedge to the left.
                shaft_x=[args[0][0] for op,args in ops[:end]
                         if op=='lineTo' and abs(args[0][1]-(y+4))<0.8]
                self.assertTrue(any(abs(px-x)<0.05 for px in shaft_x))
                self.assertEqual(sum(op=='moveTo' for op,args in ops),2)

    def test_G_smooth_shoulder_and_wider_upper_aperture(self):
        for weight in WEIGHTS:
            with self.subTest(weight=weight):
                _,ops=glyph('G',weight)
                self.assertEqual(ops[0][0],'moveTo')
                self.assertEqual(ops[1][0],'curveTo')
                # Outer top starts tangent-horizontal (smooth shoulder).
                self.assertAlmostEqual(ops[1][1][0][1],ops[0][1][0][1],places=2)
                self.assertLess(ops[1][1][0][0],ops[0][1][0][0])
                # Upper terminal tip is at least 3 units above the bar.
                # First line whose endpoint is G right-bar start at x = M.x.
                mx=ops[0][1][0][0]
                crossbar_y=next(args[-1][1] for op,args in ops
                    if op=='qCurveTo' and abs(args[-1][0]-mx)<0.05
                    and 14<args[-1][1]<19)
                upper_arcs=[args for op,args in ops if op=='qCurveTo'][-4:-1]
                self.assertEqual(len(upper_arcs),3)
                upper_tip_y=upper_arcs[1][-1][1]
                self.assertGreaterEqual(crossbar_y-upper_tip_y,3.0)

    def test_g_curl_gap_opened_while_bowl_counter_retained(self):
        for weight in WEIGHTS:
            with self.subTest(weight=weight):
                _,ops=glyph('g',weight)
                self.assertEqual(sum(op=='moveTo' for op,args in ops),2)
                second=next(i for i,(op,args) in enumerate(ops[1:],1)
                    if op=='moveTo')
                outer_curves=[args for op,args in ops[:second] if op=='curveTo']
                self.assertGreaterEqual(len(outer_curves),7)
                # Three exterior cubics from the second tail rise back to
                # the left side of the bowl; the first is lifted upwards.
                lower_bowl=outer_curves[-3]
                self.assertLess(lower_bowl[0][1],25.0)
                self.assertLess(lower_bowl[1][1],24.0)

    def test_nine_correct_orientation_counter_and_weight_order(self):
        for weight in WEIGHTS:
            with self.subTest(weight=weight):
                _,ops=glyph('9',weight)
                self.assertEqual(sum(op=='moveTo' for op,args in ops),2)
                # Old mirrored 9 starts at x~22 on its bottom terminal;
                # true orientation ends left at x~7, not right.
                self.assertLess(ops[0][1][0][0],11.0)
                self.assertEqual(ops[0][1][0][1],34.55)
                moves=[args[0] for op,args in ops if op=='moveTo']
                self.assertGreater(moves[1][0],13.0)

    def test_all_edited_glyphs_valid_and_ink_increasing(self):
        for ch in ('j','G','g','9'):
            reference=source_polygons(SRC/f'U+{ord(ch):04X}.svg',3)
            masses=[]
            for weight in WEIGHTS:
                with self.subTest(ch=ch,weight=weight):
                    path,_=glyph(ch,weight)
                    shape=source_polygons(path,3)
                    self.assertTrue(shape.is_valid)
                    self.assertEqual(topology(shape),topology(reference))
                    self.assertLess(shape.area,reference.area)
                    masses.append(shape.area)
            self.assertEqual(masses,sorted(masses))


if __name__=='__main__':
    unittest.main()
