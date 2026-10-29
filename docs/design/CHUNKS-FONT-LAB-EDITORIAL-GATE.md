# Chunks font laboratory: optical/art and Android approval gate

The core reference-family **engineering build is complete**, but the source artwork is still an editorial proposal. Neither passing OpenType tests nor valid CFF winding proves that it matches the owner's desired original letterform drawings. Keep [issue #347](https://github.com/gamesbyian/Chunks/issues/347) open until the remaining gates are explicitly accepted.

## Verifiable automated acceptance already complete

- 62 basic glyphs × four lighter weights (400–700), **248 individual editable, native Bézier source masters**; immutable original reference ExtraBold 800.
- All-master source audit: one-to-one source files, reference-envelope bounds, original topology/counter counts, strictly increasing ink across weights and each Bold ink mass below reference ExtraBold.
- CFF nonzero-fill compatibility: the [final run](https://github.com/gamesbyian/Chunks/actions/runs/40812996755) repaired 68 reversed counter paths in 60 source SVGs, then verified zero outstanding winding mismatches and recompiled the entire derivative family.
- 856-codepoint CFF/OpenType shaping preservation; GPOS/GSUB/GDEF, optical pair correction, reference-grid renders at 14/24/48/82px; per-size bounded-gap and leading/tracking tests.
- Five-weight compatibility through source and derived accent, punctuation, dotless, consolidated, stacked and mixed-mark families; cross-family integrity audits passed for 20 experimental derivative binaries and unchanged Hindi shaping.
- No shipping Android font or approved reference art modifications; no font PR open; all development and generated experimental artifacts confined to [`font/lab`](https://github.com/gamesbyian/Chunks/tree/font/lab).

## Final strict small-size raster result

The latest [frozen run #40817681146](https://github.com/gamesbyian/Chunks/actions/runs/40817681146) **PASSED** a new real CFF pixel-level gate: all 20 source glyphs with expected counters in **all four lighter weights** preserve **at least 2 enclosed background pixels per counter at both 14px and 24px**. It also rebuilt all experimental derivative families and audited 20 compatible binaries. The counter and terminal improvements include A/4 adjustments and the 0.4-source-pixel upper-hole position correction for Bold 8. All results are reproducible and reflected in [current lab status](CHUNKS-FONT-LAB-STATUS.md). This resolves the earlier *measured missing-counter defects*, **not** the subjectively judged appearance of the heavy display alphabet.

## Not yet accepted

1. **Reference fidelity and aesthetics:** compare all 62 basic glyphs, especially `B/8`, `I/l/1`, `O/0/o`, `C/c/e`, `g/j/q`, `M/W`, `S/s`, and `6/9`, with the actual original reference at 14, 24, 48 and 82 px. Review counter openness, negative space, curve quality, terminal geometry and consistency between 400–800. Reject/refine as necessary; no mechanical score can substitute for this judgement.
2. **Per-weight optical kerning and readable text:** review specimens `Chunks`, `Downloading 68%`, `0123456789`, `Résumé télévision`, `Ärger Übergröße`, long filename strings and mixed accented cases at body sizes; correct local outlier pairs and compare baseline/line heights before approving text style.
3. **Real on-device Android:** complete actual Compose 100%/200% system-font-scale screens, TalkBack and contrast/long-label navigation. [Application PR #488](https://github.com/gamesbyian/Chunks/pull/488) must be reconciled and verified separately. Debug-only TextView pixel examples do not substitute for final app acceptance.
4. **Explicit shipping authorisation:** deliberate owner confirmation of visual style and acceptance of licensing/provenance, followed by a single frozen batch promotion PR that includes the applicable CI. Do not replace `app/src/main/res/font/**` simply because the lab technically compiles.

The correct current receipts, build instructions and isolation contract live in [CHUNKS-FONT-LAB-STATUS](CHUNKS-FONT-LAB-STATUS.md), [CHUNKS-FONT-LAB-OFFLINE-BUILD](CHUNKS-FONT-LAB-OFFLINE-BUILD.md) and [CHUNKS-FONT-LAB-OPERATIONS](CHUNKS-FONT-LAB-OPERATIONS.md). The final actual-font specimen images are in [GitHub Actions run #40812996755](https://github.com/gamesbyian/Chunks/actions/runs/40812996755) under `chunks-font-lab-all-families`.

## Actual-font artifact inspection, 2026-10-28 (Calgary)

The final run's actual CFF raster exports were retrieved and inspected directly, not inferred from geometry or CI alone: `reference-panel-five-weights.png`, `reference-panel-glyph-grid-24px.png`, the 82px uppercase/lowercase/digit grid, and `chunks-font-variants-14px.png`. This is **a preliminary engineering visual inspection, not owner approval or full accessibility acceptance**.

Observed successes: O/0/o, B/8, D/P/Q, a/e/g, and other multi-contour forms retain their visible counters after the nonzero-winding repair; C/c retain their open apertures; j's detached dot and hooked descender appear distinct; numeric 5 is no longer rendered with an accidental enclosed pocket. Across 400/500/600/700 the family has a recognisable cohesive rounded, chunky voice, and the original reference 800 remains distinguishable.

**Historical optical-risk observation from an earlier specimen:** some A/B/O/0/8 and lowercase bowl counters approached pinholes in heavier weights. The subsequently reworked candidate passes the objective 14px/24px counter visibility gate in all four lighter weights, although individual counters can still appear small and certain 400-to-700 differences remain subtle relative to the heavy display style. Small text can therefore appear dense. This font should **not be treated as a signed-off general Android body font** merely because its kerning gap metrics and shaping tests pass. Review these visual concerns against the owner's intended reference at the real Android physical sizes and in light/dark contrast conditions before any promotion.

The latest frozen source/Bézier CFFs, strict raster counter JSON and complete atlas can be retrieved from [full-family acceptance #40817681146](https://github.com/gamesbyian/Chunks/actions/runs/40817681146), artifact `chunks-font-lab-all-families`.
