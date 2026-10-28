# Chunks Reference Panel: individually edited vertical-stem weight masters

**Status:** eight nonshipping, independent editable vector-source design candidates. **Not a replacement for the approved font.**

The original 62 reference glyph SVGs remain immutable. Earlier Regular–Bold font studies used geometric erosion of the heavier reference silhouette; the owner wants real independent contour design for all weights. This change starts a small, auditable class of intentionally authored weight-specific source outlines rather than claiming that automatic contour erosion is a finished font.

## Art direction and precise scope

The uppercase `I` (U+0049) and lowercase `l` (U+006C) are both essentially solid, vertical rounded stems in the supplied original reference. A contour designer can edit their shape without touching complex counters or joins. Four **separately editable** SVG masters per glyph now live at:

`assets/fonts/source/reference-alphabet/weight-overrides/{400,500,600,700}/U+{0049,006C}.svg`

They use actual `M`, `L`, `Q` and `Z` commands, not polygonal samples, and deliberately introduce independent corner rounding and stroke width at each weight. Weight widths increase monotonically, while the baseline and original reference canvas are preserved. ExtraBold remains identical to the original source reference and is not edited.

The weight builder imports these exact editor-owned native Bézier path segments directly to OpenType CFFs, after validating contour topology, area envelope and ≤3 reference-pixel extremity motion. All other 60 glyphs per face continue the existing clearly labeled **interim contour-derived** method. The source files are intentionally simple, inspectable and independently editable to enable real typographic refinement.

CI rebuilds four independent nonshipping `ChunksReferencePanel-*-Study.otf` files and their optical pair kerning; verifies 856 Unicode cmap, CFF outlines, unrelated-script shaping, all 62 characters per weight, 14/24/48/82px rendering and sampled glyph collision. A new test requires these eight source SVGs to be actual curves, records byte-for-byte source hashes, checks that compiled CFFs preserve real Bézier ops, and ensures progressive ink mass across 400/500/600/700. Results remain in `assets/fonts/experimental/weight-studies`.

## Explicit limitations

These are *candidate editor-authored variations*, not claimed exact reconstructions of separate per-weight user-supplied reference images. A single class of stem edits is not a completed type family. Other weights' 60 glyphs, all remaining source-specific accents, final kerning/tracking/leading review, and native Compose 200%/TalkBack visual approval remain open. The downstream Consolidated, Stacked and Mixed CFFs are separate reversible experiments; rebuilding upstream weight studies does not silently rewrite or certify downstream fonts. Art signoff and promotion continue to be gated by [#347](https://github.com/gamesbyian/Chunks/issues/347).

The feature branch retains the four **actual rebuilt** `weight-studies/ChunksReferencePanel-{Regular,Medium,Semibold,Bold}-Study.otf` files and their exact source SHA/provenance metrics. Review the source and compiled fonts together; source SVGs alone do not make a reproducible checkout. The source-branch CI writes the generated files using a bounded fetch/rebase/push retry when multiple commits arrive during the same job.

## Next editorial class: dotted lowercase i

Four additional independently editable Bézier masters now specify `U+0069` at weights 400/500/600/700. Unlike the single-contour `I` and `l`, each is a deliberately separated **two-contour** stem-plus-dot source. The dot's vertical gap is held above 1.5 source pixels at every candidate weight, with a wider opening in Regular and monotonic ink mass through Bold. Each dot and stem is a native quadratic/straight-path composition; no low-resolution polygonized offset is being passed off as a manually edited SVG.

New regression tests require exactly two source and compiled CFF contours, original reference bbox motion at most three pixels, non-colliding dot and stem, byte-for-byte native SVG hashes and four-weight area progression. The weight-study pipeline now records **12 independently edited base-source glyph masters** across three characters, leaving the other 59 alphabet characters per light weight in the explicitly unfinished automated category. Neither the original letter SVGs nor the approved ExtraBold or shipping app resources are changed. Editorial and device approval are still open.

The rebuild of this three-glyph-class experiment produces checked-in actual native CFF source hashes and `reference-panel-weight-study-metrics.json` with **12** explicit SVG overrides, precisely **two contours** per dotted-`i` glyph, and four monotone native ink-area ratios for `i`: Regular 0.7249, Medium 0.8043, Semibold 0.8859, Bold 0.9677 relative to its immutable source reference. Each experimental OTF must be compiled from the matched SVG source before accepting the PR, not merely inferred from the SVG's existence.
