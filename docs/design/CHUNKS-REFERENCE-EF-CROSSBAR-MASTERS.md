# Chunks Reference: independently designed E/F three- and two-bar masters

**Status: nonshipping experimental designs.** These eight editable SVGs are source-native quadratic Bézier contours, not accepted artwork, and do not alter the original 62 reference SVGs, original ExtraBold, or shipping Android font resources.

## Design and validation intent

The four weights (Regular 400, Medium 500, Semibold 600, Bold 700) each have separately specified left stem, horizontal bar thickness, interior notch depth, top/bottom radius and bar join radius. The uppercase **E** retains three horizontal bars with two clear negative-space notches; uppercase **F** retains two bars and an open lower-right area. Each remains a single connected contour with no counters. Outside corners have rounded ends, while deliberately independent inside fillets preserve open notches at lighter weights.

The point values are weight-specific design choices rather than sampled inward offsets, and the CFF builder preserves the native Bézier handles. Preliminary offline source-polygon measurements against the immutable reference were:

| Weight | E ink/reference | F ink/reference |
|---|---:|---:|
| 400 | 75.96% | 72.06% |
| 500 | 83.48% | 79.25% |
| 600 | 91.92% | 88.42% |
| 700 | 98.63% | 97.49% |

These approximate pre-build geometry measurements were subsequently confirmed to rounding by the real CFF-source ledger after the source-branch generator rebuilt all four study OTFs. They remain **not final approval or perceptual font-rendering evidence**.

## Rebuilt source-branch artifact receipt

The source-branch generator committed four new experimental CFFs and the reproducible `reference-panel-weight-study-metrics.json` at `bf323131ced55428c6e417e2d5ab4cc174e5d407`. Its report identifies `overrides_used=32` and combined override SHA-256 `bebfdc78f2e9fe0c1cbc7d18e81e8fa846de08ec0cc96be8c3d37cae6b9c473b`. The compiled study hashes are:

| Weight | New CFF SHA-256 |
|---|---|
| Regular 400 | `9bd3fdfbc1c83caa13af05fb6e39b52f3e649d22ab17ac782fa9e9913a4911ac` |
| Medium 500 | `84b3d51ca1ce5a83c83c67d5da9aa0be5f1f0ac96383753d62c9356d02f761b6` |
| Semibold 600 | `7703da3ba2a03d751b50428cfff82a753c6d8de430c8a26d0ace1d95cc53850b` |
| Bold 700 | `c61acab159c0592b02fee197db599695fe1a860561340c3449334fcd57536b8e` |

Source-branch generation is a **separate gate** from exact final PR-head CI. GitHub Actions bot artifact commits may not launch further PR checks automatically; this documentation receipt is a user-authored follow-up commit that permits those checks to validate the rebuilt artifacts. **Do not merge without all required exact-head checks showing success**, and keep art/device review open. The reproducible CFF study builder must rebuild and hash all 32 lighter-weight masters (now 8 reference-basic glyphs per weight), verify the original 856-codepoint cmap and unrelated shaping, retain editability and pass the native-curve/topology/bounds/monotonic-ink regression suite. Review 14/24/48/82px grids for E/F terminal rhythm, mid-bar placement and optical pairs `FE`, `EF`, `EE`, `FL`, `FT`. Do not promote these candidates without actual visual editorial approval and Android Compose / 200% / TalkBack acceptance. Remaining original reference-specific basic glyphs requiring independent weight designs: **54 per lighter face** after integration.

Source: `assets/fonts/source/reference-alphabet/weight-overrides/{400,500,600,700}/U+{0045,0046}.svg`. Acceptance owner: [issue #347](https://github.com/gamesbyian/Chunks/issues/347).
