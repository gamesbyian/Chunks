# 12 November 2026: Add source precedence, Details files continuity, startup scope

**Evidence tier:** local JVM/Robolectric in a cloud sandbox (no KVM, so no local emulator), plus GitHub workflow results at the exact PR head where recorded below. No physical device, protected host or production signing. This record is history, not an instruction source; the active order is in the [work queue](../WORK-QUEUE.md).

## Starting point

`main` at `97cc78f` with zero open PRs. #1647 was merged while its PR text said it was unqualified; its final head `ef2ca2ba` later had foundation, both native Add shards, atlas, R8, API29, two-server, remote Remove/data and installed real-magnet runs **succeed** (runs 43117051950, 43117051823, 43117051892, 43117051980, 43117051922, 43117051966, 43117053752, 43117051826). #1649's head `2b5e0ed7` likewise succeeded on foundation, native Add, atlas, R8, two-server, remote Remove/data (43120018930 … 43120019005) and a dispatched installed real-magnet run 43122865347. Those runs are emulator/disposable-server evidence only.

## Defects found and fixed

| Defect (pass-2 follow-up list) | Observed evidence | Change |
| --- | --- | --- |
| A `.torrent` opened in the server form after typing a magnet kept the magnet; switching Add target then showed or restored the stale magnet (#1649 defect class) | New `UniversalAddSourcePrecedenceTest` drives real `MainActivity` against a loopback read-only rTorrent fixture and the real `OPEN_DOCUMENT` result: **red** on unfixed source at the device-target assertion | The server picker's validated `.torrent` replaces the typed text, as phone Add and HTTPS intake already did |
| An explicit `.torrent file` choice over unchanged text was reverted to the magnet by any target round trip | Same test class, second case: **red** (`Selected = false`) on unfixed source | `selectAuthenticatedAddTarget()` applies the Add screen's own rule: a file chosen while the current text was present is not superseded by a target switch |
| Canonical Details files panel discarded page/search/selection on every inventory refresh and blanked while stale | New `CanonicalRemoteFilesRefreshContinuityTest`: **red** on unfixed source when the inventory went stale | Keep the exact-job list visible (read-only) through stale/refresh and re-read on each accepted revision; any account/job/name/Place change still drops it |
| Cold-start automatic reconnect overwrote the persisted Transfers scope with "only this server" | Code reading against UX "persist … environment selection"; no JVM harness (Android Keystore vault) | Startup return to canonical Transfers keeps the saved scope. `CombinedTwoRealServersInstrumentedTest` now asserts the saved All scope survives relaunch reconnect (installed CI gate) |

A third precedence case (magnet then `.torrent` on the **device** target, then server) passed before and after the change and is kept as a guard. No assertion was weakened or removed.

## Audited, not changed

- **Forget saved login:** per-profile Forget and Forget all are reachable on Connections. The `ServerScreen` branch for `Section.SERVER` in `MainActivity` is unreachable because Connections handles that section first.
- **Media session ownership:** opened by mounted `CombinedTransfersScreen`; enrichment pauses on Connections/Options/Add. Queued as the next G4 task.
- **API 29 Compose crash:** job 130780133703 recorded `ArrayIndexOutOfBoundsException: length=10240; index=-711` in `SlotWriter.moveSlotGapTo` → `CompositionImpl.dispose` at Activity destroy during `PhoneIntakeJourneyInstrumentedTest.phaseA_externalReviewDownloadsThroughUiStopsThenProcessDies`. Not reproduced here (no emulator). A similar upstream report has no recorded fix.

## Local verification

Targeted classes pass after the change: `UniversalAddSourcePrecedenceTest` (3), `CanonicalRemoteFilesRefreshContinuityTest` (1), `CanonicalRemoteFilesEligibilityTest`, `RemoteAddSessionContinuityTest` (6), `ExternalSourceReplacementTest`. Full local JVM suite on the changed tree: **2,309 tests, 4 failed, 57 skipped**. The four failures are the sandbox-only set already documented in the [pass-2 audit](OPEN-PR-RECONCILIATION-2026-11-11-PASS2.md): two `CreateSeedCopyAcquisitionRaceTest` and one `LocalCheckedPayloadReservationsTest` root-user filesystem cases, and `CombinedTransfersScreenTest.oldAccountBatchConfirmationCannotBeReusedForSameHashOnAnotherAccount`, which **passes in isolation** on this tree. Instrumented sources compile (`:app:compileDebugAndroidTestKotlin`). Exact-head CI results are appended below when available.
