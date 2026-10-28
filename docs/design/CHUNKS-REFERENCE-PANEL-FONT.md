# Chunks Reference Panel: experimental CFF display OTF

**Not shipping.** This is an opt-in **second font family proposal** compiled from the 62 independently editable reference-alphabet SVGs. The approved Android header continues using `Chunks-ExtraBold.otf`, with its six primary-wordmark outline variants. This new face is named `Chunks Reference Panel ExtraBold` to avoid overwriting the installed Chunks family or unintentionally replacing a font in an app.

## Reproducible font construction

`tools/build_reference_panel_candidate.py` reads each `U+NNNN.svg`, parses its compound vector path, transforms quadratic outlines into native cubic Type 2 charstrings and assigns source-grid-derived scale, baseline and side bearings. Unlike a bitmap-in-font or an embedded SVG image, the output is a real OpenType/CFF `.otf` with selectable copyable Unicode text, proper glyph advances and the inherited Latin/Latin-ext/Vietnamese/non-Latin glyph coverage.

It swaps only the 62 Basic Latin letter/number CFF charstrings, leaving the remaining original glyphs and native GSUB/GDEF/GPOS shaping untouched. The upstream SIL Open Font License and attribution still apply; see `assets/fonts/source/OFL.txt`. Source-grounded 62 SVGs remain independently editable. The six primary-wordmark letter forms are **not** overwritten in the shipping font.

```bash
python -m pip install -r tools/requirements-chunks-font.txt
python tools/build_reference_panel_candidate.py
python -m unittest discover -s tests -p test_reference_panel_font.py
python tools/render_reference_panel_comparison.py
```

The dedicated CI run checks 62 distinct outlines, exactly matching Unicode coverage, native shaping parity on Hindi text, positive sampled ink clearance at 14/24/48 pixels, and identical output bytes from a second complete build. It publishes the experimental OTF and a specimen rendered by real OpenType shaping.

## Pending before this may replace any approved type

- Optical spacing/kerning recalibration based on *new* contours. Existing base-font GPOS pairs are retained only as a temporary draft.
- Distinct, visually consistent 400/500/600/700/800 weight redraws. This sheet supplies one heavy reference master, not five distinct weight designs.
- Shape counter and curve editorial review at small sizes, especially B/D/O/Q/0/6/8/9 and e/g/a.
- Correct accent matching for resized base letters, language-specific marks, Unicode combining sequences, and readability of long filenames.
- Native Android pixel atlas and accessibility tests before any production use. The accepted Chunks wordmark SVG and its primary six-glyph font variant are not silently substituted.

The temporary inherited source kerning should not be interpreted as finished optical typography even when tests pass. This file is an *alternative for mixing and matching*, as requested, and remains isolated until deliberate approval.
## Verified experiment

[Exact source-head run 40612081763](https://github.com/gamesbyian/Chunks/actions/runs/40612081763) passed reference glyph compilation, a second byte-identical rebuild, CFF/Unicode/GPOS/GSUB parity checks, shape changes for all 62 Basic Latin letters/numerals and unchanged Hindi shaping. Measured 14/24/48px sample gaps were positive. The experiment produced a separately named installable OTF and a real-font comparison PNG. The approved five Chunks weights and Android header are still unchanged.


## Reference-panel optical kerning, second experimental pass

The 62-glyph reference-panel trial now has an **independently optically re-spaced alternate** (a separate OTF, not a replacement for the approved Chunks family or the unkerned reference-panel candidate). `tools/optimize_reference_panel_kerning.py` shapes 151 Latin/currency/punctuation probe characters at the final 800-weight outlines, evaluates 22,801 candidate pairs, and appends bounded *additional* GPOS `kern` corrections **only when at least one glyph is among the 62 altered letters/numerals**. It preserves every existing source GPOS, GDEF, GSUB and cmap table and the original Unicode references.

Measured local prototype: 13,796 candidate-only pairs received bounded corrections. Smallest sampled visible ink gap (14, 24, 48px) changed from **1.44, 2.66, 5.31px** to **0.61, 1.70, 3.17px**. These are positive, although tighter spacing alone does not establish superior readability. CI rebuilds twice and compares binary hashes; authentic Android and multilingual editorial tests remain open.

Generated output: `assets/fonts/experimental/ChunksReferencePanel-ExtraBold-Optical.otf`, separate from `ChunksReferencePanel-ExtraBold.otf` and from all production `Chunks-*.otf`. `assets/fonts/experimental/reference-panel-spacing.json` preserves exact source/optimized SHA-256 and measured results. Keep the experimental source and approved master distinguishable in UI and design discussions.