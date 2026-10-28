#!/usr/bin/env python3
"""Regression checks for the nonshipping five-face consolidated font proposal."""
from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

from fontTools.ttLib import TTFont
from fontTools.pens.recordingPen import RecordingPen
from PIL import ImageFont

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from build_reference_panel_consolidated import (
    EXPECTED, SAMPLES, WEIGHTS, apply_punctuation, digest,
)
from spacing_optimizer import ink_gaps_at_size

BASE = ROOT / 'assets/fonts/experimental/dotless-kerned'
OUT = ROOT / 'assets/fonts/experimental/consolidated'
MASTERS = ROOT / 'assets/fonts/source/reference-punctuation'
PROFILE = ROOT / 'tools/reference_weight_profiles.json'


def recording(font, char):
    glyph = font.getBestCmap()[ord(char)]
    pen = RecordingPen()
    font.getGlyphSet()[glyph].draw(pen)
    return pen.value


class ConsolidatedReferenceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.report = json.loads((OUT / 'reference-panel-consolidated-metrics.json').read_text())

    def test_exact_source_and_five_complete_otfs(self):
        self.assertEqual(len(EXPECTED), 28)
        self.assertEqual(set(self.report['weights']), set(WEIGHTS.values()))
        manifest = json.loads((MASTERS / 'manifest.json').read_text())
        self.assertEqual(set(EXPECTED), set(manifest['glyphs']))
        self.assertEqual(self.report['source_manifest_sha256'], digest(MASTERS / 'manifest.json'))
        self.assertEqual(self.report['weight_profile_sha256'], digest(PROFILE))
        for weight, style in WEIGHTS.items():
            with self.subTest(style=style):
                original = BASE / f'ChunksReferencePanel-{style}-DotlessKerned.otf'
                final_path = OUT / f'ChunksReferencePanel-{style}-Consolidated.otf'
                row = self.report['weights'][style]
                self.assertEqual(row['input_sha256'], digest(original))
                self.assertEqual(row['output_sha256'], digest(final_path))
                self.assertEqual(row['font'], final_path.name)
                self.assertEqual(len(row['punctuation']), 28)
                f = TTFont(final_path)
                base = TTFont(original)
                self.assertEqual(f.sfntVersion, 'OTTO')
                self.assertIn('CFF ', f)
                self.assertNotIn('glyf', f)
                self.assertEqual(f.getBestCmap(), base.getBestCmap())
                self.assertEqual(len(f.getBestCmap()), 856)
                self.assertTrue({'GPOS', 'GSUB', 'GDEF'}.issubset(f.keys()))
                self.assertEqual(f['OS/2'].usWeightClass, weight)
                self.assertEqual(f['name'].getDebugName(1), 'Chunks Reference Panel Consolidated')
                self.assertEqual(f['CFF '].cff.fontNames[0],
                                 f'ChunksReferencePanelConsolidated-{style}')
                self.assertEqual(f['CFF '].cff.topDictIndex[0].FamilyName,
                                 'Chunks Reference Panel Consolidated')
                self.assertEqual(f['name'].getDebugName(6),
                                 f'ChunksReferencePanelConsolidated-{style}')
                self.assertGreater(row['kerning']['adjusted_punctuation_pairs'], 0)
                self.assertGreater(row['kerning']['sampled_punctuation_pairs'], 0)
                for char in EXPECTED:
                    item = row['punctuation'][f'U+{ord(char):04X}']
                    self.assertEqual(item['source_svg_sha256'],
                                     digest(MASTERS / f'U+{ord(char):04X}.svg'))
                    self.assertNotEqual(recording(f, char), recording(base, char), (style, char))
                    self.assertGreater(item['advance_units'], 0)
                    self.assertGreater(item['ink_area_ratio'], 0)
                    self.assertEqual(f['hmtx'][f.getBestCmap()[ord(char)]][0],
                                     item['advance_units'])

    def test_untouched_accents_and_nonlatin_shapes_and_metrics(self):
        for weight, style in WEIGHTS.items():
            before = TTFont(BASE / f'ChunksReferencePanel-{style}-DotlessKerned.otf')
            after = TTFont(OUT / f'ChunksReferencePanel-{style}-Consolidated.otf')
            for char in 'Chunks0123456789íĵįịÀŶहिन्दी':
                if ord(char) not in before.getBestCmap():
                    continue
                self.assertEqual(recording(before, char), recording(after, char),
                                 (style, char))
                self.assertEqual(before['hmtx'][before.getBestCmap()[ord(char)]],
                                 after['hmtx'][after.getBestCmap()[ord(char)]])
            old = ImageFont.truetype(str(BASE / f'ChunksReferencePanel-{style}-DotlessKerned.otf'),
                                     48, layout_engine=ImageFont.Layout.RAQM)
            new = ImageFont.truetype(str(OUT / f'ChunksReferencePanel-{style}-Consolidated.otf'),
                                     48, layout_engine=ImageFont.Layout.RAQM)
            for txt in ('Chunks', 'ǐȉ', 'हिन्दी', 'देवनागरी', 'íĵįị', '1234567890'):
                self.assertAlmostEqual(old.getlength(txt), new.getlength(txt), delta=.06,
                                       msg=(style, txt))

    def test_real_punctuation_small_size_probes(self):
        for weight, style in WEIGHTS.items():
            face = OUT / f'ChunksReferencePanel-{style}-Consolidated.otf'
            for size in (14, 24, 48, 82):
                gaps = ink_gaps_at_size(face, SAMPLES, px=size)
                self.assertTrue(gaps, (style, size))
                self.assertGreater(min(gaps), -0.5, (style, size, min(gaps)))

    def test_raw_punctuation_compilation_deterministic(self):
        with tempfile.TemporaryDirectory() as temp:
            first = Path(temp) / 'first.otf'
            second = Path(temp) / 'second.otf'
            original = BASE / 'ChunksReferencePanel-Regular-DotlessKerned.otf'
            profile = json.loads(PROFILE.read_text())
            a = apply_punctuation(original, MASTERS, first, 400, profile)
            b = apply_punctuation(original, MASTERS, second, 400, profile)
            self.assertEqual(a, b)
            self.assertEqual(digest(first), digest(second))
            self.assertEqual(len(a), 28)


if __name__ == '__main__':
    unittest.main()
