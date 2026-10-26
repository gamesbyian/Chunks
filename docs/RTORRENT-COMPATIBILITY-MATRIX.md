# Read-only rTorrent compatibility proof matrix

This file owns the protocol acceptance protocol for #1, not the current task priority. Created 2026-10-24.

| Evidence class | Acceptance meaning |
|---|---|
| Pure XML-RPC and synthetic gateway fixtures | Parser, transport and expected response shapes only, PR #12 |
| Ephemeral real rTorrent + nginx localhost SCGI | **Passed** [run 40060755245](https://github.com/gamesbyian/Chunks/actions/runs/40060755245), source SHA `cd337cc8560c8c1d90c581be3de6f61176106e1c`, Ubuntu rTorrent package `0.9.8-1ubuntu0.1`; version/probe/empty-list in Chunks JVM adapter |
| Real protected seedbox | Protected HTTPS authentication and version RPC passed [run 40063547095](https://github.com/gamesbyian/Chunks/actions/runs/40063547095). Kotlin full-list, Android handset and actual Add/remove remain unverified |
| Newer rTorrent JSON/XML-RPC builds | Separate method/encoding and transport verification; not yet verified |
| Physical Android | Separate Android network and lifecycle acceptance; not yet verified |

The narrowly scoped one-off GitHub Actions workflow installs an ephemeral rTorrent server on the runner, binds SCGI only to loopback, and never touches production media. The first version-only trial added no torrents; the later isolated real rTorrent 0.9.8 workflow [run 40116608163](https://github.com/gamesbyian/Chunks/actions/runs/40116608163) exercised stopped binary Add and lifecycle changes on disposable data in PR #46. Verify its source SHA and scope before extending that claim. The one-shot [run 40060755245](https://github.com/gamesbyian/Chunks/actions/runs/40060755245) succeeded on the original experiment SHA; its PR trigger has now been retired and only `workflow_dispatch` remains. Update the canonical QUALITY-LEDGER after merging this test tooling.

A passing ephemeral server test is stronger than a mock but cannot prove managed seedbox compatibility, live data relocation, or performance under large torrent lists. Record the exact server package/version, Actions run and source SHA with any conclusion.
### Parser boundary receipt (2026-10-24)

[#105](https://github.com/gamesbyian/Chunks/pull/105) merged pre-DOM SAX structure and decoded-value budgets; [Android/JVM 40163171672](https://github.com/gamesbyian/Chunks/actions/runs/40163171672) and [real API35 40163171740](https://github.com/gamesbyian/Chunks/actions/runs/40163171740) passed on tested source `66957c77a9ebae6bee1f18c7c6eddd813c2bf25e`. This proves the Android platform parser accepts ordinary synthetic rTorrent-shaped replies and refuses measured pathological nesting, arrays and fields. **Do not infer real Seedhost method shape, exhaustive inventory, 12k-row performance or another rTorrent version from these fixtures.**

### Duplicate infohash view safety (merged #108)

The app's normal `d.multicall2` list path refuses an entire view if two rows carry a case-insensitive equal infohash; selecting either row would be ambiguous for the hash-targeted command surface and keyed native list. [#108](https://github.com/gamesbyian/Chunks/pull/108) merged `7eab30fcc710389d91d7bb18bbae4a33f022828a` after Android/JVM [40163747682](https://github.com/gamesbyian/Chunks/actions/runs/40163747682) passed at source `5b7d09639c68170a095a36f923638cd12e871375`. The negative test is a synthetic authenticated/loopback-style RPC reply, not evidence of actual duplicate entries at the designated Seedhost, nor proof the `main` view enumerates every remote torrent. Never silently deduplicate real server transfers in UI without provenance.

### Malformed torrent identity refusal (merged #111)

The remote transfer list now requires exactly 40 hexadecimal characters for v1-style identities or 64 hexadecimal characters for v2-shaped identities before placing any row in the native keyed queue. Earlier generic nonempty/128-character acceptance could expose malformed action targets. [#111](https://github.com/gamesbyian/Chunks/pull/111) merged `2e021ecf2478028638dd8f8c7966675624b753b2` after exact source `35b895c30e4cfae5918f748dae6ea5a04ee632f7` passed [Android/JVM 40229375537](https://github.com/gamesbyian/Chunks/actions/runs/40229375537). Synthetic loopback XML-RPC tests reject wrong lengths and nonhex IDs, preserve valid hex-shaped 64-character listing and send no mutation. This validates parse-level identity only, **not** actual newer-rTorrent/v2 command compatibility or full authenticated Seedhost inventory.

## Large-queue listing fallback (fixture evidence only)

A single `d.multicall2` reply for roughly 6k+ transfers exceeds Chunks' 4 MiB transport cap or the XML element/value budgets. Such replies now raise a distinct `RpcReplyTooLarge`, and listing falls back to one `download_list` (identities only) plus `system.multicall` pages of 250 hashes × exact-hash `d.*` getters. Any per-call fault (e.g. a torrent erased mid-read) fails the whole snapshot closed; a requested/returned hash mismatch is refused. Hostile deep nesting stays an ordinary refusal and never triggers paging. Evidence: localhost XML-RPC fixture with 7,000 synthetic transfers, plus the disposable real rTorrent 0.9.8 job ([run 40238780520](https://github.com/gamesbyian/Chunks/actions/runs/40238780520)) where the paged read matched `d.multicall2` (hashes, names, directories, sizes, extended getters) for a small real queue. Not proven: owner Seedhost build, a real multi-thousand queue, latency.

## Owner development Seedhost (observed 2026-10-26)

rTorrent 0.9.8 behind ruTorrent HTTP-RPC over HTTPS with challenged authentication. Read-only Kotlin adapter evidence in [run 40247072785](https://github.com/gamesbyian/Chunks/actions/runs/40247072785): full 436-transfer main view, paged listing parity, `f.multicall` file listings, all write methods advertised. `directory.default` is empty on this host, so Chunks cannot suggest a server default Place from it.
