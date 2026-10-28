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

Source pixels are those of the normalized 1536×1152 reference image. Individual glyph taper multipliers are defined in `tools/reference_weight_profiles.json`, not buried in font binaries. **Weight-specific hand-edited SVG masters take precedence** when present at `assets/fonts/source/reference-alphabet/weight-overrides/<weight>/U+XXXX.svg` (for example `400/U+0041.svg` for A). Each override must use the original normalized glyph coordinate envelope and retain the same contour/counter topology; the builder validates its area/bounds, records the SVG hash, and bypasses automatic offsets only for that glyph. This lets designers replace one study character at a time without breaking the other 61 or any accepted font. Empty override directories are perfectly valid. All variants retain the 856-character source repertoire, native GSUB/GDEF/GPOS tables and native non-Latin shaping. Some unsupported accent combinations remain stylistically mixed with the Baloo 2-derived glyphs: **Unicode coverage does not establish reference-art accuracy for all 856 characters.**

Every weight receives a fresh, shape-aware 22,801-pair optical probe, with bounded adjustments for pairs involving any of the 62 rebuilt glyphs. The optimized kerning is additive to upstream GPOS. Tracking and leading are recorded as measured application-level recommendations, **not** arbitrarily inserted as uniform font-wide spacing. For completely unrestricted multilingual text the full glyph-extent line-height recommendation must be used; Latin-only UI roles may use tighter presets.

## Rebuild

```sh
python -m pip install -r tools/requirements-reference-panel-weights.txt
python tools/build_reference_panel_weight_studies.py
python -m unittest discover -s tests -p test_reference_panel_weights.py -v
python tools/render_reference_panel_weights.py
```

For a focused local build add `--weights 700` or another subset. The generated JSON report merges compatible partial builds only when source, profile **and override-content checksums** match, but only a complete four-weight build is eligible for CI acceptance. CI in `.github/workflows/reference-panel-weight-studies.yml` rebuilds all four, tests, renders the exact compiled CFFs and uploads the five-font comparison and original SIL OFL license. It commits only **experimental** study files to source feature branches, never to `main` or Android.

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
## Native-curve import for genuine edited weight masters

The default Regular–Bold offset studies still use contour-derived polygon geometry. A designer-provided weight-specific SVG override now takes a distinct route: the builder validates topology, ink-area ratio, source-coordinate bounds and absence of unprocessed SVG transforms using sampled geometry, then **passes the original SVG path's quadratic/cubic Bézier instructions directly to the CFF compilation pen**. No Shapely sampled line segments replace the editor's curve handles. Exact curve-based left side bearings are measured from the path bounds, and provenance explicitly distinguishes `native-bezier-curves` from `sampled-offset-polygons` in the per-glyph JSON. If an SVG contains nested transforms, it must be flattened intentionally by its author, rather than being silently ignored.

The source parser now also accepts cubic SVG path commands for geometric safety checks, so native editors do not have to convert their curves to quadratics or polygon outlines before submitting a draft. Regression tests prove that an overridden capital A compiles to `curveTo` operations while its non-overridden B neighbor stays a provisional offset study, and reject unflattened transforms. **This is an acceptance path for future separately drawn Regular/Medium/Semibold/Bold source art, not evidence that such art has already been completed or visually approved.**

### Verified native-curve acceptance-path evidence

[#431](https://github.com/gamesbyian/Chunks/pull/431) squash-merged at `1fb0aa02f93d844022bd8750f13829354d0bd384`; exact source head `eef7a1b0e44ba3ca8277bb946a6579481e1a7f5c` passed **eight** checks, including [weight-study source compilation and tests](https://github.com/gamesbyian/Chunks/actions/runs/40644103799) and the other cross-family audits. The test converts the reference A curve geometry to cubic SVG commands through a standard fontTools editor pen, imports it as a **400-only manual master**, and asserts native CFF `curveTo` rather than polygonal `lineTo`, while a neighboring unedited B remains a provisional polygon derivation. The checked-in four-weight metrics now record `sampled-offset-polygons` for default automatic glyphs. This verifies the **pipeline**, not the existence or approval of newly designed 62-glyph lighter source sets.

## Full compiled-font glyph-by-glyph editorial grids

The existing multiweight phrase specimens can hide single-letter defects. `tools/render_reference_glyph_grid.py` now produces **four real OpenType/CFF image matrices** at the accepted **14, 24, 48 and 82 pixel** review sizes. Each page shows **all 62 source characters** (A–Z, a–z, 0–9) across **all five fonts**: the four Regular/Medium/Semibold/Bold study weights and preserved ExtraBold optical comparator. Glyphs share drawn horizontal baselines; rows carry Unicode codepoints, and columns are explicitly labelled by weight. This makes counters, joins, tapers, terminals, italic-like overhangs and apparent weight jumps inspectable at character granularity instead of relying on only a few whole words.

Rebuild after the four study weights exist:

```sh
python tools/render_reference_glyph_grid.py
python -m unittest discover -s tests -p test_reference_glyph_grid.py -v
```

Outputs are `reference-panel-glyph-grid-{14,24,48,82}px.png` plus `reference-panel-glyph-grid-metrics.json` with exact input font SHA-256, true CFF/856-cmap assertions, all glyph row IDs, page dimensions and output SHA-256. CI uploads each page; it does not copy any image or experimental font into Android. Rendering native compiled fonts is necessary inspection evidence, not independent proof of artistic quality, font legibility, contrast or TalkBack behavior. Disposition and per-glyph design approval remain on [#347](https://github.com/gamesbyian/Chunks/issues/347).
