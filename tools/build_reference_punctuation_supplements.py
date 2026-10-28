#!/usr/bin/env python3
"""Derive four reversible punctuation SVG masters from *existing* traced art.

The original immutable 28 reference SVGs remain untouched. These are explicit,
identified derivative designs, not claims of previously unseen raster glyphs.
Apostrophe / right-single-quote: right component of U+201D.
Left-single-quote: left component of U+201C.
Ellipsis: three reference U+002E periods, spaced by a fixed source-grid gap.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import xml.etree.ElementTree as ET

from fontTools.svgLib.path import parse_path
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.recordingPen import RecordingPen, replayRecording
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen

ORIGINS = {
    "'": ('”', 'rightmost'),
    '‘': ('“', 'leftmost'),
    '’': ('”', 'rightmost'),
    '…': ('.', 'three-periods'),
}
PERIOD_STEP = 13.0  # 8px source grid period + 5px independent punctuation air
STATUS = 'NONSHIPPING derived punctuation SVG drafts, not original source contours'


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def paths(source: Path):
    root = ET.fromstring(source.read_text())
    shapes = [el.attrib['d'] for el in root.iter() if el.tag.endswith('path')]
    if len(shapes) != 1:
        raise AssertionError(f'{source}: expected exactly one compound source path')
    recording = RecordingPen()
    parse_path(shapes[0], recording)
    segments = []
    current = []
    for command in recording.value:
        if command[0] == 'moveTo' and current:
            segments.append(current)
            current = []
        current.append(command)
        if command[0] in ('closePath', 'endPath'):
            segments.append(current)
            current = []
    if current:
        segments.append(current)
    if not segments:
        raise AssertionError(f'{source}: no usable contours')
    return segments


def bounds(commands):
    pen = BoundsPen(None)
    replayRecording(commands, pen)
    if pen.bounds is None:
        raise AssertionError('Empty reference contour')
    return pen.bounds


def transform(commands, dx=0, dy=0):
    output = RecordingPen()
    replayRecording(commands, TransformPen(output, (1, 0, 0, 1, dx, dy)))
    return output.value


def create_svg(commands, width, height, codepoint):
    pen = SVGPathPen(None)
    replayRecording(commands, pen)
    path_data = pen.getCommands()
    if not path_data:
        raise AssertionError(f'U+{codepoint:04X}: empty SVG path')
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" '
        f'role="img" aria-label="Chunks derived punctuation U+{codepoint:04X}">\\n'
        f'<title>Source-derived experimental U+{codepoint:04X} glyph</title>\\n'
        f'<g fill="#0B0F17" fill-rule="evenodd"><path d="{path_data}"/></g>\\n'
        f'</svg>\\n'
    )


def generate(source: Path, out: Path, check=False):
    master = json.loads((source / 'manifest.json').read_text())
    built = {}
    svg_files = {}
    for char, (parent, strategy) in ORIGINS.items():
        file = source / f'U+{ord(parent):04X}.svg'
        components = paths(file)
        item = master['glyphs'][parent]
        if strategy in ('rightmost', 'leftmost'):
            if len(components) != 2:
                raise AssertionError(f'{char}: expected two independent quote components')
            components.sort(key=lambda contour: (bounds(contour)[0] + bounds(contour)[2]) / 2)
            selected = components[-1] if strategy == 'rightmost' else components[0]
            selected_bounds = bounds(selected)
            selected = transform(selected, dx=1 - selected_bounds[0])
        else:
            if len(components) != 1:
                raise AssertionError('Period must be exactly one source contour')
            selected = []
            for index in range(3):
                selected += transform(components[0], dx=index * PERIOD_STEP)
        rect = bounds(selected)
        width = int(math.ceil(rect[2] + 1))
        height = item['height']
        if rect[0] < 0 or rect[1] < 0 or rect[2] > width or rect[3] > height:
            raise AssertionError(f'{char}: outside explicit source grid')
        cp = f'U+{ord(char):04X}'
        svg_files[f'{cp}.svg'] = create_svg(selected, width, height, ord(char))
        built[char] = {
            'source_glyph': parent,
            'source_svg_sha256': sha(file),
            'source_strategy': strategy,
            'source_bbox': [0, item['source_bbox'][1], width,
                            item['source_bbox'][1] + height],
            'width': width,
            'height': height,
            'reference_baseline_row': 568,
            'derived_from_original_reference': True,
        }
    manifest = {
        'status': STATUS,
        'source_original_manifest_sha256': sha(source / 'manifest.json'),
        'source_image_sha256': master['source_sha256'],
        'glyph_count': len(built),
        'period_step_reference_px': PERIOD_STEP,
        'glyphs': built,
    }
    svg_files['manifest.json'] = json.dumps(manifest, ensure_ascii=False, indent=2) + '\\n'
    if check:
        for filename, expected in svg_files.items():
            target = out / filename
            if not target.exists() or target.read_text() != expected:
                raise AssertionError(f'Derived punctuation stale: {target}; run generator')
    else:
        out.mkdir(parents=True, exist_ok=True)
        for filename, content in svg_files.items():
            (out / filename).write_text(content)
    return manifest


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--source', type=Path,
                   default=Path('assets/fonts/source/reference-punctuation'))
    p.add_argument('--out', type=Path,
                   default=Path('assets/fonts/source/reference-punctuation/supplemental'))
    p.add_argument('--check', action='store_true')
    a = p.parse_args()
    result = generate(a.source, a.out, a.check)
    print(f"{'Verified' if a.check else 'Generated'} {result['glyph_count']} "
          'source-derived punctuation SVGs')
