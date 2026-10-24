# Chunks evidence and release gate ledger

Updated 2026-10-24. This ledger alone records proven gates; GitHub issues track work and WORK-QUEUE.md tracks priorities. Exact CI SHA matters. Mock/fixtures cannot prove server or handset functionality.

| Gate | Evidence observed | Status |
|---|---|---|
| Project scaffold | merged [#11](https://github.com/gamesbyian/Chunks/pull/11), main 6dba63fed72c92c7f97128ef3fa08d278a37b730 | Established |
| First Android debug build and JVM tests | [run 40057523588](https://github.com/gamesbyian/Chunks/actions/runs/40057523588), PR SHA 201b53fc3d16482cf2aa1a08c9c01d9e0351174b, 42 Gradle tasks successful | Passed for that SHA |
| Read-only synthetic RPC gateway fixtures | merged [#12](https://github.com/gamesbyian/Chunks/pull/12), [run 40057876761](https://github.com/gamesbyian/Chunks/actions/runs/40057876761), PR SHA 5b5b75ed4abaa9f4e97ec4c7e4fa517130e8f681, Gradle test and debug assemble passed | Passed synthetic JVM fixtures only |
| Existing relocation planner tests | Included in Android/JVM build of #11; pure decision model only | Passed decision logic, NOT filesystem execution |
| Read-only native dashboard + new catalog/inspection fixtures | [merged #14](https://github.com/gamesbyian/Chunks/pull/14), [run 40060156362](https://github.com/gamesbyian/Chunks/actions/runs/40060156362), PR SHA `7a7aae0a811542d38b3b523640d05f9d24fccbd7`, debug artifact `chunks-debug-prealpha` | JVM tests + APK assembly passed; no server/device proof |
| Android Share/Open With and destination-draft validation | [merged #17](https://github.com/gamesbyian/Chunks/pull/17), [run 40060539699](https://github.com/gamesbyian/Chunks/actions/runs/40060539699), PR SHA `d586f712da63ba5c56a3d275ec68e29eb8834a7b`, `chunks-debug-prealpha` | Kotlin/JVM + APK compile passed, no real device intent test |
| Actual disposable rTorrent 0.9.8 read-only XML-RPC compatibility | [merged #15](https://github.com/gamesbyian/Chunks/pull/15), [run 40060755245](https://github.com/gamesbyian/Chunks/actions/runs/40060755245), tested SHA `cd337cc8560c8c1d90c581be3de6f61176106e1c`. rTorrent package `0.9.8-1ubuntu0.1` behind local nginx/SCGI. JVM version, empty-list and probe passed | **Passed for disposable Ubuntu build only** |
| Hosted ruTorrent URL derivation + protected smoke harness | [PR #20 merged](https://github.com/gamesbyian/Chunks/pull/20), [run 40062658654](https://github.com/gamesbyian/Chunks/actions/runs/40062658654), tested SHA `b7fc42a8b6d8307ee00309fddb256dbbca9a91c4`, APK `chunks-debug-prealpha` | Kotlin/JVM and debug assembly **passed**; manual secret-backed test **not run** |
| Protected actual Seedhost HTTPS XML-RPC version/auth | [run 40063547095](https://github.com/gamesbyian/Chunks/actions/runs/40063547095) on SHA `2f2660780363faf91a8d04ff8415264f0176c229`: `authenticated read-only response verified` | **Passed** for one authenticated version response; no torrent-list/app transport proof |
| Real rTorrent reachability/auth | One successful authenticated XML-RPC version response; no actual torrent enumeration or Android connection evidence | Partial |
| Legacy/modern rTorrent compatibility | No dual-server observations, Digest/JSON-RPC/i8 or view coverage proof | Not tested |
| Complete large remote list and reconnect | No real fleet/performance test | Not tested |
| Remote Add and ordinary write controls | Hash-targeted start/stop API implemented in #27, JVM fixtures and debug build passed; no server/device evidence or UI controls, Add still absent | Partial core; real-server test pending |
| Actual live data relocation | No journal executor, disposable crash/volume test or seed recheck | Not implemented |
| Safe backed-file rename and library copy | No verified seeding/complete file publication case | Not implemented |
| Organization and rules | Simple local classifier fixture exists; no mature rules, review or provenance acceptance | Prototype only |
| Local BitTorrent | No local torrent network engine or seeding | Not implemented |
| Real handset Android | No screenshot, accessibility, lifecycle or on-device UI validation | Not observed |
| Signed/secure release | No signed release, user-ready credential store, permissions review or license decision | Blocked |

## Source and status rules

The current XML-RPC adapter is read-only and Basic-auth capable, now with optional transfer rates, multiple server views, file inspection and a synthetic fallback for unsupported metric methods. The Add screen is a local, non-submitting draft. The synthetic RPC tests cover authenticated fixture listing, capability absence/presence, redirect refusal and unsafe remote HTTP rejection. Most are JVM fixtures. The dedicated #15 run separately exercised the same adapter against an actual rTorrent process; this proves only the ephemeral 0.9.8 deployment, **not** authenticated remote seedbox, JSON-RPC, filesystem writes or physical Android behavior.

The build artifacts are **debug-only** and not production-qualified. No production server data was moved, renamed, deleted, copied or modified in these Chunks runs.

Future evidence must record exact source SHA, server/Android build as appropriate, sanitized environment topology, test action, verification result and remaining uncertainty. Test fixture pass means no more than the fixture asserts.

## Development server testing authorization

The owner approved destructive test operations on the designated Seedhost rTorrent instance and approved publishing nonsecret inventory and visual fixture data. **Authorization is not test evidence**: no remote Add, stop/start, remove, data erase, filesystem move or rename is considered implemented or verified because this consent exists. The approved target is the development torrent population, not credentials, other FTP paths or unrelated servers. Dedicated exact-hash gates and observed outcomes remain required.

## First controlled remote lifecycle mutation candidate

[PR #27](https://github.com/gamesbyian/Chunks/pull/27) proposes an allowlisted exact-v1-hash start/stop adapter with state read-back and no automatic retries. It merged at `ef03e099011f796118f0626bcc32aa1cadf045fa` following [CI run 40064096463](https://github.com/gamesbyian/Chunks/actions/runs/40064096463), PR SHA `124cee10a2468a88eb0cb576bb308ae944baf97c`, successful JVM tests and debug APK assembly. **Actual live-server start/stop remains untested**. Pending/uncertain outcomes must never be displayed as verified success.

## Public-source fixture factory

The [Archive/Commons collector](PUBLIC-TORRENT-FIXTURES.md) stages strict validation, exact infohash identity and metadata-only provider retrieval. Proposed until CI verification: real Archive `.torrent` and derived magnets, Commons manifest-only file metadata. Collector does not contact rTorrent, download full media, or prove application Add, seeding or lifecycle behavior.

## Remote Add readback prototype

The stopped Add API in PR #31 is pending JVM/Android CI and real-server acceptance. Synthetic RPC acknowledgements, if successful, prove command sequencing only. `VerifiedStopped` requires observed matching main-view hash, `d.directory_base` and stopped state. Pending/uncertain results must not be reported as success. No automatic retry or cleanup on uncertainty; external live test is a distinct gate.

## Public Add protocol acceptance harness

A separate manual-only [Seedhost Add proof](../.github/workflows/seedhost-public-add-smoke.yml) now tests exact-hash-confirmed `load.raw_verbose` and records readback status. Before a real manual run, this is **offline-tested scaffolding only**, not proof of actual Seedhost Add, binary XML-RPC interoperability, physical file bytes, or an Android Add flow. `already_present_no_write`, `acknowledged_pending_readback` and `outcome_uncertain` are not successful new Adds.
