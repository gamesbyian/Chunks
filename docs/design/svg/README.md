# Chunks vector asset library

[Reference PNGs](../reference-images/README.md) · [Production plan and per-category policy](../reference-images/SVG-ASSET-PRODUCTION-PLAN.md) · [Master inventory](master-inventory.json) · [Visual gallery](gallery.html)

This is an original, **all-variants-preserved** SVG collection. No source PNG is modified or raster-embedded. All currently committed files are **vector drafts** awaiting reference-fidelity review. Their inclusion is not an endorsement over one another.

**Shipping invariant:** the approved launcher remains [../approved/chunks-app-icon-master.svg](../approved/chunks-app-icon-master.svg), with the existing generated Android VectorDrawable. Everything under `launcher-alternatives/` is explicitly non-shipping.

- `icons/`: 108 64×64 glyph/doodle masters with stable category/name and `icons/catalog.json`.
- `brand/`, `launcher-alternatives/`, `illustrations/`: 26 independently renderable alternatives, catalogued in `variants-catalog.json`.
- `characters/`: 39 dimensional/flat/monochrome character composites and separable face/top/side/pictogram layers.
- `approved-derived/`: 25 exact-geometry layers/alternative compositions extracted from the existing canonical launcher SVG (still non-shipping variants).
- `ui/`: 96 reusable vector UI **specimens**, not actual input controls; Android must implement them with dynamic semantics and text.
- `palettes/`: 32 standalone swatches/strips, preserving competing colour tokens and flagging approximate labels.
- `typography/`: 306 individual exploratory stroke-path glyphs spanning A–Z, a–z, digits, punctuation and symbols in three stylistic families. Not a licensed font or faithful final wordmark; stroke-expansion and full font metrics remain future requirements.
- `new-variants/`: 60 independent action-symbol and decorative alternatives.
- `further-variants/`: 34 launcher-resolution, media-cover and Android design specimens.
- `character-parts/`: 16 new standalone folder/material/cube layers; 19 equivalent planned roles reuse their existing SVG masters without duplicating files.
- `scenes/`: 36 character combinations, promotional scenes and editable empty sticker shapes.
- `brand/reference-traced/`: original PNG-silhouette-derived Chunks lettering, fixed taglines and alternate logo lockups, including constituent glyphs. Only source-contour precision is measured; manual approval remains open.
- `fidelity/`: 12 additional independently renderable sticker and brand-screen SVG candidates, generated deterministically from original reference contours, checked-in Chunks font outlines and approved SVG geometry; no live text or embedded PNGs. [Fidelity and review notes](fidelity/README.md).\n- `master-inventory.json`: 836 named targets: 815 distinct SVG drafts, 20 exact graphic aliases, zero missing first-pass slots (optical review and native integration are still outstanding), 1 protected launcher source.
- `gallery.html`: fully searchable static library of 815 current SVG drafts, with category filters, dark/light/checkerboard surfaces, side-by-side comparison and an experimental layer preview.
- `python3 tools/validate_svg_library.py`: validates manifest, XML, self-contained SVG structure, gallery coverage and file coverage. CI runs it on relevant PRs.

## Experimental layer composition workbench

The [gallery](gallery.html) supports a four-layer, **non-destructive vector workbench**: select differing logo, mascot, sticker or icon variants; drag the active layer on the preview or use keyboard-accessible sliders to adjust position, scale, rotation and opacity; reorder layers; change the background; and export a **self-contained SVG** containing actual source paths and gradients. Save or reload a compact JSON recipe to preserve editable layer identities and settings. Identical internal IDs are namespaced in exported files.

For browser SVG export, load the gallery from an HTTP(S) origin that also serves the whole `docs/design/svg/` tree (browser security normally disallows `fetch()` of adjacent local `file://` SVGs):

```bash
python3 -m http.server 8765 --directory docs/design/svg
# Open http://localhost:8765/gallery.html
```

No external image loading, bitmap embedding or executable SVG content is permitted. The browser refuses unknown recipe asset IDs and unsafe/path-traversing paths. The composed SVG is a new user-created **experimental** asset, not an approved brand file; original variants, reference PNGs and Android launcher resources remain untouched. This is an in-browser editor of static SVG sources, not native Compose component generation.

The engine has an independent Node/stdlib-XML test: `node --test tools/tests/svg_mixer.test.cjs`. The gallery preserves its existing 96-at-a-time lazy-card pagination.

Palette experiments are retained rather than reconciled by fiat: Brand Essence uses pink #FF4EC4, purple #A56BFF, blue #3B82FF, yellow #FFD93D, green #22D97A, cyan #00D4FF. The typography sheet supplies a distinct colour/type hierarchy (for example slightly different purple/pink/cyan and separate warning/error colours). A visual token-selection pass is still pending.

**Honest fidelity:** these are carefully named original vector interpretations, not automatic tracings or guaranteed exact reproductions. The hand-drawn logo is vector-outlined; some fixed tagline and sticker lettering still has SVG text fallback until approved font outlines are available. Native Android text and controls must not be substituted with screenshots.
