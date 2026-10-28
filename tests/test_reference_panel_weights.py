#!/usr/bin/env python3
"""Reproducibility and safety gates for four non-shipping optical weight studies."""
from __future__ import annotations
import hashlib,json,sys,tempfile,unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from PIL import ImageFont
from fontTools.ttLib import TTFont
from fontTools.pens.recordingPen import RecordingPen
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.qu2cuPen import Qu2CuPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.svgLib.path import parse_path

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
            # A realistic editorial file from a cubic-native SVG editor.
            # Re-encode the exact source quadratic reference as cubic Beziers.
            # This preserves identical silhouette/topology while exercising
            # the actual direct cubic CFF import, not polygonized Q samples.
            root=ET.fromstring((GLYPHS/'U+0041.svg').read_text())
            path_node=next(x for x in root.iter() if x.tag.endswith('path'))
            cubic=SVGPathPen(None)
            parse_path(path_node.attrib['d'],
                       Qu2CuPen(cubic,max_err=0.001,all_cubic=True))
            path_node.attrib['d']=cubic.getCommands()
            self.assertIn('C',path_node.attrib['d'])
            (folder/'U+0041.svg').write_text(ET.tostring(root,encoding='unicode'))
            raw=Path(d)/'override.otf'
            result=make_weight(BASE/'Chunks-Regular.otf',GLYPHS,PROFILE,400,raw,folder.parent)
            self.assertEqual(result['A']['outline_source'],'weight-specific-svg-master')
            self.assertEqual(result['A']['outline_encoding'],'native-bezier-curves')
            self.assertEqual(result['B']['outline_encoding'],'sampled-offset-polygons')
            a_font=TTFont(raw)
            a_glyph=a_font.getBestCmap()[ord('A')]
            b_glyph=a_font.getBestCmap()[ord('B')]
            a_rec=RecordingPen()
            b_rec=RecordingPen()
            a_font.getGlyphSet()[a_glyph].draw(a_rec)
            a_font.getGlyphSet()[b_glyph].draw(b_rec)
            self.assertEqual(a_font.getBestCmap(),
                             TTFont(BASE/'Chunks-Regular.otf').getBestCmap())
            rendered=ImageFont.truetype(str(raw),24,layout_engine=ImageFont.Layout.RAQM)
            baseline=ImageFont.truetype(str(BASE/'Chunks-Regular.otf'),24,
                                        layout_engine=ImageFont.Layout.RAQM)
            self.assertAlmostEqual(rendered.getlength('हिन्दी'),
                                   baseline.getlength('हिन्दी'),delta=.02)
            self.assertGreater(sum(op=='curveTo' for op,_ in a_rec.value),0,
                               'Reviewed SVG master must retain native Bezier curves')
            self.assertEqual(sum(op=='curveTo' for op,_ in b_rec.value),0,
                             'Unreviewed derived shapes remain polygonal')
            self.assertIsNotNone(result['A']['override_svg_sha256'])
            self.assertEqual(result['B']['outline_source'],'contour-aware-derivation')
            self.assertEqual(len(result),62)
            self.assertEqual(TTFont(raw)['OS/2'].usWeightClass,400)

    def test_cubic_svg_accepted_for_geometry_without_ignoring_transforms(self):
        with tempfile.TemporaryDirectory() as d:
            svg=Path(d)/'custom.svg'
            svg.write_text('<svg xmlns="http://www.w3.org/2000/svg">'
                           '<path d="M1 1 C2 0 5 0 6 1 L6 6 L1 6 Z"/>'
                           '</svg>')
            geom=source_polygons(svg,0.01)
            self.assertEqual(topology(geom),(1,0))
            self.assertGreater(geom.area,15)
            svg.write_text('<svg xmlns="http://www.w3.org/2000/svg">'
                           '<g transform="translate(40,0)">'
                           '<path d="M1 1 C2 0 5 0 6 1 L6 6 L1 6 Z"/>'
                           '</g></svg>')
            with self.assertRaisesRegex(ValueError,'transforms'):
                source_polygons(svg,0.01)

    def test_eight_weight_specific_vertical_stem_masters_are_native_curves(self):
        """Nonshipping I/l candidates must have real editable Bezier paths.

        Verify source provenance and an ordered four-weight design gradient.
        This does not imply that their visual style has been owner approved.
        """
        masters=GLYPHS/'weight-overrides'
        original={ch:source_polygons(GLYPHS/f'U+{ord(ch):04X}.svg',3)
                  for ch in ('I','l')}
        manifest=json.loads((STUDIES/'reference-panel-weight-study-metrics.json').read_text())
        self.assertEqual(manifest['overrides_used'],32)
        for ch in ('I','l'):
            ratios=[]
            native_areas=[]
            for weight,style in WEIGHTS.items():
                with self.subTest(char=ch,weight=weight):
                    source=masters/str(weight)/f'U+{ord(ch):04X}.svg'
                    self.assertTrue(source.is_file(),source)
                    geom=source_polygons(source,3)
                    self.assertEqual(topology(geom),topology(original[ch]))
                    ratios.append(geom.area/original[ch].area)
                    native_areas.append(geom.area)
                    self.assertTrue(.30<=ratios[-1]<=1.20)
                    oldbox=original[ch].bounds
                    newbox=geom.bounds
                    self.assertTrue(all(abs(oldbox[i]-newbox[i])<=3.0
                                        for i in range(4)),(style,ch,newbox))
                    root=ET.fromstring(source.read_text())
                    element=next(el for el in root.iter() if el.tag.endswith('path'))
                    pen=RecordingPen()
                    parse_path(element.attrib['d'],pen)
                    self.assertGreater(sum(cmd=='qCurveTo' for cmd,_ in pen.value),0)
                    face=TTFont(STUDIES/f'ChunksReferencePanel-{style}-Study.otf')
                    record=RecordingPen()
                    face.getGlyphSet()[face.getBestCmap()[ord(ch)]].draw(record)
                    self.assertGreater(sum(cmd=='curveTo' for cmd,_ in record.value),0)
                    info=manifest['weights'][style]['glyph_geometry'][ch]
                    self.assertEqual(info['outline_source'],'weight-specific-svg-master')
                    self.assertEqual(info['outline_encoding'],'native-bezier-curves')
                    self.assertEqual(info['override_svg_sha256'],
                                     hashlib.sha256(source.read_bytes()).hexdigest())
            self.assertEqual(sorted(native_areas),native_areas,
                             f'{ch} must gain source ink monotonically with weight')
            self.assertLess(ratios[0],ratios[-1])
            self.assertLess(ratios[-1],1.0)

    def test_four_dotted_i_masters_have_real_detached_dot_and_stem(self):
        """Dotted lowercase i must keep two ink islands and a readable gap."""
        source=source_polygons(GLYPHS/'U+0069.svg',3)
        self.assertEqual(topology(source),(2,0))
        metrics=json.loads((STUDIES/'reference-panel-weight-study-metrics.json').read_text())
        areas=[]
        for weight,style in WEIGHTS.items():
            with self.subTest(style=style):
                file=GLYPHS/'weight-overrides'/str(weight)/'U+0069.svg'
                self.assertTrue(file.is_file())
                geom=source_polygons(file,3)
                self.assertEqual(topology(geom),(2,0))
                areas.append(geom.area)
                self.assertTrue(all(abs(a-b)<=3 for a,b in zip(geom.bounds,source.bounds)))
                pieces=sorted(components(geom), key=lambda p:p.bounds[1])
                dot,stem=pieces
                self.assertLess(dot.bounds[3],stem.bounds[1]-1.5,
                                'Dot must not collide with the separate stem')
                self.assertGreater(dot.area,20)
                self.assertGreater(stem.area,100)
                r=metrics['weights'][style]['glyph_geometry']['i']
                self.assertEqual(r['outline_source'],'weight-specific-svg-master')
                self.assertEqual(r['outline_encoding'],'native-bezier-curves')
                self.assertEqual(r['components'],2)
                self.assertEqual(r['counters'],0)
                self.assertEqual(r['override_svg_sha256'],
                                 hashlib.sha256(file.read_bytes()).hexdigest())
                font=TTFont(STUDIES/f'ChunksReferencePanel-{style}-Study.otf')
                record=RecordingPen()
                font.getGlyphSet()[font.getBestCmap()[ord('i')]].draw(record)
                self.assertEqual(sum(op=='moveTo' for op,_ in record.value),2)
                self.assertGreater(sum(op=='curveTo' for op,_ in record.value),2)
        self.assertEqual(areas, sorted(areas))
        self.assertLess(areas[-1],source.area)

    def test_four_connected_H_masters_are_editorial_native_curves(self):
        """Two stems and a middle joining bar must stay one connected path."""
        old = source_polygons(GLYPHS/'U+0048.svg',3)
        self.assertEqual(topology(old),(1,0))
        metrics=json.loads((STUDIES/'reference-panel-weight-study-metrics.json').read_text())
        areas=[]
        for weight,style in WEIGHTS.items():
            with self.subTest(style=style):
                path=GLYPHS/'weight-overrides'/str(weight)/'U+0048.svg'
                self.assertTrue(path.is_file())
                new=source_polygons(path,3)
                self.assertEqual(topology(new),(1,0))
                self.assertGreater(new.area,400)
                self.assertTrue(all(abs(a-b)<=3 for a,b in zip(new.bounds,old.bounds)))
                self.assertTrue(.30<=new.area/old.area<=1.20)
                areas.append(new.area)
                root=ET.fromstring(path.read_text())
                element=next(el for el in root.iter() if el.tag.endswith('path'))
                pen=RecordingPen()
                parse_path(element.attrib['d'],pen)
                self.assertEqual(sum(op=='moveTo' for op,_ in pen.value),1)
                self.assertGreaterEqual(sum(op=='qCurveTo' for op,_ in pen.value),12)
                info=metrics['weights'][style]['glyph_geometry']['H']
                self.assertEqual(info['outline_source'],'weight-specific-svg-master')
                self.assertEqual(info['outline_encoding'],'native-bezier-curves')
                self.assertEqual(info['components'],1)
                self.assertEqual(info['counters'],0)
                self.assertEqual(info['override_svg_sha256'],
                                 hashlib.sha256(path.read_bytes()).hexdigest())
                font=TTFont(STUDIES/f'ChunksReferencePanel-{style}-Study.otf')
                record=RecordingPen()
                font.getGlyphSet()[font.getBestCmap()[ord('H')]].draw(record)
                self.assertEqual(sum(op=='moveTo' for op,_ in record.value),1)
                self.assertGreaterEqual(sum(op=='curveTo' for op,_ in record.value),12)
        self.assertEqual(areas,sorted(areas))
        self.assertLess(areas[-1],old.area,
                        'Heaviest draft H must not exceed unedited ExtraBold ink')

    def test_four_T_and_four_L_masters_are_joined_rounded_native_paths(self):
        """Separately authored cap and foot geometries, not offset polygons."""
        root = GLYPHS/'weight-overrides'
        ledger=json.loads((STUDIES/'reference-panel-weight-study-metrics.json').read_text())
        for letter in ('T','L'):
            reference=source_polygons(GLYPHS/f'U+{ord(letter):04X}.svg',3)
            self.assertEqual(topology(reference),(1,0))
            areas=[]
            for weight,style in WEIGHTS.items():
                with self.subTest(letter=letter,weight=weight):
                    path=root/str(weight)/f'U+{ord(letter):04X}.svg'
                    self.assertTrue(path.is_file())
                    geom=source_polygons(path,3)
                    self.assertTrue(geom.is_valid and geom.area>200)
                    self.assertEqual(topology(geom),(1,0))
                    self.assertTrue(all(abs(x-y)<=3
                                        for x,y in zip(geom.bounds,reference.bounds)))
                    self.assertTrue(.30<=geom.area/reference.area<=1.20)
                    areas.append(geom.area)
                    svg=ET.fromstring(path.read_text())
                    element=next(x for x in svg.iter() if x.tag.endswith('path'))
                    commands=RecordingPen()
                    parse_path(element.attrib['d'],commands)
                    self.assertEqual(sum(op=='moveTo' for op,_ in commands.value),1)
                    self.assertGreaterEqual(sum(op=='qCurveTo' for op,_ in commands.value),
                                            8 if letter == 'T' else 6)
                    info=ledger['weights'][style]['glyph_geometry'][letter]
                    self.assertEqual(info['outline_source'],'weight-specific-svg-master')
                    self.assertEqual(info['outline_encoding'],'native-bezier-curves')
                    self.assertEqual(info['components'],1)
                    self.assertEqual(info['counters'],0)
                    self.assertEqual(info['override_svg_sha256'],
                                     hashlib.sha256(path.read_bytes()).hexdigest())
                    face=TTFont(STUDIES/f'ChunksReferencePanel-{style}-Study.otf')
                    glyph=face.getBestCmap()[ord(letter)]
                    compiled=RecordingPen()
                    face.getGlyphSet()[glyph].draw(compiled)
                    self.assertEqual(sum(op=='moveTo' for op,_ in compiled.value),1)
                    self.assertGreaterEqual(sum(op=='curveTo' for op,_ in compiled.value),
                                            8 if letter == 'T' else 6)
            self.assertEqual(areas,sorted(areas),
                             f'{letter}: independent designs must gain ink from 400 to 700')
            self.assertLess(areas[-1],reference.area,
                            f'{letter}: Bold candidate must not exceed source ExtraBold')


    def test_eight_independent_E_F_crossbar_masters_and_cff_provenance(self):
        """Three-level E/two-level F bar silhouettes must stay curved and joined."""
        ledger=json.loads((STUDIES/'reference-panel-weight-study-metrics.json').read_text())
        self.assertEqual(ledger['overrides_used'],32)
        for letter in ('E','F'):
            original=source_polygons(GLYPHS/f'U+{ord(letter):04X}.svg',3)
            self.assertEqual(topology(original),(1,0))
            areas=[]
            for weight,style in WEIGHTS.items():
                with self.subTest(letter=letter,weight=weight):
                    path=GLYPHS/'weight-overrides'/str(weight)/f'U+{ord(letter):04X}.svg'
                    self.assertTrue(path.is_file(),path)
                    geom=source_polygons(path,3)
                    self.assertTrue(geom.is_valid)
                    self.assertEqual(topology(geom),(1,0))
                    self.assertTrue(all(abs(a-b)<=3 for a,b in
                                        zip(geom.bounds,original.bounds)))
                    self.assertGreater(geom.area,250)
                    self.assertTrue(.30<=geom.area/original.area<=1.0)
                    areas.append(geom.area)
                    svg=ET.fromstring(path.read_text())
                    path_node=next(el for el in svg.iter() if el.tag.endswith('path'))
                    commands=RecordingPen()
                    parse_path(path_node.attrib['d'],commands)
                    self.assertEqual(sum(op=='moveTo' for op,_ in commands.value),1)
                    self.assertGreaterEqual(sum(op=='qCurveTo' for op,_ in commands.value),12)
                    info=ledger['weights'][style]['glyph_geometry'][letter]
                    self.assertEqual(info['outline_source'],'weight-specific-svg-master')
                    self.assertEqual(info['outline_encoding'],'native-bezier-curves')
                    self.assertEqual(info['components'],1)
                    self.assertEqual(info['counters'],0)
                    self.assertEqual(info['override_svg_sha256'],
                                     hashlib.sha256(path.read_bytes()).hexdigest())
                    face=TTFont(STUDIES/f'ChunksReferencePanel-{style}-Study.otf')
                    pen=RecordingPen()
                    face.getGlyphSet()[face.getBestCmap()[ord(letter)]].draw(pen)
                    self.assertEqual(sum(op=='moveTo' for op,_ in pen.value),1)
                    self.assertGreaterEqual(sum(op=='curveTo' for op,_ in pen.value),12)
            self.assertEqual(areas,sorted(areas))
            self.assertLess(areas[-1],original.area,
                            f'{letter}: heaviest draft cannot exceed ExtraBold reference')

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