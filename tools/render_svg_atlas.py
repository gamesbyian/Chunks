#!/usr/bin/env python3
"""Render every committed Chunks SVG draft at 32/160px, creating browsable proof.

Requires `rsvg-convert` (apt package librsvg2-bin). Standard library otherwise.
This is renderer compatibility evidence, not reference-fidelity certification.
"""
from __future__ import annotations
import argparse
import html
import json
from pathlib import Path
import shutil
import struct
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "docs/design/svg/master-inventory.json"
PNG_MAGIC = b"\x89PNG\r\n\x1a\n"

def render(path: Path, target: Path, size: int):
    target.parent.mkdir(parents=True, exist_ok=True)
    cmd = ["rsvg-convert", "--format", "png", "--keep-aspect-ratio",
           "--width", str(size), "--height", str(size), "--output", str(target), str(path)]
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=30, check=False)
    if proc.returncode:
        raise RuntimeError(f"{path.relative_to(ROOT)}: {proc.stderr.strip()}")
    data = target.read_bytes()
    if not data.startswith(PNG_MAGIC) or len(data) < 33:
        raise ValueError(f"Renderer did not produce a valid PNG: {target}")
    width, height = struct.unpack(">II", data[16:24])
    if width < 1 or height < 1 or width > size or height > size:
        raise ValueError(f"Unexpected PNG dimensions {width}x{height}: {target}")
    if len(data) > 5_000_000:
        raise ValueError(f"Unexpected oversized PNG output: {target}")
    return width, height

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", default="generated/svg-atlas")
    parser.add_argument("--sizes", default="32,160", help="comma separated output dimensions")
    args = parser.parse_args()
    if not shutil.which("rsvg-convert"):
        raise SystemExit("Missing rsvg-convert; install librsvg2-bin")
    sizes = [int(x) for x in args.sizes.split(",")]
    if any(x < 8 or x > 512 for x in sizes):
        raise SystemExit("Only render sizes between 8 and 512")
    inv = json.loads(MANIFEST.read_text(encoding="utf-8"))
    assets = [a for a in inv["assets"] if a["status"] == "vector-draft"]
    out = Path(args.out).resolve()
    cards = []
    for asset in assets:
        src = (ROOT / asset["path"]).resolve()
        if not src.is_relative_to(ROOT) or not src.exists():
            raise ValueError(f"Unsafe or missing source: {asset['path']}")
        images = []
        for size in sizes:
            target = out / "renders" / str(size) / Path(asset["path"].replace("docs/design/svg/", "")).with_suffix(".png")
            width, height = render(src, target, size)
            url = target.relative_to(out).as_posix()
            images.append(f'<figure><img loading="lazy" src="{html.escape(url, quote=True)}" alt="{html.escape(asset["id"], quote=True)}" width="{width}" height="{height}"><figcaption>{size}px</figcaption></figure>')
        cards.append(f'<article><strong>{html.escape(asset["id"])}</strong><div class="pair">{"".join(images)}</div><small>{html.escape(asset.get("source", ""))}</small></article>')
    css = """body{font:14px system-ui,sans-serif;background:#0B0F17;color:#F8FAFC;margin:22px}
    h1{font-size:25px}p{color:#C1C9D7}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(240px,1fr));gap:12px}
    article{padding:14px;background:#1A1F2B;border-radius:12px;overflow-wrap:anywhere}
    .pair{display:flex;align-items:center;justify-content:space-evenly;gap:8px;min-height:180px}
    figure{margin:0;text-align:center;background:#F8FAFC;padding:7px;border-radius:6px}
    img{display:block;max-width:100%;object-fit:contain}figcaption{font-size:11px;color:#0B0F17}
    small{color:#ADB7C6}"""
    doc = ('<!doctype html><html lang="en"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width,initial-scale=1">'
        '<title>Chunks SVG render proof</title><style>'+css+'</style></head><body>'
        f'<h1>Chunks SVG renderer compatibility atlas</h1><p>{len(assets)} SVG drafts rendered at {sizes}; '
        'this verifies decoding and visual inspection availability, not pixel-exact artwork fidelity.</p>'
        '<main class="grid">'+"\n".join(cards)+'</main></body></html>')
    out.mkdir(parents=True, exist_ok=True)
    (out / "index.html").write_text(doc, encoding="utf-8")
    (out / "manifest.json").write_text(json.dumps({
        "count": len(assets), "sizes": sizes, "renderer": "rsvg-convert",
        "scope": "source vector rendering; no visual or Android acceptance",
    }, indent=2)+"\n", encoding="utf-8")
    print(f"RENDER PASS: {len(assets)} SVGs at {len(sizes)} sizes; atlas {out / 'index.html'}.")

if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print("SVG RENDER FAIL:", exc, file=sys.stderr)
        raise
