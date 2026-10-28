#!/usr/bin/env python3
"""Non-mutating regression tests for accented Latin contextual advance measurement."""
from pathlib import Path
import sys, unittest
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from audit_accent_context import analyze, WEIGHTS, CONTEXTS

class AccentContextAuditTests(unittest.TestCase):
    def test_all_variants_and_contexts(self):
        result=analyze(ROOT)
        self.assertEqual(result['summary']['font_count'],5)
        self.assertEqual(set(result['fonts']),set(WEIGHTS))
        self.assertEqual(result['summary']['all_sampled_accents'],
                         sum(v['accented_glyphs'] for v in result['fonts'].values()))
        self.assertEqual(result['summary']['total_context_outliers_above_1px'],
                         sum(v['context_outliers_above_1px'] for v in result['fonts'].values()))
        for style,entry in result['fonts'].items():
            self.assertGreaterEqual(entry['accented_glyphs'],220)
            self.assertEqual(len(entry['contexts']),len(CONTEXTS))
            self.assertLessEqual(entry['standalone_delta_max_px'],1.0,
                f'{style}: reconstructed accents unexpectedly change own advance')
            self.assertEqual(len(entry['sha256']),64)
            for row in entry['top_outliers']:
                self.assertEqual(len(row['context_deltas_px']),len(CONTEXTS))
                self.assertGreater(row['max_context_delta_px'],1.0)

if __name__=='__main__':unittest.main()
