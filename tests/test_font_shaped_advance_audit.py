#!/usr/bin/env python3
"""Regression contract for shaped-advance vs ink-width typography reports."""
from pathlib import Path
import sys
import unittest
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from render_font_family_stress import create,WEIGHTS,SIZES,EXAMPLES

class FontAdvanceAuditTests(unittest.TestCase):
    def test_ink_and_shaped_advances_are_reported_independently(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            report=create(ROOT,Path(d))
            self.assertEqual(len(report['samples']),15*len(SIZES))
            self.assertEqual(len(report['images']),4)
            self.assertIn('advance_width_review_count',report)
            self.assertEqual(report['advance_width_review_count'],len(report['advance_width_review']))
            for size in SIZES:
                for style in WEIGHTS:
                    base=report['samples'][f'{style}/base/{size}']['samples']
                    for family in ('panel','accent'):
                        samples=report['samples'][f'{style}/{family}/{size}']['samples']
                        for sample,_ in EXAMPLES:
                            entry=samples[sample]
                            self.assertGreater(entry['advance_width_px'],0)
                            self.assertGreater(entry['ink_width_px'],0)
                            self.assertAlmostEqual(entry['advance_vs_base_ratio'],
                                entry['advance_width_px']/base[sample]['advance_width_px'],delta=.0002)
                            self.assertAlmostEqual(entry['ink_width_minus_advance_px'],
                                entry['ink_width_px']-entry['advance_width_px'],delta=.002)
            self.assertGreater(report['relative_width_review_count'],0)

if __name__=='__main__': unittest.main()
