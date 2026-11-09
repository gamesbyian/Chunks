#!/usr/bin/env python3
"""Locate only the Android-owned Pixel Launcher ANR dismissal button.

This is a narrow emulator-harness recovery. It must never dismiss an ANR for
Chunks, another foreground app or any arbitrary dialog. A nonmatch is normal.
"""
import argparse
from pathlib import Path
import re
import xml.etree.ElementTree as ET

_BOUNDS = re.compile(r"^\[(\d{1,5}),(\d{1,5})\]\[(\d{1,5}),(\d{1,5})\]$")


def close_point(xml: str) -> tuple[int, int] | None:
    if len(xml) > 262144:
        return None
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return None
    if root.tag != "hierarchy":
        return None
    nodes = list(root.iter("node"))
    titles = [n for n in nodes if n.get("package") == "android" and
              n.get("resource-id") == "android:id/alertTitle" and
              n.get("text") == "Pixel Launcher isn't responding"]
    buttons = [n for n in nodes if n.get("package") == "android" and
               n.get("resource-id") == "android:id/aerr_close" and
               n.get("text") == "Close app" and n.get("clickable") == "true"]
    if len(titles) != 1 or len(buttons) != 1:
        return None
    match = _BOUNDS.fullmatch(buttons[0].get("bounds", ""))
    if match is None:
        return None
    x1, y1, x2, y2 = map(int, match.groups())
    # Accept only an actual sized, on-screen target. The dialog cannot
    # nominate offscreen, zero-size or implausibly huge Android input taps.
    if not (0 <= x1 < x2 <= 4096 and 0 <= y1 < y2 <= 8192 and
            x2 - x1 >= 44 and y2 - y1 >= 44):
        return None
    return (x1 + x2) // 2, (y1 + y2) // 2


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("xml", type=Path)
    args = parser.parse_args()
    try:
        raw = args.xml.read_bytes()
        if len(raw) > 262144:
            return 1
        candidate = close_point(raw.decode("utf-8"))
    except (OSError, UnicodeDecodeError):
        return 1
    if candidate is None:
        return 1
    print(*candidate)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
