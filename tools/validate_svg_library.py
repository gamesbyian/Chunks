#!/usr/bin/env python3
"""Chunks reference-led SVG integrity gate (stdlib-only; no rendered screenshot claim)."""
from __future__ import annotations
import json
import pathlib
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = ROOT / "docs/design/svg"
NS = "{http://www.w3.org/2000/svg}"
FORBIDDEN = {"script", "foreignObject", "image", "iframe", "object", "embed"}
def main():
    inv = json.loads((BASE / "master-inventory.json").read_text(encoding="utf-8"))
    known = set()
    ids = set()
    pending = 0
    for asset in inv["assets"]:
        identifier = asset["id"]
        assert identifier not in ids, f"duplicate inventory id: {identifier}"
        ids.add(identifier)
        if asset["status"] == "planned":
            pending += 1
            continue
        rel = asset["path"]
        assert rel.endswith(".svg"), f"asset isn't SVG: {rel}"
        assert rel not in known, f"duplicate SVG path: {rel}"
        known.add(rel)
        path = ROOT / rel
        assert path.exists(), f"missing vector: {rel}"
        raw = path.read_text(encoding="utf-8")
        doc = ET.fromstring(raw)
        assert doc.tag == NS + "svg", f"invalid SVG namespace: {rel}"
        vb = doc.attrib.get("viewBox", "")
        nums = vb.replace(",", " ").split()
        assert len(nums) == 4, f"missing 4-part viewBox: {rel}"
        assert float(nums[2]) > 0 and float(nums[3]) > 0, f"bad viewBox size: {rel}"
        assert "data:image/" not in raw and "<!DOCTYPE" not in raw.upper(), f"embedded unsafe resource: {rel}"
        has_shapes = False
        for elem in doc.iter():
            tag = elem.tag.rsplit("}",1)[-1]
            assert tag not in FORBIDDEN, f"unsupported element {tag}: {rel}"
            for attr,val in elem.attrib.items():
                if attr.lower().endswith("href"):
                    assert not val.startswith(("http:", "https:", "data:", "//")), f"external resource: {rel}"
            if tag in {"path", "circle", "ellipse", "line", "polyline", "polygon", "rect"}:
                has_shapes = True
        assert has_shapes, f"no native vector shapes: {rel}"
    actual = {str(p.relative_to(ROOT)) for p in BASE.rglob("*.svg")}
    unlisted = actual - known
    assert not unlisted, f"SVG file(s) missing in inventory: {sorted(unlisted)}"
    assert inv["totals"]["approved"] == 1
    assert len(actual) == inv["totals"]["vectorDraft"], (len(actual), inv["totals"]["vectorDraft"])
    assert pending == inv["totals"]["planned"], (pending, inv["totals"]["planned"])
    for cat in ["icons/catalog.json", "variants-catalog.json", "ui/catalog.json", "typography/catalog.json"]:
        collection=json.loads((BASE / cat).read_text(encoding="utf-8"))
        for item in collection.get("assets", collection.get("glyphs", [])):
            path="docs/design/svg/" + item["path"]
            assert path in actual, f"catalog references missing file: {path}"
    # Gallery may be opened as a static file, so its embedded inventory must be complete.
    import re
    gallery = (BASE / "gallery.html").read_text(encoding="utf-8")
    match = re.search(r'<script type="application/json" id="svg-catalog">(.*?)</script>', gallery, re.S)
    assert match, "missing static gallery JSON catalogue"
    gallery_assets = json.loads(match.group(1))
    assert len(gallery_assets) == inv["totals"]["vectorDraft"], "gallery count does not match manifest"
    gallery_paths = [item["src"] for item in gallery_assets]
    expected_gallery = {str(p.relative_to(BASE)) for p in BASE.rglob("*.svg")}
    assert len(gallery_paths) == len(set(gallery_paths)), "duplicate gallery SVG paths"
    assert set(gallery_paths) == expected_gallery, f"gallery mismatch: missing {sorted(expected_gallery-set(gallery_paths))}, extra {sorted(set(gallery_paths)-expected_gallery)}"
    print(f"SVG library PASS: {len(actual)} vector drafts and gallery entries, 1 unchanged approved external master, {pending} planned targets.")
if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print("SVG library FAIL:", exc, file=sys.stderr)
        raise
