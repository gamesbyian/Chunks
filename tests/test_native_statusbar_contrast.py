"""Offline synthetic PNG counterexamples, never counted as installed app evidence."""
import sys
from pathlib import Path
import struct
import tempfile
import unittest
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "tools"))
from native_statusbar_contrast import bright_clock_pixels, require_dark_theme_status_clock


def _chunk(kind, data):
    return (struct.pack(">I", len(data)) + kind + data +
            struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff))


def fake_png(clock=True, pixels=(1080, 2400)):
    width, height = pixels
    row = bytearray(bytes([11, 15, 23, 255]) * width)
    raw = bytearray()
    for y in range(height):
        line = bytearray(row)
        if clock and 33 <= y < 50:
            for x in range(40, 120):
                line[x * 4:x * 4 + 4] = b"\xff\xff\xff\xff"
        raw.append(0)
        raw.extend(line)
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + _chunk(b"IHDR", ihdr) + \
        _chunk(b"IDAT", zlib.compress(raw, 6)) + _chunk(b"IEND", b"")


class NativeStatusBarContrastTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.file = Path(self.temp.name) / "small.png"

    def test_legible_clock_region_passes(self):
        self.file.write_bytes(fake_png(True))
        self.assertGreater(bright_clock_pixels(self.file), 100)
        require_dark_theme_status_clock([self.file])

    def test_dark_glyphs_on_dark_surface_are_rejected(self):
        self.file.write_bytes(fake_png(False))
        self.assertEqual(0, bright_clock_pixels(self.file))
        with self.assertRaisesRegex(ValueError, "Illegible status clock"):
            require_dark_theme_status_clock([self.file])

    def test_rejects_wrong_geometry_and_invalid_png(self):
        self.file.write_bytes(fake_png(True, pixels=(900, 2400)))
        with self.assertRaisesRegex(ValueError, "dimensions"):
            bright_clock_pixels(self.file)
        self.file.write_bytes(b"notpng")
        with self.assertRaisesRegex(ValueError, "Not a PNG"):
            bright_clock_pixels(self.file)


if __name__ == "__main__":
    unittest.main()
