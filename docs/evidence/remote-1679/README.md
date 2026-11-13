# Remote reliability render evidence (#1679)

Evidence-only Robolectric native-graphics API35 captures, **not approved golden
baselines, installed-device proof or owner visual approval**. All source/hash/path
content shown is synthetic. Regenerated after integration onto `19bfe3de`;
the reviewed production components and JVM producers come from checkpoint
`4aa4ea8f`. The later Android runner/fixture corrections do not alter these
rendered components. The [quality ledger](../../QUALITY-LEDGER.md) records
functional proof and remaining acceptance tiers.

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
