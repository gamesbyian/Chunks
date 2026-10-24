# Chunks evidence and release gate ledger

Updated 2026-10-24. This ledger alone records proven gates; GitHub issues track work and WORK-QUEUE.md tracks priorities. Exact CI SHA matters. Mock/fixtures cannot prove server or handset functionality.

| Gate | Evidence observed | Status |
|---|---|---|
| Project scaffold | merged [#11](https://github.com/gamesbyian/Chunks/pull/11), main 6dba63fed72c92c7f97128ef3fa08d278a37b730 | Established |
| First Android debug build and JVM tests | [run 40057523588](https://github.com/gamesbyian/Chunks/actions/runs/40057523588), PR SHA 201b53fc3d16482cf2aa1a08c9c01d9e0351174b, 42 Gradle tasks successful | Passed for that SHA |
| Read-only synthetic RPC gateway fixtures | merged [#12](https://github.com/gamesbyian/Chunks/pull/12), [run 40057876761](https://github.com/gamesbyian/Chunks/actions/runs/40057876761), PR SHA 5b5b75ed4abaa9f4e97ec4c7e4fa517130e8f681, Gradle test and debug assemble passed | Passed synthetic JVM fixtures only |
| Existing relocation planner tests | Included in Android/JVM build of #11; pure decision model only | Passed decision logic, NOT filesystem execution |
| Read-only native dashboard + new catalog/inspection fixtures | [merged #14](https://github.com/gamesbyian/Chunks/pull/14), [run 40060156362](https://github.com/gamesbyian/Chunks/actions/runs/40060156362), PR SHA `7a7aae0a811542d38b3b523640d05f9d24fccbd7`, debug artifact `chunks-debug-prealpha` | JVM tests + APK assembly passed; no server/device proof |
| Real rTorrent reachability/auth | No sanitized actual-server connection evidence | Not tested |
| Legacy/modern rTorrent compatibility | No dual-server observations, Digest/JSON-RPC/i8 or view coverage proof | Not tested |
| Complete large remote list and reconnect | No real fleet/performance test | Not tested |
| Remote Add and ordinary write controls | No implemented reviewed mutation API or real results | Not implemented |
| Actual live data relocation | No journal executor, disposable crash/volume test or seed recheck | Not implemented |
| Safe backed-file rename and library copy | No verified seeding/complete file publication case | Not implemented |
| Organization and rules | Simple local classifier fixture exists; no mature rules, review or provenance acceptance | Prototype only |
| Local BitTorrent | No local torrent network engine or seeding | Not implemented |
| Real handset Android | No screenshot, accessibility, lifecycle or on-device UI validation | Not observed |
| Signed/secure release | No signed release, user-ready credential store, permissions review or license decision | Blocked |

## Source and status rules

The current XML-RPC adapter is read-only and Basic-auth capable, now with optional transfer rates, multiple server views, file inspection and a synthetic fallback for unsupported metric methods. The Add screen is a local, non-submitting draft. The synthetic RPC tests cover authenticated fixture listing, capability absence/presence, redirect refusal and unsafe remote HTTP rejection. These are JVM tests, not real server verification, JSON-RPC compatibility or physical Android observations.

The build artifacts are **debug-only** and not production-qualified. No production server data was moved, renamed, deleted, copied or modified in these Chunks runs.

Future evidence must record exact source SHA, server/Android build as appropriate, sanitized environment topology, test action, verification result and remaining uncertainty. Test fixture pass means no more than the fixture asserts.
