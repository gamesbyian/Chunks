#!/usr/bin/env python3
"""Reproducibility and safety gates for four non-shipping optical weight studies."""
from __future__ import annotations
import hashlib,json,sys,tempfile,unittest
from pathlib import Path
from PIL import ImageFont
from fontTools.ttLib import TTFont
from fontTools.pens.recordingPen import RecordingPen
from fontTools.pens.boundsPen import BoundsPen

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from build_reference_panel_weight_studies import (
    WEIGHTS,EXPECTED,make_weight,source_polygons,topology,components)
from spacing_optimizer import ink_gaps_at_size,safe_line_height
GLYPHS=ROOT/'assets/fonts/source/reference-alphabet'
BASE=ROOT/'assets/fonts/chunks'
STUDIES=ROOT/'assets/fonts/experimental/weight-studies'
PROFILE=json.loads((ROOT/'tools/reference_weight_profiles.json').read_text())

class ReferencePanelWeightTests(unittest.TestCase):
    def test_all_source_outlines_parse_and_keep_counters(self):
        self.assertEqual(len(EXPECTED),62)
        v=json.loads((GLYPHS/'manifest.json').read_text())
        self.assertEqual(set(v['glyphs']),set(EXPECTED))
        for c in EXPECTED:
            with self.subTest(glyph=c):
                g=source_polygons(GLYPHS/f'U+{ord(c):04X}.svg',PROFILE['min_valid_component_area'])
                self.assertGreater(g.area,40)
                self.assertGreaterEqual(topology(g)[0],1)
        for c in 'ABDOPQR0689abdepqo':
            self.assertGreaterEqual(topology(source_polygons(GLYPHS/f'U+{ord(c):04X}.svg',3))[1],1)

    def test_shaping_and_font_identity_for_all_weights(self):
        for weight,style in WEIGHTS.items():
            with self.subTest(style=style):
                study=STUDIES/f'ChunksReferencePanel-{style}-Study.otf'
                self.assertTrue(study.is_file(),study)
                f=TTFont(study)
                base=TTFont(BASE/f'Chunks-{style}.otf')
                self.assertEqual(f.sfntVersion,'OTTO')
                self.assertIn('CFF ',f)
                self.assertEqual(f['OS/2'].usWeightClass,weight)
                self.assertEqual(f.getBestCmap(),base.getBestCmap())
                self.assertGreaterEqual(len(f.getBestCmap()),850)
                self.assertTrue({'GPOS','GSUB','GDEF'}.issubset(f.keys()))
                self.assertIn('Chunks Reference Panel',f['name'].getDebugName(1))
                for c in EXPECTED:
                    glyph=f.getBestCmap()[ord(c)]
                    after=RecordingPen();f.getGlyphSet()[glyph].draw(after)
                    before=RecordingPen();base.getGlyphSet()[glyph].draw(before)
                    self.assertNotEqual(after.value,before.value,(style,c))
                    bound=BoundsPen(f.getGlyphSet());f.getGlyphSet()[glyph].draw(bound)
                    self.assertIsNotNone(bound.bounds,(style,c))
                    self.assertLessEqual(abs(f['hmtx'][glyph][1]-round(bound.bounds[0])),2)
                self.assertGreaterEqual(safe_line_height(f)['minimum_multiscript_line_height_em'],1.5)
                for size in (14,24,48):
                    self.assertGreater(min(ink_gaps_at_size(study,['Chunks','Downloading 68%','Résumé télévision','0123456789'],px=size)),.25)
                for text in ('हिन्दी','देवनागरी'):
                    a=ImageFont.truetype(str(study),24,layout_engine=ImageFont.Layout.RAQM)
                    b=ImageFont.truetype(str(BASE/f'Chunks-{style}.otf'),24,layout_engine=ImageFont.Layout.RAQM)
                    self.assertAlmostEqual(a.getlength(text),b.getlength(text),delta=.02)
                self.assertNotIn('glyf',f)

    def test_per_glyph_shape_weights_are_monotonic_and_provenanced(self):
        report=json.loads((STUDIES/'reference-panel-weight-study-metrics.json').read_text())
        self.assertEqual(set(report['weights']),set(WEIGHTS.values()))
        self.assertEqual(report['status'].split()[0],'NONSHIPPING')
        self.assertEqual(report['profile_sha256'],hashlib.sha256((ROOT/'tools/reference_weight_profiles.json').read_bytes()).hexdigest())
        self.assertEqual(report['source_manifest_sha256'],hashlib.sha256((GLYPHS/'manifest.json').read_bytes()).hexdigest())
        for style,weight in zip(WEIGHTS.values(),WEIGHTS):
            r=report['weights'][style]
            self.assertEqual(r['weight'],weight)
            self.assertEqual(r['glyphs_changed'],62)
            self.assertEqual(r['codepoints'],856)
            self.assertGreater(r['kerning_direct_pairs'],12000)
            self.assertEqual(r['sha256'],hashlib.sha256((STUDIES/r['file']).read_bytes()).hexdigest())
            self.assertEqual(set(r['tracking_em']),{'display','heading','body','small_labels'})
            self.assertGreaterEqual(r['leading_em']['body_multiscript'],1.6)
        for char in EXPECTED:
            values=[report['weights'][s]['glyph_geometry'][char]['ink_area_ratio'] for s in WEIGHTS.values()]
            self.assertEqual(values,sorted(values),(char,values))
            self.assertLess(values[-1],1)
            for s in WEIGHTS.values():
                g=report['weights'][s]['glyph_geometry'][char]
                self.assertGreater(g['components'],0)
                self.assertGreaterEqual(g['counters'],0)

    def test_explicit_weight_master_can_override_one_glyph_safely(self):
        with tempfile.TemporaryDirectory() as d:
            folder=Path(d)/'masters'/'400'
            folder.mkdir(parents=True)
            # A placeholder 400 design uses the source heavy geometry. This tests
            # precedence, provenance and safety, NOT aesthetic approval.
            (folder/'U+0041.svg').write_bytes((GLYPHS/'U+0041.svg').read_bytes())
            raw=Path(d)/'override.otf'
            result=make_weight(BASE/'Chunks-Regular.otf',GLYPHS,PROFILE,400,raw,folder.parent)
            self.assertEqual(result['A']['outline_source'],'weight-specific-svg-master')
            self.assertIsNotNone(result['A']['override_svg_sha256'])
            self.assertEqual(result['B']['outline_source'],'contour-aware-derivation')
            self.assertEqual(len(result),62)
            self.assertEqual(TTFont(raw)['OS/2'].usWeightClass,400)

    def test_raw_build_is_deterministic_without_touching_accepted_masters(self):
        with tempfile.TemporaryDirectory() as d:
            a=Path(d)/'a.otf';b=Path(d)/'b.otf'
            args=(BASE/'Chunks-Bold.otf',GLYPHS,PROFILE,700)
            glyphs_a=make_weight(*args,a)
            glyphs_b=make_weight(*args,b)
            self.assertEqual(a.read_bytes(),b.read_bytes())
            self.assertEqual(glyphs_a,glyphs_b)
            self.assertEqual(len(glyphs_a),62)

if __name__=='__main__':unittest.main()