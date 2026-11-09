"""Offline counterexamples for the four *real-image* native atlas receipt labels.

These synthetic PNG/XML files exercise the validator only. They are never
uploaded as native app images or counted as installed Android evidence.
"""
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from xml.sax.saxutils import escape
import zlib

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
from native_visual_atlas import CASES, REQUIRED_FIRST_LAUNCH_TEXT, report


def png_chunk(kind: bytes, data: bytes) -> bytes:
    return (struct.pack(">I", len(data)) + kind + data +
            struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF))


def fixture_png(width: int, height: int) -> bytes:
    """Valid RGBA PNG, generated in-memory only for rejection/acceptance tests."""
    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    blank_scanline = b"\x00" + b"\x00" * (width * 4)
    pixels = zlib.compress(blank_scanline * height, level=1)
    return (b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", header) +
            png_chunk(b"IDAT", pixels) + png_chunk(b"IEND", b""))


def fixture_xml(labels: set[str]) -> str:
    nodes = "".join('<node text="' + escape(label, {'"': '&quot;'}) +
                    '" content-desc="" />' for label in sorted(labels))
    return '<hierarchy rotation="0">' + nodes + '</hierarchy>'


class NativeAtlasFirstLaunchReceiptTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for case, spec in CASES.items():
            width, height = spec["pixels"]
            (self.root / f"{case}.png").write_bytes(fixture_png(width, height))
            (self.root / f"{case}.xml").write_text(
                fixture_xml(REQUIRED_FIRST_LAUNCH_TEXT), encoding="utf-8")

    def test_all_four_screen_receipts_are_explicitly_labeled_and_hash_bound(self):
        report(self.root)
        data = json.loads((self.root / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(4, len(data["cases"]))
        self.assertIn("first-launch empty device Transfers", data["tested_screen"])
        self.assertIn("physical device", data["claims_excluded"])
        self.assertTrue(all(case["first_launch_transfer_inventory_verified"]
                            for case in data["cases"]))
        self.assertTrue(all(len(case["sha256"]) == 64 for case in data["cases"]))
        self.assertIn("screen-reader usability", (self.root / "REVIEW.md").read_text())

    def test_missing_hierarchy_cannot_claim_first_launch(self):
        (self.root / "larger_text.xml").unlink()
        with self.assertRaisesRegex(ValueError, "Missing UIAutomator hierarchy for larger_text"):
            report(self.root)
        self.assertFalse((self.root / "manifest.json").exists())

    def test_malformed_hierarchy_cannot_claim_first_launch(self):
        (self.root / "standard.xml").write_text("<hierarchy><node", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Malformed UIAutomator hierarchy for standard"):
            report(self.root)

    def test_absent_footer_action_cannot_claim_fixed_navigation(self):
        (self.root / "wide.xml").write_text(
            fixture_xml(REQUIRED_FIRST_LAUNCH_TEXT - {"Connections"}), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Connections"):
            report(self.root)

    def test_content_description_alone_does_not_fake_visible_text(self):
        labels = REQUIRED_FIRST_LAUNCH_TEXT - {"+ Add"}
        xml = fixture_xml(labels).replace(
            "</hierarchy>", '<node text="" content-desc="+ Add"/></hierarchy>')
        (self.root / "standard.xml").write_text(xml, encoding="utf-8")
        with self.assertRaisesRegex(ValueError, r"\+ Add"):
            report(self.root)

    def test_wrong_dimensions_and_missing_png_are_rejected(self):
        (self.root / "small.png").write_bytes(fixture_png(900, 2400))
        with self.assertRaisesRegex(ValueError, "Unexpected screenshot dimensions"):
            report(self.root)
        (self.root / "small.png").unlink()
        with self.assertRaises(FileNotFoundError):
            report(self.root)


if __name__ == "__main__":
    unittest.main()
