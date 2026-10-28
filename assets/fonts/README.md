# Chunks font asset catalog

Two font families intentionally coexist. **Only one weight of the primary family is currently installed in Android.**

| Asset | Status | Purpose |
| --- | --- | --- |
| [Chunks Regular](chunks/Chunks-Regular.otf), [Medium](chunks/Chunks-Medium.otf), [Semibold](chunks/Chunks-Semibold.otf), [Bold](chunks/Chunks-Bold.otf), [ExtraBold](chunks/Chunks-ExtraBold.otf) | Generated production candidates, all five original weight ranges | General-purpose baseline; based on SIL OFL Baloo 2 vectors with custom source-traced Chunks C/h/u/n/k/s letters |
| [Primary family metrics](chunks/chunks-font-metrics.json) | Reproducible | Measured kerning, letter spacing and vertical recommendations per weight |
| [Reference Panel ExtraBold](experimental/ChunksReferencePanel-ExtraBold.otf) | Experimental, **not shipped** | Uses all 62 reference-letter vectors (26 uppercase + 26 lowercase + 10 digits), inherits remaining glyphs from the licensed base |
| [Reference Panel ExtraBold Optical](experimental/ChunksReferencePanel-ExtraBold-Optical.otf) | Experimental, **not shipped** | Additional GPOS optical adjustments against the replacement glyph outlines |
| [Reference Panel spacing](experimental/reference-panel-spacing.json) | Experimental measurement | Pair gap, tracking and Latin/multiscript leading audit |
| [Reference-alphabet SVG masters](source/reference-alphabet/) | Experimental and independently editable | Every 0–9, A–Z and a–z in an individual numbered vector, with provenance manifest |
| [Reference-traced wordmark](source/reference-traced-wordmark.svg) | Editable independent font master | Six original branded glyphs |
| [Stroke-based logo alternative](source/wordmark-stroke-masters.svg) | Alternate draft | Preserved simplified stroke variants |
| [Upstream Baloo 2 variable vector](source/Baloo2%5Bwght%5D.ttf), [OFL license](source/OFL.txt) | Licensed upstream source | Preserve with all derived fonts and distributions |

**Android status.** Only `app/src/main/res/font/chunks_extrabold.otf` is packaged for the native `ChunksBrandHeader`, with unchanged license `app/src/main/assets/licenses/Baloo2-OFL.txt`. Gradle's `verifyChunksWordmarkFont` checks byte identity against the master. Dense transfer/folder/metadata typography retains native/system font until device-scale acceptance; changing these requires deliberate design review. The standalone logo SVG remains authoritative for graphic artwork.

**Rebuild and test baseline:**
```sh
python -m pip install -r tools/requirements-chunks-font.txt
python tools/build_chunks_fonts.py --source 'assets/fonts/source/Baloo2[wght].ttf' --out assets/fonts/chunks
python -m unittest discover -s tests -p 'test_font_pipeline.py' -v
python tools/audit_chunks_fonts.py --fonts assets/fonts/chunks --report chunks-font-audit.json
```
Workflow: [chunks-font-family.yml](../../.github/workflows/chunks-font-family.yml). It rebuilds all five fonts, performs binary/shaping/glyph checks, renders a five-weight specimen/atlas, and checks collision regression. The artifact is regenerated from source rather than using the screenshots as font geometry.

**Rebuild experimental reference-panel alternatives:** See `tools/build_reference_panel_candidate.py`, `tools/optimize_reference_panel_kerning.py`, and [chunks-reference-panel-font.yml](../../.github/workflows/chunks-reference-panel-font.yml). The [source-alphabet generation workflow](../../.github/workflows/chunks-reference-alphabet.yml) re-extracts 62 SVGs from the original unmodified PNG and validates them.

**Visual and acceptance documentation.** [Base family and tracing](../../docs/design/CHUNKS-FONT-FAMILY.md), [five-weight iteration](../../docs/design/CHUNKS-FONT-ITERATION-2.md), [62 source glyphs](../../docs/design/CHUNKS-REFERENCE-ALPHABET.md), [experimental CFF variants](../../docs/design/CHUNKS-REFERENCE-PANEL-FONT.md).

**Unfinished:** [Issue #347](https://github.com/gamesbyian/Chunks/issues/347) is the live acceptance checklist for independently designed reference-panel 400–700 contours, per-weight optical refinement, accent fidelity, small-size rendering and user/device approval. Do **not** substitute synthetic outline thinning for finished source-designed weights, or silently replace the approved Android master with an experimental font.

**License:** OpenType derivatives of Baloo 2 remain subject to SIL Open Font License 1.1; the upstream copyright/attribution remains intact. Keep the OFL text in redistributed archives.

## Reference Panel five-weight design studies (non-shipping)

Four additional draft CFF/OpenType weights (400, 500, 600 and 700) can be reproduced from the 62 source-traced reference SVGs with topologically constrained offsets, protected narrow strokes, per-weight optical kerning and measured application tracking/leading. They live under [`experimental/weight-studies/`](experimental/weight-studies/) after CI generates them. Pair these with the separately preserved 800-weight [`ChunksReferencePanel-ExtraBold-Optical.otf`](experimental/ChunksReferencePanel-ExtraBold-Optical.otf). These drafts **do not constitute individually finished contour masters**. The source profiles, exact generator, five-face specimen and acceptance checklist are in [`CHUNKS-REFERENCE-WEIGHT-STUDIES.md`](../../docs/design/CHUNKS-REFERENCE-WEIGHT-STUDIES.md). CI never packages them into Android or overwrites the approved Chunks fonts.
