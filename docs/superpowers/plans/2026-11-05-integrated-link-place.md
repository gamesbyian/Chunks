# Integrated link and phone Place implementation plan

**Goal:** complete HTTPS-link transfers and distinguish one-time phone destinations from the saved default on one qualified candidate.
**Execution:** sole agent, as explicitly directed by the owner; no delegation or intermediate approval gates. Existing UX, Add-source, storage and real-transfer contracts are the design authority.
**Architecture:** retain the existing fetcher, reviewed Add, native engine and rTorrent adapter. Keep the current Add's exact Place identity in saveable UI state; resolve it against fresh verified choices. Persist only through an explicitly labelled default action. No relocation.
**Tech stack:** Kotlin, Compose, Android API29/API35, existing Gradle/Actions/native peer infrastructure.
**Specs:** [Add source](../../ADD-SOURCE-INTAKE.md), [UX](../../UX.md), [storage](../../STORAGE-AND-EXTRACTION.md), [real transfer](../../REAL-TRANSFER-ACCEPTANCE.md).

Execution card: outcome = real reviewed HTTPS Add/Start/pieces and scoped phone destinations; evidence = installed native journeys plus focused persistence/Compose tests; authority = owner campaign, public/disposable fixtures only; intervention = Add destination state and acceptance extensions; positive/negative = exact hash/root/pieces/default preservation plus denied/unavailable/cancel/recreation; exit = qualified integrated source, receipts, merge and gated APK.

## Constraints and review focus

- Preserve HTTPS verification, private-network refusal, exact environment identity and token redaction.
- A revoked or changed one-time root blocks Add; never silently switch to another root.
- Default changes cannot redirect an already reviewed one-time Add; successful Add resets only its override.
- Review/cancel/recreation must preserve intent without persisting an override as a default.
- 320×700/412×892, 200% fonts, 48dp controls, keyboard and semantics receive automated evidence; physical and human review remain separate.

## Task 1: phone destination scope

Files: `LocalDownloadPlacePolicy.kt`, `LocalDownloadPlacePicker.kt`, `LocalEngineScreen.kt`; matching JVM and native tests.
- [x] Add failing policy/Compose/persistence tests for one-time choice, explicit default save, unavailable exact root and recreation.
- [x] Implement exact verified draft selection, accessible scope wording and explicit saved-default action; clear after successful Add/cancel, retain on refusal.
- [x] Run focused host checks and commit this independently understandable slice.

## Task 2: production HTTPS transfer acceptance

Files: existing installed real-peer journey utilities and scripts/workflows; add focused installed link journeys where reuse requires it.
- [x] Reuse public metainfo and controlled real peer fixtures; enter genuine public HTTPS URL through installed production source field.
- [x] Prove review is non-mutating, explicit Add, Start, received/verified piece growth and exact target/root; retain errors and unchanged unrelated inventory.
- [x] Combine phone one-time/default persistence and recovery with link journey where compatible; commit acceptance slice.

## Task 3: assembled qualification and integration

- [x] Review complete diff separately; no subagents under owner's sole-agent instruction.
- [x] Freeze one candidate; run coordinated foundation, API29/API35, R8 and real-rTorrent acceptance without intermediate full CI.
- [x] Diagnose exact phase/assertion on failure; rerun only invalidated tiers. Record executed/skipped receipts.
- [x] Merge after required checks; reconcile canonical queue/trace/ledger/handoff once and publish source-matched debug/preview APK only after publication gates.

Qualification: merged #1301 as `19ff3145`; immutable checkout `f07a8331f81f` and complete executed/skipped receipts in [QUALITY-LEDGER](../../QUALITY-LEDGER.md). Author performed the separate full-diff review under the explicit sole-agent mandate. Earlier failed diagnostic source experiments were discarded; no subagents or protected host writes. The final tracking refresh changes documentation only.
