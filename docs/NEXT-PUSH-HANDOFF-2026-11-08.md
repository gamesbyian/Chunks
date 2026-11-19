# Chunks next-push handoff: executable entry

**Owner priority:** Complete Android back-end functionality and its reliability before elective UI polish. Deliver Transmission, qBittorrent and Deluge client support **before automatic Smart Routing**. This is the single executable handoff, not a competing backlog: [WORK-QUEUE](WORK-QUEUE.md) owns priority, [TECHNICAL-COMPLETENESS-LEDGER](TECHNICAL-COMPLETENESS-LEDGER.md) owns status, [QUALITY-LEDGER](QUALITY-LEDGER.md) owns evidence. Verify fresh GitHub state before acting.

## 0. Inspect and preserve current source

1. Read [AGENTS](../AGENTS.md), [BACKEND-FIRST-COMPLETION](BACKEND-FIRST-COMPLETION.md), [REMOTE-BACKEND-ADAPTERS](REMOTE-BACKEND-ADAPTERS.md), [UNIFIED-ENVIRONMENTS](UNIFIED-ENVIRONMENTS.md), [Universal Add parity](design/UNIVERSAL-ADD-CLIENT-PARITY.md), [CI-POLICY](CI-POLICY.md), then inspect live main, exact open PR heads, tests and concurrent edits. Current `main` and CI must always be re-fetched; do not trust the checkpoint SHA below.
2. **[Draft #1859](https://github.com/gamesbyian/Chunks/pull/1859) was closed as superseded** after the complete vertical implementation merged in #1863. The historical branch remains intact for audit, but its old snapshot **must not be merged**. #1866 and #1871 are merged.
3. Already **merged and source-exact checked**: #1849–#1851 exact-endpoint read-only Transmission/qBittorrent/Deluge RPC adapters; #1852 protocol identity + denied-write capabilities; #1853 normalized observations; #1854 typed authenticated read-only inspection; #1855 separately encrypted typed profile vault, legacy rTorrent preservation and global remember opt-out; #1856 privacy-fenced feed into canonical Combined Transfers; #1857 optional picker in the existing Connections form; #1858 manual trusted-main, read-only development Seedhost compatibility harness. **None proves three new clients can yet be added via the installed app or that real Seedhost tests have run.**

## 1. Current state (2026-11-19) and immediate next steps

Merged since 2026-11-18. Read live PRs first.

| PR | Commit | What it adds |
|---|---|---|
| #1877 | `f6f39fe` | One native Local Add sequence; This device job Move in both directions, including a metadata-less magnet via libtorrent's own magnet; Change folder; opt-in Smart Routing pre-select |
| #1878 | | Installed protocol-Add file choices |
| #1879, #1880, #1882 | | Add-time Low/High in **all three** Add forms (shared workflow and protocol Add, rTorrent High-only, device Low/High). An unstorable level is refused before any write |
| #1884 | | Installed Add-time High |
| #1885 | `92ce4c9` | **Finish in Order for Transmission/qBittorrent/Deluge** through `ClientFinishInOrderPort` |
| #1886 | `d85551c` | Installed and live proof of that Finish in Order |

**Live Seedhost** on `d85551c` (runs 44438688040, 44438691935, 44438695847, fixture-only): Transmission, qBittorrent and Deluge each passed the full mutation journey, Add-time file choices (Low stored on Transmission and Deluge), and Finish in Order (Applied, then an Idle step with no write).

1. **Local rig.** Start it with `tools/interop/start_local_clients.sh build/interop && source tools/interop/local_env.sh build/interop`. The interop suites are `RealClientInteropTest`, `LocalCrossClientInteropTest`, `SharedAddFileChoicesInteropTest`, `ClientFinishInOrderInteropTest` and `PendingMagnetInventoryInteropTest`; run them with `--no-configuration-cache`.
   - Use `/opt/gradle-9.3.1/bin/gradle`. The default `gradle` is 8.14.3 and fails at plugin apply.
   - CI runners install **qBittorrent 5.1.4** and **Deluge 2.2.1**, while the container apt has 4.6.3 and 2.1.2. A static qBittorrent 5.1.4 (`userdocs/qbittorrent-nox-static` release `release-5.1.4_v2.0.11`) runs on the same profile and port after stopping 4.6.3, and every shared-workflow suite passed on it.
   - Daemons die with a container restart.
   - Loopback Transmission and rTorrent never dial peers (a rig limitation).
2. **Installed CI gates after any Add/client change.** These run only in GitHub CI; there is no KVM here.
   - `installed-protocol-client.yml`: Connect → Details → Add → device Move round trip → Open With file choices with High → Details Finish in Order.
   - `installed-rtorrent-magnets.yml`, `installed-combined-two-servers.yml`, the native Add shards and the atlas. Dispatch a gate on the branch when a shared component it renders changes but its path filter misses.
3. **Test-reliability rule.** Activate toggles and buttons in installed journeys through their semantics `OnClick` after reading their state, never with coordinate taps. Two intermittent "product" failures, the second-remote-Add stall and "added stopped", were missed taps (QUALITY-LEDGER 2026-11-18/19).
4. **Known flake.** `LocalTorrentEngineTest.startAfterPayloadTruncated…` times out at `repaired-download-flush` about 1 run in 3. Re-hinting `connect_peer` made it worse (6 of 6 failed); investigate why the first connect fails rather than repeating it.
5. **Next architecture.** This is the owner's queue stop condition: **one app-owned `UniversalAddAttemptController`**, step 2 of [Universal Add parity](design/UNIVERSAL-ADD-CLIENT-PARITY.md). Today:
   - `VerifiedAddScreen` (rTorrent) and `LocalEngineScreen` each own their Add orchestration, outcome and navigation.
   - `ProtocolAddScreen` already uses `ClientOperations` with an app-scoped `ProtocolAddAttempt`.

   Suggested order:
   1. Define the engine-neutral submission/result interface over the existing adapters. Write no new engine writer.
   2. Move `ProtocolAddAttempt` and the rTorrent attempt behind it first. rTorrent's metadata-pending native Place and BEP9 proofs stay inside its adapter.
   3. Move Local last.
   4. Only then build one capability-driven Add form and route.

   Keep the no-replay latches and process-death uncertainty. Requalify with the installed real-magnet and protocol journeys on every step.

## 2. Continue real-engine proof and mutation support, then Smart Routing

1. Run [#1858's manual trusted-main workflow](../.github/workflows/seedhost-other-protocols-readonly.yml) once for **each** development-only Seedhost client, after checking real GitHub secret **names**, exact HTTPS endpoint and Deluge daemon host ID. One non-skipped passing fixture per protocol is required. Keep credentials, torrent names/hashes, directories, tracker URLs, cookies and raw bodies out of logs. Missing secrets or a never-dispatched workflow is **unverified**, not success. Respect explicit owner authorization to use disposable development queues; still enforce exact account/path identity and safe operation scope.
2. Add authenticated operation support in coherent vertical batches using shared intent/authority/readback: source → reviewed exact client/Place/Start → Add .torrent/magnet → confirm registration and state → Start/Stop, files/priorities, session Remove. Separately authorize Remove+data only where exact filesystem scope and independently observed cleanup are possible. A lost ACK/timeout is uncertain, never an automatic second write. Use actual Transmission, qBittorrent and Deluge instances to test each independently.
3. Only after multi-client Add/capability/Place/Start proof integrate Smart Routing's existing inert preview/rules with Universal Add and deliberate exact-client ASK/default behavior. Job-only Move never silently copies payload data or retargets a running download.
4. Use spare independent capacity for high-leverage **backend-first** work: finish one app-owned Local/rTorrent Add operation/readback/form rather than another isolated parity microtask; owner Pixel Open With (#1786), stopped magnet and partial-file recovery; active per-client seeding rules; snapshot recovery; safe storage/Move and declarative Settings bindings. Preserve approved UX designs for coordinated final visual polish.

## 3. Guardrails and report

Preserve exact authenticated client/hash/destination/Start scope, existing rTorrent credentials, local data and all explicit destructive approvals. No blind uncertain-write replay, no wrong-client mutation, no stale/locked disclosure, no fabricated full inventory or saved-login success. Never claim physical Pixel, protected live-host, peer bytes or signing acceptance based on emulator/JVM only. The user's PC is **not** project infrastructure: work in GitHub/repo; do not use Desktop Commander.

At stop, reconcile live PRs and checks; merge only qualified work; update [WORK-QUEUE](WORK-QUEUE.md), [TECHNICAL-COMPLETENESS-LEDGER](TECHNICAL-COMPLETENESS-LEDGER.md), [SPEC-COVERAGE](SPEC-COVERAGE.md) and historical [QUALITY-LEDGER](QUALITY-LEDGER.md) as needed. Report exact main/PR SHAs, what actually works on Android, accepted tests, failures and unknowns, deferred live fixture runs and the highest-value next executable task. Never call an unconnected callback or a queued workflow completed.
