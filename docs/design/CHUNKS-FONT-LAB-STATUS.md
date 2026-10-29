# Chunks reference typeface: authoritative isolated-lab status

**Updated 2026-10-28 (Calgary time).** This is the current typeface engineering handoff for [`font/lab`](https://github.com/gamesbyian/Chunks/tree/font/lab). Earlier glyph-specific design documents are **dated snapshots**; their old "current" master counts are not the present project inventory.

## Built, reproducible, still nonshipping

- All **62 original basic Latin/digit glyphs** have weight-specific, editable, native quadratic/cubic SVG candidates in 400, 500, 600 and 700: **248/248 source masters**. The original 62 traced reference SVGs and separate ExtraBold 800 master are unchanged.
- The full [source and weight build run #40812154148](https://github.com/gamesbyian/Chunks/actions/runs/40812154148) **PASSED**: source topology/bounds/weight gradient, editable source SHA receipts, four distinct actual CFF OpenType faces, 856-codepoint cmap/shaping tests, at least 12,000 kerning pairs per lighter weight, leading/tracking gates, five-weight glyph grid renders at 14/24/48/82px.
- The first [full family acceptance #40812391453](https://github.com/gamesbyian/Chunks/actions/runs/40812391453) **PASSED**: the single offline process rebuilt weight, accents, accent-kerned, dotless-kerned, punctuation, consolidated, stacked-accent and mixed-mark families, preserving unchanged Hindi shaping, and **audited 20 source/weight derivatives**. Reproducible experimental OTFs and metrics were committed to this branch; no `app/`, shipping font, approved reference or original reference SVG changed.
- **Final [full-family winding-corrected acceptance #40812996755](https://github.com/gamesbyian/Chunks/actions/runs/40812996755) PASSED in its entirety.** [Winding normalizer](../../tools/font_lab_normalize_winding.py) reversed **68 inner counter paths in 60 edited SVGs**, preserving their original Bézier geometry. Its read-only second pass reported zero remaining incorrect counters; all 12 local source tests and the full derivative family test sequence passed. The action committed 60 corrected source SVGs, 28 changed experimental OTF binaries and 12 remaining derived reports at exact lab head [`08dfa8b17bb6acf3cd134204ce4ae69650b09f16`](https://github.com/gamesbyian/Chunks/commit/08dfa8b17bb6acf3cd134204ce4ae69650b09f16). The [248-master CFF hash ledger](../../assets/fonts/experimental/weight-studies/reference-panel-weight-study-metrics.json) records deterministic source and OTF checksums. The full lab artifact ZIP is available on the action run. The GitHub commit diff confirms **zero** protected original source/ExtraBold, shipping Android assets or base-font changes.

## What engineering completion does not mean

- **Not owner's approved visual design.** The 248 SVGs are editable individually but substantially proposed/generated, and must be optically checked against the original reference artwork. A compiled CFF, source SHA or bounding-box test does not establish that the shapes match the exact desired Chunks type style. Review counters, joins, overshoots, diagonals, stroke rhythm and spacing in the generated actual-OTF specimens, especially 14/24px.
- **Not complete manual customisation of 856 cmap glyphs.** The full mapping and layered Latin/mark constructions are supported, but the 62 basic reference shapes and the many reconstructed accents/complex mark variants have different design/provenance classes. Do not describe every mapped codepoint as an individually approved contour.
- **Not installed in the shipping Android app.** Production font assets are unchanged. Genuine Compose 200%-system-font acceptance, TalkBack and contrast/visual review require independent device checks; [PR #488](https://github.com/gamesbyian/Chunks/pull/488) is an application-owned acceptance track, not automatic typeface authorisation.
- **No promotion PR until a meaningful, frozen reviewed tranche is ready.** The owner directed a single quiet font branch, local-only routine work and deliberate one-shot freeze runs. Do not reopen per-glyph PR or unrelated CI fanout.

## Commands and next owner decision

```sh
python tools/font_lab_source_ledger.py --require-full
bash tools/font_lab_gate.sh source
python tools/font_lab_normalize_winding.py
bash tools/font_lab_build_all.sh
```

All commands are local-first and do not push. `font_lab_build_all.sh` explicitly rejects incorrect source counter winding. Install the pinned font dependencies first. The final winding-corrected [full-family artifact bundle](https://github.com/gamesbyian/Chunks/actions/runs/40812996755) contains compiled nonshipping CFF samples and render evidence. Preserve the frozen exact-head source and binary hashes when evaluating any additional glyph edits.

**Remaining gates:** complete the winding-corrected all-family run with committed exact SHA receipts; actual image-by-image optical/kerning review; accessible Android 200%/TalkBack evaluation; explicit decision whether to promote the candidate into shipping assets. Track final decisions on [issue #347](https://github.com/gamesbyian/Chunks/issues/347). The single-branch isolation contract remains authoritative in [FONT-LAB-OPERATIONS](CHUNKS-FONT-LAB-OPERATIONS.md).
