#!/usr/bin/env python3
"""Guard native CFF source integrity of optional mixed above/below mark studies."""
from __future__ import annotations
import hashlib
import json
import sys
import unicodedata as ud
import unittest
from pathlib import Path
from PIL import ImageFont
from fontTools.ttLib import TTFont
from fontTools.pens.recordingPen import RecordingPen

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from build_reference_mixed_marks import directions, WEIGHTS

PREVIOUS = ROOT / 'assets/fonts/experimental/stacked-studies'
RESULTS = ROOT / 'assets/fonts/experimental/mixed-mark-studies'
ORIGINAL = ROOT / 'assets/fonts/chunks'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def record(font, cp):
    p = RecordingPen()
    font.getGlyphSet()[font.getBestCmap()[cp]].draw(p)
    return p.value


class MixedMarkTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.report = json.loads((RESULTS / 'reference-mixed-mark-metrics.json').read_text())
        cls.previous = json.loads((PREVIOUS / 'reference-stacked-accent-metrics.json').read_text())

    def test_combining_direction_policy_is_explicit(self):
        self.assertEqual(directions(ud.normalize('NFD', 'Ậ')[1:]),
                         {'above': 1, 'below': 1})
        self.assertEqual(directions(ud.normalize('NFD', 'Ệ')[1:]),
                         {'above': 1, 'below': 1})
        self.assertIsNone(directions(ud.normalize('NFD', 'Ǖ')[1:]))
        self.assertIsNone(directions(''))

    def test_five_fonts_keep_unmodified_all_other_856_glyphs(self):
        self.assertEqual(set(self.report['weights']), set(WEIGHTS.values()))
        for weight, style in WEIGHTS.items():
            with self.subTest(style=style):
                row = self.report['weights'][style]
                before_path = PREVIOUS / f'ChunksReferencePanel-{style}-Stacked.otf'
                after_path = RESULTS / f'ChunksReferenceMixedMarks-{style}.otf'
                original_path = ORIGINAL / f'Chunks-{style}.otf'
                self.assertEqual(row['input_stacked_sha256'], digest(before_path))
                self.assertEqual(row['input_original_sha256'], digest(original_path))
                self.assertEqual(row['output_sha256'], digest(after_path))
                self.assertEqual(row['kerning']['output_sha256'], digest(after_path))
                font = TTFont(after_path)
                earlier = TTFont(before_path)
                self.assertEqual(font.sfntVersion, 'OTTO')
                self.assertIn('CFF ', font)
                self.assertNotIn('glyf', font)
                self.assertEqual(font.getBestCmap(), earlier.getBestCmap())
                self.assertEqual(len(font.getBestCmap()), 856)
                self.assertEqual(font['OS/2'].usWeightClass, weight)
                self.assertTrue({'GPOS', 'GSUB', 'GDEF'}.issubset(font.keys()))
                self.assertEqual(font['name'].getDebugName(1), 'Chunks Reference Mixed Marks')
                self.assertEqual(font['CFF '].cff.fontNames[0],
                                 f'ChunksReferenceMixedMarks-{style}')
                self.assertEqual(row['rebuilt_count'], len(row['rebuilt']))
                self.assertEqual(row['skipped_count'], len(row['skipped']))
                old_skip = {x['glyph'] for x in self.previous['weights'][style]['skipped']}
                self.assertEqual(row['previously_skipped'], len(old_skip))
                rebuilt = {x['glyph'] for x in row['rebuilt']}
                skipped = {x['glyph'] for x in row['skipped']}
                self.assertFalse(rebuilt & skipped)
                self.assertTrue(rebuilt | skipped <= old_skip)
                for item in row['rebuilt']:
                    cp = ord(item['glyph'])
                    self.assertEqual(item['nfd_marks'],
                                     [f'U+{ord(ch):04X}' for ch in ud.normalize('NFD', item['glyph'])[1:]])
                    self.assertEqual(directions(ud.normalize('NFD', item['glyph'])[1:]),
                                     {'above': 1, 'below': 1})
                    self.assertGreaterEqual(item['separated_above'], 1)
                    self.assertGreaterEqual(item['separated_below'], 1)
                    self.assertTrue(-480 <= item['bbox'][1] < item['bbox'][3] <= 1040)
                    self.assertNotEqual(record(font, cp), record(earlier, cp))
                    self.assertEqual(font['hmtx'][font.getBestCmap()[cp]][0],
                                     font['hmtx'][font.getBestCmap()[ord(item['base'])]][0])
                for cp in earlier.getBestCmap():
                    if chr(cp) in rebuilt:
                        continue
                    with self.subTest(style=style, character=f'U+{cp:04X}'):
                        self.assertEqual(record(font, cp), record(earlier, cp))
                        g = font.getBestCmap()[cp]
                        self.assertEqual(font['hmtx'][g], earlier['hmtx'][g])

    def test_native_hindi_and_unmodified_latin_shaping(self):
        for style in WEIGHTS.values():
            old = ImageFont.truetype(str(PREVIOUS / f'ChunksReferencePanel-{style}-Stacked.otf'),
                                     32, layout_engine=ImageFont.Layout.RAQM)
            new = ImageFont.truetype(str(RESULTS / f'ChunksReferenceMixedMarks-{style}.otf'),
                                     32, layout_engine=ImageFont.Layout.RAQM)
            for s in ('हिन्दी', 'देवनागरी', 'Chunks0123456789', 'Résumé…'):
                self.assertAlmostEqual(old.getlength(s), new.getlength(s), delta=0.02)


if __name__ == '__main__':
    unittest.main()
