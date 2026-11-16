# Declarative settings registry and page composition

**Binding architectural direction (owner 2026-11-15).** Preserve the existing eight Options categories, immediate-apply behavior, and single owning store for every preference. Implement a **declarative, reusable setting definition layer** between domain-owned persistence/state and Compose presentation. This is an incremental refactor, not permission to rewrite working settings or reclassify unfinished features as implemented.

## Three separate responsibilities

1. **Setting definition**: stable semantic ID, label, help text, value type/control hint, scope (global, client, transfer), applicable capability, validation policy, effective-value/default provenance, owning read/write adapter, availability, and search aliases. Definitions do not directly perform torrent operations. One canonical definition per ID.
2. **Setting binding**: typed, reactive reads of actual effective values and authorized writes through the existing domain repository/controller. Do not introduce a second preference database. Existing secure credential storage remains separate from ordinary preferences. Protect write order and process-restart persistence. A remote operation is pending until independently verified where required.
3. **Page composition**: ordered groups of setting IDs and specialist screen/action references. A page determines placement, not the underlying value or persistence. The same setting may be shown from multiple routes without cloning its value, callbacks or defaults. Contextual use must bind the correct client or transfer identity.

Reusable Compose controls handle switches, choices, sliders, folder selection and text input; specialist existing screens (connection setup, rule editor, filesystem verification, destructive actions) remain specialist and may be referenced by page composition rather than forced into a generic switch renderer.

## Invariants

- Keep **one authoritative write path per setting** even when displayed from multiple pages. Never create two independent stores for the same preference.
- **Definition identity and instance identity differ**: a per-client setting has one definition but effective instances keyed by exact client identity; a transfer override is keyed by exact client plus torrent identity. Reuse never leaks values between clients.
- Global defaults, inherited client values, and transfer overrides must expose effective value, provenance and reset semantics. Do not overwrite a parent setting when editing an override.
- A setting's visibility and enabled state are distinct. An unsupported or unauthorized control must not look functional. Capability gates are evaluated against the selected actual client/backend. Do not infer rTorrent capabilities from another client.
- A setting may appear on multiple pages, but one canonical home owns discovery; secondary occurrences are contextual shortcuts or intentional shared controls. Search and deep links use stable setting IDs and route to the canonical home, retaining origin/back navigation.
- Distinguish **configuration** from a consequential command (Add, Delete, Move, server folder Create). Those operations retain verification/confirmation and safety guarantees.
- Avoid extra save/apply gestures for independently valid preferences. Keep transactional exceptions specified in the Options architecture.
- Reuse currently shipped preferences and IDs without migration unless necessary. If migration is necessary, test it across process death and old installs. Keep existing secure credentials isolated.
- Preserve Compose state/focus, TalkBack labels, 44–48 dp controls, search, root footer, and the eight category cards. Do not expose a future setting just to fill a page.
- Do not equate an Options category or navigation shortcut with an implemented setting. UI capability and persistence must both exist.

## Implementation decision and migration guards

Before extending the metadata foundation, make a bounded inventory of affected controls: stable semantic ID, owner store/key and all readers/writers, global/client/transfer scope, fresh-install versus existing-install default, explicit opt-out, effective-value provenance, override/reset behavior, canonical search location and runtime consumer. Classify each candidate as **preference, temporary view state, per-task override, consequential action, OS capability/status or composite editor**. Only persistent preferences and explicitly approved overrides belong in the typed settings value system. Do not add a toggle for a speculative ability or move a frequent contextual command into Options.

Choose the smallest conventional mechanism that fits the inspected code. AndroidX Preference, Compose with hoisted state, existing SharedPreferences/Keystore-backed repositories and DataStore are distinct options, **not a migration mandate**. `SettingsRegistry.kt` is currently a *metadata-only* catalog; introducing a full generic renderer or new storage layer is justified only when a shipping user journey and demonstrated duplication require it. A page's category, visual component and search entry do not constitute a second store. A typed per-client definition may have separate *instances* keyed by exact immutable client ID. The effective resolver must name its supported precedence (never assume global → client → transfer is universally valid), expose provenance and reset only the selected override.

Where only placement changes, prefer zero storage migration. If semantics or keys change, test old populated values including **absent, explicit false, empty, and unknown values** before/after install upgrade; preserve explicit revocation of network metadata/artwork consent and keep credentials protected separately. A change that saves a preference locally must still verify any actual downstream engine/provider behavior before claiming success. Rapid edits, failed writes, invalid typed text and uncertain remote applies require truthful feedback and must not allow stale completions to win.

Prior-art basis (research, not an external implementation dependency): [Android settings hierarchy](https://developer.android.com/develop/ui/views/components/settings), [Compose state](https://developer.android.com/develop/ui/compose/state), [DataStore](https://developer.android.com/topic/libraries/architecture/datastore/), [VS Code configuration contribution points](https://code.visualstudio.com/api/references/contribution-points) and [Apple settings placement guidance](https://developer.apple.com/design/human-interface-guidelines/settings). These support reusable definitions and context-aware presentation; they do not require JSON Schema, a form generator, or a class per toggle.

## Incremental adoption

**Phase 0 (foundation, safe):** establish a small typed metadata registry and ordered page declarations for verified existing settings, with uniqueness, scope and placement tests. It is descriptive until wiring is implemented; *existing screens and stores remain authoritative*. No user-visible behavior claim.

**Phase 1 (one vertical slice):** choose ordinary media presentation toggles whose current stores and controllers already work. Bind registered definitions to existing store/controller flows, render via shared controls in the current Artwork & metadata category, and prove toggling/relaunch/reset/search/deep links. Refactor without duplicating default values in code. Test old behavior before replacing presentation.

**Phase 2 (broader catalog):** inventory each shipped setting against source paths, storage keys/defaults, scope, owning read/write, and tested status; migrate other simple toggles and choices by domain. Include per-client binding tests using two distinct remote profiles and the Local client. Keep credential screens and advanced rules as custom page entries.

**Phase 3 (composition):** allow all Options subpages to declare ordered setting IDs and specialist entries. Preserve the current eight categories and search aliases. Move original `.torrent` file policy to **Downloads & storage** as a global preference; remove the duplicate Add-screen control only after the global preference is implemented, migrated and tested. Add may show a summary/deep link but may not silently override global policy.

## Required evidence before claiming complete

- Registry uniqueness, stable IDs, control type/scope, missing references, canonical homes, duplicate placements and intentional cross-page reuse.
- Existing preference value and default behavior identical before/after migration; immediate persistence after relaunch and rapid toggles.
- Two remote clients with distinct values (no cross-profile leakage), transfer overrides and reset semantics where applicable.
- Search to individual settings, screen-reader naming, back/focus, narrow handset and large text acceptance.
- Media privacy/consent off stops new online work; changing appearance does not mutate artwork retrieval policy.
- Regression checks on Add external intents, remote folder browser and torrent lifecycle. Compare test contracts with current UX before running expensive suites.

## Current code landmarks

- `OptionsCategories.kt` defines the eight visual/navigation categories and search synonyms.
- `MainActivity.kt` still dispatches route/shortcut choices explicitly.
- `media/MediaSettingsStore.kt` is an existing authoritative domain preference store.
- `OPTIONS-INFORMATION-ARCHITECTURE.md` governs category taxonomy, scope, persistence and immediate apply.

The initial `SettingsRegistry.kt` is only a **non-UI metadata foundation** for selected shipped media preferences, not a second settings store, a replacement screen or evidence that the app has a schema-driven renderer. Continue according to the single ranked `WORK-QUEUE.md`; do not create a new development queue.
