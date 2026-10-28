#!/usr/bin/env python3
"""Compare reference-traced Chunks letter silhouettes with their actual source PNG.

Uses a separate SVG renderer and Pillow, not the geometric source paths.
The scores are *pixel silhouette IoU*, not a human/final-brand approval.
Small handwritten strokes are low-res and deliberately have separate thresholds.
"""
from __future__ import annotations
from pathlib import Path
from tempfile import TemporaryDirectory
import subprocess
import sys
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
REF = ROOT / "docs/design/reference-images/03-typography-and-wordmark.png"
VECTORS = ROOT / "docs/design/svg/brand/reference-traced"
CASES = [
    ("wordmark", "wordmark-original-black.svg", (42, 5, 723, 157), (90, 90, 105), .95),
    ("tagline", "tagline-torrents-your-way.svg", (134, 154, 484, 199), (90, 90, 105), .66),
    ("sticker-lettering", "big-files-lettering-black.svg", (765, 35, 930, 180), (105, 102, 105), .63),
]

def run():
    source = Image.open(REF).convert("RGB")
    # The owner-supplied 1536x1152 original was downsampled to 1448x1086
    # when committed as the immutable reference PNG. The contour coordinates
    # are in the original 1536-space. Upscale the *read-only in-memory*
    # comparison image; never rewrite either reference.
    if source.size == (1448, 1086):
        source = source.resize((1536, 1152), Image.Resampling.BICUBIC)
    elif source.size != (1536, 1152):
        raise ValueError(f"Unexpected Chunks reference size {source.size}; manually recalibrate crops")
    print("Reference comparison coordinate space: 1536x1152 (source PNG unchanged).")
    with TemporaryDirectory(prefix="chunks-brand-trace-") as scratch:
        for name, asset, crop, threshold, minimum in CASES:
            bounds = crop
            w, h = bounds[2] - bounds[0], bounds[3] - bounds[1]
            out = Path(scratch) / f"{name}.png"
            cmd = ["rsvg-convert", "-w", str(w), "-h", str(h), "-a", "-o", str(out), str(VECTORS / asset)]
            cp = subprocess.run(cmd, capture_output=True, text=True, timeout=25)
            if cp.returncode:
                raise RuntimeError(cp.stderr.strip())
            reference_pixels = source.crop(bounds).getdata()
            render = Image.open(out).convert("RGBA")
            if render.size != (w, h):
                raise ValueError(f"Unexpected rendering size for {asset}: {render.size}")
            alpha = [px[3] > 127 for px in render.getdata()]
            original = [r < threshold[0] and g < threshold[1] and b < threshold[2] for r,g,b in reference_pixels]
            intersection = sum(a and b for a,b in zip(alpha, original))
            union = sum(a or b for a,b in zip(alpha, original))
            score = intersection / union if union else 0.0
            print(f"{name}: IoU={score:.4f}, reference={sum(original)} px, rendered={sum(alpha)} px, threshold={minimum:.2f}")
            if score < minimum:
                raise AssertionError(f"Contour regression in {asset}: IoU {score:.4f} < {minimum:.2f}")
    print(f"REFERENCE TRACE PASS: {len(CASES)} letter-silhouette comparisons.")

if __name__ == "__main__":
    try:
        run()
    except Exception as err:
        print("REFERENCE TRACE FAIL:", err, file=sys.stderr)
        raise
