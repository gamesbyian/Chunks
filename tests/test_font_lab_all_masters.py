#!/usr/bin/env python3
"""All 248 nonshipping basic masters: actual polygon topology and ink progression.

Collect every defect in one run so a source batch can be corrected without
multiple iterative GitHub Actions queue entries.
"""
from __future__ import annotations
import json
import sys
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from build_reference_panel_weight_studies import source_polygons,topology
SRC=ROOT/'assets/fonts/source/reference-alphabet'
WEIGHTS=(400,500,600,700)

class AllFontLabMasters(unittest.TestCase):
    def test_248_reference_compatible_svg_masters_and_gradient(self):
        original=json.loads((SRC/'manifest.json').read_text())['glyphs']
        self.assertEqual(len(original),62)
        problems=[]
        for ch in sorted(original):
            try:
                baseline=source_polygons(SRC/f'U+{ord(ch):04X}.svg',3)
            except Exception as e:
                problems.append(f'{ch!r} immutable source error: {e}')
                continue
            previous=0
            for w in WEIGHTS:
                path=SRC/'weight-overrides'/str(w)/f'U+{ord(ch):04X}.svg'
                if not path.is_file():
                    problems.append(f'{ch!r} {w}: missing source')
                    continue
                try:
                    new=source_polygons(path,3)
                except Exception as e:
                    problems.append(f'{ch!r} {w}: invalid geometry {e}')
                    continue
                ratio=new.area/baseline.area
                if not new.is_valid or topology(new)!=topology(baseline):
                    problems.append(f'{ch!r} {w}: topology {topology(new)} != {topology(baseline)}')
                if any(abs(a-b)>3 for a,b in zip(new.bounds,baseline.bounds)):
                    problems.append(f'{ch!r} {w}: bounds {new.bounds} vs {baseline.bounds}')
                if not 0.30 <= ratio <= 1.20:
                    problems.append(f'{ch!r} {w}: ink ratio {ratio:.4f} outside safety range')
                if not new.area>previous+1e-4:
                    problems.append(f'{ch!r} {w}: ink area failed strict progression')
                previous=new.area
                if w==700 and not ratio < 1.0:
                    problems.append(f'{ch!r} Bold 700 is not lighter than ExtraBold source: {ratio:.4f}')
        self.assertEqual([],problems,'\n'.join(problems))

if __name__=='__main__':
    unittest.main()
