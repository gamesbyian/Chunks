#!/usr/bin/env python3
"""Reproducible five-face layered Latin design coverage checks."""
from __future__ import annotations
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'tools'))
from audit_reference_layered_latin_coverage import inspect, write, STYLES


class LayeredCoverageTests(unittest.TestCase):
    def test_every_mapped_latin_glyph_has_exactly_one_layer(self):
        result = inspect()
        self.assertEqual(result['mapped_unicode'], 856)
        self.assertEqual(result['mapped_latin_letters'], 495)
        self.assertEqual(len(result['latin_letters']), 495)
        self.assertEqual(set(result['weights']), set(STYLES))
        for style in STYLES:
            row = result['weights'][style]
            self.assertEqual(row['mapped_latin_letters'], 495)
            self.assertEqual(row['reconstructed_mixed_above_below'], 14)
            self.assertEqual(row['unresolved_after'],
                             row['unresolved_before']
                             - row['reconstructed_stacked_above']
                             - row['reconstructed_mixed_above_below'])
            self.assertEqual(row['unresolved_after'],
                             row['remaining_simple_mark']
                             + row['remaining_stacked_marks']
                             + row['remaining_other_latin'])
            self.assertGreater(row['unresolved_after'], 80)
        all_ids = [r['unicode'] for r in result['latin_letters']]
        self.assertEqual(len(all_ids), len(set(all_ids)))
        for item in result['latin_letters']:
            self.assertEqual(set(item['layered_status_by_weight']), set(STYLES))
            for style in STYLES:
                old = item['status_by_weight'][style]
                new = item['layered_status_by_weight'][style]
                if old != new:
                    self.assertEqual(old, 'unrebuilt_stacked_marks')
                    self.assertIn(new, ('stacked_above','mixed_above_below'))
                self.assertEqual(style in item['layered_unresolved_weights'],
                                 new.startswith('unrebuilt_'))

    def test_report_is_byte_for_byte_reproducible(self):
        with tempfile.TemporaryDirectory() as folder:
            a = Path(folder)/'a.json'
            b = Path(folder)/'b.json'
            self.assertEqual(write(a), write(b))
            self.assertEqual(a.read_bytes(), b.read_bytes())
            self.assertEqual(json.loads(a.read_text()), inspect())


if __name__=='__main__':
    unittest.main()
