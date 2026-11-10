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
# Match real UIAutomator text on EACH fresh-data emulator before claiming the
# canonical Welcome is the actual first screen. Do not silently accept the old
# local-only list, a locked/privacy screen or a spinner as first-install success.
REQUIRED_FIRST_LAUNCH_TEXT = frozenset({
    "Welcome to Chunks", "Add a torrent",
    "Transfers", "+ Add", "Connections", "Options",
})
# Exact labels observed in the original failed 320dp screenshot must remain
# disqualifying even when a Welcome heading is also visible. A heading alone
# does not prove that the unused first-launch catalog chrome is gone.
FORBIDDEN_FIRST_LAUNCH_TEXT = frozenset({
    "Your transfers will appear here",
    "This device",
    "All environments",
    "Search transfers on this device",
    "Search names or hashes",
    "Group by environment",
    "Batch tools",
    "Batch & tools",
    "Filter transfers",
    "Sort by",
})
# The filter/sort control includes mutable settings in its spoken label.
# Do not hard-code one state and accidentally accept another variation.
FORBIDDEN_FIRST_LAUNCH_PREFIXES = (
    "Choose filters & sort",
    "Showing ",
    "Filter transfers",
    "Sort by ",
)

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
        "tested_screen": "first-launch evidence-gated canonical Welcome, not an empty device-management inventory",
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
        unexpected = (FORBIDDEN_FIRST_LAUNCH_TEXT & texts) | {
            label for label in texts
            if any(label.startswith(prefix) for prefix in FORBIDDEN_FIRST_LAUNCH_PREFIXES)
        }
        if missing or unexpected:
            raise ValueError(
                f"First-launch canonical Welcome/footer not verified in {case}: "
                + f"missing={sorted(missing)}, forbidden_visible={sorted(unexpected)}"
            )
        result["accessibility_hierarchy_nodes"] = len(nodes)
        result["hierarchy_file"] = xml.name
        result["first_launch_welcome_verified"] = True
        report_data["cases"].append(result)
    root.joinpath("manifest.json").write_text(json.dumps(report_data, indent=2) + "\n")
    root.joinpath("REVIEW.md").write_text(
        "# Native Chunks first-launch Welcome screenshot review\n\n" +
        "These are real, source-exact emulator PNGs of the canonical Welcome "
        "at 4 viewport/font states. Each case's UIAutomator hierarchy proves "
        "the Welcome heading, safe Add/Connections entry points and all four "
        "footer navigation labels, while refusing the obsolete empty phone list "
        "and transfer-management controls.\n\n" +
        "Review truncation, tap targets, text-scale overflow, safe insets and contrast. "
        "Populated transfer rows, Add/Details/Connections behavior, screen-reader "
        "usability and physical Pixel evidence remain OPEN.\n\n" +
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
