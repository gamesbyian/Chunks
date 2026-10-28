#!/usr/bin/env python3
"""Verify conservative stacked accent CFF prototypes retain every baseline."""
from __future__ import annotations

import hashlib
import json
import sys
import unicodedata as ud
import unittest
from pathlib import Path

from fontTools.ttLib import TTFont
from fontTools.pens.recordingPen import RecordingPen
from PIL import ImageFont

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from build_reference_stacked_accents import WEIGHTS, stacked_candidates

BASE=ROOT/'assets/fonts/chunks'
INPUT=ROOT/'assets/fonts/experimental/consolidated'
OUTPUT=ROOT/'assets/fonts/experimental/stacked-studies'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def char_record(font,char):
    pen=RecordingPen()
    font.getGlyphSet()[font.getBestCmap()[ord(char)]].draw(pen)
    return pen.value


class StackedAccentTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.report=json.loads((OUTPUT/'reference-stacked-accent-metrics.json').read_text())

    def test_five_real_otfs_and_all_safe_composites(self):
        self.assertEqual(set(self.report['weights']),set(WEIGHTS.values()))
        for weight,style in WEIGHTS.items():
            with self.subTest(style=style):
                row=self.report['weights'][style]
                original=BASE/f'Chunks-{style}.otf'
                consolidated=INPUT/f'ChunksReferencePanel-{style}-Consolidated.otf'
                result=OUTPUT/f'ChunksReferencePanel-{style}-Stacked.otf'
                self.assertEqual(row['weight'],weight)
                self.assertEqual(row['source_sha256'],digest(original))
                self.assertEqual(row['input_consolidated_sha256'],digest(consolidated))
                self.assertEqual(row['output_sha256'],digest(result))
                self.assertEqual(row['kerning']['output_sha256'],digest(result))
                self.assertGreater(row['built_count'],4)
                self.assertEqual(row['built_count'],len(row['built']))
                self.assertEqual(row['skipped_count'],len(row['skipped']))
                self.assertEqual(row['built_count']+row['skipped_count'],row['eligible_stacked_non_ij'])
                after=TTFont(result)
                before=TTFont(consolidated)
                self.assertEqual(after.sfntVersion,'OTTO')
                self.assertIn('CFF ',after)
                self.assertNotIn('glyf',after)
                self.assertEqual(after.getBestCmap(),before.getBestCmap())
                self.assertEqual(len(after.getBestCmap()),856)
                self.assertTrue({'GDEF','GPOS','GSUB'}.issubset(after.keys()))
                self.assertEqual(after['OS/2'].usWeightClass,weight)
                self.assertEqual(after['name'].getDebugName(1),'Chunks Reference Panel Stacked')
                self.assertEqual(after['CFF '].cff.fontNames[0],f'ChunksReferencePanelStacked-{style}')
                rebuilt=set()
                for item in row['built']:
                    char=item['glyph']
                    rebuilt.add(char)
                    dec=ud.normalize('NFD',char)
                    self.assertGreaterEqual(len(dec),3)
                    self.assertEqual(item['base'],dec[0])
                    self.assertNotIn(item['base'],'ij')
                    self.assertEqual(len(item['marks_nfd']),len(dec)-1)
                    self.assertEqual(after['hmtx'][after.getBestCmap()[ord(char)]][0],
                                     after['hmtx'][after.getBestCmap()[ord(dec[0])]][0])
                    self.assertNotEqual(char_record(after,char),char_record(before,char))
                    self.assertLessEqual(item['bbox'][3],1040)
                    self.assertGreaterEqual(item['bbox'][1],-480)
                source=TTFont(original)
                self.assertEqual(len(list(stacked_candidates(source))),row['eligible_stacked_non_ij'])
                for char in 'Chunks0123456789Áàíĵįị“”!?.हिन्दी':
                    if ord(char) in after.getBestCmap() and char not in rebuilt:
                        self.assertEqual(char_record(before,char),char_record(after,char),
                                         (style,char,'must retain previously accepted contours'))

    def test_complex_scripts_remain_native_and_unchanged(self):
        for style in WEIGHTS.values():
            orig=ImageFont.truetype(str(INPUT/f'ChunksReferencePanel-{style}-Consolidated.otf'),
                                    48,layout_engine=ImageFont.Layout.RAQM)
            fresh=ImageFont.truetype(str(OUTPUT/f'ChunksReferencePanel-{style}-Stacked.otf'),
                                     48,layout_engine=ImageFont.Layout.RAQM)
            for sample in ('हिन्दी','देवनागरी','Chunks','0123456789'):
                self.assertAlmostEqual(orig.getlength(sample),fresh.getlength(sample),
                                       delta=.02,msg=(style,sample))


if __name__=='__main__':
    unittest.main()
