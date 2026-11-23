# Chunks visual specimen atlas: implementation plan

**Status:** Owner-requested plan; source-exact **pilot selection completed 2026-11-23**, not final accepted atlas. See [pilot evidence receipt](design/visual-atlas/PILOT-2026-11-23.md): 17 representative genuine emulator PNGs identified across two existing test campaigns, with fixture limitations. Tall scrolling, route/component inventory, consistent app-shell geometry and approved atlas deliverable are still open.  
**Purpose:** Produce a compact, source-derived visual reference of the *actual rendered Android app*, not an exhaustive interaction/state test suite or a substitute for functional acceptance.  
**Authority:** [UX](UX.md), [feature experience contract](FEATURE-EXPERIENCE-CONTRACT.md), [test architecture](TEST-ARCHITECTURE.md), [CI policy](CI-POLICY.md), [documentation standard](DOCUMENTATION-STANDARD.md). If rendered code diverges from approved designs, record both the observed implementation and the governing design without silently editing the image to conform.

## Outcome and strict budget

Deliver **10–20 final screenshots**, targeting **15–17**, sufficient for a human to audit the app's overall style, actual font sizes and weights, control dimensions, shapes, surface hierarchy, colour palette, spacing, text fields, switches, checkboxes, radio/selection controls, menus, sheets, dialogs, status indicators, illustrations, artwork slots, navigation treatments and as many distinct symbols/icons as reasonably possible. Do **not** strive to show every sentence, label, button permutation, backend state, or screen-state Cartesian product.

Use a phone-width presentation matching the Pixel 6 Pro reference configuration. Allow **tall captures** (e.g. approximately 412 logical dp wide by 1,800–2,500+ dp high) when that shows useful components at their *natural size*. Do not shrink content to fit more controls. Capture conventional viewport images where height, keyboard/insets, overlays or fixed footer geometry is part of what is being audited. Prefer genuinely scrollable-content stitching, or capture-only tall-host rendering, to a fabricated screenshot that changes production layout.

An atlas image is a **visual observation only**. It cannot attest to actual Add/Start/download/seed, permission safety, connectivity, accessibility semantics or cross-client functional correctness.

## Required outputs

Store versioned, reviewable outputs in a dedicated atlas artifact directory chosen after inspecting existing image/test conventions (suggestion: `docs/design/visual-atlas/` for the README, manifest and selected representative images; large full-resolution PNGs may remain CI artifacts with a durable index rather than bloating Git history):

1. **Atlas README / contact sheet:** Contact sheet of all final images, linked to individual original-resolution PNGs; distinguish tall captures and viewport captures; note source SHA, APK SHA-256 when installed, device profile, Android API, density, font scale, theme, locale and capture method.
2. **Machine-readable manifest** (`atlas-manifest.json` or YAML): for each screenshot, stable ID, route, fixture, prerequisite state, exact captured elements and meaningful visual variants, source component references, capture method, dimensions, SHA-256 and known limitations. No private data or endpoints.
3. **UI vocabulary inventory:** stable semantic keys and source file/component locations for visual primitives and screens; categorize repeated instances as the same component with explicit variants.
4. **Coverage report:** matrix of visual components/variants versus screenshots; identify uncovered items and the reason each was excluded or not reproducible. Include an explicit count of final screenshots (must remain 10–20).
5. **Reproduction instructions:** local and/or GitHub Actions commands, immutable fixture configuration and how to regenerate images at an exact Git SHA. Separate reproducible automated captures from manual/stitched ones.
6. **Audit worksheet:** checklist per screenshot for typography, geometry, touch-target proximity, colours/contrast, clipping/overflow, alignment, icon consistency, sheets/dialog elevation, loading/error affordances and major layout inconsistencies. Record observations rather than auto-changing product design.

## Execution procedure

### Phase 1: Derive a UI and visual-state map from source

- Read current `main`, active PRs and [UX](UX.md) before selecting screenshots. Inspect Compose navigation destinations, all root and subordinate screens, nested flows, alerts/dialogs, modal sheets, popup menus, input widgets, reusable composables, design tokens, icons/assets and conditional branches. Do not rely solely on named screen files; follow navigation, state hoisting and conditional rendering through to leaf components.
- Build a screen inventory with at least: Welcome/first-run; Transfers/empty/populated/expanded; universal Add (source, client, destination, options and outcome); Connections (empty, roster, manage/connect); transfer Details including files/priorities; Options and representative settings pages; relevant media/artwork handling; reusable dialogs and permission/error surfaces. **Verify actual existence**: approved, planned-only features do not become capture requirements for the current binary.
- Record each visually distinct *component family*: filled/outlined/text/danger/disabled buttons; footer tabs and toolbar icons; chips/segmented choices; switches/checkboxes/radio choices; text/URL/password/numeric fields; helper/error messages; selectors/folder controls; progress and speed/ratio labels; transfer rows/artwork placeholders; expandable sections; cards; banners/toasts/snackbars; dialogs/sheets/menus; empty/loading/error illustrations; icon and typography tokens.
- For each family, identify meaningful appearances (e.g. selected vs unselected, checked vs unchecked, enabled vs disabled, focused vs unfocused, empty vs populated, error vs success). Collapse states that render equivalently. Flag combinations that materially alter geometry, not every possible textual value.
- Trace visual elements back to reusable source composables and semantics/test tags where possible. Do not make assumptions about UI based on unimplemented specs. For every missing or inaccessible component, record the distinction between *specified*, *implemented but unreachable*, and *visible in the installed app*.

