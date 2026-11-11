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
