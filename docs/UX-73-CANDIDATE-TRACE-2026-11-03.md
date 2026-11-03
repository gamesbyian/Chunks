# Chunks: 73 UI/UX candidate coverage trace (2026-11-03)

**Purpose:** a dated reconciliation index for the owner's 73-candidate bulk UX implementation request. This is **not** a second work queue or product contract; [UX](UX.md), [SPEC-COVERAGE](SPEC-COVERAGE.md), [WORK-QUEUE](WORK-QUEUE.md) and [QUALITY-LEDGER](QUALITY-LEDGER.md) retain authority. Numbering is stable for verification and session batching, **not** priority. This list deliberately includes already-present functionality; counting an item never proves its installed user journey.

**Observed source baseline:** `main aa30c0ceefd25ff062510743c955c2a4ce358796`, incorporating merged [#1128](https://github.com/gamesbyian/Chunks/pull/1128), [#1129](https://github.com/gamesbyian/Chunks/pull/1129), [#1130](https://github.com/gamesbyian/Chunks/pull/1130). New source work is [draft PR #1132](https://github.com/gamesbyian/Chunks/pull/1132), pending checks and not yet integrated. **Source present** means a current implementation was inspected or previously qualified, **not** physical Pixel 6 Pro acceptance or full breadth. **Partial** means some implementation is visible but the whole candidate has not been proved. **Needs audit** means no defensible implementation status yet; do not assume missing or complete. **In #1132** denotes unmerged code that must pass exact-head checks and review before promotion.

**Coverage tally (not a completion claim):** 32 Partial; 5 Needs audit; 30 Source present; 6 In #1132. No candidate involving Delete data, missing permission or unverified server capability becomes safe by changing presentation alone.

| # | Area | Candidate | Observed state | Evidence/entry point |
| ---: | --- | --- | --- | --- |
| 1 | Navigation and orientation | Return launch to a useful Transfers inventory | Partial | [MainActivity.kt](../app/src/main/java/io/github/gamesbyian/chunks/MainActivity.kt) |
| 2 | Navigation and orientation | Skippable first-run setup | Needs audit | [MainActivity.kt](../app/src/main/java/io/github/gamesbyian/chunks/MainActivity.kt) |
| 3 | Navigation and orientation | Remove success slogans from daily-use surfaces | Source present | [CompactRemoteInventoryHeader.kt](../app/src/main/java/io/github/gamesbyian/chunks/CompactRemoteInventoryHeader.kt) |
| 4 | Navigation and orientation | Compact environment name, count and state | Source present | [CompactRemoteInventoryHeader.kt](../app/src/main/java/io/github/gamesbyian/chunks/CompactRemoteInventoryHeader.kt) |
| 5 | Navigation and orientation | Move technical endpoint/account URLs to connection details | Partial | [MainActivity.kt](../app/src/main/java/io/github/gamesbyian/chunks/MainActivity.kt) |
| 6 | Navigation and orientation | Persistent Transfers / Add / Options navigation | Partial | [AuthenticatedTransfersFooter.kt](../app/src/main/java/io/github/gamesbyian/chunks/AuthenticatedTransfersFooter.kt) |
| 7 | Navigation and orientation | Keep Add reachable while scrolling | Partial | [AuthenticatedTransfersFooter.kt](../app/src/main/java/io/github/gamesbyian/chunks/AuthenticatedTransfersFooter.kt) |
| 8 | Navigation and orientation | Options groups settings, environments, tools and privacy | Partial | [MainActivity.kt](../app/src/main/java/io/github/gamesbyian/chunks/MainActivity.kt) |
| 9 | Navigation and orientation | Keep specialty tools off primary transfer list | Source present | [LocalEngineScreen.kt](../app/src/main/java/io/github/gamesbyian/chunks/LocalEngineScreen.kt) |
| 10 | Navigation and orientation | Preserve navigation origin, scroll and focused context | Partial | [CombinedTransfersScreen.kt](../app/src/main/java/io/github/gamesbyian/chunks/CombinedTransfersScreen.kt) |
| 11 | Navigation and orientation | Use one shared user-facing vocabulary across engines | Partial | [UX.md](UX.md) |
| 12 | Transfer inventory and controls | Thin collapsed transfer rows | In #1132 | [CombinedTransfersScreen.kt](../app/src/main/java/io/github/gamesbyian/chunks/CombinedTransfersScreen.kt) |
| 13 | Transfer inventory and controls | Accessible truncation of long names | Partial | [MainActivity.kt](../app/src/main/java/io/github/gamesbyian/chunks/MainActivity.kt) |
| 14 | Transfer inventory and controls | Small truthful artwork or file-type fallback | In #1132 | [TransferFileTypeBadge.kt](../app/src/main/java/io/github/gamesbyian/chunks/TransferFileTypeBadge.kt) |
| 15 | Transfer inventory and controls | Environment colour plus text identification | Source present | [CombinedTransfersScreen.kt](../app/src/main/java/io/github/gamesbyian/chunks/CombinedTransfersScreen.kt) |
| 16 | Transfer inventory and controls | Compact truthful transfer status | Source present | [model/UnifiedTransferCatalog.kt](../app/src/main/java/io/github/gamesbyian/chunks/model/UnifiedTransferCatalog.kt) |
| 17 | Transfer inventory and controls | Segmented progress for all transfer lists | In #1132 | [ChunkedProgressBar.kt](../app/src/main/java/io/github/gamesbyian/chunks/ChunkedProgressBar.kt) |
| 18 | Transfer inventory and controls | Concise progress, rate, size and ratio as available | Partial | [CombinedTransfersScreen.kt](../app/src/main/java/io/github/gamesbyian/chunks/CombinedTransfersScreen.kt) |
| 19 | Transfer inventory and controls | Expand richer metadata and per-transfer actions | In #1132 | [CombinedTransfersScreen.kt](../app/src/main/java/io/github/gamesbyian/chunks/CombinedTransfersScreen.kt) |
| 20 | Transfer inventory and controls | Stable exact-key expansion across list refresh | In #1132 | [CombinedTransfersScreen.kt](../app/src/main/java/io/github/gamesbyian/chunks/CombinedTransfersScreen.kt) |
| 21 | Transfer inventory and controls | Avoid repeated giant button banks in collapsed view | In #1132 | [CombinedTransfersScreen.kt](../app/src/main/java/io/github/gamesbyian/chunks/CombinedTransfersScreen.kt) |
| 22 | Transfer inventory and controls | Search with accessible clear control | Source present | [ClearableTransferSearchField.kt](../app/src/main/java/io/github/gamesbyian/chunks/ClearableTransferSearchField.kt) |
| 23 | Transfer inventory and controls | Clear filters or restore all visible transfers | Source present | [CombinedViewPreferences.kt](../app/src/main/java/io/github/gamesbyian/chunks/CombinedViewPreferences.kt) |
| 24 | Transfer inventory and controls | Explain new Add results hidden by current filters | Partial | [CombinedTransfersScreen.kt](../app/src/main/java/io/github/gamesbyian/chunks/CombinedTransfersScreen.kt) |
| 25 | Transfer inventory and controls | Open exact transfer in its owning environment | Source present | [UnifiedTransferDetailPanel.kt](../app/src/main/java/io/github/gamesbyian/chunks/UnifiedTransferDetailPanel.kt) |
| 26 | Transfer inventory and controls | Distinguish empty, stale, locked and offline states | Source present | [CombinedTransfersScreen.kt](../app/src/main/java/io/github/gamesbyian/chunks/CombinedTransfersScreen.kt) |
| 27 | Transfer inventory and controls | Compact group-by-environment headings | Source present | [CombinedTransfersScreen.kt](../app/src/main/java/io/github/gamesbyian/chunks/CombinedTransfersScreen.kt) |
| 28 | Transfer inventory and controls | Compact saved, reversible sort/group controls | Source present | [CombinedViewPreferences.kt](../app/src/main/java/io/github/gamesbyian/chunks/CombinedViewPreferences.kt) |
| 29 | Add flow and destination | Single entry for magnet, torrent file and Android share | Source present | [VerifiedAddScreen.kt](../app/src/main/java/io/github/gamesbyian/chunks/VerifiedAddScreen.kt) |
| 30 | Add flow and destination | No compulsory duplicate Review step on ordinary Add | Source present | [VerifiedAddScreen.kt](../app/src/main/java/io/github/gamesbyian/chunks/VerifiedAddScreen.kt) |
| 31 | Add flow and destination | Show explicit Add environment instead of inferring from list | Partial | [CombinedAddActions.kt](../app/src/main/java/io/github/gamesbyian/chunks/CombinedAddActions.kt) |
| 32 | Add flow and destination | Show actual verified default Place with optional Change | Source present | [LocalDownloadPlacePicker.kt](../app/src/main/java/io/github/gamesbyian/chunks/LocalDownloadPlacePicker.kt) |
| 33 | Add flow and destination | Offer recent destinations scoped to environment | Partial | [RecentRemotePlaceStore.kt](../app/src/main/java/io/github/gamesbyian/chunks/RecentRemotePlaceStore.kt) |
| 34 | Add flow and destination | Start-when-added option with visible effective default | Source present | [LocalEngineScreen.kt](../app/src/main/java/io/github/gamesbyian/chunks/LocalEngineScreen.kt) |
| 35 | Add flow and destination | Initial wanted/unwanted file selection where supported | Partial | [LocalEngineScreen.kt](../app/src/main/java/io/github/gamesbyian/chunks/LocalEngineScreen.kt) |
| 36 | Add flow and destination | Finish in Order offered when genuinely supported | Source present | [FinishInOrderPanel.kt](../app/src/main/java/io/github/gamesbyian/chunks/FinishInOrderPanel.kt) |
| 37 | Add flow and destination | Explain seeding-group effect at Add | Partial | [SeedingRulesScreen.kt](../app/src/main/java/io/github/gamesbyian/chunks/SeedingRulesScreen.kt) |
| 38 | Add flow and destination | Keep optional advanced Add options compact | Partial | [VerifiedAddScreen.kt](../app/src/main/java/io/github/gamesbyian/chunks/VerifiedAddScreen.kt) |
| 39 | Add flow and destination | Preserve Add draft after failed or cancelled actions | Partial | [VerifiedAddScreen.kt](../app/src/main/java/io/github/gamesbyian/chunks/VerifiedAddScreen.kt) |
| 40 | Add flow and destination | Show verified Add result and exact View transfer action | Partial | [VerifiedAddScreen.kt](../app/src/main/java/io/github/gamesbyian/chunks/VerifiedAddScreen.kt) |
| 41 | Add flow and destination | Identify remote default Place without suggesting phone files | Source present | [VerifiedAddScreen.kt](../app/src/main/java/io/github/gamesbyian/chunks/VerifiedAddScreen.kt) |
| 42 | Transfer details and files | Task-first transfer detail hierarchy | Source present | [UnifiedTransferDetailPanel.kt](../app/src/main/java/io/github/gamesbyian/chunks/UnifiedTransferDetailPanel.kt) |
| 43 | Transfer details and files | Put raw hash and capability diagnostics behind disclosure | Source present | [UnifiedTransferDetailPanel.kt](../app/src/main/java/io/github/gamesbyian/chunks/UnifiedTransferDetailPanel.kt) |
| 44 | Transfer details and files | Use clear Start and Stop language per environment | Partial | [TransferLifecycleControl.kt](../app/src/main/java/io/github/gamesbyian/chunks/TransferLifecycleControl.kt) |
| 45 | Transfer details and files | Separate requested or acknowledged from verified activity | Source present | [UnifiedTransferDetailPanel.kt](../app/src/main/java/io/github/gamesbyian/chunks/UnifiedTransferDetailPanel.kt) |
| 46 | Transfer details and files | Explain zero traffic without claiming a cause | Source present | [MainActivity.kt](../app/src/main/java/io/github/gamesbyian/chunks/MainActivity.kt) |
| 47 | Transfer details and files | Show last observed time and stale-read context | Source present | [UnifiedTransferDetailPanel.kt](../app/src/main/java/io/github/gamesbyian/chunks/UnifiedTransferDetailPanel.kt) |
| 48 | Transfer details and files | Show exact save Place and available file actions | Partial | [MainActivity.kt](../app/src/main/java/io/github/gamesbyian/chunks/MainActivity.kt) |
| 49 | Transfer details and files | Expose files, peers, trackers and priority controls | Partial | [TransferFilePager.kt](../app/src/main/java/io/github/gamesbyian/chunks/TransferFilePager.kt) |
| 50 | Transfer details and files | Allow display-alias edits without renaming payload | Source present | [TransferAliasEditor.kt](../app/src/main/java/io/github/gamesbyian/chunks/TransferAliasEditor.kt) |
| 51 | Transfer details and files | Keep original torrent name available | Source present | [MainActivity.kt](../app/src/main/java/io/github/gamesbyian/chunks/MainActivity.kt) |
| 52 | Transfer details and files | Distinguish job Copy/Move from payload-copy operations | Source present | [TransferMoveControl.kt](../app/src/main/java/io/github/gamesbyian/chunks/TransferMoveControl.kt) |
| 53 | Environments, settings and connection states | Friendly custom environment names | Partial | [CombinedTransfersScreen.kt](../app/src/main/java/io/github/gamesbyian/chunks/CombinedTransfersScreen.kt) |
| 54 | Environments, settings and connection states | Palette colour retained in all relevant scope controls | Partial | [EnvironmentColourStore.kt](../app/src/main/java/io/github/gamesbyian/chunks/EnvironmentColourStore.kt) |
| 55 | Environments, settings and connection states | Clearly show setting environment and transfer scope | Partial | [SeedingRulesScreen.kt](../app/src/main/java/io/github/gamesbyian/chunks/SeedingRulesScreen.kt) |
| 56 | Environments, settings and connection states | Expose current value and provenance (automatic/override) | Needs audit | [UX.md](UX.md) |
| 57 | Environments, settings and connection states | Contextual Change and Reset to automatic | Partial | [EnvironmentColourStore.kt](../app/src/main/java/io/github/gamesbyian/chunks/EnvironmentColourStore.kt) |
| 58 | Environments, settings and connection states | Distinguish one-off override from persistent default | Partial | [SeedingRulesScreen.kt](../app/src/main/java/io/github/gamesbyian/chunks/SeedingRulesScreen.kt) |
| 59 | Environments, settings and connection states | Show connecting, offline, stale and auth-required honestly | Partial | [MainActivity.kt](../app/src/main/java/io/github/gamesbyian/chunks/MainActivity.kt) |
| 60 | Environments, settings and connection states | Offer context-specific connection repair actions | Partial | [MainActivity.kt](../app/src/main/java/io/github/gamesbyian/chunks/MainActivity.kt) |
| 61 | Environments, settings and connection states | Phone mode remains usable without remote login | Source present | [LocalEngineScreen.kt](../app/src/main/java/io/github/gamesbyian/chunks/LocalEngineScreen.kt) |
| 62 | Environments, settings and connection states | Search settings without creating a second settings system | Needs audit | [MainActivity.kt](../app/src/main/java/io/github/gamesbyian/chunks/MainActivity.kt) |
| 63 | Visual consistency and accessibility | Shared typography, density and spacing across surfaces | Partial | [UX.md](UX.md) |
| 64 | Visual consistency and accessibility | Honor current ExtraBold-only approved font policy | Needs audit | [UX.md](UX.md) |
| 65 | Visual consistency and accessibility | Accessible palette colours and contrast fallback | Source present | [model/EnvironmentPalette.kt](../app/src/main/java/io/github/gamesbyian/chunks/model/EnvironmentPalette.kt) |
| 66 | Visual consistency and accessibility | At least 48dp interactive touch targets | Partial | [MainActivity.kt](../app/src/main/java/io/github/gamesbyian/chunks/MainActivity.kt) |
| 67 | Visual consistency and accessibility | TalkBack descriptions and accurate state descriptions | Partial | [UnifiedTransferDetailPanel.kt](../app/src/main/java/io/github/gamesbyian/chunks/UnifiedTransferDetailPanel.kt) |
| 68 | Visual consistency and accessibility | Keyboard/inset/IME correctness with persistent footer | Partial | [AuthenticatedTransfersFooter.kt](../app/src/main/java/io/github/gamesbyian/chunks/AuthenticatedTransfersFooter.kt) |
| 69 | Visual consistency and accessibility | Respect reduced motion in scroll and animation | Source present | [ReturnToTopControl.kt](../app/src/main/java/io/github/gamesbyian/chunks/ReturnToTopControl.kt) |
| 70 | Visual consistency and accessibility | Never use colour alone to identify state or environment | Source present | [CombinedTransfersScreen.kt](../app/src/main/java/io/github/gamesbyian/chunks/CombinedTransfersScreen.kt) |
| 71 | Visual consistency and accessibility | Distinguish unavailable observations from measured zero | Source present | [model/UnifiedTransferCatalog.kt](../app/src/main/java/io/github/gamesbyian/chunks/model/UnifiedTransferCatalog.kt) |
| 72 | Visual consistency and accessibility | Wrap long names, paths and current-value summaries accessibly | Partial | [MainActivity.kt](../app/src/main/java/io/github/gamesbyian/chunks/MainActivity.kt) |
| 73 | Visual consistency and accessibility | Restore keyboard focus and accessibility navigation context | Needs audit | [ClearableTransferSearchField.kt](../app/src/main/java/io/github/gamesbyian/chunks/ClearableTransferSearchField.kt) |

## Batching and closure rules

Keep independently justified changes on a common exact source when their source and operation boundaries overlap. Start with cheaper Kotlin/JVM/Compose checks, then one joined installed Android campaign for the affected phone, authenticated rTorrent and Combined UI. Keep each failed, skipped or platform-specific case individually identifiable. Preserve pre-existing real peer/piece, storage readback and reboot receipts rather than rerunning unrelated full journeys. A screenshot or mock result cannot qualify deletion, Start/Stop, actual remote File Copy or file-provider consent. Avoid adding speculative controls that advertise unimplemented functionality. Reconcile each row with live source and behavior before marking genuinely complete; do not erase its remaining physical, OEM, profile or accessibility tiers.