### Phase 2: Select a minimal representative set

- Create a candidate-screen-by-visual-feature matrix, weighting **unique components and visual treatments** more heavily than duplicate text or backend combinations. Give secondary weight to common user journeys (Transfers, Add, Details, Connections, Options), useful contrasting appearances, and the primary phone's genuine layout.
- Apply a greedy weighted set-cover selection and a human editorial pass. Prefer specimens that naturally show many different controls without turning into synthetic, unbelievable UI. Remove screenshot candidates that contribute no distinctive elements.
- Initial **planning budget only** (reallocate after actual code inventory): Transfers 2; Add across genuinely distinct client/option arrangements 3; Connections/manage 2; Details/files/artwork 3; Options/settings 2; overlays/dialogs/errors 3; unusual remaining visual families 2 = **17**.
- Deliberately choose fixtures that make one populated Transfers image show mixed transfer statuses, differing artwork/icon treatments, a selected client chip, toolbar controls, compact cards and (if naturally possible) one expanded card. Use another image for empty/focus/error or other uncovered geometry.
- Select a small number of visually distinct Add client configurations; do not capture five identical Add forms merely because five backends exist. If one client introduces a distinct folder picker, capability warning or authentication field, account for it.
- Keep interactive overlays separate when they obscure important underlying controls, or make a clearly labeled **composite contact sheet**. Never mislabel a manually combined image as one unmodified app screenshot.

### Phase 3: Deterministic actual-Compose rendering

- Reuse existing Android instrumentation/Compose screenshot infrastructure first. If missing, add a bounded **debug/test-only atlas fixture host** or instrumentation entry points that render production composables with fake read models and explicit local UI state. Never introduce production-visible debug controls, mutate real remote clients or broaden startup permissions.
- Fixtures must be stable: fixed representative transfer identities/names, reasonable lengths including one long title, fake progress/speeds, known client colours, artwork provided by repository-licensed or generated test fixtures, error/help text, deterministic clock/status and disabled transitions. Avoid live user torrents, tracker passkeys, server addresses, tokens and real private artwork.
- Document which states require true installed navigation versus isolated composable hosting. Prefer assembled Android screens for the final atlas; isolated component fixtures may fill gaps when a component is unreachable or would require unsafe operations. Label this distinction prominently.
- Standardize emulator/device and font scale, density, theme (default app theme first), locale, system bars, display cutouts, animation clocks and asynchronous image loading. Wait on explicit UI idle/data-ready signals rather than fixed delays; disable transient status changes in fixtures.
- Preserve the **real control geometry and text wrapping**. Capture a reference phone-width viewport in addition to any elongated images when height/insets/fixed position matter. Verify actual persistent bottom footer placement at ordinary device height; a tall viewport alone may create an unrealistic amount of free vertical space.

### Phase 4: Tall capture strategy and image integrity

Choose per screen in this order:

1. **Native long screenshot / scroll capture** on the installed emulator, when supported and faithful to the same screen width, sticky elements and viewport layout.
2. **Deterministic scroll-and-stitch:** capture consecutive scroll positions with a calibrated overlap; match pixels in a stable content anchor, deduplicate overlaps, and separately preserve a normal-height screenshot for pinned header/footer and modal context. Check that lazy items have rendered before each tile. Document how sticky headers/footers, shadows, insets, animations and edge gradients were treated.
3. **Tall-height test host** only where the Compose layout genuinely behaves as the same phone-width content layout; explicitly label it `tall-host` because viewport height can change responsive/sticky behavior.
4. **Supplemental annotated composite** only if none of the above is honest/useful. Keep raw source frames, label seams/overlays, do not forge native geometry or use generated images to imitate actual Compose output.

Capture at native resolution and preserve pixel accuracy: no resizing, post hoc text rewriting, generative redraw, UI restyling or colour adjustments. Trim only extraneous emulator chrome if provenance is retained. Verify no doubled controls, dropped lazy-list rows, discontinuous progress bars, clipping, shifted icons, mismatched status bars or duplicated footer after stitching.

### Phase 5: Coverage verification and review

- Generate the feature-to-screenshot matrix automatically from the inventory/manifest. Check every **distinct visual family** appears at least once, and every distinctive state that affects shape, layout or colour appears somewhere or is explicitly recorded as uncovered.
- Compare final output with the source-derived inventory **manually**, inspecting full-resolution PNGs rather than assuming successful screenshot tests prove correct appearance. Compare against approved design references separately and flag current-vs-approved differences without silently substituting speculative visuals.
- Audit one representative at normal Pixel height, at least one long scrolling specimen, focused text/IME when material, a representative dialog, a modal/sheet or menu, a settings page, and meaningful empty/error states.
- Optionally attach additional focused diagnostic images during development, but **only 10–20** curated final screenshots enter the delivered atlas. If coverage will exceed 20, consolidate or document omissions; do not silently expand the budget.
- Treat results as a **baseline snapshot at the exact source SHA**, not as automatic visual regression approval. On later UI changes, locate affected components via the manifest, recapture only impacted specimens and allow human approval before replacing accepted reference PNGs.

