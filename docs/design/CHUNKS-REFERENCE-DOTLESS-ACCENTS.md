# Chunks Reference Panel: dotless i/j accent completion experiment

**Status: tested, separately named five-weight CFF/OTF font experiment, not installed in Android.** The approved Chunks Regular/Medium/Semibold/Bold/ExtraBold family, SVG wordmarks, Android ExtraBold and preceding experimental variants are untouched. Preserve source licensing (`assets/fonts/source/OFL.txt`).

## The missing letters

The 62 reference-traced letters contain native Chunks-style lowercase **i** and **j**, each with a detached dot. The earlier accented family conservatively left all their accented variants in the licensed Baloo 2 style to avoid stacking a native dot under an accent. This experiment identifies the original custom letter's two contours, removes only the **dot** (keeping the complete stem / j descender), and attaches the native licensed accent contours from the original precomposed source font. Marks are horizontally centered on the actual custom stem and vertically separated by 28 font units. Enclosed counters, mark components and the original Unicode names/mappings are retained.

Exactly **12 above-accent forms in each of five weights** are rebuilt: `ì í î ï ĩ ī ĭ ǐ ȉ ȋ ỉ ĵ`. Forms with a mark below the letter or an ogonek, such as `į` and `ị`, are expressly **not** converted because their attached accents require separate editorial drawing. Any missing or integrated accent that is not safely separable retains the source font's original outline. The resulting experimental font is independently installable under family name **Chunks Reference Panel Dotless**.

## Full contextual optical recalculation

The existing, bounded accent-context kerning optimizer runs on the final glyph shapes **after** dotless construction. It measures approximately **96,000** adjusted pairs per weight between the 1,228 constructed accented glyphs and Latin alphabet/numerals. Every bare–bare pair, non-Latin glyph, original GSUB/GDEF layout feature, and unchanged source CFF charstring is preserved. Each rebuilt accented glyph's standalone advance equals its custom bare `i`/`j` base; eight shaped-neighbor contexts are independently checked at 48px.

An additional small-glyph stress pass at real **14/24/48/82px** detected one particularly tight `ǐȉ` combination in ExtraBold and adds a **29-unit positive GPOS PairPos XAdvance** to that exact pair, after the default bounded kerning is applied. Its tightest measured ink clearance changes from **−0.953px** to **+0.438px** at 48px; at 14px from **−0.031px** to **+0.375px**. Other glyph pairs are unaffected. This is a constrained specific correction, not an indiscriminate font-wide tracking change. These are Pillow/libraqm smoke tests, not proof of every possible collision or Android text engine.

## Fully reproducible build and tests

```sh
python -m pip install -r tools/requirements-chunks-font.txt
for weight in 400 500 600 700 800; do
  python tools/build_reference_dotless_accents.py --weights "$weight"
done
python -m unittest discover -s tests -p test_reference_dotless_accents.py -v
python tools/render_reference_dotless.py
```

Output: five `assets/fonts/experimental/dotless-kerned/ChunksReferencePanel-{weight}-DotlessKerned.otf` files and `reference-dotless-kerned-metrics.json` with source/output SHA-256, 12 changed charforms, relative positioning and per-pair precision corrections. The workflow `.github/workflows/reference-panel-dotless-kerned.yml` rebuilds, tests and publishes exact compiled OTFs, the reference image and upstream OFL. Partial builds merge metrics only for byte-identical input/output files. CI never writes into shipping `assets/fonts/chunks/` or Android resources.

## Remaining acceptance before potential promotion

- Finish distinct hand-drawn letter shapes in Regular/Medium/Semibold/Bold rather than relying solely on thinning the heavy reference art.
- Draw the **below-mark i/j variants** with preserved dot/ogonek, plus integrated accents and stacking diacritics that lack safe native separation.
- Review all punctuation, cursive/script coverage, terminal shapes, counters, and overhang at realistic Android Compose UI widths. Test 200% user font scaling, TalkBack and filename ellipsizing.
- Do not assume 856 Unicode mappings mean all 856 characters share faithful custom Chunks artwork. Source licensing and attribution remain required.

See [issue #347](https://github.com/gamesbyian/Chunks/issues/347) for the complete visual/engineering acceptance gate.
