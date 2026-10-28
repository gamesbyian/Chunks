#!/usr/bin/env python3
"""Checks for real-CFF 62-glyph editorial grid output and reproducibility."""
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
from render_reference_glyph_grid import (
    GLYPHS, STYLES, SIZES, font_paths, generate, verify_fonts,
)


class ReferenceGlyphGridTests(unittest.TestCase):
    def test_source_fonts_are_five_real_cffs_with_856_mappings(self):
        info=verify_fonts(font_paths())
        self.assertEqual(len(GLYPHS),62)
        self.assertEqual(tuple(info),STYLES)
        self.assertEqual(tuple(SIZES),(14,24,48,82))
        for style in STYLES:
            self.assertEqual(info[style]['codepoints'],856)
            self.assertEqual(info[style]['weight'],(STYLES.index(style)+4)*100)

    def test_compiled_glyph_page_metadata_and_pixels_are_reproducible(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            first=root/'first'
            second=root/'second'
            a=generate(first,sizes=(14,))
            b=generate(second,sizes=(14,))
            self.assertEqual(a,b)
            self.assertEqual(a['glyph_count'],62)
            self.assertEqual(a['glyphs'],GLYPHS)
            self.assertEqual(set(a['pages']),{'14'})
            page=a['pages']['14']
            self.assertEqual(page['glyph_rows'],62)
            self.assertEqual(page['weight_columns'],5)
            f=first/page['file']
            with Image.open(f) as bitmap:
                self.assertEqual(bitmap.format,'PNG')
                self.assertEqual(list(bitmap.size),page['size_px'])
                self.assertGreater(bitmap.size[1],3000)
            self.assertEqual(page['sha256'],hashlib.sha256(f.read_bytes()).hexdigest())
            self.assertEqual(f.read_bytes(),(second/page['file']).read_bytes())
            self.assertEqual(json.loads((first/'reference-panel-glyph-grid-metrics.json').read_text()),a)

    def test_invalid_sizes_fail_before_writing(self):
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaises(ValueError):
                generate(Path(temp),sizes=(13,))
            with self.assertRaises(ValueError):
                generate(Path(temp),sizes=(14,14))


if __name__=='__main__':
    unittest.main()
