#!/usr/bin/env python3
"""CFF nonzero-fill must preserve all editable reference glyph counters."""
import sys
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from font_lab_normalize_winding import normalize, run

class CorrectCounterWinding(unittest.TestCase):
    def test_synthetic_same_direction_hole_is_reversed_only_once(self):
        original = ("M0 0 L20 0 L20 20 L0 20 Z "
                    "M5 5 L15 5 L15 15 L5 15 Z")
        fixed, changed = normalize(original)
        self.assertEqual(changed, 1)
        unchanged, again = normalize(fixed)
        self.assertEqual(again, 0)
        self.assertEqual(unchanged, fixed)

    def test_all_248_svg_masters_have_open_type_compatible_counters(self):
        changed, count, problems = run(write=False)
        self.assertEqual((changed, count, problems), (0, 0, []),
                         "\n".join(problems[:30]))

if __name__ == "__main__":
    unittest.main()
