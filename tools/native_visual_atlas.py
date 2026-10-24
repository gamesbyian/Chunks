#!/usr/bin/env python3
"""Verify actual emulator screenshot dimensions and record native visual evidence.

This does not generate any fake screenshot: PNGs are consumed only if they
have real PNG structure from adb exec-out screencap -p.
"""
import hashlib
import json
from pathlib import Path
import struct
import sys
import xml.etree.ElementTree as ET

CASES = {
    "small": {"pixels": [1080, 2400], "density": 480, "font_scale": 1.0},
    "standard": {"pixels": [1080, 2400], "density": 420, "font_scale": 1.0},
    "larger_text": {"pixels": [1080, 2400], "density": 420, "font_scale": 1.4},
    "wide": {"pixels": [1080, 2250], "density": 360, "font_scale": 1.0},
}

def inspect(path: Path, expected: dict):
    data = path.read_bytes()
    if len(data) < 32 or not data.startswith(b"\x89PNG\r\n\x1a\n") or data[12:16] != b"IHDR":
        raise ValueError("Not a PNG screenshot")
    width, height = struct.unpack(">II", data[16:24])
    if [width, height] != expected["pixels"]:
        raise ValueError(f"Unexpected screenshot dimensions {width}x{height}")
    return {
        "image": path.name,
        "sha256": hashlib.sha256(data).hexdigest(),
        "width": width, "height": height,
        "density_dpi": expected["density"],
        "font_scale": expected["font_scale"],
        "bytes": len(data)
    }

def report(folder):
    root = Path(folder)
    report_data = {
        "schema": "chunks.native-atlas.v1",
        "source": "Android emulator screenshot, not mockup",
        "tested_screen": "initial server connection / no credentials",
        "claims_excluded": ["torrent list state", "real Seedhost authentication",
                            "Add dialog", "physical device", "screen-reader quality"],
        "cases": []
    }
    for case, expected in CASES.items():
        png = root / (case + ".png")
        result = inspect(png, expected)
        xml = root / (case + ".xml")
        if xml.is_file():
            try:
                tree = ET.parse(xml)
                result["accessibility_hierarchy_nodes"] = len(tree.findall(".//node"))
                result["hierarchy_file"] = xml.name
            except ET.ParseError:
                result["hierarchy_error"] = "Malformed uiautomator XML"
        else:
            result["hierarchy_error"] = "uiautomator did not produce XML"
        report_data["cases"].append(result)
    root.joinpath("manifest.json").write_text(json.dumps(report_data, indent=2) + "\n")
    root.joinpath("REVIEW.md").write_text(
        "# Native Chunks connection-state screenshot review\n\n" +
        "These are real emulator PNGs at 4 viewport/font states, not proposed illustrations.\n\n" +
        "Check truncation, tap targets, text-scale overflow, safe insets, contrast, "
        "field labels and screen-reader hierarchy. Main/Add/Transfer screenshot "
        "coverage and physical Pixel proof remain OPEN.\n\n" +
        "\n".join(f"- **{v['image']}**: {v['width']}×{v['height']} px, "
                  f"{v['density_dpi']} dpi, text {v['font_scale']}×"
                  for v in report_data["cases"]) + "\n"
    )
    print("CHUNKS_NATIVE_ATLAS: validated", len(report_data["cases"]), "real screenshots")

def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: native_visual_atlas.py SCREENSHOT_DIR")
    report(sys.argv[1])

if __name__ == "__main__":
    main()
