#!/usr/bin/env python3
"""Acceptance gates for the full reconstructed-accent glyph review sheets."""
from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from render_reference_accent_grid import (
    STYLES, SIZES, ROWS_PER_PAGE, coverage, font_files, verify_fonts, generate,
)


class AccentGridTests(unittest.TestCase):
    def test_five_source_manifests_and_true_cff_repertoire(self):
        chars, classes = coverage()
        self.assertGreaterEqual(len(chars), 250)
        self.assertEqual(len(set(chars)), len(chars))
        self.assertEqual(set(classes), set(STYLES))
        fonts = verify_fonts(font_files(), chars)
        self.assertEqual(set(fonts), set(STYLES))
        for style in STYLES:
            self.assertEqual(len(classes[style]['dotless']), 14)
            self.assertGreaterEqual(len(classes[style]['recomposed']), 240)
            self.assertEqual(fonts[style]['codepoints'], 856)

    def test_small_real_font_sheet_and_binary_determinism(self):
        chars, _ = coverage()
        sample = ''.join(chars[:10])
        with tempfile.TemporaryDirectory() as temp:
            a = generate(Path(temp) / 'a', sizes=(14,), selected=sample)
            b = generate(Path(temp) / 'b', sizes=(14,), selected=sample)
            self.assertEqual(a, b)
            self.assertFalse(a['full_scope'])
            self.assertEqual(a['character_count'], 10)
            self.assertEqual(len(a['pages']['14']), 1)
            page = a['pages']['14'][0]
            self.assertEqual(page['weight_columns'], 5)
            self.assertEqual(page['rows'], 10)
            pa = Path(temp) / 'a' / page['file']
            pb = Path(temp) / 'b' / page['file']
            with Image.open(pa) as image:
                self.assertEqual(image.format, 'PNG')
                self.assertEqual(list(image.size), page['size_px'])
            self.assertEqual(pa.read_bytes(), pb.read_bytes())
            self.assertEqual(hashlib.sha256(pa.read_bytes()).hexdigest(), page['sha256'])
            self.assertEqual(json.loads((Path(temp) / 'a' / 'reference-accent-grid-metrics.json').read_text()), a)

    def test_invalid_sizes_and_unknown_glyphs_fail_closed(self):
        with tempfile.TemporaryDirectory() as temp:
            dest = Path(temp)
            for sizes in ((13,), (14, 14), ()):
                with self.subTest(sizes=sizes):
                    with self.assertRaises(ValueError):
                        generate(dest, sizes=sizes, selected='ì')
            with self.assertRaises(ValueError):
                generate(dest, sizes=(14,), selected='😀')
            with self.assertRaises(ValueError):
                generate(dest, sizes=(14,), selected='ìì')


if __name__ == '__main__':
    unittest.main()
