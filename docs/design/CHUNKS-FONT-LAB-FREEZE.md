# Font laboratory full-family freeze, candidate 6: owner C/c/j optical changes (weights 400–700 only)

- Branch: `font/lab`
- Scope: 62 basic reference source glyphs × 4 independently editable lighter-weight masters, plus immutable reference ExtraBold and compatible derived accents, punctuation and marks.
- Explicit acceptance trigger: change this marker only when a substantial batch is source-complete, then use one [font-lab full-family workflow](../../.github/workflows/font-lab-full-acceptance.yml).
- This snapshot requests **one** sequential local-in-runner build via `bash tools/font_lab_build_all.sh`. It does not enable routine font CI or open a font PR.
- Baseline: 248/248 source masters and four CFF OTFs verified by [single-weight-study run #40812154148](https://github.com/gamesbyian/Chunks/actions/runs/40812154148), with all exact-head checks successful.
- Pending: complete derivative rebuild, optical and visual design review, owner approval, Android real 200% accessibility and release promotion.
- The four lighter faces are **not shipping or approved**. Do not update `main` or Android resources solely because this gate passes.

## Second integration gate

The first full-family run [#40812391453](https://github.com/gamesbyian/Chunks/actions/runs/40812391453) passed all stages and committed derived experimental CFF families. This deliberately scheduled second run normalizes native SVG counter winding (source edits confined to lighter weight overrides) before rebuilding the same CFF and mark chain; the new raw-path and synthetic nonzero-fill regression tests must pass. The approved artwork, ExtraBold, Android app and main stay untouched.

## Third integration gate

This frozen batch changes only the four lighter-weight SVG inner counters in A and 4 and adds the actual-CFF raster counter audit. The prior winding-corrected all-family run #40812996755 passed. Require all 248 editable source geometry tests, actual 24px counter raster safety, 856 cmap/shaping/kerning, complete accent and mark family compatibility, and one 20-variant cross-family audit before considering this optical correction integrated. 14px is deliberately diagnostic because this is a heavy display font. Keep nonshipping; preserve original source/ExtraBold/Android isolation.

## Fourth and final small-size source acceptance

The third full run [#40816749998](https://github.com/gamesbyian/Chunks/actions/runs/40816749998) passed all source, CFF, accents, kerning, marks and cross-family tests; its real raster counter gate reported zero 24px failures and the remaining 14px warnings Medium 4, Bold g/p/8. Apply only four inner-counter fixes, then demand **zero 14px and 24px counter visibility failures** in all four lighter weights. Rebuild all derived families sequentially in one freeze-triggered workflow; preserve the original source, ExtraBold, Android shipping font and app branches. This is source/raster engineering completion only, not owner artwork signoff.

## Fifth strict-small-pixel gate

The fourth run #40817339389 failed solely on Bold 8's 14px upper counter, proving the new strict audit works. The fifth candidate shifts only that edited upper counter by -0.4 SVG source pixels. Require **all** 14px and 24px expected CFF counter islands ≥2 pixels, full 248-master topology/ink tests, complete five-weight derived CFF family and 20-binary audit, and deterministic experimental OTF commit. Do not promote to main or Android; owner visual and device acceptance remain separate.

## Sixth frozen batch: owner-requested design refinement

The owner is happy with fifth-weight ExtraBold 800 and explicitly requested two adjustments **only** to 400/500/600/700: align the dot above the actual vertical shaft of `j`, and smoothly round the inner paths of `C` and `c`. The exact twelve SVG source changes and a source-level optical regression test are on the isolated lab branch at commit `9eb2e5c6c8e74906c6879d5389ed4ce3d562d73e` (after typography commit `ed91751806f2288b837004b93a3ee0503da0ca12`). No original source, approved 800, app, other glyphs, or workflows touched. Use a single freeze-triggered full-family acceptance; require all 248 masters, native tangency/source-geometry tests, 14/24px counter raster, GPOS/GSUB shaping, stacked/mark/derivative family builds and 20 binary audit. On success commit only nonshipping derived OTF assets and document exact checksums; do not automatically promote to shipping Android.

## Seventh frozen batch: owner-requested j/G/g/9 fixes

The owner again explicitly described the fifth ExtraBold 800 as perfect and ordered **only** Regular 400, Medium 500, Semibold 600 and Bold 700 fixes: remove the pointed top-left `j` body spur, remove the upper-right `G` pinch while enlarging the open terminal/crossbar aperture, open the small `g` tail/body gap, and mirror the incorrect 400–700 `9` so its lower curl faces the correct direction. Sixteen source SVG masters were changed in [`8681f415`](https://github.com/gamesbyian/Chunks/commit/8681f4156cc6c9fb0765decac82f3a804ff813cc), with focused source tests added in [`10c5e328`](https://github.com/gamesbyian/Chunks/commit/10c5e328a430a0824e88786dd6ed1fa35d0d716a). All changes remain isolated to `font/lab`; original sources, immutable 800, shipping Android, 6, previous C/c shapes, all app branches unchanged. One full-family sequential CFF rebuild and geometry/typography/shaping/counter/20-binary acceptance is intentionally required; reject if counter winding, holes, outline validity, size14/24 raster counter visibility or full 248-master gradient fails. Validate fifth compiled ExtraBold binary byte-for-byte against previous run #40820307607. This is a nonshipping candidate, not owner signoff for the revised first four weights.

## Eighth frozen source correction (retry gate, no parallel fanout)

The first round-seven acceptance [#40822338290](https://github.com/gamesbyian/Chunks/actions/runs/40822338290) failed **before** building CFFs: two lighter `G` outline bounds exceeded the ≤3-source-unit reference envelope, and the new aperture test incorrectly searched for a line endpoint that is actually a native SVG quadratic endpoint. The narrow correction commit [`03bfe73c`](https://github.com/gamesbyian/Chunks/commit/03bfe73c20cd31ced8faec8e13384e54c13f331d) adjusts only the four `G` second outer cubic control-point heights to preserve the intended tangent while restoring the reference silhouette envelope, and fixes the focused landmark test. Recheck all first-four `j/G/g/9` owner corrections through the same sequential all-family acceptance. No shipping/main/800 changes. Run once only; do not start separate workflows per font or weight.
