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

# A screenshot's pixel hash proves its bytes, not which screen was captured.
# Match real UIAutomator text on each fresh-install emulator before claiming
# that the first screen was the device transfer inventory with fixed navigation.
REQUIRED_FIRST_LAUNCH_TEXT = frozenset({
    "Your transfers will appear here",
    "Search transfers on this device",
    "Transfers", "+ Add", "Connections", "Options",
})

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
        "tested_screen": "first-launch empty device Transfers inventory and four visible navigation labels",
        "claims_excluded": ["populated transfer rows", "real peer/piece progress",
                            "protected server authentication", "Add and Details screens",
                            "physical device", "screen-reader usability"],
        "cases": []
    }
    for case, expected in CASES.items():
        png = root / (case + ".png")
        result = inspect(png, expected)
        xml = root / (case + ".xml")
        if not xml.is_file():
            raise ValueError(f"Missing UIAutomator hierarchy for {case}: first-launch screen unverified")
        try:
            tree = ET.parse(xml)
        except ET.ParseError as failure:
            raise ValueError(f"Malformed UIAutomator hierarchy for {case}") from failure
        nodes = tree.findall(".//node")
        texts = {node.get("text", "") for node in nodes}
        missing = REQUIRED_FIRST_LAUNCH_TEXT - texts
        if missing:
            raise ValueError(
                f"First-launch device Transfers/footer not verified in {case}: "
                + ", ".join(sorted(missing))
            )
        result["accessibility_hierarchy_nodes"] = len(nodes)
        result["hierarchy_file"] = xml.name
        result["first_launch_transfer_inventory_verified"] = True
        report_data["cases"].append(result)
    root.joinpath("manifest.json").write_text(json.dumps(report_data, indent=2) + "\n")
    root.joinpath("REVIEW.md").write_text(
        "# Native Chunks first-launch Transfers screenshot review\n\n" +
        "These are real, source-exact emulator PNGs of the empty device transfer "
        "inventory at 4 viewport/font states. Each case's UIAutomator hierarchy "
        "proves the inventory heading, device search and all four navigation labels.\n\n" +
        "Review truncation, tap targets, text-scale overflow, safe insets and contrast. "
        "Populated transfer rows, Add/Details/Connections, screen-reader usability "
        "and physical Pixel evidence remain OPEN.\n\n" +
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
