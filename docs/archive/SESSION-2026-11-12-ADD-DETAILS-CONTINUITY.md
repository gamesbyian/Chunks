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

## Independent review follow-up (same PR, #1650)

An independent code review of the first head (`4ea4259`) found real gaps, now fixed:

- A server-form `.torrent` cleared the text but left an **older device `.torrent`** as the parent's reviewed document, so a target round trip could restore the older file. The server picker and HTTPS intake now adopt the newest file as the app's one reviewed document. New red-first case `newerServerTorrentReplacesOlderDeviceTorrentOnBothTargets`.
- The kept-while-stale file list ignored *why* the owner was stale. Canonical Details now keeps it only for a transient `CURRENT`/`STALE` read; `OFFLINE`, `LOCKED`, `INCOMPLETE`, `NOT_IN_CURRENT_LIST` and `ENVIRONMENT_MISSING` drop it (`offlineOrNoLongerListedOwnerDropsTheFileList`). Copy no longer promises a list that is absent, and a transient re-read timeout/error keeps the previous list read-only beside the error instead of discarding page/search.
- The installed startup-scope assertion now waits for idle after the authenticated-only "Only connected server" control appears, so the old startup focus effect would have run before the preference is read.

Not changed, still queued: with both a typed magnet and an explicitly chosen server `.torrent`, the **device** form shows the magnet (it holds one source). Distinct phone handoff and picker paths still record `textAtFileSelection` differently; unify them only together with that owner decision.

A test-harness finding: the JDK `com.sun.net.httpserver` fixture combined with `HttpURLConnection` keep-alive delayed a later POST by the 15 s read timeout. The files-panel tests use the raw `Connection: close` socket fixture that other app tests use. Affected classes after the follow-up: 167 tests, 0 failures, and instrumented sources compile.

## CI red on `4ea4259`: phone clipboard prefill race (pre-existing, fixed here)

Foundation `build` ([run 43128374172](https://github.com/gamesbyian/Chunks/actions/runs/43128374172), job 130886110853): 2,309 tests, **1 failed**, `PhoneMagnetDraftCheckTest.withPrefillOffPasteFillsOnlyWhenTapped:131`. The test had passed in the local full run, and the PR did not touch phone Add. Root cause: the phone form caches the clipboard-prefill preference once per composition, while the Options opt-out commits the preference on an IO thread *before* posting its in-memory update. A return to Add inside that window filled the copied magnet against a committed opt-out. Fix: re-read the persisted preference (already loaded in memory) immediately before any automatic fill. New deterministic `committedOptOutWinsOverTheFormsCachedPreference` commits the opt-out directly while the cached copy is still on: **red** without the fix, green with it. The whole `PhoneMagnetDraftCheckTest` class passes.

## Exact-head qualification and merge of #1650

Head `3728bbaee65d194f17df1f9bed9b8b4c376fde61`: `tools/pr_acceptance_report.py --profile integration-real-peer` reported **ACCEPTABLE** (12/12 executed and passed: check, build, build-once, both native Add shards and verifier, native atlas, R8 minified smoke, API29 Downloads, two real rTorrent servers including the new startup-scope assertion, remote Remove/data, installed real-peer lifecycle). The installed real-peer receipt (job 130895157703) recorded the matching installed APK `sha256=5daf237f11cecc6e4e7ee0ee6feb62005ec35f08bbc3b89b2772ba3ae55c0897` (71,597,090 bytes, an intermediate test build), the phone HTTPS journey `verified=true received=59269281` with the default Place preserved, and the finisher `two public https sources, UI controls, resumed verified pieces, peerless truth and cleanup passed`. Squash-merged as `197977c25156a8410d1e6c64aa881b65f7605b1e`. Emulator, disposable rTorrent and controlled peer only.

## Published debug pre-alpha APK

[`android-debug-197977c25156`](https://github.com/gamesbyian/Chunks/releases/tag/android-debug-197977c25156) from `publish-debug-apk.yml` run 43132690632 on `main` `197977c25156a8410d1e6c64aa881b65f7605b1e`, whose Git tree `80e17df248be86d04b72fa2d72319a99357250a7` is identical to the ACCEPTABLE head `3728bba`. Independently downloaded and verified with `sha256sum -c`: `chunks-preview.apk` `b1c4c6c9c8f264515e2f35d99cfdefd240313b53f29d57de5bf37bdf98a1e97c` (71,597,070 bytes); `chunks-debug.apk` `1b65de4e6944c0691535e43bfc7b1018c07537244bcd5d488c7a0e4ca5ff57d3` (71,597,050 bytes). It is debug signed and built separately from the CI-installed intermediate test APK, so its bytes are not the ones exercised by the real-peer run. It is not physical-device, OEM/Doze, protected-host or publisher-signing evidence.

## Media session navigation continuity: attempted and withdrawn

A `MediaInventoryHostLease` (detach the one session when Transfers leaves composition; revoke on privacy/account/saved-profile witness change or after 10 minutes) was built with four passing lease unit tests and then **withdrawn unmerged** after independent review of PR #1651 found:

- **No real continuity.** Re-entering Transfers runs `replace()` with a freshly built snapshot, which revokes and restarts the active run, so the scan still restarts. The unit test hid this because it never called `replace()` after re-attach.
- **Weakened exact-job revocation.** While detached, the session's authorization predicate compares a frozen snapshot with itself. A job removed elsewhere would stay authorized for up to the whole window.
- **Wrong witness.** `TransferPrivacyRuntime.lifecycleRevision` changes on every `onStart`/`onStop`, so opening the document picker from Add revokes the session anyway. Saved-profile identity used ids only (an edited endpoint or credential under the same id would not revoke), and the 60 s tick overshoots the window.

Correct direction: build the authorized media inventory **above navigation** from live observations (phone frame, authenticated server list, explicitly read saved profiles), so authorization stays dynamic while Transfers is unmounted. Make `replace()` with identical rows and witnesses continue the active run instead of restarting it. Use the privacy *lock* state, not every lifecycle tick, as the revocation witness.

Full local JVM suite with the withdrawn slice: 2,316 tests, 4 failed. Three are the documented sandbox filesystem cases. The fourth, `RemoteAddSessionContinuityTest.lostMutationReplyAndSameAccountReconnectKeepFrozenReadOnlyRecovery`, waits 15 s on a `com.sun.net.httpserver` fixture (the keep-alive stall class above) and **passed twice in isolation**.
