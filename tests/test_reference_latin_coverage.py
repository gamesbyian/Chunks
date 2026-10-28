#!/usr/bin/env python3
"""Checks that Latin source coverage classes are exhaustive and reproducible."""
from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
import unicodedata as ud
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from audit_reference_latin_coverage import (
    STYLES, ORDER, classify, inventory, write,
)


class LatinCoverageTests(unittest.TestCase):
    def test_all_latin_characters_classified_per_weight(self):
        report = inventory()
        self.assertEqual(report['mapped_total_unicode'], 856)
        self.assertGreaterEqual(report['mapped_latin_letter_count'], 300)
        self.assertEqual(len(report['latin_letters']),
                         report['mapped_latin_letter_count'])
        self.assertEqual(len({row['unicode'] for row in report['latin_letters']}),
                         report['mapped_latin_letter_count'])
        self.assertEqual(set(report['status_counts']), set(STYLES))
        self.assertGreater(report['unresolved_any_weight'], 0)
        by_cp = {row['character']: row for row in report['latin_letters']}
        for style in STYLES:
            counts = report['status_counts'][style]
            self.assertEqual(set(counts), set(ORDER))
            self.assertEqual(sum(counts.values()), report['mapped_latin_letter_count'])
            self.assertGreaterEqual(counts['recomposed'], 240)
            self.assertEqual(counts['dotless_rebuilt'], 14)
            self.assertEqual(counts['reference_base'], 52)
            self.assertEqual(by_cp['A']['status_by_weight'][style], 'reference_base')
            self.assertEqual(by_cp['À']['status_by_weight'][style], 'recomposed')
            self.assertEqual(by_cp['ì']['status_by_weight'][style], 'dotless_rebuilt')
        for record in report['latin_letters']:
            self.assertEqual(set(record['status_by_weight']), set(STYLES))
            self.assertEqual(record['canonical_nfd'],
                             [f'U+{ord(ch):04X}' for ch in ud.normalize('NFD',record['character'])])
            for style, status in record['status_by_weight'].items():
                self.assertIn(status, ORDER)
                self.assertEqual(style in record['unresolved_weights'],
                                 status.startswith('unrebuilt_'))

    def test_unicode_buckets_have_intentionally_different_meanings(self):
        self.assertEqual(classify('A', set(), set()), 'reference_base')
        self.assertEqual(classify('À', {'À'}, set()), 'recomposed')
        self.assertEqual(classify('ì', set(), {'ì'}), 'dotless_rebuilt')
        self.assertEqual(classify('Á', set(), set()), 'unrebuilt_simple_mark')
        self.assertEqual(classify('ǟ', set(), set()), 'unrebuilt_stacked_marks')
        self.assertEqual(classify('Æ', set(), set()), 'unrebuilt_other_latin')

    def test_report_bytes_reproduce_without_source_mutation(self):
        with tempfile.TemporaryDirectory() as tmp:
            first = Path(tmp) / 'a.json'
            second = Path(tmp) / 'b.json'
            self.assertEqual(write(first), write(second))
            self.assertEqual(first.read_bytes(), second.read_bytes())
            self.assertEqual(json.loads(first.read_text()), inventory())


if __name__ == '__main__':
    unittest.main()
