# Font laboratory full-family freeze, candidate 2 (correct native CFF counter winding)

- Branch: `font/lab`
- Scope: 62 basic reference source glyphs × 4 independently editable lighter-weight masters, plus immutable reference ExtraBold and compatible derived accents, punctuation and marks.
- Explicit acceptance trigger: change this marker only when a substantial batch is source-complete, then use one [font-lab full-family workflow](../../.github/workflows/font-lab-full-acceptance.yml).
- This snapshot requests **one** sequential local-in-runner build via `bash tools/font_lab_build_all.sh`. It does not enable routine font CI or open a font PR.
- Baseline: 248/248 source masters and four CFF OTFs verified by [single-weight-study run #40812154148](https://github.com/gamesbyian/Chunks/actions/runs/40812154148), with all exact-head checks successful.
- Pending: complete derivative rebuild, optical and visual design review, owner approval, Android real 200% accessibility and release promotion.
- The four lighter faces are **not shipping or approved**. Do not update `main` or Android resources solely because this gate passes.

## Second integration gate

The first full-family run [#40812391453](https://github.com/gamesbyian/Chunks/actions/runs/40812391453) passed all stages and committed derived experimental CFF families. This deliberately scheduled second run normalizes native SVG counter winding (source edits confined to lighter weight overrides) before rebuilding the same CFF and mark chain; the new raw-path and synthetic nonzero-fill regression tests must pass. The approved artwork, ExtraBold, Android app and main stay untouched.
