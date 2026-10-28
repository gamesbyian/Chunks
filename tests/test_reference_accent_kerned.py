#!/usr/bin/env python3
"""Five-weight integrity, deterministic and shaping checks for accent kerning candidate."""
from __future__ import annotations
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from PIL import ImageFont
from fontTools.ttLib import TTFont
from fontTools.pens.recordingPen import RecordingPen

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from build_reference_accent_kerned import WEIGHTS, optimize, SAMPLES
from spacing_optimizer import ink_gaps_at_size
DIR=ROOT/'assets/fonts/experimental'
SOURCE=DIR/'accent-studies'
OUTPUT=DIR/'accent-kerned'
CONTEXTS=('{}','A{}','{}V','T{}r','{}n','R{}s','{}r','W{}')


def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()

class AccentKernedFamily(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source_manifest=json.loads((SOURCE/'reference-accent-study-metrics.json').read_text())
        cls.manifest=json.loads((OUTPUT/'reference-accent-kerned-metrics.json').read_text())

    def test_five_independent_installable_CFF_fonts(self):
        self.assertEqual(set(self.manifest['weights']),set(WEIGHTS.values()))
        for weight,style in WEIGHTS.items():
            with self.subTest(style=style):
                before=SOURCE/f'ChunksReferencePanel-{style}-AccentStudy.otf'
                after=OUTPUT/f'ChunksReferencePanel-{style}-AccentKerned.otf'
                f=TTFont(after);orig=TTFont(before)
                self.assertEqual(sha(after),self.manifest['weights'][style]['output_sha256'])
                self.assertEqual(sha(before),self.manifest['weights'][style]['input_sha256'])
                self.assertEqual(f.sfntVersion,'OTTO')
                self.assertIn('CFF ',f);self.assertNotIn('glyf',f)
                self.assertTrue({'GSUB','GPOS','GDEF'}.issubset(f.keys()))
                self.assertEqual(f.getBestCmap(),orig.getBestCmap())
                self.assertGreaterEqual(len(f.getBestCmap()),850)
                self.assertEqual(f['hmtx'].metrics,orig['hmtx'].metrics)
                self.assertEqual(f['OS/2'].usWeightClass,weight)
                self.assertFalse(bool(f['OS/2'].fsSelection & (1<<5)) and bool(f['OS/2'].fsSelection & (1<<6)))
                self.assertEqual(bool(f['OS/2'].fsSelection & (1<<5)),weight>=700)
                self.assertEqual(bool(f['head'].macStyle & 1),weight>=700)
                self.assertEqual(f['name'].getDebugName(1),'Chunks Reference Panel Kerned')
                self.assertEqual(f['name'].getDebugName(6),f'ChunksReferencePanelKerned-{style}')
                self.assertGreater(self.manifest['weights'][style]['adjusted_pairs'],75000)
                self.assertGreater(self.manifest['weights'][style]['bounded_subtables'],10)
                for sample in ('A','Á','ḍ','8','ह'):
                    gn=f.getBestCmap().get(ord(sample))
                    if not gn:continue
                    a=RecordingPen();b=RecordingPen()
                    f.getGlyphSet()[gn].draw(a);orig.getGlyphSet()[gn].draw(b)
                    self.assertEqual(a.value,b.value,(style,sample))

    def test_all_1216_constructed_accents_match_base_context(self):
        total=0
        for style in WEIGHTS.values():
            font=ImageFont.truetype(str(OUTPUT/f'ChunksReferencePanel-{style}-AccentKerned.otf'),48,layout_engine=ImageFont.Layout.RAQM)
            before=ImageFont.truetype(str(SOURCE/f'ChunksReferencePanel-{style}-AccentStudy.otf'),48,layout_engine=ImageFont.Layout.RAQM)
            manifest=self.source_manifest['weights'][style]['glyphs']
            for row in manifest:
                char,base=row['glyph'],row['base']
                for ctx in CONTEXTS:
                    target=ctx.format(char);bare=ctx.format(base)
                    self.assertLess(abs(font.getlength(target)-font.getlength(bare)),1.0,(style,char,ctx))
                    if ctx=='{}':
                        self.assertEqual(font.getlength(target),font.getlength(bare))
                        self.assertEqual(font.getlength(target),before.getlength(target))
                total+=1
        self.assertEqual(total,1216)

    def test_native_nonaccent_and_indic_unchanged(self):
        texts=('Chunks','TORRENTS, YOUR WAY.','1234567890 68%','हिन्दी','देवनागरी','The.Wanderer.2024.mkv')
        for style in WEIGHTS.values():
            b=ImageFont.truetype(str(SOURCE/f'ChunksReferencePanel-{style}-AccentStudy.otf'),48,layout_engine=ImageFont.Layout.RAQM)
            f=ImageFont.truetype(str(OUTPUT/f'ChunksReferencePanel-{style}-AccentKerned.otf'),48,layout_engine=ImageFont.Layout.RAQM)
            for text in texts:
                self.assertEqual(b.getlength(text),f.getlength(text),(style,text))

    def test_collision_smoke_at_four_actual_pixel_sizes(self):
        for style in WEIGHTS.values():
            target=OUTPUT/f'ChunksReferencePanel-{style}-AccentKerned.otf'
            for size in (14,24,48,82):
                gap=min(ink_gaps_at_size(target,SAMPLES,px=size))
                self.assertGreater(gap,0.25,(style,size,gap))

    def test_reproducible_regular_variant(self):
        style='Regular'
        with tempfile.TemporaryDirectory() as tmp:
            dest=Path(tmp)/'test.otf'
            optimize(SOURCE/f'ChunksReferencePanel-{style}-AccentStudy.otf',dest,
                self.source_manifest['weights'][style]['glyphs'])
            self.assertEqual(sha(dest),sha(OUTPUT/f'ChunksReferencePanel-{style}-AccentKerned.otf'))

if __name__=='__main__':unittest.main()