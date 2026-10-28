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
## Exact repository build evidence

[Run 40612815302](https://github.com/gamesbyian/Chunks/actions/runs/40612815302) completed successfully: both experimental OTFs and `reference-panel-spacing.json` were generated. The checked-in optimized file is 683,192 bytes and retains the same 856-character repertoire. The optimized version measures 13,796 additional GPOS pairs involving replaced glyphs; sample gaps remain positive across 14/24/48px. All approved Android resources are unchanged. Run the full experiment again before release if the approved master fonts or 62 traced SVG inputs change.

### Measured tracking and leading (experimental face)

The optical audit also computes role-specific *application* tracking and full-glyph vertical bounds, not just pair kerning. Prototype ExtraBold 800 tracking: display −0.007em; headings +0.014em; body +0.018em; compact labels +0.024em. The latter two hit the configured positive tracking limit, a useful warning that dense UI copy needs more editorial work. Initial Latin line-height ratios remain 1.14/1.22/1.36/1.42 by role; a complete multiscript glyph-extents scan recommends **at least 1.67em** for unrestricted strings. These are recorded in the reproducible `reference-panel-spacing.json` and must be applied in Android TextStyle or another client, rather than misrepresented as a universal font-internal tracking value.

**Next owner-independent engineering gate:** [Issue #347](https://github.com/gamesbyian/Chunks/issues/347) tracks contour-reviewed reference-panel weights 400–700, per-weight optical QA, accent composition and real Android comparison. The optical ExtraBold experiment is complete at the measured CFF/CI level, but those downstream acceptance gates are deliberately still open.
