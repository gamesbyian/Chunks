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

These approximate pre-build geometry measurements are **not final approval or font-rendering evidence**. The reproducible CFF study builder must rebuild and hash all 32 lighter-weight masters (now 8 reference-basic glyphs per weight), verify the original 856-codepoint cmap and unrelated shaping, retain editability and pass the native-curve/topology/bounds/monotonic-ink regression suite. Review 14/24/48/82px grids for E/F terminal rhythm, mid-bar placement and optical pairs `FE`, `EF`, `EE`, `FL`, `FT`. Do not promote these candidates without actual visual editorial approval and Android Compose / 200% / TalkBack acceptance. Remaining original reference-specific basic glyphs requiring independent weight designs: **54 per lighter face** after integration.

Source: `assets/fonts/source/reference-alphabet/weight-overrides/{400,500,600,700}/U+{0045,0046}.svg`. Acceptance owner: [issue #347](https://github.com/gamesbyian/Chunks/issues/347).
