# Chunks visual design references

**Owner-selected direction (2026-10-26).** Six unmodified PNGs from the original repository root, renamed and organized for implementation. The overview is the primary visual reference; the other five are supporting explorations, not exported code, fonts, SVGs or accepted screenshots.

| Reference | What to take from it |
| --- | --- |
| [01: Overview visual direction](01-overview-visual-direction.png) | Primary Chunks look: chunky shapes, expressive wordmark, high-contrast dark UI, bright modular cards, dual-panel concept and segmented transfer progress |
| [02: Iconography contact sheet](02-iconography-contact-sheet.png) | Navigation icons, functional symbols, file types, warnings, state chips and decorative accents |
| [03: Typography and wordmark](03-typography-and-wordmark.png) | Distinctive display typography, hierarchy for cards/labels/numerals, branding exploration; actual font licensing and selection still required |
| [04: Brand lockups and app icon](04-brand-lockups-and-app-icon.png) | Primary/compact/reversed logo, adaptive launcher and splash inspiration; create editable vector assets |
| [05: UI components and states](05-ui-components-and-states.png) | Segmented progress bars, list cards, tabs, storage meters, action buttons, warning, disabled and error states |
| [06: Colour, illustrations and stickers](06-colour-illustrations-and-stickers.png) | Palette, geometric background forms, 3D-style symbol family, playful stickers and empty-state art |

## Implementation status (first limited native slice)

[Merged #222](https://github.com/gamesbyian/Chunks/pull/222) introduces an actual Compose `ChunksBrandHeader` and stronger transfer-card outline. These consume the overview's bold modular silhouette with native text/geometry and accessibility rather than rasterizing any reference. Exact-head Android/JVM, API35 and emulator atlas checks passed (see [QUALITY-LEDGER](../../QUALITY-LEDGER.md)), but the screenshot pixels have not yet been independently reviewed and no owner sign-off is implied. Merged [#223](https://github.com/gamesbyian/Chunks/pull/223) adds a small original seven-block C Android adaptive launcher vector, plus a monochrome themed-icon path; the generated brand sheet remains inspiration and has not been converted into app bitmap assets. Full font licensing, more vector assets, dense lists, all status states, actual launcher screenshot inspection, and physical handset review remain open.

## Implementation notes

- Reproduce the **segmented progress bar** as a proper progress indicator with a continuously accurate percent; segment boundaries are decorative and must not falsify completion or conceal intermediate values.
- Actual navigation, terminology, capabilities, safety and engineering contracts are authoritative in [PRODUCT](../../PRODUCT.md), [UX](../../UX.md), [ARCHITECTURE](../../ARCHITECTURE.md), and [STORAGE-AND-EXTRACTION](../../STORAGE-AND-EXTRACTION.md). Mockup labels like *Workspace* and *Discover* do not replace the Transfers / Places / Collections / Servers model.
- The pictured cross-place Move/Sync, storage caps, auto-extraction and deletion affordances must be **capability gated**. In particular, auto-extraction is opt-in/off by default, display-alias renames never change backing data, and deleting sources needed for seeding requires explicit review and verified impact.
- Text, media images, numbers, UI labels, fonts, icons and hex colour labels inside the generated art are **illustrative**, not independent specifications, licence grants or reusable production assets. Select actual licensed typefaces; author original vector assets; build components natively.
- Keep 48dp-class touch targets, 200% font-scale support, keyboard/TalkBack access, reduced-motion alternatives, redundant text/icon status and high contrast. These concepts are not device acceptance evidence.

**Provenance:** Original Git blob identities were reused unchanged, so the six images are byte-for-byte preserved. Old filenames correspond to the sorted roles: 01 `file_000000007c4081fb9cdda350378ec751.png`, 02 `file_00000000c4cc81fbb2d135aec09d7077.png`, 03 `file_00000000664881fb83a054424ab2f5f9.png`, 04 `file_00000000734881f5996b1a209fc3a9ec.png`, 05 `file_00000000cdd081f5a12bc10d437725fd.png`, 06 `file_00000000c2bc820c88422f3ea61b98d1.png`.
