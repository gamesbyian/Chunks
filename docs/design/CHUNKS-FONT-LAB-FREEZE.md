# Font laboratory full-family freeze, candidate 4 (strict 14px and 24px optical gate)

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
