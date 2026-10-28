# Chunks OpenType: reference-traced contour iteration 2

**Source of truth:** Six raster-traced editable glyph silhouettes taken from the owner-supplied `03-typography-and-wordmark.png` and originally introduced as independent SVGs on the `feat/svg-characters-and-ui-variants-20261027` visual-assets branch (PR #315). The combined path copy in `assets/fonts/source/reference-traced-wordmark.svg` is pinned for reproducible font builds. It is **not** a human-approved perfect trace. SVG asset variants elsewhere in the repo remain independent; the reference PNG is unchanged.

**Five real OTF weights:** The builder instantiates the licensed OFL Baloo 2 variable master as a Unicode-complete foundation (including native OpenType shaping for the scripts that master supports), converts its quadratic outlines to cubic CFF, replaces exactly six glyph outlines (`C h u n k s`) with traced Chunks wordmark silhouettes, then measures and applies Latin optical kerning **after** replacement. ExtraBold uses the filled reference contour, lighter weights use defined inward outline offsets before fitting each silhouette into its original per-weight ink box. Its relative shape is retained; original advance widths and all other glyphs remain unchanged. The older `wordmark-stroke-masters.svg` is retained as an alternative, not silently discarded.

**Automated spacing:**

1. Per-weight kerning: measure 151 Latin/Latin-extended probe characters at 126px with HarfBuzz; examine 22,801 candidate pairs/weight, constrain individual PairPos corrections to 0.072em, preserve existing GPOS/GSUB/GDEF. Accent combinations outside the measured alphabet and all non-Latin writing systems retain upstream kerning, shaping and positioning. A separate font-wide script is still required for class-based optical optimization in these groups. Never claim every Unicode pair is individually tuned.
2. Tracking: evaluate rendered ink clearances on representative actual UI strings after the final OTF is built. The lower-tail gap quantile drives conservative size/context-dependent tracking presets for display, headings, body and compact labels, with documented adjustment bounds. Tracking remains an Android/Compose `letterSpacing` value rather than pretending the font has one global ideal.
3. Leading: hhea/OS/2 metrics remain consistent across all five weights. The spacing optimizer measures upper and lower vertical glyph extrema **across every glyph**, reports a bounded full-script-safe minimum, and distinguishes this from tighter *Latin-specific* display/body line-height examples. Compose must not apply Latin-only line heights to unsupported text or multilingual filenames indiscriminately.
4. Regression and visual QA: independent unit tests verify raster-traced filled silhouettes, alternative stroke masters, deterministic CFF outlines, increasing `Chunks` ink density by weight and all five OTFs' Unicode/format/table/leading invariants. CI renders a five-weight specimen and writes JSON sample width/audit diagnostics. It publishes fonts and audit artifacts, and auto-commits deterministic generated binary outputs to its feature branch.

**Known limitations / next gates:** The six custom silhouettes are currently a display-focused approximation. Lowercase `c` is still the upstream foundation glyph; corresponding accented/ligature-derived variants of the six letters are not yet manually matched to new base contours. All other basic glyphs remain Baloo-derived, so visually identical typography to every reference sample is not claimed. The hand-traced contour selection, kerning around heavy `Ch`, `hu`, `ks`, line metrics at 200% scaling, actual Android `FontFamily` adoption and device screenshot comparisons remain editorial/device gates. Avoid replacing generic body/UI text before reviewing density and truncation with native screenshot tests.

## Commands

```bash
python -m pip install -r tools/requirements-chunks-font.txt
python tools/build_chunks_fonts.py --source 'assets/fonts/source/Baloo2[wght].ttf' --out assets/fonts/chunks
python -m unittest discover -s tests -p test_font_pipeline.py -v
python tools/audit_chunks_fonts.py --fonts assets/fonts/chunks --preview chunks-font-specimen.png --report chunks-font-audit.json
```

The licensed source and SIL OFL text must accompany redistributed derivatives.
## Verified CI result / visual inspection

- [GitHub Actions run 40607816631](https://github.com/gamesbyian/Chunks/actions/runs/40607816631) passed the five CFF font rebuilds, five source/glyph/format unit tests, full glyph audit, rendered five-weight specimen and binary/artifact generation.
- The generated OTFs each retain **1,601 glyphs / 856 Unicode codepoints**. Their 151-character Latin optical probe examines **22,801 possible pairs per weight**, with weight-specific corrections from **18,826 (Regular)** to **14,017 (ExtraBold)**; these are counts, not subjective quality scores.
- Measured tracking presets are preserved in per-weight JSON. Full-script-safe multiline leading spans approximately **1.60–1.67 em** by weight, while Latin-only display/body recommendations remain shorter. No Android renderer or human font inspection proof is implied by CI.
- The rendered specimen was inspected: bold filled silhouettes now reflect the original six reference-traced letters much more closely than the source-foundation face; still open are matching the rest of the alphabet, checking outline fidelity at small sizes and intentional spacing around heavy counters.
