# Remote reliability evidence (#1679)

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
