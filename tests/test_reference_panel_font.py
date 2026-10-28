"""No shipping font may be changed by constructing an alternate display font."""
from __future__ import annotations
import hashlib,json,sys,unittest
from pathlib import Path
from PIL import ImageFont
from fontTools.ttLib import TTFont
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from build_reference_panel_candidate import build
from spacing_optimizer import ink_gaps_at_size

class ReferencePanelFontTest(unittest.TestCase):
    def test_alternative_preserves_shaping_and_has_62_distinct_contours(self):
        source=ROOT/'assets/fonts/chunks/Chunks-ExtraBold.otf'
        candidate=ROOT/'assets/fonts/experimental/ChunksReferencePanel-ExtraBold.otf'
        original=TTFont(source)
        adjusted=TTFont(candidate)
        self.assertEqual(original.getBestCmap(),adjusted.getBestCmap())
        self.assertEqual(set(original.keys()),set(adjusted.keys()))
        self.assertEqual(adjusted.sfntVersion,'OTTO')
        self.assertEqual(adjusted['OS/2'].usWeightClass,800)
        self.assertIn('GPOS',adjusted)
        before=original['CFF '].cff.topDictIndex[0].CharStrings
        after=adjusted['CFF '].cff.topDictIndex[0].CharStrings
        sample='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
        for ch in sample:
            glyph=original.getBestCmap()[ord(ch)]
            self.assertNotEqual(before[glyph].bytecode,after[glyph].bytecode, ch)
            self.assertGreater(adjusted['hmtx'][glyph][0],0)
        # Preserve a non-Latin contour exactly rather than retouching unmeasured scripts.
        for ch in ('ह','ि','द','ी'):
            glyph=original.getBestCmap()[ord(ch)]
            self.assertEqual(before[glyph].bytecode,after[glyph].bytecode,ch)
        orig=ImageFont.truetype(str(source),42)
        alt=ImageFont.truetype(str(candidate),42)
        self.assertAlmostEqual(orig.getlength('हिन्दी'),alt.getlength('हिन्दी'),delta=.1)
        for px in (14,24,48):
            gaps=ink_gaps_at_size(candidate,['Chunks','TORRENTS, YOUR WAY.','1234567890','Downloading 68%'],px)
            self.assertTrue(gaps)
            self.assertGreater(min(gaps),.2,(px,min(gaps)))

    def test_rebuild_is_binary_reproducible(self):
        from tempfile import TemporaryDirectory
        source=ROOT/'assets/fonts/chunks/Chunks-ExtraBold.otf'
        glyphs=ROOT/'assets/fonts/source/reference-alphabet'
        shipped=ROOT/'assets/fonts/experimental/ChunksReferencePanel-ExtraBold.otf'
        with TemporaryDirectory() as directory:
            tmp=Path(directory)/'regenerated.otf'
            result=build(source,glyphs,tmp)
            self.assertEqual(result['glyphs_replaced'],62)
            self.assertEqual(hashlib.sha256(tmp.read_bytes()).hexdigest(),hashlib.sha256(shipped.read_bytes()).hexdigest())

if __name__=='__main__':unittest.main()