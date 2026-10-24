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