## Proposed capture catalogue (revise after source inspection)

| Candidate | Elements to exhibit | Preferred capture |
| --- | --- | --- |
| T1 populated Transfers | Brand, root footer, source chips, search/filter/sort, progress, artwork, compact row, status icons | Tall scrolling |
| T2 Transfers alternate | Empty/onboarding, expanded row and/or distinct failure/focus appearance | Viewport or tall |
| A1 Universal Add base | Source field, paste/picker, client choice, Place, Start, primary action | Tall |
| A2 Add client-specific | Real distinct destination folder/selection/capability widgets | Tall |
| A3 Add conditional/outcome | Checked/unchecked options, validation, disabled action, outcome | Tall/viewport |
| C1 Connections roster | Saved connection cards, colours, status, add/manage affordances | Tall |
| C2 Connections manage | Form fields, credential treatment, toggles, selectors and error/help | Tall |
| D1 Details identity | Poster/placeholder, metadata, original title, source and primary controls | Tall |
| D2 Details files | File list, folder hierarchy, priority choices, per-file status/icons | Tall |
| D3 Details advanced | Limits, seeding, secondary actions and distinct cards/inputs | Tall |
| O1 Options index | Coloured index cards and option navigation | Viewport/tall |
| O2 Settings specimen | Toggle, check/radio, slider, dropdown, text and helper styles where actually present | Tall |
| M1 Confirmation dialog | Destructive or consequential confirmation, primary/secondary actions | Viewport |
| M2 Selector/sheet/menu | Modal surface, selection, separators and iconography | Viewport |
| M3 Failure/permission | Non-sensitive warning/error/denied/empty messaging | Viewport |
| X1–X2 Gap fillers | Any genuinely distinct controls not yet represented | As appropriate |

These are **candidate labels, not a claim that all screens or widgets presently ship**. Consolidate, replace or remove candidates based on the actual inventory.

## Tooling and CI discipline

- First survey existing `app/src/androidTest`, screenshot helpers, Android emulator workflows, image artifacts and any Compose preview harness; extend an existing compatible gate rather than automatically creating a new workflow or dispatching the entire Android matrix.
- Provide one opt-in, bounded regeneration command/workflow with a **fixed artifact-retention policy**, build-once reuse where safe, screenshot count/size guardrails and deterministic failure diagnostics. Keep expensive emulator work manual/on-demand unless the CI policy justifies an automatic narrowly scoped trigger.
- Make static source inventory and atlas manifest validation cheap/offline when practical. Separate offline successful checks, installed screenshot capture, visual human review and genuine real-transfer acceptance in evidence.
- Capture/source manifest should include a Git SHA and ideally installed APK digest; ensure private data never enters public screenshot/log/artifact paths. Existing [CI policy](CI-POLICY.md) determines accepted source/provenance standards, not this document.
- Avoid the owner's PC/Desktop Commander; GitHub Actions Android emulators or another authorized isolated environment suffice. Do not connect to actual Seedhost or mutate any real torrent for atlas production.

## Acceptance and completion gate

- [ ] Repo-derived route/component/state inventory reviewed against source, with planned-only surfaces distinguished.
- [ ] Final delivered set includes **10–20**, ideally 15–17, full-resolution actual-Compose screenshots, with **at least one genuine useful tall scrolling image** if content warrants it.
- [ ] Every visually distinct implemented UI element family is shown, or individually documented as an exception; distinct styling variants are represented without pursuing meaningless permutations.
- [ ] At least one regular-height device capture confirms actual phone proportions, system insets, pinned footer and overlay geometry.
- [ ] Tall captures are legible at actual phone width, with overlap/seam validation and stated capture method.
- [ ] Screens use safe deterministic fixtures; no live account, endpoint, credential, passkey, private torrent, or copyrighted third-party artwork leaked.
- [ ] Manifest, coverage matrix, README/contact sheet, provenance, rerun instructions and audit worksheet accompany the images.
- [ ] Human full-resolution inspection identifies major geometry, typography, spacing, control-shape and icon inconsistencies; failures and uncovered designs remain visible rather than claimed accepted.
- [ ] Capture does not mutate production UX, change engine safety contracts, or inflate CI fan-out; functional tests remain independent.

## Relationship to current work

This is a **bounded owner-requested visual audit deliverable**, not a replacement for [WORK-QUEUE](WORK-QUEUE.md), a second active feature backlog or evidence that backend-first completion is finished. Plan and implement the atlas when explicitly dispatched; do not let it derail active exact-head functional/integrity gates. Maintain the plan here, keep executed receipts in [QUALITY-LEDGER](QUALITY-LEDGER.md) and the actual implementation status in the appropriate existing coverage/queue documents.
