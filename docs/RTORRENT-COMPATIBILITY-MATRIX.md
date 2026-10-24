# Read-only rTorrent compatibility proof matrix

This file owns the protocol acceptance protocol for #1, not the current task priority. Created 2026-10-24.

| Evidence class | Acceptance meaning |
|---|---|
| Pure XML-RPC and synthetic gateway fixtures | Parser, transport and expected response shapes only, PR #12 |
| Ephemeral real rTorrent + nginx localhost SCGI | An actual installed legacy rTorrent process answers Chunks version/probe/empty-list read-only requests |
| Real protected seedbox | Compatibility with a specific deployment/proxy/auth and actual torrent views; not yet verified |
| Newer rTorrent JSON/XML-RPC builds | Separate method/encoding and transport verification; not yet verified |
| Physical Android | Separate Android network and lifecycle acceptance; not yet verified |

The narrowly scoped one-off GitHub Actions workflow installs an ephemeral rTorrent server on the runner, binds SCGI only to loopback, and never touches production media. No torrent is added. Once a successful run is captured in QUALITY-LEDGER, remove its PR trigger and retain it manual-only, per CI policy.

A passing ephemeral server test is stronger than a mock but cannot prove managed seedbox compatibility, live data relocation, or performance under large torrent lists. Record the exact server package/version, Actions run and source SHA with any conclusion.