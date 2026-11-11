# Open-PR reconciliation, second pass (2026-11-11 UTC)

**Evidence tier:** GitHub workflow results at exact heads, plus **local JVM/Robolectric runs** in a sandbox with an installed Android SDK. No emulator, physical device or protected-host run. This extends [the first audit](OPEN-PR-RECONCILIATION-2026-11-11.md); the first audit's per-PR table is still the per-branch record and is not repeated here.

## Merged in this pass

| PR | Result |
| --- | --- |
| [#1640](https://github.com/gamesbyian/Chunks/pull/1640) | Squash-merged as `56efe338df2e24bbb544863b6a11b9c3ce6e38c8` (exact saved-profile scope plus strict first-run Welcome on one source). Exact head `fb49e9e9`: foundation, native add, R8, API29, two-server and remote-delete green. |

**Disposition of the one red on #1640.** The PR-triggered installed real-magnet job failed at `last_ui_stage=artwork-choice` (run 42950701947, rerun attempt 2 failed again). Controlled dispatches of the same workflow on disposable rTorrent with a controlled peer, `real_media=false`:

| Ref | `source=magnet` | `source=https` |
| --- | --- | --- |
| `main` `8869eb3d` | success ([42960552995](https://github.com/gamesbyian/Chunks/actions/runs/42960552995)) | **failure** ([42963097366](https://github.com/gamesbyian/Chunks/actions/runs/42963097366)) |
| #1640 head `fb49e9e9` | success ([42961354619](https://github.com/gamesbyian/Chunks/actions/runs/42961354619)) | success ([42963101148](https://github.com/gamesbyian/Chunks/actions/runs/42963101148)) |

Conclusion: the `source=https` journey is **already red on plain main** and is intermittent; the #1640 delta did not cause it and passes the exact head in both modes. This is flakiness evidence, not a fix: the intermittent `artwork-choice` failure in the installed journey (`MainActivityRealMagnetJourney.kt` `click()` timing around the Artwork & metadata disclosure) remains **an open defect** and is the likely explanation for the same signature on #1587 and #1637. Do not treat a single green dispatch as a proof it is gone.

## Assembled integration PR

[#1644](https://github.com/gamesbyian/Chunks/pull/1644) (draft) is one tree on current main: the #1641 verified-Connect acknowledgement, #1642 evidence-gated Welcome and #1643 source-first Universal Add commits, plus ported tests. The three stacked PRs were not edited. **Nothing in #1644 is qualified until exact-head CI, native shards, R8 and installed real-peer Add pass.**

What the stacked chain was actually red for (reproduced locally in about 90 seconds per class group): not product defects but tests written for the old gateway Add. #1641/#1642 failed because the footer **+ Add** always opened the old `ADD_CHOOSE` gateway, which #1643 removes; #1643 then failed 19 JVM tests because of deliberate renames and a flow change:

- phone field label `Or paste a magnet or torrent link` became `Magnet or HTTP(S) torrent URL`;
- `Review Add`, `Add magnet and start` and `Confirm: add then start` became the single explicit **Add transfer** click (the separate review card and `Confirm one server operation` are gone);
- `Start downloading after verified Add (recommended)` became `Start when added`;
- the destination chooser became an inline selector (`This device`, `On <server>`) and `On the connected server` / `Add a download to this server` no longer exist;
- the Combined first-run Welcome is opt-in (`enableFirstRunWelcome`) and evidence-gated.

**Pre-submit identity witness.** `MainActivityRealMagnetJourney` used to assert the full `Infohash:` and `Source: Magnet link` on the pre-submit review card. The card is gone, but #1643's own atlas test shows the full `Infohash:` and `Destination:` inline before Add, so the journey now asserts the inline `Infohash:` before submitting. The `Source: Magnet link` text no longer exists anywhere in the product, so that one assertion (HTTPS-preview source not retained) is dropped and now rests on the post-Add tracker-list and BEP9 witnesses. This is unrun on an emulator.

**Atlas contract.** CI `check` on #1644 failed because `tools/native_add_review.sh` still required `add-confirmation-real-compose.png`; the capture is now `add-source-and-destination-real-compose.png` and the list was updated (`tools/check_native_atlas_contract.py` passes: 24 captures).

Local result on the assembled head: the 11 formerly red JVM classes pass; a full JVM run had 2235 tests with 5 failures, four of which (`LocalCheckedPayloadReservationsTest`, `CreateSeedCopyAcquisitionRaceTest` x2, `LocalResumeDatabaseTest`) are filesystem/root-user artifacts of the sandbox that CI did not report, and one (`CombinedTransfersScreenTest.oldAccountBatchConfirmationCannotBeReusedForSameHashOnAnotherAccount`) passed in isolation, also on unmodified #1643. Instrumented-test sources compile; none were run.

## Recovery map for the remaining stacked children

With full history (an earlier shallow clone produced false "no merge base" results), each child's own delta was merged onto the #1643 tree with `git merge-tree --merge-base=<parent PR>`:

| Child over parent | Delta files | Conflicts onto #1643 tree |
| --- | --- | --- |
| #1594 over #1521 | 9 | `VerifiedAddScreen.kt` |
| #1611 over #1594 | 4 | `MainActivity.kt` |
| #1546 over #1521 | 12 | `MainActivityRealMagnetJourney.kt` |
| #1534 over #1528 | 5 | `RemoteDeleteDataInstalledInstrumentedTest.kt` |
| #1535 over #1528 | 14 | `ConnectAdvancedPathTest.kt`, `PhoneFirstFooterAddRenderTest.kt`, `PhoneInventoryFirstViewportTest.kt` |
| #1545 over #1528 | 19 | the four files above (without `MainActivityRealMagnetJourney`) |
| #1566 over #1565 | 4 | `CombinedTransfersScreen.kt` |

These are small, so recovery is realistic as ordered, individually tested ports on top of #1644, not wholesale merges. #1638's two commits are patch-identical to commits inside #1640, so #1638 is redundant. #1587 and #1637 touch the same two production files as #1640's Welcome change but were **not** patch-compared and stay open until that is done.

## Local verification setup (for the next agent)

A cloud sandbox can run JVM tests without CI: install Android command-line tools and `platforms;android-37.0`, Gradle **9.3.1** (follow the 307 redirect when downloading), run with `--no-daemon --max-workers=3 --no-configuration-cache`, retry on Maven Central HTTP 429, and place the Robolectric `android-all-instrumented:15-robolectric-13954326-i7` jar and pom under `~/.m2/repository` (Robolectric's own fetcher fails behind the proxy). Create a **full** clone (`git fetch --unshallow`) before judging branch relationships. This setup is not provenance for any release claim.

## Next executable tasks

1. Read #1644 exact-head CI; repair genuine failures (native shards, R8, installed magnet) on that tree; fix the intermittent `artwork-choice` journey timing.
2. Port #1594, #1611, #1546, then guarded Details (#1534, #1565, #1566) onto #1644 one at a time, each with its tests, per the table above.
3. Close #1638 as redundant; close #1641-#1643, #1637, #1587 and the older stacks only after their unique tests are confirmed on the accepted tree.
4. Independent review and an installed real-public-peer Add, Stop/Start, Remove run on the assembled APK before any acceptance claim.

## Exact-head evidence for #1644 at `86cacf6` (CI, source `86cacf682646664cc4f453ad14e135bc3545bd75`)

Produced with `python3 tools/pr_acceptance_report.py --sha 86cacf6… --profile integration-real-peer`; annotations pulled per shard.

**Passed on this exact commit:** `check`, `build` (full JVM, 2,260 tests locally with only three sandbox-only filesystem failures), `build-once`, native atlas, R8 minified smoke, API29 Downloads, two-server Combined, remote-delete-data. **The phone HTTPS Add journey passed with real verified pieces** (`verified=true`, 82,991,443 bytes received, default Place preserved) inside the installed real-magnet job; that job as a whole is still red.

**Failed (6 named native tests + 2 dependent gates):**

| Shard | Test | Classification |
| --- | --- | --- |
| 0 | `ChunksMainActivityAtlasTest.installedAppNavigatesPopulatedReadOnlyRpcScreens` | Test-contract drift: waits for the legacy connected list text `3 transfers` after Connect; verified Connect now acknowledges and stays on Connections (#1515) |
| 0 | `InstalledMediaArtworkInstrumentedTest.installedRemoteRowShowsUserArtworkAndOptionsSettingTurnsItOff` | Same drift (`2 transfers` after Connect) |
| 0 | `PhoneAddHiddenInCombinedInstrumentedTest.anAcknowledgedPhoneAddHiddenByTheCombinedSearchIsExplained` | Drift: `reachPhone` waits for a pane/entry (`Open phone downloads`) that the Combined-first flow no longer offers; `Open device transfer controls` is the current entry |
| 1 | `FooterImeInstalledInstrumentedTest.footerRemainsAvailableThroughSoftKeyboardAndAfterSearchKey`, `...catalogSearchSurvivesActivityRecreation` | Precondition drift: needs a catalog with a search field; a genuinely empty first install shows only the Welcome by spec, so these need a seeded-inventory precondition |
| 1 | `MainActivitySafeRouteRestoreInstrumentedTest.createCommandRouteRestoresOnlyCanonicalTransfersAndKeepsOfflineToolsReachable` | Not diagnosed beyond the failing assertion `Create torrent` after opening the Create tool; needs an emulator run |
| - | `installed-magnet-lifecycle` (remote journey `MainActivityRealMagnetJourney`) | Test-contract drift: after Connect it waits for the legacy server queue (`+ Add torrent`, `All environments…`, `Refresh`), used across about 300 lines of the journey; failed at stage `connect-submitted` (line 224) |
| - | `verify-complete-native-add` | Fails because the shards above failed; not independent |

No assertion was deleted, skipped or weakened to reach these results. `PhoneToolFocusReturnInstrumentedTest`, which failed on `40fe74b`, now passes after waiting for the stable footer id.

## Decisions made in this pass

- **#1594 ported into #1644; #1611 reverted from it.** The #1611 test block failed `two-server-combined` at `connect(urlB)` waiting for `Connected ·` (run 42968879065). Reverting it restored the gate to passed (confirmed on `86cacf6`). #1611's commit `db19baac1` stays in #1644's history and as PR #1611; re-land it separately once its interaction with the #1641 verified-Connect acknowledgement is debugged on an emulator.
- **Canonical Transfers heading is `Transfers`, not `All environments`** (#1642, `a00e1bb40`); `ChunksSemantics.TRANSFERS_HEADING` is the stable id. Tests that waited for the old text were drift.
- The acceptance report now picks the newest check run per gate and no longer labels a journey step as environment failure just because its name contains "Installed".
- Pushing to a PR cancels in-flight native shards and re-runs everything, because path filters evaluate the whole PR diff, including for docs-only pushes. Batch changes; the verifier job runs after a cancel and fails for missing receipts, which is not evidence.

## Next executable tasks (updated)

1. On an emulator or via workflow dispatch of `installed-rtorrent-magnets.yml` (these are not cancelled by pushes), port `MainActivityRealMagnetJourney` to the canonical flow stage by stage: Connect → `Connection verified` → `Continue to Connections` → `View transfers` → footer Add with `ADD_TARGET_REMOTE` → Add → verified job. Keep every real-peer assertion.
2. Port the three shard-0 drifted tests the same way, then give `FooterIme…` and the route-restore test a seeded-inventory precondition.
3. Re-land #1611, then #1546, then guarded Details (#1534, #1565, #1566) one at a time on the green tree.
4. Do not merge #1644 until `pr_acceptance_report.py` reports ACCEPTABLE on the exact head and an independent review is done.

## Guarded Details ported; real-peer journey ported but unrun (head `d0d2b7d`)

- **#1534, #1565 and #1566 are ported into #1644** (commits `f73769c20`, `dccdff8e2`, `4df4e0e2c`), one resolved conflict (`RemoteDeleteDataInstalledInstrumentedTest`: the interim handoff to the legacy queue was replaced by the canonical Details Remove this stack provides). Full local JVM suite: 2,277 tests, only the three sandbox-only filesystem failures. **Native shards 0 and 1 on `d0d2b7d` fail exactly the same six tests as before the stack** (annotations from check runs 130575371517 and 130575371660), so the stack added no new native failures.
- **`MainActivityRealMagnetJourney` was ported to canonical navigation** (verified Connect → `Continue to Connections` → `View transfers`, footer Transfers with exact-hash search, row → full Details for Stop/Start/Remove, footer Add with the explicit `ADD_TARGET_REMOTE` choice). All real-peer assertions are unchanged; the instrumented sources compile. **It has not been run**: every installed job on this commit failed at runner setup.
- **Environment outage, not a product or test result.** `remote-delete-data`, `two-server-combined` and `installed-magnet-lifecycle` (and their one re-run each) failed with `exit code 124` at `sudo timeout 180s apt-get install python3-libtorrent`, before any emulator or test started. A control dispatch of the same workflow on plain `main` (run 43036762606) failed identically, so the cause is the runner's package mirror, not this branch. These jobs passed earlier at `86cacf6` (two-server, remote-delete). Re-check when the mirror recovers; consider a bounded retry around that apt step if it recurs.
- Pull results from `gh api repos/gamesbyian/Chunks/check-runs/<id>/annotations`; full job logs are very large.

## Runner outage duration and status

Control dispatches of `installed-rtorrent-magnets.yml` on plain `main` failed identically at the apt step with `exit code 124` and no stage reached: run 43036762606 (14:12 UTC), 43042422389 (14:49 UTC) and 43053184539 (15:55 UTC), i.e. over two hours. It affects every job that installs `rtorrent`, `nginx` or `python3-libtorrent` through apt (`installed-magnet-lifecycle`, `two-server-combined`, `remote-delete-data`). **Real-peer and disposable-rTorrent acceptance for #1644 is therefore blocked by an external runner/package-mirror outage, not by this branch.** The canonical real-peer journey port (`d0d2b7d`) remains unrun. Re-dispatch it on the branch once a control run on `main` passes the apt step: `installed-rtorrent-magnets.yml`, `source=https`, `environment=disposable`, `discovery=controlled`, then read failures from check-run annotations.

## Next-step plan adopted and first actions (head after `42a2660`)

An independent read of the repository reached the same conclusion as this audit: finish one integrated build candidate (#1644) rather than merging 23 branches, then retire the superseded PRs with recorded dispositions. Actions taken from that plan:

- **#1644 refreshed against current `main`** (was one commit behind; merged cleanly).
- **Package-install fallback.** The outage failed at the first apt command (`apt-get update`), so `tools/apt_install_retry.sh` now retries with short request timeouts, falls back from `azure.archive.ubuntu.com` to `archive.ubuntu.com`, verifies every package is installed, and exits non-zero otherwise (never success on a silent failure). It is wired into `installed-rtorrent-magnets.yml`, `installed-combined-two-servers.yml` and `installed-remote-delete-data.yml` and has five offline tests. Whether it clears the outage is **unproven until those jobs run on this head**.
- **Three more native tests ported** (`ChunksMainActivityAtlasTest.installedAppNavigatesPopulatedReadOnlyRpcScreens`, `InstalledMediaArtworkInstrumentedTest.installedRemoteRowShowsUserArtworkAndOptionsSettingTurnsItOff`, `PhoneAddHiddenInCombinedInstrumentedTest.reachPhone`): navigation moved to Connect → acknowledgement → View transfers → canonical catalog and `Open device transfer controls`; real assertions kept. Unrun on an emulator at the time of writing.
- **Still open:** `FooterIme…` (2 tests; need a seeded-inventory precondition) and `MainActivitySafeRouteRestore…createCommand…` (undiagnosed); #1611 (interaction with the verified-Connect acknowledgement) and #1546 (alias) re-landing; remaining Universal Add parity items (source Keep/Archive/Delete, some file-priority choices, Smart Routing); independent review; superseded-PR disposition on each PR; `main` has no required-check ruleset, which should be set so a mergeable-looking PR cannot bypass qualification; publishing a Preview APK from `main` via `publish-debug-apk.yml` only after exact-head acceptance.


## Real-peer journey reconciliation and the no-churn rule (heads `315c309` → `431fa2b`)

**Evidence tier:** CI emulator runs on disposable rTorrent with a controlled libtorrent peer. Not physical-device, OEM or protected-host evidence.

The ported real-peer journey (`MainActivityRealMagnetJourney`) was walked forward one failure at a time. Each failure was **test-contract drift** against the canonical UI, never a product write change:

| Stage that failed | Cause | Reconciled assertion |
| --- | --- | --- |
| `click-stop-transfer` / `click-review-start-stop` | Canonical Details hands Start/Stop to the batch review (`Start…`/`Stop…` → `Start selected`/`Stop selected`) | Journey uses that review; state readback unchanged |
| `click-automatically-identify-and-absent` (intermittent) | Probe inferred the disclosure state from composition/visibility and collapsed the opt-out | Disclosure `Expanded`/`Collapsed` state is read and re-established inside a bounded wait; the option tap is never retried |
| `click-name-missing-rows-1` / name wait | Compact row title overrides its description when merged | Original metadata name asserted through the row's accessible name `Details for <name> on <environment>`; numeric diagnostics added if it still differs |
| Phone HTTPS `read(/magnet3.torrent)` (PR-triggered runs only, intermittent) | Unknown controller/adb-reverse hiccup | Idempotent GET retried for 30 s; failure now names the status |

Diagnostics added (bounded, no app text): `click-<control>-absent|ambiguous|unusable`, `click-geo-<top>-<bottom>-<root>`, `click-seen-<6 flags>`, `click-nm-<rowLen>-<nameLen>-<common>`.

**Process failure recorded:** about twenty consecutive pushes between 17:50 and 20:14 UTC cancelled every in-flight native/R8 batch, and two of them introduced androidTest compile errors that were only caught in CI. From `431fa2b` on: no push until every required gate on the head has reported; fixes are batched into one commit; androidTest is compiled locally first (`/opt/gradle-9.3.1/bin/gradle --offline :app:compileDebugAndroidTestKotlin`).

**`431fa2b` status (20:55 UTC):** GitHub reported an Actions major outage. Test steps for `build`/JVM, native atlas, API35 FGS, R8 smoke, `two-server-combined` and `remote-delete-data` completed successfully, but several artifact uploads failed and job conclusions were not reported. `api29-downloads` failed in its test step (an earlier head showed a Compose `performMeasureAndLayout called during measure layout` crash in the Android 10 permission journey). Both real-peer runs reached the evidence finisher, which failed; the installed step always exits 0, so **the real-peer result is not yet known**. Nothing on this head is accepted.

**Next executable task:** when GitHub reports `431fa2b`, run `python3 tools/pr_acceptance_report.py --sha 431fa2b2c… --profile integration-real-peer`, read the finisher's `reason=` annotation, then make one batched, locally compiled fix commit and let its full suite finish. Merge #1644 and publish the Preview APK once one exact head qualifies; re-land #1611/#1546 and retire superseded PRs after that.

**Partial results rescued from the stalled `431fa2b` jobs (owner decision at 21:00 UTC: stop waiting).** Real-peer job 130779934015: the phone HTTPS journey passed (verified real pieces, default Place preserved); the remote journey failed at `click-nm-90-31-0` — the canonical Transfers row still carried rTorrent's pre-metadata name. Classified **product failure**: canonical Transfers only re-read the current server after a mutation, so a magnet stayed listed as `<infohash>.meta` after metadata arrived (the legacy queue had a manual Refresh). Fix: selecting the footer Transfers root performs the existing read-only, mutation-gate-fenced `refresh()`, as ruTorrent/Transdroid lists do; the assertion is unchanged. `api29-downloads` job 130780133703 failed with a Compose runtime `ArrayIndexOutOfBoundsException` in `SlotTable` during composition disposal (`phaseA_externalReviewDownloadsThroughUiStopsThenProcessDies`); the previous head failed with a different Compose-internal crash in another API 29 test, and the gate passed on `6880e21`. Recorded as an **open intermittent API 29 defect** (possible off-main-thread state write), not fixed here.

## Independent review of #1644 at `e72f3e2` and the qualifying batch

**First passing installed real-peer lifecycle on the integrated tree:** PR-triggered run 43104987216 on `e72f3e2` (merge tree `74c0940` identical to the head): receipt `result=passed`, independent peer evidence verified, precompleted control preserved; Debian amd64 and Blender Sintel each proved metadata, real peer pieces, piece verification, Stop/Start with resumed verified bytes and test-verified cleanup; the peerless control stayed at zero received bytes; the phone HTTPS journey verified real pieces with the default Place preserved. Emulator plus disposable rTorrent and controlled peer only.

A code review of the `app/src/main` diff (unverified findings, then checked here) found two regressions introduced by the integration, fixed in the next commit:

- **Finish in Order did not advance** in canonical navigation: its 30-second runner was composed only inside the legacy connected route. It now runs at composable level for the connected server with the same privacy-scope, mutation-gate and readback guards.
- **Trackerless warning lost** from remote Add when the confirmation card was removed: restored beside the inline infohash/destination before the single Add action (`RemoteAddTrackerlessWarningTest`).

**Open follow-ups recorded, not fixed in #1644** (each needs its own reproduction): stale picker/handoff results can overwrite a newer shared source (`LocalEngineScreen` review read; `incomingTorrent` no longer clears phone handoffs); `selectAuthenticatedAddTarget()` can drop a just-set server `.torrent` when the draft holds a magnet; "Connect to <saved profile>" from Add while another server is connected cannot complete (overlaps #1611); "Forget saved login while staying connected" and the stale-list indicator lived on the now-unreachable `ServerScreen`; startup auto-reconnect narrows Transfers to that server; the canonical files panel re-reads and resets on every refresh; redundant owner re-checks in `runCombinedLifecycle`.

## Merged #1644 and what remains

**Merged** as `2812eaf` (squash, 22:25 UTC) at qualified head `687bb04d6cab326c80d79d336c05738268ea01b4`; the merge tree equals the qualified tree (`4fb6844`). Exact-head acceptance (`integration-real-peer`): **ACCEPTABLE**, every required gate executed and passed. Real-peer PR run 43109677545 receipt: `result=passed`, independent peer evidence verified, precompleted control preserved, phone HTTPS journey verified, qualified debug preview `chunks-https-verified-preview-a24126bd5f4d.apk` (build source `a24126b`, same tree).

**Closed as superseded, each with a disposition comment:** #1641, #1642, #1643, #1594, #1534, #1565, #1566.

**Still open:**
- #1611 (exact-account saved-profile Add authentication) and #1546 (frozen display alias): re-land on current main with the two-server gate; #1611's earlier port failed at the second Connect.
- #1646: independent media-identification work; not part of this integration.
- #1521, #1528, #1535, #1545, #1548, #1551, #1587, #1613, #1616, #1618, #1619, #1637, #1639: older overlapping assemblies and predecessors. Most of their content is in main through #1641-#1644, but each must be checked for unique tests or fixes before it is closed.
- Review follow-ups listed in the previous section; the intermittent API29 Compose runtime crash; the intermittent phone HTTPS controller read and two-server second-Connect failures (each passed on later heads, not closed by a rerun).
