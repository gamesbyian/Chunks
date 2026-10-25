# chunks.

**Your downloads have places to be.**

Chunks is an Android-native BitTorrent client and first-class rTorrent remote. The product unifies **Transfers**, **Places** (storage destinations), and **Collections** (semantic groupings) so users can add, move, rename, and organize content without losing seeding integrity.

## Status

**Pre-alpha / runnable Android remote.** The native app can connect to a ruTorrent-style HTTPS XML-RPC endpoint with challenge-negotiated Basic/Digest authentication, display, search, filter and inspect transfers, and show Places/collection suggestions. A two-step Add flow accepts a v1 magnet or Android Files `.torrent`, demands a destination and confirmation, then attempts a stopped load and distinguishes verified/pending/uncertain results. Start/Stop, session-only Remove (which never deletes backing bytes), and individual paged-file priority changes require exact identity, deliberate confirmation and server readback. Native confirmation state survives transfer-list recycling, with [#89](https://github.com/gamesbyian/Chunks/pull/89) independently passing Android/JVM and API35 scrolling regressions. See the [evidence ledger](docs/QUALITY-LEDGER.md) for tested source SHAs. These are pre-alpha controls, **not yet a proven handset-to-Seedhost journey**. Moving/renaming actual seeding files, deletion with payload, persistent profiles, local BitTorrent engine, and release APK signing remain unfinished.

## Read in this order

1. [Agent operating contract](AGENTS.md)
2. [Critical design review](docs/CRITICAL-REVIEW-2026-10-24.md) and [product contract](docs/PRODUCT.md)
3. [Live work queue](docs/WORK-QUEUE.md) and [GitHub issues](https://github.com/gamesbyian/Chunks/issues)
4. [Architecture and failure semantics](docs/ARCHITECTURE.md)
5. [rTorrent real-server compatibility matrix](docs/RTORRENT-COMPATIBILITY-MATRIX.md), [engineering decisions](docs/DECISIONS.md), [independent acceptance](docs/QUALITY-LEDGER.md), [CI policy](docs/CI-POLICY.md)
6. [Research and upstream references](docs/RESEARCH.md), [cross-project reuse audit](docs/REUSE-AUDIT.md), [security](docs/SECURITY.md), [interaction journeys](docs/UX.md)

Every feature must distinguish **requested**, **acknowledged**, and **verified** outcomes. Neither success on an emulator nor a green Gradle check proves a real seedbox operation, storage safety, Android background reliability, or an accessible physical UI.

Repository: https://github.com/gamesbyian/Chunks

No third-party service, telemetry, account, or remote metadata lookup is required by the product concept. Licensing and release distribution remain undecided; public repository visibility does not grant permission to redistribute code.
## Hosted ruTorrent connection

The connection form accepts either an HTTPS rTorrent XML-RPC endpoint or a hosted ruTorrent web URL such as `https://host.example/rutorrent/`. It derives the candidate HTTP-RPC plugin path automatically. See [provider testing and secrets policy](docs/SEEDHOST-REMOTE-TESTING.md). Chunks does not save credentials; the protected real Seedhost version/auth probe passed [run 40063547095](https://github.com/gamesbyian/Chunks/actions/runs/40063547095), but app/device list and mutation compatibility still require their own live evidence.

## Licensed public Add fixture and test workflows

For repeatable Add tests, the [public fixture collector](docs/PUBLIC-TORRENT-FIXTURES.md) handles genuine Internet Archive `.torrent` files, verified-hash magnets and optional small Wikimedia Commons files converted to local test torrents. [Fixture collection](https://github.com/gamesbyian/Chunks/actions/workflows/public-source-fixtures.yml) is metadata-only by default and does not contact rTorrent. [Protected Seedhost Add acceptance](https://github.com/gamesbyian/Chunks/actions/workflows/seedhost-public-add-smoke.yml) defaults to preview and requires explicit hash confirmation for one stopped test Add; no live Add has yet been proven merely by merging this workflow.

## Optional human-friendly transfer names (merged pre-alpha)

The merged native implementation [#74](https://github.com/gamesbyian/Chunks/pull/74) lets users keep the original torrent/filename and seeding untouched while displaying a custom queue name, with conservative editable offline suggestions and one-tap reset. Aliases remain device-local, not synchronized to rTorrent; both original and alias can be searched. This is not a backing-file rename or a finished release feature: real Seedhost queue, physical handset and accessibility acceptance are still required.

## Storage, ZIP safety and acceptance boundaries

The optional on-device storage-cap warnings, cleanup impact preview and ZIP inventory currently exist as **non-mutating models**, not a storage-management UI or deletion engine. [#92](https://github.com/gamesbyian/Chunks/pull/92) adds bounded, read-only ZIP central-directory member-type checks that block Unix symlinks, special files and unsupported ZIP64/encrypted/split archive types. [#101](https://github.com/gamesbyian/Chunks/pull/101) additionally reads actual inflated bytes and verifies CRCs with strict limits, but does not write any extracted files. Safe streamed archive extraction, manual/opt-in auto-extraction, quota enforcement and real cleanup remain future issue [#70](https://github.com/gamesbyian/Chunks/issues/70); an extracted copy never licenses deletion of a torrent-backed source archive.

The actual installed Android MainActivity populated synthetic XML-RPC journey **merged** as [#82](https://github.com/gamesbyian/Chunks/pull/82), with [API35 40155244582](https://github.com/gamesbyian/Chunks/actions/runs/40155244582) passing 17/17 and companion [Android/JVM 40155244595](https://github.com/gamesbyian/Chunks/actions/runs/40155244595) and [four-viewport 40155244601](https://github.com/gamesbyian/Chunks/actions/runs/40155244601) success. Native screenshots were inspected for populated navigation, selected alias, Save and original name. Authenticated full Seedhost inventory, actual remote writes and physical Android accessibility remain separate gates. Current source receipts live in [WORK-QUEUE](docs/WORK-QUEUE.md) and [QUALITY-LEDGER](docs/QUALITY-LEDGER.md).

## Review several queue names together

From Transfers, **Review suggested queue names** opens the new [merged #98](https://github.com/gamesbyian/Chunks/pull/98) native batch review. Existing custom aliases are skipped; users select, optionally edit, review and confirm up to 100 current-view conservative offline proposals. One device-local write applies the batch, and Undo is guarded against overwriting later manual changes. Tested with JVM and real API35 Compose interactions, with inspected proposal and saved-state PNGs. No remote RPC or filesystem operation is involved; external titles, style automation and backing-file renames remain separate future features.

The [merged native phone-storage card (#104)](https://github.com/gamesbyian/Chunks/pull/104) now reports OS-measured total/free bytes for the Android app's internal volume and an active low-storage alert when available, with an explicit Refresh action. The numbers include other apps and system reservations. They are **not** remote rTorrent capacity, Chunks-only usage, a user-set quota or a guarantee about future downloads. The actual low-storage threshold is not exposed to ordinary apps; automated deletion/enforcement remain unimplemented.
