# Settings definitions, scoped values and Options composition: adoption plan

**Status:** approved architectural direction and implementation plan, **not evidence of a shipped registry**. This plan is subordinate to [the canonical Options/UX contract](OPTIONS-INFORMATION-ARCHITECTURE.md), [the active queue](WORK-QUEUE.md), [owner decisions](DECISIONS.md) and [the current feature-experience contract](FEATURE-EXPERIENCE-CONTRACT.md). This work does not create new user-facing features or top-level Options categories. Its priority remains tied to affected R1/R2 UX work, not a separate architecture campaign.

## Outcome

Make each persistent user preference independently identifiable, validated, scoped, and readable/writable through its existing owning store; compose Options pages and relevant contextual shortcuts from references to those definitions. Relocating a setting changes placement rather than persistence or feature semantics. Keep exact-client identity, approved defaults, opt-outs, user-selected overrides, existing data, failure feedback, accessibility and live engine behavior intact.

**Non-goals:** rewriting all existing SharedPreferences/other stores to DataStore; one class/file per toggle; JSON Schema interchange; inventing a ninth top-level category; auto-generated editors for connection profiles/rules; prematurely creating a generic remote policy engine; migrating untouched settings solely for architectural uniformity.

## Current inspectable implementation anchors

- `app/src/main/java/io/github/gamesbyian/chunks/OptionsCategories.kt`: eight category enum values, descriptions, search synonyms, card controls, and LocalOptionsShortcut routing. Its source explicitly says **navigation taxonomy only; preferences remain in existing single stores**. Preserve this existing visual taxonomy and category IDs while adding definition-level composition incrementally.
- `app/src/main/java/io/github/gamesbyian/chunks/MainActivity.kt`: current Options navigation, screen composition and client/transfer entrypoints must be traced on latest main before edits. Avoid a parallel navigation stack or duplicate state.
- `app/src/main/java/io/github/gamesbyian/chunks/media/MediaSettingsStore.kt`: example of existing authoritative media preference store; currently app-private SharedPreferences plus separate Android Keystore-backed optional provider credential. It is **not** a DataStore repository. Keep existing store and credential behavior while projecting it through definitions; any later storage migration needs an independently justified compatibility plan.
- [The canonical Options contract](OPTIONS-INFORMATION-ARCHITECTURE.md) already requires stable key, default, scope, provenance, applicable capability, validation, one writer, observable behavior, search, correct reset and immediate-apply semantics where independently valid.
- [The owner Add/Options/folder batch](WORK-QUEUE.md) requires changing original `.torrent` source-file handling to a global option, removing Add per-transfer radios while preserving verified source ingestion and archive/delete safety. Under the fixed eight-card taxonomy, use **Downloads & storage** with an **Add & files** subheading; do not invent a ninth root page. This is independent of the Android Open With intake bug and remote New folder/Rename capabilities, which must still receive their own R1 proofs.

## Steps and inspectable deliverables

### A. Inventory and boundary reconstruction (before writing shared scaffolding)

1. Inspect live main, current PR overlaps and active queue. Enumerate every actually persistent Options and contextual preference: its current UI, testTag, owning class/store/key, default, value type, consumer, global/client/transfer scope, explicit override/reset policy, risk, source-authoritative requirement and implementation state. Search both UI -> persistence -> consumer and persistence -> all readers and writers.
2. Classify each item as PREFERENCE, PER-TRANSFER OVERRIDE, CONTEXTUAL UI STATE, ACTION, CAPABILITY/OS STATUS or COMPOSITE EDITOR. Keep New folder/Rename, Add/Start/Stop, file deletion and OS notification permissions in action/platform semantics; never model these as passive stored toggles.
3. Record existing keys and values from actual versions used by current installs. Preserve legacy keys and explicitly stored media network opt-out. Do not conflate unset with explicitly false; identify which defaults are for fresh installs only.
4. Retain one current inventory in the existing Options spec or linked implementation work item. Do not start competing value/plan documents. Where conflicting old prose exists, resolve using binding newer owner requirements without deleting historical evidence.

**A exit:** sample rows can be traced to actual code, not just described in docs; no duplicate owner is accepted as canonical without a migration plan.

### B. Introduce the smallest typed definition layer justified by inventory

