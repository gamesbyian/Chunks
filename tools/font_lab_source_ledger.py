#!/usr/bin/env python3
"""Read-only source provenance and outstanding font lab authorship inventory.

Explicit overrides are editable source candidates, not proof of independent
manual design, visual approval, Android acceptance or production promotion.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "assets/fonts/source/reference-alphabet"
WEIGHTS = (400, 500, 600, 700)

def inspect() -> dict:
    manifest = json.loads((SRC / "manifest.json").read_text())
    basic = sorted(manifest["glyphs"], key=ord)
    if len(basic) != 62:
        raise AssertionError("Expected 62 original source glyphs")
    detail = {}
    full = []
    incomplete = []
    source_count = 0
    for ch in basic:
        cp = f"U+{ord(ch):04X}.svg"
        by_weight = {}
        for weight in WEIGHTS:
            path = SRC / "weight-overrides" / str(weight) / cp
            if path.is_file():
                raw = path.read_bytes()
                root = ET.fromstring(raw)
                paths = [node for node in root.iter()
                         if node.tag.endswith("path")]
                if len(paths) != 1 or not paths[0].attrib.get("d"):
                    raise AssertionError(f"{path}: expected one editable compound path")
                by_weight[str(weight)] = {
                    "sha256": hashlib.sha256(raw).hexdigest(),
                    "source": path.relative_to(ROOT).as_posix(),
                    "source_bytes": len(raw),
                }
                source_count += 1
        detail[ch] = by_weight
        (full if len(by_weight) == 4 else incomplete).append(ch)
    if source_count > 248:
        raise AssertionError("Unexpected duplicate master inventory")
    return {
        "status": "NONSHIPPING; optical and human editorial acceptance outstanding",
        "original_reference_glyphs": len(basic),
        "required_lighter_weight_masters": 62 * 4,
        "explicit_editable_source_masters": source_count,
        "glyphs_with_all_four_editable_masters": len(full),
        "glyphs_needing_one_or_more_masters": len(incomplete),
        "complete_four_weight_glyphs": full,
        "missing_or_partial_glyphs": incomplete,
        "masters": detail,
        "not_claimed": [
            "all contours independently drawn",
            "approved reference fidelity",
            "Android shipping font",
            "Compose 200% and TalkBack user acceptance",
        ],
    }

if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--out", type=Path)
    p.add_argument("--require-full", action="store_true",
                   help="Fail when any of the original 62 glyphs lacks four masters")
    args = p.parse_args()
    result = inspect()
    content = json.dumps(result, indent=2, ensure_ascii=False) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(content)
    else:
        print(content)
    if args.require_full and result["glyphs_needing_one_or_more_masters"]:
        p.exit(1, "Font master inventory incomplete: "
               + "".join(result["missing_or_partial_glyphs"]) + "\n")
