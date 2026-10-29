# Chunks reference typeface: authoritative isolated-lab status

**Updated 2026-10-28 (Calgary time).** This is the current typeface engineering handoff for [`font/lab`](https://github.com/gamesbyian/Chunks/tree/font/lab). Earlier glyph-specific design documents are **dated snapshots**; their old "current" master counts are not the present project inventory.

## Built, reproducible; ExtraBold now shipping, 400–700 still nonshipping

## Production promotion: approved ExtraBold 800 ONLY

**Merged to `main`:** [PR #528](https://github.com/gamesbyian/Chunks/pull/528) (commit [`e2b6f61`](https://github.com/gamesbyian/Chunks/commit/e2b6f61c3ac8430dcb6c4fdf36c2525ab68a3acd)) explicitly promotes the owner-approved **reference-panel ExtraBold 800** to `app/src/main/res/font/chunks_extrabold.otf`. The packaged file and the checked-in `assets/fonts/experimental/consolidated/ChunksReferencePanel-ExtraBold-Consolidated.otf` have **identical Git blob SHA `25caa12768178db59a843779225f5c9602e73591`**, and Gradle preBuild checks byte-for-byte identity. Existing Compose brand header and title/headline display slots consume that font; system text handles body and dense metadata.

**This supersedes earlier statements below that ExtraBold was nonshipping.** The current five-weight experiments are not a shipping family: **only 800 is now approved and packaged**, and 400/500/600/700 remain intentionally isolated on this lab branch for a future **structural type-design redesign** to inherit ExtraBold's distinct geometry, irregularities and visual DNA, rather than further isolated cosmetic repair. Keep 800 immutable. No Android publication/device installation is claimed from merging repository code. PR #528 passed font audit, font build, stress, Android build, R8, native atlas, and emulator Add review checks before merge. Physical handset TalkBack/200%-scale visual acceptance remains separate.

## Latest owner-directed j/G/g/9 refinement, verified 2026-10-28

**CURRENT NONSHIPPING CANDIDATE:** [one final complete-family acceptance #40822542503](https://github.com/gamesbyian/Chunks/actions/runs/40822542503) **PASSED every step** after a first rejected geometry candidate [#40822338290](https://github.com/gamesbyian/Chunks/actions/runs/40822338290) was corrected to respect the strict reference envelope. Exactly **16 native SVG masters** (four weights 400/500/600/700 × `j/G/g/9`) changed in [source commit `8681f415`](https://github.com/gamesbyian/Chunks/commit/8681f4156cc6c9fb0765decac82f3a804ff813cc); the narrow G bounds/test correction is [`03bfe73c`](https://github.com/gamesbyian/Chunks/commit/03bfe73c20cd31ced8faec8e13384e54c13f331d). Full experimental binaries, all five 20-derivative family specimens and metrics were saved in [build commit `0aaacec0f`](https://github.com/gamesbyian/Chunks/commit/0aaacec0f84c794684447a8a9029210b6435296f).

- `j`: straightened top-left vertical stem, removed the 4.01–4.45 source-unit spur, kept the previously centred separate dot, descender/hook, and existing `C/c` corrections.
- `G`: softened the upper-right outer shoulder to smooth horizontal Bezier tangency, opened the crossbar/upper-terminal gap by 2.31 source units, kept the source bounds within the approved reference envelope.
- `g`: increased the white gap beneath the lower bowl, without moving the tail or counter.
- `9`: mirrored the editable outline and inner counter horizontally so the lower terminal curls left, the correct orientation; `6` and every other glyph unchanged.
- All **248/248 editable weight masters**, full 856-codepoint CFF/shaping/kerning/metrics, **20 derivative family binaries**, unchanged Hindi shaping and zero raster counter problems for 20 counter-bearing glyphs at 14/24 px across all four lighter weights **passed** in the same one-job frozen workflow.
- **Original reference and ExtraBold 800 preserved, byte-for-byte.** Previous vs new ExtraBold consolidated CFF SHA-256 is identical: `55ba6f8a114a0da6223023bde4e0b6ad12d469616b2743ac471d8415b1d8813c`. No Android shipping resource, `main`, source reference or other development branch touched. No font PR or routine push CI.
- New experimental weight-study OTF SHA-256 receipts:

| Weight | Current SHA-256 |
|---|---|
| Regular | `9b20a113c812a9ff6f4f9321d06be7cb9c0443cab4ccfec29e5485f109a79ccb` |
| Medium | `546e37aef073b982dec7a42592f1d8eaa93520e3462d9d8331d7752d72b7ae8a` |
| Semibold | `6874db06ddfecd6c7108578b65436b08652997679f8cfa05ab9eb9f2867fdeaf` |
| Bold | `1336893c36093db04757b9b51a3b2dbae46900a3a62ecfdefe28c8886bcbad75` |

The old [C/c/j run #40820307607](https://github.com/gamesbyian/Chunks/actions/runs/40820307607) below is preserved as a **previous candidate**, useful for before-and-after actual-font comparisons. **Outstanding:** the owner has approved the fifth weight but has not yet signed off the *revised* 400–700 glyphs, Android physical device font scaling/TalkBack, or production font promotion. See [specific round-two corrections](CHUNKS-FONT-LAB-OWNER-OPTICAL-REFINEMENTS-ROUND2.md), [editorial gate](CHUNKS-FONT-LAB-EDITORIAL-GATE.md), and [issue #347](https://github.com/gamesbyian/Chunks/issues/347).

## Earlier owner-directed C/c/j refinement, verified 2026-10-28

**Current frozen validated candidate:** [one controlled full-family run #40820307607](https://github.com/gamesbyian/Chunks/actions/runs/40820307607) **PASSED every step**, persisted exact OTF / source-hash receipts to isolated lab commit [`44c419ac4`](https://github.com/gamesbyian/Chunks/commit/44c419ac487c71a79bf70b95e560671a2e26d733), and attached the complete `chunks-font-lab-all-families` optical specimen bundle.

- **Owner instruction:** the fifth (ExtraBold, 800) weight is perfect and must not be redesigned. Only the first four weights (Regular 400, Medium 500, Semibold 600, Bold 700) change in `C`, `c`, `j` (12 editable SVG files).
- `j`: centered its detached dot over the **actual vertical shaft** using separately measured horizontal-only translations +4.53/+4.71/+4.88/+5.03 source units, maintaining the dot's exact original native Bézier geometry, vertical position and size.
- `C`, `c`: removed the pinched, segmented internal contour in favor of **two continuous cubic curves** with a matching vertical tangent at their leftmost point and tangent-aligned rounded arm connections. Outside silhouettes, terminal lobes and all other glyphs unchanged. Ink remains strictly ordered across the lighter weights and below the 800 reference.
- Both new [source-focused tests](../../tests/test_font_lab_owner_optical_refinements.py) **passed**, along with the existing 248-master geometry/contour/winding validations, 856-cmap/shaping/kerning/line metric tests, **zero 14px and 24px rendered counter failures** in all four lighter weights, unchanged Hindi shaping and **20 derivation-family/weight binary checks**.
- The actual compiled **ExtraBold-Consolidated.otf is byte-for-byte identical** across the previous and current experimental full-family artifacts. SHA-256 unchanged: `55ba6f8a114a0da6223023bde4e0b6ad12d469616b2743ac471d8415b1d8813c`.
- Current nonshipping base study CFF SHA-256 receipts:

| Style | Latest SHA-256 |
|---|---|
| Regular | `82d3d3995175cd3b763734d983512e7549040c04830c0cf712fb4a86133e59c2` |
| Medium | `cfc89524a6af0c983f8acb87ed0a43d16d78b718ce31b31d53a844e4d3005d4a` |
| Semibold | `8a7ac1f4dc0f72d9519d631edad693df5401991d94f8c41333c0889f7efa85d5` |
| Bold | `705b2d69a8b9f22e5e955eec3b02d92fa748b56401d5f60ce5089c1b85de7f51` |

See [owner optical change design](CHUNKS-FONT-LAB-OWNER-OPTICAL-REFINEMENTS.md). The older run #40817681146 and hashes below are the **previous candidate**, retained for before/after comparison and provenance. Engineering acceptance does not replace aesthetic evaluation of the revised first four weights in real app context, Android 200%/TalkBack, or explicit shipping authorisation. Keep source art on one quiet `font/lab` branch, no per-glyph PR.

## Previous frozen engineering acceptance: 2026-10-28 (historical candidate)

**Green frozen acceptance:** [GitHub Actions #40817681146](https://github.com/gamesbyian/Chunks/actions/runs/40817681146), exact generated artifact source commit [`12f05bc06dd42b09befe317b417fa74a9a1d2f1e`](https://github.com/gamesbyian/Chunks/commit/12f05bc06dd42b09befe317b417fa74a9a1d2f1e). This run passed every workflow step, persisted reproducible experimental CFF binaries/metrics and attached the `chunks-font-lab-all-families` specimen/evidence artifact.

- **248/248** editable native Bézier source masters (62 basic source glyphs × four lighter weights), immutable original ExtraBold and approved reference drawings.
- **Zero 14px or 24px counter-visibility defects**: all **20 counter-bearing glyphs** in each of Regular, Medium, Semibold and Bold showed at least 2 rendered interior pixels for every expected counter, with thresholded *actual OpenType CFF rasterizations*. The same raster JSON documents 48px and 82px as additional nonstrict specimen sizes. [Source/test](../../tools/font_lab_raster_counter_audit.py), [optical fix details](CHUNKS-FONT-LAB-SMALL-COUNTERS.md).
- The source topology/weight gradient, CFF nonzero winding, 856-cmap and text shaping, GPOS/kerning/leading/tracking, accent/dotless/punctuation/stacked/mixed-mark construction, Hindi invariance, and cross-family integrity across **20 derivative binaries** all passed in the **same isolated full-family run**.
- Binary SHA-256 for the nonshipping 400–700 **base weight study OTFs** (authoritative machine-readable receipts in `assets/fonts/experimental/weight-studies/reference-panel-weight-study-metrics.json`):

| Style | SHA-256 |
|---|---|
| Regular 400 | `d6d790f81e037caeb5f8402a485a9db31956d3c5ceb3bb96fa837f91b8819ab1` |
| Medium 500 | `59d54773ae88482cf012e07061f455097466faaa9ee9bbc1b4e0abbda4d76599` |
| Semibold 600 | `7509e63ece8e722630a98ba807ff8241287cc24ba437d5c1f22494f4746ed519` |
| Bold 700 | `4a97ba57e97c7b4ca1da8f87fad82e84f330a9a8336059185253378eabb9c6c5` |

**Engineering validation now complete for the frozen experimental candidate.** This does **not** establish owner approval of style, reference fidelity, universally accessible body-text legibility or on-device Compose/TalkBack integration. Shipping fonts and Android application assets are still unchanged. The owner-directed single-branch quiet-lab policy remains in force, with no font PR open and no routine font push CI.


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

All commands are local-first and do not push. `font_lab_build_all.sh` explicitly rejects incorrect source counter winding. Install the pinned font dependencies first. The final small-size-corrected [full-family artifact bundle](https://github.com/gamesbyian/Chunks/actions/runs/40817681146) contains the actual compiled CFF specimens, per-size raster counter audit and derivative family evidence. Preserve the frozen exact-head source and binary hashes when evaluating any additional glyph edits.

**Remaining gates (not automated engineering failures):** owner/editorial image-by-image style approval against the intended original; accessible Android 200%/TalkBack device evaluation; and an explicit owner decision whether to promote the candidate into shipping assets. Track final decisions on [issue #347](https://github.com/gamesbyian/Chunks/issues/347). The single-branch isolation contract remains authoritative in [FONT-LAB-OPERATIONS](CHUNKS-FONT-LAB-OPERATIONS.md).
