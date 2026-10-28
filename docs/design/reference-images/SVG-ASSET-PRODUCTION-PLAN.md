# Chunks SVG asset production plan and complete visual inventory

**Owner directive · 2026-10-27:** Every inconsistent reference-image variant is to receive its **own** independent SVG. Do not resolve away alternatives or select an undocumented winner. All six reference PNGs remain untouched, immediately alongside this plan.

**Authoritative per-asset ledger:** [Master inventory](../svg/master-inventory.json) has **822 named entries** (778 SVG draft files, 19 direct aliases to existing masters, 24 planned reference-fidelity slots, and one existing approved SVG). Each entry has an ID, category, origin, status and exact file path when present. The inventory is the exhaustive tracking index; this document explains how to make and test assets. The CSV-like task list is *not* a competing product work queue: [WORK-QUEUE](../../WORK-QUEUE.md) remains the repo's ranked authority.

## Reference map

| PNG | Roles / variant families to retain |
| --- | --- |
| [01](01-overview-visual-direction.png) | Screens, branded four-character header, transfer cards, storage, Workspace, details, media covers |
| [02](02-iconography-contact-sheet.png) | Navigation, actions, status, folders/file media, chips, decorative marks, monochrome/colour |
| [03](03-typography-and-wordmark.png) | Letters A–Z, a–z, 0–9, punctuation, display/marker/UI families, wordmark, hierarchy and alternates |
| [04](04-brand-lockups-and-app-icon.png) | Primary/stacked/compact/reverse/mono wordmarks, launcher concepts, sticker/tagline and minimum sizes |
| [05](05-ui-components-and-states.png) | Segmented progress, cards, tabs, action buttons, chip states, toggles, file rows, modals and warnings |
| [06](06-colour-illustrations-and-stickers.png) | Four dimensional mascots, layers, backgrounds, hearts, doodles, speech stickers and palettes |

## Non-destructive variant policy

1. Save each visually distinct proposal under a stable variant ID and a standalone SVG. Preserve contradictory palettes, dimensional vs flat forms, shaded vs flat marks, dark/light/mono, and every logo arrangement.
2. Even a weak or redundant variant remains available to mix and match. Use catalog labels, provenance and visual QA instead of silently deleting or replacing it.
3. Keep **approved/shipped** separate from **experimental**: `../approved/chunks-app-icon-master.svg` is still the sole launcher source; `tools/generate_launcher_vector.cjs` continues generating the Android VectorDrawable. Never point Android at a variant without explicit later approval. Keep the original approved PNG untouched as reference.
4. A true SVG contains local vector geometry. Do not wrap the six PNGs or another bitmap in `<image>`. Illustrator-style variants must keep front, rear, side, highlights, eyes, mouths and shadows as independently extractable elements or sub-assets.
5. Decorative glyphs, brand composites and icons may have independent masters. Component **states** should mostly share native Android Compose geometry and dynamic accessible text; store representative vector specimens without replacing real controls with inaccessible image controls.
6. Exact reference typography cannot be asserted by visual guessing. Preserve display-outline draft vs licensed UI font vs text-based placeholder distinctly. Do not bundle or redistribute unlicensed font binaries. Fixed approved wordmark and tagline may be finalized as outlines; editable regular application text must remain text.

## Coverage categories (every named sub-item is in the master ledger)

- **Identity:** wordmark black/grey/white/purple offset, mini/compact/horizontal/stacked, dark inverse, monochrome, tagline options, slogan stickers, app header, splash and hero.
- **Launchers:** approved colourful folder-with-blocks and white marks; alternate yellow/cream/black smiling folder, flat-folder variant, purple C, confetti C, interlocking cubes, concept folder with blocks, notification and tiny sizes.
- **Character construction:** folder shell, rear wall, rim, tab, face, eyes, smile, side/extrusion, highlights/shadows; green arrow cube, purple database cube, coral media cube; simple coloured cubes; original loose composite lineup and poses.
- **Functional symbols:** Transfers, Workspace, Storage, Discover, Settings, add/import/export, move/copy/sync, torrent control, speed/peers, storage/cleanup, files, devices, servers, search/menus/chevrons, status and alert.
- **UI specimens:** segmented and multicolour progress, storage allocation and legend, cards and rows, active/inactive filter chips, buttons, tabs, toggle/checkbox/radio states, panels, drop zones, dialogs, banners, metric strips.
- **Decorations:** hearts, stars, sparkles, crown, two/three mark bursts, brush lines, marker underlines, blobs, splashes, dots, sprinkles, corner forms, arrows and speech stickers.
- **Typeface specimens:** independent SVG glyph per uppercase/lowercase, digit, punctuation and symbolic character for display/marker/compact specimens; weights, tracking and text-size role samples. Actual app fonts require licensing and separate integration.
- **Scenes:** onboarding, empty state, four-character promotional art, dark/light hero, storage and cleanup, Workspace, transfer-details visuals and optional sample-media thumbnail artwork.
- **Platform examples:** Android status and adaptive-icon masks, sample handset frames and reduced-detail tiny sizes. Keep platform UI native in production.

