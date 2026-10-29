# Chunks font laboratory: optical/art and Android approval gate

The core reference-family **engineering build is complete**, but the source artwork is still an editorial proposal. Neither passing OpenType tests nor valid CFF winding proves that it matches the owner's desired original letterform drawings. Keep [issue #347](https://github.com/gamesbyian/Chunks/issues/347) open until the remaining gates are explicitly accepted.

## Verifiable automated acceptance already complete

- 62 basic glyphs × four lighter weights (400–700), **248 individual editable, native Bézier source masters**; immutable original reference ExtraBold 800.
- All-master source audit: one-to-one source files, reference-envelope bounds, original topology/counter counts, strictly increasing ink across weights and each Bold ink mass below reference ExtraBold.
- CFF nonzero-fill compatibility: the [final run](https://github.com/gamesbyian/Chunks/actions/runs/40812996755) repaired 68 reversed counter paths in 60 source SVGs, then verified zero outstanding winding mismatches and recompiled the entire derivative family.
- 856-codepoint CFF/OpenType shaping preservation; GPOS/GSUB/GDEF, optical pair correction, reference-grid renders at 14/24/48/82px; per-size bounded-gap and leading/tracking tests.
- Five-weight compatibility through source and derived accent, punctuation, dotless, consolidated, stacked and mixed-mark families; cross-family integrity audits passed for 20 experimental derivative binaries and unchanged Hindi shaping.
- No shipping Android font or approved reference art modifications; no font PR open; all development and generated experimental artifacts confined to [`font/lab`](https://github.com/gamesbyian/Chunks/tree/font/lab).

## Not yet accepted

1. **Reference fidelity and aesthetics:** compare all 62 basic glyphs, especially `B/8`, `I/l/1`, `O/0/o`, `C/c/e`, `g/j/q`, `M/W`, `S/s`, and `6/9`, with the actual original reference at 14, 24, 48 and 82 px. Review counter openness, negative space, curve quality, terminal geometry and consistency between 400–800. Reject/refine as necessary; no mechanical score can substitute for this judgement.
2. **Per-weight optical kerning and readable text:** review specimens `Chunks`, `Downloading 68%`, `0123456789`, `Résumé télévision`, `Ärger Übergröße`, long filename strings and mixed accented cases at body sizes; correct local outlier pairs and compare baseline/line heights before approving text style.
3. **Real on-device Android:** complete actual Compose 100%/200% system-font-scale screens, TalkBack and contrast/long-label navigation. [Application PR #488](https://github.com/gamesbyian/Chunks/pull/488) must be reconciled and verified separately. Debug-only TextView pixel examples do not substitute for final app acceptance.
4. **Explicit shipping authorisation:** deliberate owner confirmation of visual style and acceptance of licensing/provenance, followed by a single frozen batch promotion PR that includes the applicable CI. Do not replace `app/src/main/res/font/**` simply because the lab technically compiles.

The correct current receipts, build instructions and isolation contract live in [CHUNKS-FONT-LAB-STATUS](CHUNKS-FONT-LAB-STATUS.md), [CHUNKS-FONT-LAB-OFFLINE-BUILD](CHUNKS-FONT-LAB-OFFLINE-BUILD.md) and [CHUNKS-FONT-LAB-OPERATIONS](CHUNKS-FONT-LAB-OPERATIONS.md). The final actual-font specimen images are in [GitHub Actions run #40812996755](https://github.com/gamesbyian/Chunks/actions/runs/40812996755) under `chunks-font-lab-all-families`.
