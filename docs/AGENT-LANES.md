# Parallel development ownership
Live files/issues override this default map if another agent is already active.

## Lane A: rTorrent protocol and networking
Own #1, then server read/write controls in #4. Own app/src/main/.../rpc and fixtures; secure transport and capability probe. Do not implement filesystem moves by writing a new path string. Share API contracts before changing the application coordinator.

## Lane B: storage / companion / safety
Own #2/#3/#7/#9. Durable server-side operation journal, permission model, path and link validation, fault injection and independent verification. No production server mutations during testing. Independent acceptance should not be written by the implementation agent alone.

## Lane C: Android interface and classification
Own #6/#8 and UI portion of #4. Typed UI state, Places/Collections organization, transparent classification, TalkBack and physical device proofs. Avoid altering RPC schemas without coordinating Lane A.

## Lane D: final integration and release
Own #5/#10 and shared Gradle/GHA/docs/WORK-QUEUE.md / QUALITY-LEDGER.md reconciliation. Review live PRs before changing shared contracts. Must report exact SHA/test/artifact and unverified functionality. Merge only after normal checks.

GitHub issues identify work, not automatic running agents. Reassign on actual activity; avoid claiming someone is executing because an issue exists. No secret-containing data, seedbox names/URLs or user torrent inventory in any artifact.