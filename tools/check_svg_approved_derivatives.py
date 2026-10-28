#!/usr/bin/env python3
"""Assert approved-art derivatives retain the exact underlying canonical paths.

Intent: the approved launcher is the sole master. 7 derived layers must be
literal geometry slices. Other derived variant arrangements are experimental,
never substitutes for the canonical launcher.
"""
from __future__ import annotations
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "docs/design/approved/chunks-app-icon-master.svg"
DEST = ROOT / "docs/design/svg/approved-derived/layers"
IDS = [
    "purple-document", "green-download-tile", "white-emphasis-rays",
    "folder-rear-wall", "coral-media-tile", "folder-front-face",
    "folder-face-features",
]

def main():
    text = SOURCE.read_text(encoding="utf-8")
    comments = list(re.finditer(r"<!--[\s\S]*?-->", text))
    if len(comments) != len(IDS):
        raise AssertionError(f"Canonical path group count changed: {len(comments)}")
    for i, name in enumerate(IDS):
        start = comments[i].end()
        end = comments[i+1].start() if i+1 < len(comments) else text.rfind("</svg>")
        expected = text[start:end].strip()
        target = (DEST / f"{name}.svg").read_text(encoding="utf-8")
        body = re.search(r'<g id="artwork">([\s\S]*)</g></svg>\s*$', target)
        if not body or body.group(1).strip() != expected:
            raise AssertionError(f"Stale geometry in extracted approved-art layer: {name}")
    print(f"APPROVED SVG LAYERS PASS: all {len(IDS)} base regions match canonical geometry.")

if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print("APPROVED SVG LAYERS FAIL:", exc, file=sys.stderr)
        raise