1. Begin with a small representative pilot: a global presentation toggle, a global media preference, and a per-client default or profile colour *only where that existing value is already supported*. The first user-visible R1 priority remains moving original source handling. Do not block that fix on a generic registry if an adapter suffices.
2. Define stable semantic IDs, typed value domains, validation, owner adapter, permitted scopes, default/explicit-off behavior, capabilities/dependencies and search aliases. Keep IDs decoupled from user-visible label; owner direction now prefers **client**, but underlying stable stored keys need not be renamed.
3. Distinguish a definition from a stored instance identified by exact client ID, effective resolved value and provenance, page placement and real operational effect. A one-off remote Add folder must never change a saved default folder. A transfer override may only exist where approved; resetting it removes only that override.
4. Expose an observable value through the current owning data layer and ViewModel/state holder. Compose renderers receive values and emit callbacks; no disk/network mutation in composition. Existing storage remains authoritative. Handle pending/failed saves and rapid repeated edits honestly.
5. Bind existing Compose widgets/components to typed definitions; retain specialized multi-field/credential/remote folder/rule editors and their coherent commit semantics. No broad source-code generator or JSON renderer is required.

**B exit:** pilot pages and any other placements share the same real owner; a scope-resolution test confirms two clients cannot overwrite each other.

### C. Declarative Options and discoverability

1. Make category/subpage manifests choose existing stable setting IDs, order, headings and supported presentation hints; preserve existing eight solid-colour category cards and four-tab footer. A category group may contain an action link/editor entry, explicitly typed as such rather than disguised as a preference.
2. Use a single canonical setting record for search (title, synonyms, category, optional deep-link anchor). Search must navigate to the right subpage and focus a reachable control, preserving origin/search/scroll/unfinished draft. Do not expose hidden or incapable controls as if actionable.
3. Allow approved contextual appearances to reference the same global definition without a second store. Put frequent task-specific controls in the task, with optional shortcut to their global default. No per-Details auto-enrichment preference is reintroduced.
4. Apply current owner-approved appearance, component geometry, word choice, actual state summaries, 48dp-class targets and Pixel/200% font/focus requirements. Do not mark a merely navigable category as a finished feature.

**C exit:** moving an ordinary existing setting between pages changes mostly composition/navigation mappings while persisted state and consumer stay identical.

### D. Migration, testing and rollout

1. Prefer **no persistence migration for UI-only relocation**. When a semantic/key/scope change truly requires migration, use an old-key/scope/default/value mapping and persisted legacy fixtures. Preserve explicitly disabled network lookup and credential isolation; safeguard old installs without inventing data-preservation requirements for build variants the owner explicitly treats as disposable.
2. Before broad tests, inspect impacted navigation, testTags, screenshots, fixture builders, search assertions and CI oracles against current binding requirement; KEEP / REWRITE / REPLACE-EVIDENCE / RETIRE / BLOCK. Preserve behavior and accessibility assertions even when screenshot layouts become obsolete.
3. Implement a coherent, reviewable batch; run cheap definition/resolution tests first and one shared Android/installed UI test boundary for changed journeys. Confirm actual preference write -> observed restart value -> real consuming behavior, rapid changes, failed write feedback and scope isolation. Remote controls need exact-host verified postconditions; neither a local write nor an RPC acknowledgment is proof of applied behavior.
4. Update the current Options spec, implementation coverage and R1/R2 queue with the exact scoped work completed, and report functional vs interaction vs rendered-presentation evidence separately. Do not claim all settings migrated because pilot definitions work.

**D exit:** accepted controls are discoverable, correct and accessible in an installed app; every touched setting's old and new paths preserve identity, permitted scope and intended effect. Untouched settings may remain in existing direct Compose screens until a relevant change justifies migration.

## Implementation ordering and authority

The actual urgent Android Open With intake regression and existing owner-approved Add/Options UX batch remain ahead of a wholesale registry. Apply the definition/placement pattern **during** those changes, then extend it to other Options categories as agents naturally work on them. R2 Options and media work can expand definitions and page composition when it reduces real duplication. Do not create a stand-alone architecture lane or broad all-repo refactor merely because this plan exists.

A portable general technique is documented in **Settings Systems Engineering** in the canonical Skills package, pending verified vendoring to Chunks. This project-specific plan is self-contained and remains usable even before that Skills update is installed; current `skills/VENDOR-SOURCE.md` must not claim a later source revision until an actual synchronized copy has been reviewed and verified.
