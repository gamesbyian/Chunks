# Remote reliability evidence (#1679)

## Actual primary Seedhost deletion accepted (2026-11-14)

[#1708](https://github.com/gamesbyian/Chunks/pull/1708) merged as `8d485caa5884e12b4af13eb10e6448c2ccda4a6f`; its tree equals reviewed/tested candidate `9d2a00662e6a259c8b2b6a73b40fc8f9e8dd144e` (`00fdfb7a0d2b8518772f3e14dede76daaa118d2b`). The actual account's newer erasedata endpoint is missing (404), while the genuine legacy browser adapter and exact-principal hooks are present. Its required inventory/coverage/CWD getters work. The implemented adapter follows those existing hooks, with fresh identity/full-session/shared-file guards and one submission; it does not install hooks or call arbitrary remote commands.

Actual protected [run43546968883](https://github.com/gamesbyian/Chunks/actions/runs/43546968883) passed3cases/0failures/0skips: one512KiB synthetic transfer downloaded and piece-verified from the runner; mismatched review refused without changing its file; session-only Remove left the payload; stopped re-registration followed by data removal erased the exact session and independently observed payload entry through PHP filesystem listing. All unrelated entries and other session hashes were preserved. The app still reports queued cleanup rather than claiming that an acknowledgement proves file deletion.

- [Source-bound receipt](seedhost-9d2a0066/RECEIPT.json), [sanitized host witness](seedhost-9d2a0066/SEEDHOST-WITNESS.txt), and [12/12 integration-real-peer gates](seedhost-9d2a0066/INTEGRATION.txt). Both disposable legacy/modern engine gates also passed. Foundation:2,404executed/0failed/62conditional skips.
- [Installed disposable removal43546974495](https://github.com/gamesbyian/Chunks/actions/runs/43546974495) independently verified actual file absence and unchanged foreign canary bytes. Its actual installed APK matched72,288,164bytes/SHA256 `b34b7ea329fbb04021a97ee591b155c032bff796a3a41df1f454bb2a692f98e5`; its PR checkout tree equals the candidate/merge tree. This was internal test installation, not APK publication.
- [Queued/session-absence state](seedhost-9d2a0066/remote-delete-queued-result.png) and [unavailable capability/recovery](seedhost-9d2a0066/remote-delete-unavailable-controls.png): API35 Robolectric320×700mdpi/200%text, captured using temporary capture-only instrumentation in existing passing tests, then restoring tracked source. Primary and independent inspection found readable wrapping, accessible actions and truthful session-versus-file wording. These are synthetic component captures, not golden images or owner Pixel approval.

Earlier runs remain failures:43545246997 verified real peer bytes and Stop but failed its original observer before any data-deletion request;43546155602 failed the observer subprocess lookup before Add. The observer now uses authenticated exact-parent JSON and validates availability before Add. Disposable reds on the older candidate exposed genuine PHP HEAD405 behavior and an obsolete queued/session-pending assertion; both were corrected and the final cohort reran. The source-bound receipt retains their scope rather than upgrading them to acceptance.

**Limits:** Seedhost unrelated-entry preservation is not a byte-for-byte audit of every unrelated payload. Physical Pixel, human TalkBack and OEM acceptance remain separate. Other servers that deny complete inventory or lack genuine adapter evidence still refuse data deletion; session-only Remove remains available. No APK publication was triggered. The two previously owner-resolved Add symptoms retain their regression coverage.

## Qualified disposable installed campaign

[PR #1683](https://github.com/gamesbyian/Chunks/pull/1683) integrated candidate
`949e743deaa8f95c69c0bf8ed446ae0f8755890e` as merge
`f4b6d350f6952d1aeb5d5bc32102449575095535`. The actual installed peer checkout
`642ce7cfa5dfff4ff5d5eaca0a5708089eca091b`, candidate and merge all have tree
`b2fdaaad5940e87b4477acd5507dd3b828a6d7ac`. These receipts preserve safe source-bound
results after temporary Actions artifacts expire:

- [Campaign receipt](final-949e743d/CAMPAIGN-RECEIPT.json): all 12 profile gates plus both engine jobs, artifact digests, case counts and remaining gaps.
- [Installed app receipt](final-949e743d/RECEIPT.json), [APK identity](final-949e743d/APK-IDENTITY.json) and [independent peer receipt](final-949e743d/PEER-RECEIPT.json): one passing API35 installed journey, mandatory canonical Recheck, two publisher candidates, resumed piece growth, exact session cleanup and truthful negatives. The pulled installed APK is SHA-256 `6937af8d85ab09523ccc6cc59ba93f0357b1b7220c072aa8fca02327814c930c`, 72,206,280 bytes, equal to the built candidate.
- [Independent removal witness](final-949e743d/REMOVAL-WITNESS.txt): installed canonical review/queued/session-absence journey followed by the real collector proving exact payload absence and an intact foreign canary. That separate artifact has no standalone installed APK digest; the peer APK receipt does not certify its bytes.

The two public downloads proved **partial pieces**, not completed publisher payloads.
Full synthetic multi-file byte equality is separate local authenticated interop proof
in the [ledger](../../QUALITY-LEDGER.md). No owner-host, physical Pixel, human
TalkBack, OEM/Doze or signing acceptance follows from this emulator/disposable proof.
The owner confirmed the blank optional Add destination and paused-new-transfer symptoms resolved on 2026-11-13. Preserve those regressions. Remove+data has separate actual-host qualification under #1679; the older disposable receipts alone do not establish it. See the [current deletion contract](../../REMOTE-CAPABILITY-DISCOVERY-AND-DELETION.md).

## Inspected component renders

Evidence-only Robolectric native-graphics API35 captures, **not approved golden
baselines, installed-device proof or owner visual approval**. All source/hash/path
content shown is synthetic. Regenerated after integration onto `19bfe3de`;
the reviewed production components and JVM producers come from checkpoint
`4aa4ea8f`. The later Android runner/fixture corrections do not alter these
rendered components. The [quality ledger](../../QUALITY-LEDGER.md) records
functional proof and remaining acceptance tiers.

The additional canonical Details control has its own [Recheck evidence record](RECHECK.md),
including isolated ready, historical-idle and large-text pending renders.

| Capture | Producer / viewport | Agent inspection |
| --- | --- | --- |
| [Native default unknown](remote-native-default-unknown.png) | `RemoteDestinationResolutionTest.blankOptionalFolderSubmitsAtNativeDefaultWithoutInventingAnOverride`, 412×892 mdpi | Blank optional field, explicit unknown folder and reachable Add; test submits once and reads back actual Place |
| [Storage refusal](remote-add-storage-refusal.png) | `RemoteDestinationResolutionTest.anUnopenedHashFailureExplainsStorageRecoveryOnTheActualAddResult`, 412×892 mdpi | Fixed actionable diagnosis, no false started result, checking distinct from failure, read-only recovery below the outcome; content scrolls |
| [Pending removal](remote-removal-pending.png) | `TransferSessionRemoveLargeTextTest.scopeLabelChangeDoesNotUnlockAnInFlightRemoval`, 320×700 mdpi | Neutral pending panel and readable stage; test changes label/staleness while one removal remains in flight, then settles without a retry |
| [Native metadata pending](remote-native-metadata-pending.png) | `RemoteDestinationResolutionTest.nativeMetadataWaitsForPayloadPlaceThenReadOnlyRecoveryConfirmsWithoutAnotherWrite`, 412×892 mdpi | Healthy active metadata wait uses neutral progress; unknown payload Place stays unverified, no verified-navigation shortcut appears, and read-only confirmation remains reachable. Subsequent metadata resolution confirms Place/Start without another write |

The pending-removal capture isolates the removal component; the installed canonical Details
screen supplies its surrounding account/transfer context. The feature uses the
existing Add/Details components, colours, typography and action pattern under the
[feature experience contract](../../FEATURE-EXPERIENCE-CONTRACT.md). Pixel, human
TalkBack, IME and installed interaction acceptance remain separate.
