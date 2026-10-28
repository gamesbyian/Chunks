"""Optical kerning regression for the nonshipping 62-reference-glyph font."""
from __future__ import annotations
import hashlib,sys,unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from fontTools.ttLib import TTFont
from PIL import ImageFont
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from optimize_reference_panel_kerning import optimize
from spacing_optimizer import ink_gaps_at_size

class ReferenceOpticalKerningTest(unittest.TestCase):
    def test_reproducible_optical_kerning_without_nonlatin_mutation(self):
        before=ROOT/'assets/fonts/experimental/ChunksReferencePanel-ExtraBold.otf'
        after=ROOT/'assets/fonts/experimental/ChunksReferencePanel-ExtraBold-Optical.otf'
        original=TTFont(before)
        refined=TTFont(after)
        self.assertEqual(original.getBestCmap(),refined.getBestCmap())
        self.assertEqual(original['hmtx'].metrics,refined['hmtx'].metrics)
        self.assertEqual(original['OS/2'].usWeightClass,refined['OS/2'].usWeightClass)
        self.assertEqual(set(original.keys()),set(refined.keys()))
        self.assertEqual(len(refined['GPOS'].table.LookupList.Lookup),len(original['GPOS'].table.LookupList.Lookup)+1)
        orig=ImageFont.truetype(str(before),64)
        opt=ImageFont.truetype(str(after),64)
        for phrase in ('हिन्दी','₹'):
            self.assertAlmostEqual(orig.getlength(phrase),opt.getlength(phrase),delta=.01)
        for size in (14,24,48):
            samples=['Chunks','TORRENTS, YOUR WAY.','Downloading 68%','0123456789']
            gaps=ink_gaps_at_size(after,samples,px=size)
            self.assertTrue(gaps)
            self.assertGreater(min(gaps),.25,(size,min(gaps)))
        with TemporaryDirectory() as tmp:
            regenerated=Path(tmp)/'test.otf'
            metrics=Path(tmp)/'metrics.json'
            report=optimize(before,regenerated,metrics)
            self.assertEqual(report['modified_glyphs'],62)
            self.assertEqual(report['measured_pairs'],22801)
            self.assertGreater(report['additional_optical_pairs'],5000)
            self.assertEqual(hashlib.sha256(regenerated.read_bytes()).hexdigest(),hashlib.sha256(after.read_bytes()).hexdigest())

if __name__=='__main__':unittest.main()