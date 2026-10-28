# Chunks Reference Panel: consolidated five-weight punctuation candidate

**Status:** experimental, independently installable CFF/OpenType only. **Not** the
Android shipping font, not approved as a finished typeface, and not a substitute
for independently drawn lighter masters. Tracks [issue #347](https://github.com/gamesbyian/Chunks/issues/347).

## Purpose

Previous five-weight dotless-accent candidates and the 28 original punctuation
SVG contours were separate. The new `tools/build_reference_panel_consolidated.py`
combines them into **one separately named, reversible experimental family**:
`Chunks Reference Panel Consolidated` Regular, Medium, Semibold, Bold and ExtraBold.

Each input is the matching **existing** `ChunksReferencePanel-*-DotlessKerned.otf`
with its native reference letters/numerals, reconstructed Latin accents,
14 specially handled i/j forms, bounded contextual kern and source shaping.
The original 28 traced punctuation outlines are retained, plus four explicitly
**source-derived** supplementary masters, generated separately from those
originals. The originals remain in
`assets/fonts/source/reference-punctuation/U+NNNN.svg` and derived alternatives
in `assets/fonts/source/reference-punctuation/supplemental/U+NNNN.svg`.

The 28 are:
`! ? . , : ; “ ” ( ) [ ] { } @ # $ % & * + - = < > / \\ "`.
The straight double quote intentionally reuses a source-curly variant.
The normal apostrophe U+0027 and right single quote U+2019 use one
**rightmost** source U+201D component; left single U+2018 uses the **leftmost**
source U+201C component. Ellipsis U+2026 repeats the original period's contour
three times, with a fixed source-grid gap. These four are named derivative
experiments with their own SVGs and hash provenance. **Other untraced punctuation**
remains the licensed source-font fallback, pending deliberate design.
No additional Unicode mapping is invented.

## Geometry and optical policy

- Reference row bottom **568** in the immutable 1536 × 1152 source artwork
  is the punctuation baseline; 19.2 units per source pixel matches the
  reference alphabet pipeline.
- Geometry is read as even-odd compound shapes with contour/counter topology
  preserved. Regular through Bold use an intentionally conservative 42% of
  the letter-weight erosion values and per-component topology backoff;
  **these are still provisional contour derivatives**, not redrawn masters.
  ExtraBold keeps the original source contours.
- Side bearings and advances are determined from each final contour and its
  source bounding-box width. No texture, bitmap or SVG table is embedded
  in the CFF font.
- Once the **new punctuation outlines are compiled**, Pillow/libraqm
  measures the final actual-font pair gaps. Additional GPOS pairs are
  restricted to contexts containing at least one replaced punctuation glyph,
  bounded by 0.072em. Original native shaping, preexisting Latin/diacritic
  positioning, accent kerning and non-punctuation glyph outlines are retained.
- Output metrics record SHA-256 for every master source, including generated\n  supplements, and each input/output
  face, contour topology, weight erosion and backoff, hmtx advance/bearing,
  raw pair counts, clipped corrections, app-level tracking/leading recommendations
  and small-size shaped gaps at 14/24/48/82px. A 5-row PNG is rendered from
  the **compiled OTFs**, not from a design mockup.

## Reproduce

```sh
python tools/build_reference_punctuation_supplements.py
python tools/build_reference_punctuation_supplements.py --check
python -m pip install -r tools/requirements-reference-panel-weights.txt
python tools/build_reference_panel_consolidated.py
python -m unittest discover -s tests -p test_reference_panel_consolidated.py -v
python tools/render_reference_panel_consolidated.py
```

Outputs: five `assets/fonts/experimental/consolidated/ChunksReferencePanel-{Style}-Consolidated.otf`
files, their `reference-panel-consolidated-metrics.json`, and
`reference-panel-consolidated-five-weights.png`. You can pass
`--weights 400 800` for a partial build; the report retains previously
built faces only when both source and output checksums still match.
CI builds and tests all five in one invocation. Source OFL 1.1 attribution
remains in `assets/fonts/source/OFL.txt`.

## Not yet accepted

1. Independently redraw and editorially inspect all 62 letters/numerals
   in four lighter weights. Automated erosion is a scaffold, including for
   this experimental punctuation stage.
2. Develop a full style-coherent apostrophe, ellipsis and extended mark system;
   manually review quote direction, reference-set punctuation joins, counters,
   vertical alignment, non-Latin glyph consistency and any thin-component
   reduction compromises.
3. Examine the actual-font 14, 24, 48 and 82px atlas, long filenames, mixed
   case, punctuation and accent collisions, as well as per-weight color/contrast.
   Clipped GPOS corrections are flags for editorial work, not automatic signoff.
4. Run Android normal and 200% text-size screenshots, wrapping/truncation,
   chip and metadata readability, TalkBack and non-Latin layout acceptance.
   **No automatic change to** `app/src/main/res/font/chunks_extrabold.otf`,
   the five approved base `Chunks-*.otf` files or wordmark SVGs.

The existing punctuation SVG sources and all preceding experimental OTF families
remain selectable variants, not overwritten or discarded.

## Initial compiled five-face optical evidence (2026-10-28)

[Source-head CI 40640492388](https://github.com/gamesbyian/Chunks/actions/runs/40640492388) and [push CI 40640487275](https://github.com/gamesbyian/Chunks/actions/runs/40640487275) passed all four compiled CFF tests, deterministic raw build, full Unicode parity, native CFF family-name separation, unchanged nonpunctuation glyph/advance/shaping samples, and actual specimen generation. The five OTFs and per-weight JSON are retained on PR [#421](https://github.com/gamesbyian/Chunks/pull/421). The bot artifact commit was `08e9a2b6ee6c9c78b5d745e15a0bb9c9703ce0ff`, followed by a documentation reconciliation; require a final exact-head run before merging.

| Face | Additional punctuation GPOS pairs | Corrections clipped by safety bound | Minimum sampled ink gap at 14px |
| --- | ---: | ---: | ---: |
| Regular 400 | 7,270 | 4,461 | 0.8281px |
| Medium 500 | 7,363 | 4,380 | 0.7850px |
| Semibold 600 | 7,670 | 4,476 | 0.6250px |
| Bold 700 | 7,714 | 4,355 | 0.7188px |
| ExtraBold 800 | 7,770 | 4,234 | 0.6094px |

All sampled gaps were positive at 14/24/48/82px, but **the large number of clipped adjustments requires focused optical design review**. A safety-bound correction is not necessarily an aesthetically optimal one. These are shaped Pillow/libraqm raster measurements and cannot certify exhaustive Unicode collision clearance, original-reference fidelity, Android system text rasterization or user acceptance. Preserve the unclipped raw requested deltas in a future expanded metrics iteration for prioritized editorial correction.

## 2026-10-28 source-derived punctuation continuation

The four newly added supplementary glyphs are explicitly **inferred designs**, not previously recovered shapes in the original source image. They close ordinary UI text gaps without replacing either the 28 source-traced SVGs or earlier candidate fonts. The generator makes deterministic editable SVGs and a source-hash manifest. It fails if the original reference shape set changes without deliberate regeneration. The consolidated OTF builder uses all **32** punctuation shapes, remeasures punctuation-adjacent kerning against the final outlines, and keeps 856 mappings and existing complex-script shaping. CI regenerates, asserts reproducibility, tests all five genuine CFFs, and uploads the four new source SVGs alongside OTFs.

**Editorial gate:** single-quote direction, apostrophe scale and ellipsis rhythm need human font-sheet review at 14/24/48/82px and actual Android text rendering. These source-derived drafts are preferable to inconsistent fallback for a cohesive prototype, but they are not a final proof of correct typography.

### Verified supplementary-glyph build and optical outliers

[CI run 40642509834](https://github.com/gamesbyian/Chunks/actions/runs/40642509834) passed the four-SVG source generation/check, five native CFF compilations, five-weight derived glyph and inherited-shaping tests, and actual-font specimen publishing. Generated OTFs/masters were checked into the feature branch; later source changes require a fresh exact-head check. After adding four new punctuation characters, the optical pair audit measured **8,368 / 8,474 / 8,790 / 8,863 / 8,925** additional punctuation-containing GPOS corrections for Regular through ExtraBold. Of these, **5,399 / 5,306 / 5,382 / 5,243 / 5,088** reached the configured ±72-unit cap. These counts are diagnostics only, not proof of good optical design. All sampled 14/24/48/82px ink gaps remained positive; the global minimum sampled at 14px was 0.6094px in ExtraBold. A follow-up needs per-pair unclipped deltas, intentional punctuation-role rules, and a genuine editorial review of the worst cases, not blanket relaxation of the cap.

## Full bounded optical correction evidence (follow-up experiment)

Earlier metrics recorded only total clipped pair counts, obscuring which punctuation joins require design attention. The consolidated builder now saves a deterministic, UTF-8 JSON **gzip sidecar per weight** with every requested adjustment exceeding the ±72-font-unit safety cap. Each pair includes the Unicode codepoints, raw requested XAdvance in font units, safe applied XAdvance, unapplied remainder, observed shaped 126px 18th-percentile ink gap, estimated post-adjustment clearance and optical target. Worst residuals are sorted deterministically, and the leading twelve cases are echoed in the compact JSON metrics; full sidecar SHA-256 and compressed/uncompressed sizes are recorded.

`tools/render_reference_punctuation_clamps.py` reads those gzip reports and actual consolidated OTF files, and produces `reference-punctuation-clamp-review.png` with the most severely clipped punctuation pairs across all five weights. The complete reports are retained in `assets/fonts/experimental/consolidated/reference-punctuation-clipped-{Style}.json.gz`; CI artifacts include these files. No codepoint mapping, charstring, font advance or requested design safety bound is changed merely by collecting diagnostics. Future hand-edited mark geometry and kern exceptions must be justified against these concrete cases, then rerun all glyph/shaping and Android tests. Neither a negative desired correction nor a high clip count alone constitutes a detected collision or authorizes bypassing the bounds.

To inspect one report using the standard library:

```sh
python -c 'import gzip,json; p="assets/fonts/experimental/consolidated/reference-punctuation-clipped-Regular.json.gz"; r=json.loads(gzip.decompress(open(p,"rb").read())); print(*r["pairs"][:20],sep="\\n")'
```

### First exact compiled measurement receipt

[CI run 40643072801](https://github.com/gamesbyian/Chunks/actions/runs/40643072801) passed: native five-weight CFF build, complete compressed clipped-pair receipts, deterministic full-count tests, actual-font stress/specimen atlases, and source/OTF artifact publication. The recorded outlier counts are **5,399 Regular; 5,306 Medium; 5,382 Semibold; 5,243 Bold; 5,088 ExtraBold**. Illustrative highest unresolved requests include `Ç*` and `p…` in Regular, with desired corrections around −435/−426 font units, respectively; only −72 units are applied. These are often punctuation optical/semantic exceptions, not permissions to expand kerning bounds. Revalidate against the final source-head CI after generated receipts are committed, and use the atlas to rank manual design changes.
