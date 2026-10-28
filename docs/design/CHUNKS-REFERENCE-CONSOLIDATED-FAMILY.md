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
Only 28 traced punctuation outlines are replaced, using the original
`assets/fonts/source/reference-punctuation/U+NNNN.svg` masters.

The 28 are:
`! ? . , : ; “ ” ( ) [ ] { } @ # $ % & * + - = < > / \\ "`.
The straight double quote intentionally reuses a source-curly variant.
**Apostrophe, ellipsis, untraced dash shapes and other untraced marks remain
unchanged licensed source-font fallbacks** pending separate type design.
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
- Output metrics record SHA-256 for every master source and each input/output
  face, contour topology, weight erosion and backoff, hmtx advance/bearing,
  raw pair counts, clipped corrections, app-level tracking/leading recommendations
  and small-size shaped gaps at 14/24/48/82px. A 5-row PNG is rendered from
  the **compiled OTFs**, not from a design mockup.

## Reproduce

```sh
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
