#!/usr/bin/env python3
"""Owner-requested optical amendments: centered j dots and smooth C/c inner bowls.

This test is source-only: no Actions, font build or production-resource writes.
The full frozen-family builder also checks real CFFs and rasterized specimens.
"""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

from fontTools.pens.recordingPen import RecordingPen
from fontTools.pens.boundsPen import BoundsPen
from fontTools.svgLib.path import parse_path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'tools'))
from build_reference_panel_weight_studies import source_polygons, topology

SRC = ROOT/'assets/fonts/source/reference-alphabet'
WEIGHTS = (400, 500, 600, 700)


def commands(ch: str, weight: int):
    path = SRC/'weight-overrides'/str(weight)/f'U+{ord(ch):04X}.svg'
    xml = ET.fromstring(path.read_text())
    d = next(element.attrib['d'] for element in xml.iter()
             if element.tag.endswith('path'))
    pen = RecordingPen()
    parse_path(d, pen)
    return pen.value


def near(a, b, tolerance=0.05):
    return abs(a - b) <= tolerance


class OwnersOpticalImprovements(unittest.TestCase):
    def test_lowercase_j_dot_is_centered_on_actual_vertical_shaft(self):
        prior_centres = []
        for weight in WEIGHTS:
            with self.subTest(weight=weight):
                ops = commands('j', weight)
                self.assertEqual(sum(op=='moveTo' for op,_ in ops), 2)
                pieces = []
                active = []
                for item in ops:
                    active.append(item)
                    if item[0] == 'closePath':
                        pieces.append(active)
                        active = []
                self.assertEqual(len(pieces), 2)
                shaft = pieces[0]
                dot = pieces[1]
                right_x = max(p[0] for op, args in shaft
                              for p in args if len(p)==2)
                # Last lower-case-j shaft line returns up its left-hand edge.
                left_x = [args[0][0] for op,args in shaft if op=='lineTo'][-1]
                center = (right_x + left_x) / 2
                bounds = BoundsPen(None)
                for op,args in dot:
                    getattr(bounds, op)(*args)
                self.assertIsNotNone(bounds.bounds)
                x0,_,x1,_ = bounds.bounds
                dot_center = (x0+x1)/2
                self.assertLess(abs(center-dot_center), 0.6)
                original_dot_y = min(p[1] for op,args in dot for p in args if len(p)==2)
                self.assertGreaterEqual(original_dot_y, 1.19)
                self.assertLess(x1, 22)
                prior_centres.append(dot_center)
        self.assertEqual(prior_centres, sorted(prior_centres))

    def test_c_and_C_cubic_inner_turn_is_tangent_smooth(self):
        for ch in ('C','c'):
            original = source_polygons(SRC/f'U+{ord(ch):04X}.svg',3)
            areas = []
            for weight in WEIGHTS:
                with self.subTest(ch=ch,weight=weight):
                    ops = commands(ch, weight)
                    self.assertEqual(sum(op=='moveTo' for op,_ in ops), 1)
                    self.assertEqual(sum(op=='curveTo' for op,_ in ops), 5)
                    # Outer three cubic sweeps, followed by two deliberate inner
                    # cubic sweeps. They share a vertical tangent at the left
                    # apex; terminal joins match existing quadratic tangents.
                    cubic = [args for op,args in ops if op=='curveTo']
                    low,high = cubic[-2:]
                    self.assertTrue(near(low[2][0],high[0][0]))
                    self.assertTrue(near(low[2][0],low[1][0]))
                    self.assertTrue(near(low[2][1]-low[1][1],
                                         high[0][1]-low[2][1]))
                    # Equal-alignment of the surrounding Bezier tangent rays
                    # means there is no kink where the two arcs meet.
                    self.assertGreater(low[2][1], high[0][1])
                    shape = source_polygons(
                        SRC/'weight-overrides'/str(weight)/f'U+{ord(ch):04X}.svg',3)
                    self.assertTrue(shape.is_valid)
                    self.assertEqual(topology(shape),topology(original))
                    self.assertLess(shape.area,original.area)
                    self.assertTrue(all(abs(a-b)<=3 for a,b
                                        in zip(shape.bounds, original.bounds)))
                    areas.append(shape.area)
            self.assertEqual(areas,sorted(areas))


if __name__ == '__main__':
    unittest.main()
