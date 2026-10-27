# Chunks vector asset library

[Reference PNGs](../reference-images/README.md) · [Production plan and per-category policy](../reference-images/SVG-ASSET-PRODUCTION-PLAN.md) · [Master inventory](master-inventory.json) · [Visual gallery](gallery.html)

This is an original, **all-variants-preserved** SVG collection. No source PNG is modified or raster-embedded. All currently committed files are **vector drafts** awaiting reference-fidelity review. Their inclusion is not an endorsement over one another.

**Shipping invariant:** the approved launcher remains [../approved/chunks-app-icon-master.svg](../approved/chunks-app-icon-master.svg), with the existing generated Android VectorDrawable. Everything under `launcher-alternatives/` is explicitly non-shipping.

- `icons/`: 108 64×64 glyph/doodle masters with stable category/name and `icons/catalog.json`.
- `brand/`, `launcher-alternatives/`, `illustrations/`: 26 independently renderable alternatives, catalogued in `variants-catalog.json`.
- `characters/`: 39 dimensional/flat/monochrome character composites and separable face/top/side/pictogram layers.\n- `master-inventory.json`: 775 named targets, including not-yet-created glyphs, lettering, characters, UI components and reference-derived variants. `planned` entries are not files.
- `gallery.html`: static searchable viewer of all present SVG drafts with dark/light previews.
- `python3 tools/validate_svg_library.py`: validates manifest, XML, self-contained SVG structure and file coverage. CI runs it on relevant PRs.

Palette experiments are retained rather than reconciled by fiat: Brand Essence uses pink #FF4EC4, purple #A56BFF, blue #3B82FF, yellow #FFD93D, green #22D97A, cyan #00D4FF. The typography sheet supplies a distinct colour/type hierarchy (for example slightly different purple/pink/cyan and separate warning/error colours). A visual token-selection pass is still pending.

**Honest fidelity:** these are carefully named original vector interpretations, not automatic tracings or guaranteed exact reproductions. The hand-drawn logo is vector-outlined; some fixed tagline and sticker lettering still has SVG text fallback until approved font outlines are available. Native Android text and controls must not be substituted with screenshots.
