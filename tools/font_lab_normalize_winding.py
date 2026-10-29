#!/usr/bin/env python3
"""Normalize *only* counter winding in editable, nonshipping SVG masters.

The reference-panel builder preserves the source Beziers in CFF. An SVG uses
fill-rule=evenodd, but CFF has a nonzero fill rule: a same-direction
interior path is therefore not a real hole. A Shapely symmetric_difference
topology check alone cannot detect this discrepancy.

The default mode is read-only and fails when a counter requires reversal.
--write reverses the exact path commands without polygonizing the curves.
No original source, ExtraBold, Android font, PR or Actions job is touched.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

from fontTools.pens.recordingPen import RecordingPen
from fontTools.pens.reverseContourPen import ReverseContourPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.svgLib.path import parse_path
from shapely.geometry import Polygon

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / "assets/fonts/source/reference-alphabet/weight-overrides"
WEIGHTS = ("400", "500", "600", "700")
SVG_D = re.compile(r'(<path\s+d=")([^"]+)(")')


def split_commands(d: str) -> list[list[tuple]]:
    rec = RecordingPen()
    parse_path(d, rec)
    contours = []
    current = []
    for op, points in rec.value:
        if op == "moveTo":
            if current:
                raise ValueError("unterminated open contour")
            current = [(op, points)]
        elif op in ("lineTo", "qCurveTo", "curveTo"):
            if not current:
                raise ValueError("path element before moveTo")
            current.append((op, points))
        elif op == "closePath":
            if not current:
                raise ValueError("closePath without contour")
            current.append((op, points))
            contours.append(current)
            current = []
        else:
            raise ValueError(f"unsupported path operation {op}")
    if current:
        raise ValueError("source outlines must be explicitly closed")
    return contours


def approximate(contour: list[tuple]) -> Polygon:
    pts = []
    at = None
    for op, data in contour:
        if op == "moveTo":
            at = data[0]
            pts = [at]
        elif op == "lineTo":
            at = data[0]
            pts.append(at)
        elif op == "qCurveTo":
            if len(data) != 2:
                raise ValueError("quadratic curve needs one control and an endpoint")
            b, end = data
            a = at
            for j in range(1, 13):
                t = j / 12
                u = 1 - t
                pts.append((u*u*a[0]+2*u*t*b[0]+t*t*end[0],
                            u*u*a[1]+2*u*t*b[1]+t*t*end[1]))
            at = end
        elif op == "curveTo":
            if len(data) != 3:
                raise ValueError("cubic curve needs two controls and an endpoint")
            b, c, end = data
            a = at
            for j in range(1, 21):
                t = j / 20
                u = 1 - t
                pts.append((u**3*a[0]+3*u*u*t*b[0]+3*u*t*t*c[0]+t**3*end[0],
                            u**3*a[1]+3*u*u*t*b[1]+3*u*t*t*c[1]+t**3*end[1]))
            at = end
    if len(pts) < 3:
        raise ValueError("degenerate contour")
    return Polygon(pts)


def winding(p: Polygon) -> int:
    xy = list(p.exterior.coords)
    signed = sum(xy[i][0]*xy[i+1][1] - xy[i+1][0]*xy[i][1]
                 for i in range(len(xy)-1))
    if abs(signed) < 1e-7:
        raise ValueError("contour has zero oriented area")
    return 1 if signed > 0 else -1


def normalize(d: str) -> tuple[str, int]:
    contours = split_commands(d)
    if not contours:
        raise ValueError("empty SVG master")
    polys = [approximate(c) for c in contours]
    for p in polys:
        if not p.is_valid or p.area <= 0.1:
            raise ValueError("invalid or self-intersecting raw contour")
    signs = [winding(p) for p in polys]
    changed = 0
    out = []
    for i, ops in enumerate(contours):
        # The outermost containing contour is the root of this ink component.
        # For each nested level, winding alternates. Detached dots and accents
        # are separate root components, not counters.
        parents = [j for j, p in enumerate(polys)
                   if j != i and p.area > polys[i].area
                   and p.contains(polys[i].representative_point())]
        if parents:
            root = max(parents, key=lambda j: polys[j].area)
            target = signs[root] * (-1 if len(parents) % 2 else 1)
        else:
            target = signs[i]
        sink = SVGPathPen(None)
        pen = ReverseContourPen(sink) if signs[i] != target else sink
        for op, args in ops:
            getattr(pen, op)(*args)
        out.append(sink.getCommands())
        if signs[i] != target:
            changed += 1
    if changed == 0:
        return d, 0
    return " ".join(out), changed


def run(write: bool = False) -> tuple[int, int, list[str]]:
    changed_files = 0
    reversed_contours = 0
    errors = []
    paths = [p for weight in WEIGHTS
             for p in sorted((SOURCES / weight).glob("U+*.svg"))]
    if len(paths) != 248:
        errors.append(f"Expected 248 edit masters, found {len(paths)}")
    for path in paths:
        try:
            text = path.read_text(encoding="utf-8")
            match = SVG_D.search(text)
            if not match:
                raise ValueError("requires one <path d> attribute")
            result, n = normalize(match.group(2))
            if not n:
                continue
            changed_files += 1
            reversed_contours += n
            if write:
                path.write_text(
                    text[:match.start(2)] + result + text[match.end(2):],
                    encoding="utf-8",
                )
            else:
                errors.append(f"{path.relative_to(ROOT)}: "
                              f"{n} CFF counters have wrong winding")
        except Exception as e:
            errors.append(f"{path.relative_to(ROOT)}: {e}")
    return changed_files, reversed_contours, errors


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true",
                        help="normalize only the editable source SVGs")
    args = parser.parse_args()
    changed, count, errors = run(args.write)
    print(f"Font lab winding: {changed} files, {count} counter paths "
          f"{'normalized' if args.write else 'needing normalization'}")
    for item in errors:
        print(" -", item, file=sys.stderr)
    if errors:
        sys.exit(1)
