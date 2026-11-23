#!/usr/bin/env python3
"""Narrow API35 dark-first-launch status-clock pixel assertion.

Consumes genuine ADB screenshots. It makes no screen-reader or contrast-ratio
claim, and never generates a substitute image or modifies capture pixels.
"""
from pathlib import Path
import struct
import sys
import zlib


def _paeth(a, b, c):
    p = a + b - c
    da, db, dc = abs(p - a), abs(p - b), abs(p - c)
    return a if da <= db and da <= dc else (b if db <= dc else c)


def bright_clock_pixels(path: Path) -> int:
    data = Path(path).read_bytes()
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise ValueError(f"Not a PNG: {Path(path).name}")
    at = 8
    compressed = bytearray()
    width = height = channels = None
    while at + 12 <= len(data):
        n = struct.unpack_from(">I", data, at)[0]
        kind = data[at + 4:at + 8]
        end = at + 12 + n
        if end > len(data):
            raise ValueError("Truncated PNG chunk")
        if kind == b"IHDR":
            width, height, bits, color, compression, filtering, interlace = struct.unpack_from(
                ">IIBBBBB", data, at + 8)
            if bits != 8 or color not in (2, 6) or compression or filtering or interlace:
                raise ValueError("Unsupported screenshot encoding")
            channels = 3 if color == 2 else 4
            if width != 1080 or height not in (2250, 2400):
                raise ValueError("Unexpected API35 Pixel screenshot dimensions")
        elif kind == b"IDAT":
            compressed.extend(data[at + 8:end - 4])
            if len(compressed) > 24 * 1024 * 1024:
                raise ValueError("Oversized screenshot compression")
        elif kind == b"IEND":
            break
        at = end
    if channels is None or not compressed:
        raise ValueError("Missing PNG image data")
    stride = width * channels
    inflated = zlib.decompress(compressed)
    if len(inflated) != (stride + 1) * height:
        raise ValueError("Invalid screenshot scanline count")
    previous = bytearray(stride)
    offset = 0
    bright = 0
    for y in range(min(height, 110)):
        filter_kind = inflated[offset]
        offset += 1
        decoded = bytearray(inflated[offset:offset + stride])
        offset += stride
        if filter_kind not in (0, 1, 2, 3, 4):
            raise ValueError("Unsupported PNG row filter")
        if filter_kind:
            for j in range(stride):
                left = decoded[j - channels] if j >= channels else 0
                above = previous[j]
                upper_left = previous[j - channels] if j >= channels else 0
                predictor = (left if filter_kind == 1 else
                             above if filter_kind == 2 else
                             (left + above) // 2 if filter_kind == 3 else
                             _paeth(left, above, upper_left))
                decoded[j] = (decoded[j] + predictor) & 255
        if 20 <= y < 104:
            for x in range(25, 210):
                i = x * channels
                if min(decoded[i:i + 3]) >= 150 and (
                    channels == 3 or decoded[i + 3] >= 240
                ):
                    bright += 1
        previous = decoded
    return bright


def require_dark_theme_status_clock(paths) -> None:
    for path in paths:
        count = bright_clock_pixels(Path(path))
        if count < 100:
            raise ValueError(f"Illegible status clock in {Path(path).name}: {count} bright pixels")
        print(f"CHUNKS_NATIVE_STATUS_BAR: {Path(path).name}: {count} bright clock pixels")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit("Usage: native_statusbar_contrast.py screenshot.png [more.png ...]")
    require_dark_theme_status_clock(sys.argv[1:])
