#!/usr/bin/env python3
"""Native CFF stacked coverage and editorial sheet regression tests."""
from __future__ import annotations
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from PIL import Image

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from render_reference_stacked_grid import STYLES,SIZES,source_report,generate


class StackedGridTests(unittest.TestCase):
    def test_full_five_weight_source_coverage_matches_compiled_fonts(self):
        audit=source_report()
        self.assertGreaterEqual(audit['candidate_count'],100)
        self.assertEqual(len(audit['characters']),audit['candidate_count'])
        self.assertEqual(set(audit['counts']),set(STYLES))
        for style,info in audit['counts'].items():
            self.assertEqual(info['new_stacked']+info['retained_stacked'],
                             audit['candidate_count'])
            self.assertGreater(info['new_stacked'],40)
            self.assertLess(info['remaining_unresolved'],info['baseline_unresolved'])
            self.assertGreater(info['remaining_unresolved'],0)
        for entry in audit['characters']:
            self.assertEqual(set(entry['by_style']),set(STYLES))
            self.assertEqual(set(entry['by_style'].values())-{'rebuilt','retained'},set())

    def test_compiled_sheet_is_pixel_deterministic(self):
        candidates=source_report()['characters']
        selection=''.join(x['char'] for x in candidates[:8])
        with tempfile.TemporaryDirectory() as tmp:
            a=generate(Path(tmp)/'first',sizes=(14,),selection=selection)
            b=generate(Path(tmp)/'second',sizes=(14,),selection=selection)
            self.assertEqual(a,b)
            self.assertFalse(a['full_scope'])
            page=a['pages']['14'][0]
            self.assertEqual(page['rows'],8)
            self.assertEqual(a['rendered_characters'],8)
            one=Path(tmp)/'first'/page['file']
            two=Path(tmp)/'second'/page['file']
            self.assertEqual(one.read_bytes(),two.read_bytes())
            self.assertEqual(hashlib.sha256(one.read_bytes()).hexdigest(),page['sha256'])
            with Image.open(one) as im:
                self.assertEqual(im.format,'PNG')
                self.assertEqual(list(im.size),page['pixel_dimensions'])
            report=json.loads((Path(tmp)/'first'/'reference-stacked-grid-metrics.json').read_text())
            self.assertEqual(report,a)

    def test_bad_size_and_bad_selection_fail_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            for sizes in ((13,), (14,14), ()):
                with self.assertRaises(ValueError):
                    generate(Path(tmp),sizes=sizes)
            for selection in ('😀', 'ǕǕ', ''):
                with self.assertRaises(ValueError):
                    generate(Path(tmp),sizes=(14,),selection=selection)


if __name__=='__main__':
    unittest.main()
