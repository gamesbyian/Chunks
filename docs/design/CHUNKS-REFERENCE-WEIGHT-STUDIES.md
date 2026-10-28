# Chunks Reference Panel: five-weight engineering studies

**Status: usable experimental OTFs, NOT shipping designs, NOT separately drawn finished masters.** The original five-weight Chunks family and the installed Android ExtraBold header remain untouched. See issue #347 for outstanding design acceptance.

## What exists

The source of truth for the branded appearance is the 62 reference-traced SVG glyphs in `assets/fonts/source/reference-alphabet/`, backed by the unchanged original typography PNG and its SHA-256 provenance manifest. These assets define the existing 800-weight reference-panel letterforms. `ChunksReferencePanel-ExtraBold-Optical.otf` remains the unchanged heavy comparator.

This builder introduces **four new experimental, installable, CFF/OpenType weights** (400/500/600/700). Each weight instantiates all 62 reference SVG shapes, uses explicit contour-aware offsets from the common heavy outline, preserves each component and counter, guards slender glyphs with per-character reductions, and uses a fixed advance width so weight changes do not arbitrarily reflow text. The glyphs are recomputed at the final weight *before* the optical kerning pass.

This is a deliberately reversible **engineering scaffold**, not a substitute for independently designed lighter contours: a regular face derived from geometric offsets cannot guarantee beautiful terminals, stroke contrast, join dynamics, or counters. No art asset, approved Chunks source OTF, or Android resource is replaced.

## Exact deliverables

| Font file | Weight | Source |
|---|---:|---|
| `weight-studies/ChunksReferencePanel-Regular-Study.otf` | 400 | Reference SVGs + per-glyph offset -1.60 source px (with exceptions) |
| `weight-studies/ChunksReferencePanel-Medium-Study.otf` | 500 | Reference SVGs + offset -1.25 px |
| `weight-studies/ChunksReferencePanel-Semibold-Study.otf` | 600 | Reference SVGs + offset -0.85 px |
| `weight-studies/ChunksReferencePanel-Bold-Study.otf` | 700 | Reference SVGs + offset -0.43 px |
| `ChunksReferencePanel-ExtraBold-Optical.otf` (existing sibling) | 800 | Original source-traced masters, never edited by this workflow |

Source pixels are those of the normalized 1536×1152 reference image. Individual glyph taper multipliers are defined in `tools/reference_weight_profiles.json`, not buried in font binaries. All variants retain the 856-character source repertoire, native GSUB/GDEF/GPOS tables and native non-Latin shaping. Some unsupported accent combinations remain stylistically mixed with the Baloo 2-derived glyphs: **Unicode coverage does not establish reference-art accuracy for all 856 characters.**

Every weight receives a fresh, shape-aware 22,801-pair optical probe, with bounded adjustments for pairs involving any of the 62 rebuilt glyphs. The optimized kerning is additive to upstream GPOS. Tracking and leading are recorded as measured application-level recommendations, **not** arbitrarily inserted as uniform font-wide spacing. For completely unrestricted multilingual text the full glyph-extent line-height recommendation must be used; Latin-only UI roles may use tighter presets.

## Rebuild

```sh
python -m pip install -r tools/requirements-reference-panel-weights.txt
python tools/build_reference_panel_weight_studies.py
python -m unittest discover -s tests -p test_reference_panel_weights.py -v
python tools/render_reference_panel_weights.py
```

For a focused local build add `--weights 700` or another subset. The generated JSON report merges compatible partial builds by source/profile checksum, but only a complete four-weight build is eligible for CI acceptance. CI in `.github/workflows/reference-panel-weight-studies.yml` rebuilds all four, tests, renders the exact compiled CFFs and uploads the five-font comparison and original SIL OFL license. It commits only **experimental** study files to source feature branches, never to `main` or Android.

## Automated acceptance and limits

- Every regenerated character must remain independently addressable in the Unicode cmap and have a nonempty CFF outline.
- Shape components and internal counters must survive the offset/simplification; the reference glyph `g` contains a tiny traced artifact and components smaller than 3 source-pixel² are intentionally filtered in study builds (never removed from original SVG masters).
- Geometry must become monotonically heavier from 400 to 700, and the source master 800 remains the reference.
- The four saved OTFs must have valid weight labels/metadata, the same Unicode cmap, and intact existing layout tables. Native Devanagari shaped advances are compared with the unmodified master face.
- Shaped ink separation is sampled at 14, 24 and 48px. This is a smoke test, **not exhaustive collision proof** for every Unicode pair, every Android rasterizer or extremely long filename.
- Each weight must produce provenance-linked `.json` with 62 glyph results, raw kerning evidence, bounded tracking presets, full-glyph line metrics, SHA-256, and the exact source profile checksum. Source-only raw CFF compilation is deterministic.
- Device screenshots at normal and 200% text scale, TalkBack, the Latin-extended accent repertoire and **manual contour review** remain necessary before adopting a study font for Android.

## Remaining substantive art work

Redraw four weight masters manually or with human-checked supervised contour fitting, preserving proper stroke design rather than relying on uniform thinning. Use the profiles and render atlas as an objective starting point. Accent composites currently retain licensed Baloo 2 shapes when no matching independent SVG has been developed. Only after contour review, optical QA, and actual app rendering should any experimental weight graduate to production. See [issue #347](https://github.com/gamesbyian/Chunks/issues/347).