## Physical layout

- `docs/design/reference-images/`: six immutable PNG references and this plan.
- `docs/design/approved/`: canonical approved SVG and historical reference PNG.
- `docs/design/svg/icons/<category>/`: independent 64×64 icon and doodle masters.
- `docs/design/svg/brand/`: outlined-logo drafts and independent tagline/wordmark lockups.
- `docs/design/svg/launcher-alternatives/`: *non-shipping* concept alternatives.
- `docs/design/svg/illustrations/`: composed, editable illustrations.
- `docs/design/svg/master-inventory.json`: all present and outstanding granular deliverables.
- `docs/design/svg/gallery.html`: visual browsing of every currently committed vector draft.
- `tools/validate_svg_library.py`: zero-dependency asset-integrity test.
- `.github/workflows/svg-library-check.yml`: focused PR-time integrity check.

## Completion/acceptance gates

1. SVG must parse as XML, have a meaningful `viewBox`, and contain native vector content. No `script`, `foreignObject`, embedded bitmap, external reference or third-party hotlink.
2. Confirm actual appearance against the appropriate portion of the **real reference PNG**; catalogue provenance is not a claim of pixel-fidelity. Report approximations explicitly and preserve rejected variants.
3. Inspect 16/24/32/48/64 px glyph use and 96/160 px wordmarks, light/dark surfaces, and reduced-detail variants. Minuscule artwork may need simplified geometry.
4. In Android, draw from reusable vector primitives and native components, with 48dp-class touch areas, text scaling to 200%, TalkBack descriptions, keyboard/focus, sufficient contrast and text-based status redundancy.
5. For palettes, preserve both the six-colour Brand Essence sheet and the alternative Colour & Type sheet rather than overwriting. Use named theme tokens to compare in context.
6. A shipping integration needs an exact-head native build/screenshot and QUALITY-LEDGER record; human device visual approval remains separate. This catalogue is not an APK or acceptance claim.

## Current delivery checkpoint

- **Delivered as vector drafts:** 778 standalone files: initial functional graphics, dimensionally separated characters, 25 exact-geometry approved-icon derivatives, 96 UI state specimens, 32 palette variants, 306 true path-based exploratory glyphs, 60 further icon/doodle variants, 34 launcher/media/Android specimens, 16 additional character parts and 36 scene/sticker compositions. Reference-fidelity approval is **not** implied.
- **Already approved unchanged:** the official full-colour transparent launcher SVG, generated into the Android launcher via the existing pipeline.
- **Remaining:** 24 reference-fidelity tasks, focused on exact wordmark path tracing, hand-lettered slogans and sticker wording; 19 additional visual roles explicitly reuse already existing SVG masters, without clone files.
- **Important limitations:** the logos and taglines remain exploratory visual drafts rather than exact tracings; some fixed slogan stickers still use SVG `<text>` fallbacks. The 306 alphabet SVGs are stroked paths, not finished variable font outlines or OTF files. Neither the 96 UI component specimens nor the promotional artwork replaces native, accessible Android UI. The approved launcher SVG, its original reference PNG and all six reference sheets remain untouched.

**Next practical step:** visually compare the rendered gallery/contact sheets with the actual six reference PNGs and hand-refine the 24 outstanding lettering/wordmark assets. Use the named palette variants rather than unilaterally choosing one. After pixel-fidelity and font selection, adapt the corresponding elements into accessible native Compose components; physical Pixel 6 Pro acceptance remains separate.
