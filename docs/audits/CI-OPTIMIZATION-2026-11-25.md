# CI optimization evidence, 2026-11-25

This is a historical measurement record, not a second work queue or a product acceptance claim. Active instructions: [CI policy](../CI-POLICY.md), [work queue](../WORK-QUEUE.md), [CI engineering standard](../CI-ENGINEERING-STANDARD.md), issue [#10](https://github.com/gamesbyian/Chunks/issues/10).

## Scope and measurement method

The fixed recent merged-main cohort was #2088, #2087, #2086, #2075, #2085, #2084, #2083, #2082, #2080, #2081, #2079, #2077, #2076, #2074, #2073, #2071, #2070, #2069, #2067, #2068. PR associations were inferred from same-repository branch and PR lifetime because the REST run associations were empty. All 20 selected PRs and 166 PR-event workflow runs were processed. GitHub job start/end intervals, including earlier attempts, measure allocated runner time, not billed minutes or total queue time.

The initial 1071.867 runner-minute sum double counted eight carried-forward successful jobs copied into retries under new IDs. [#2092](https://github.com/gamesbyian/Chunks/pull/2092), merged as `96f7d1ac`, fixes that accounting using exact positive-duration name/runner/time identity, preserves distinct jobs, and discloses excluded minutes. Corrected audit [45564052275](https://github.com/gamesbyian/Chunks/actions/runs/45564052275): 1020.917 runner-minutes, 50.949 duplicate minutes excluded, 92.133 cancelled-run minutes (9.02%), no unavailable PRs or unobserved attempt records.

| Workflow family | Runner-minutes |
|---|---:|
| Native Add | 260.584 |
| Universal Add | 239.433 |
| Foundation | 147.983 |
| R8 minified | 111.200 |
| Real-client interop | 69.150 |
| Combined | 67.166 |
| Remote Remove | 32.766 |
| Magnet lifecycle | 34.383 |
| API29 Downloads | 32.650 |
| Resume-death | 17.500 |
| Documentation validation | 8.102 |

Native and Universal Add together were 49.0% of observed allocation. Cancellation was concentrated in #2080 and #2079; keep draft batching and exact-head ready qualification. Do not prune path triggers from these aggregates.

## Build sharing qualification

- #2089 [45562913789](https://github.com/gamesbyian/Chunks/actions/runs/45562913789): same APK pair passed Combined (260s) and Remove (177s); common build 308s. Independent baselines [45562913808](https://github.com/gamesbyian/Chunks/actions/runs/45562913808) and [45562913820](https://github.com/gamesbyian/Chunks/actions/runs/45562913820) passed at 396s and 367s. A new standalone producer saved only 18 aggregate runner-seconds (745 versus 763), while reusing an already-required producer would save 326 incremental runner-seconds on the two consumers (437 versus 763). Critical path may lengthen with serialization.
- #2091 [45563820394](https://github.com/gamesbyian/Chunks/actions/runs/45563820394): one producer, two native shards and three protocol lanes plus both complete verifiers passed; 2454 runner-seconds, 862s job activity window. Producer 251s; selection 8s.
- #2094 merged `d76e99ca`. [45565403407](https://github.com/gamesbyian/Chunks/actions/runs/45565403407), reviewed head `abc7d392`, synthetic merge `ae6fdd96`: seven installed execution jobs and both aggregate verifiers passed from one APK pair. Native verifier 488 reported, 435 executed, 53 explicit exclusions, zero failed, 25 real PNGs. Combined and Remove independently verified identical installed app digest. 2959 runner-seconds, 793s job activity window. No dedicated producer for Combined/Remove-only work; direct independent build path retained. Manual cross-run artifact contract retained.
- #2095 extends to API29 Downloads and two resume-death device lanes; [45567238922](https://github.com/gamesbyian/Chunks/actions/runs/45567238922) selected all six families. Offline tests passed; installed qualification is separate and must be recorded before integration.

### Observed final-head co-trigger opportunity

Seven of the 20 PRs had successful final-head Native Add plus one or more compatible installed families. Counting each unique successful historical compile phase only once, 19 additional builds occupied 3762 seconds (62.7 runner-minutes) beyond the existing Native Add producer: #2083 461s (Combined + Remove), #2080 211s (Universal), #2079 180s (Universal), #2077 286s (Universal), #2074 937s (five families), #2070 860s (five families), #2067 827s (four families). This is measured historical duplicated compile-phase time, not net savings or an all-attempt counterfactual; exclude manual campaigns, distinct variants and superseded heads. Artifact transfer, selector overhead and producer timing change the net result.

### Historical six-family comparisons (not same-source controlled benchmarks)

The earlier successful exact-head campaigns below had all six families independently scheduled, and all listed jobs were first attempt. Sum job allocation across these six families only; the activity window spans first job start to last completion and excludes initial queue. Different source and runner conditions prevent a precise causal savings claim.

| Historical PR | Aggregate runner-seconds | Activity window seconds | Six compilation phase seconds |
|---|---:|---:|---:|
| #2074 | 4540 | 844 | 1156 |
| #2070 | 4490 | 876 | 1049 |

Run IDs in family order (Native, Universal, Combined, Remove, API29, Resume): #2074: 45514678277, 45514678272, 45514678418, 45514678185, 45514678416, 45514678236. #2070: 45513219642, 45513219752, 45513219867, 45513219779, 45513220251, 45513219624.

## AVD decision and residuals

[Snapshot experiment](../CI-AVD-SNAPSHOT-PILOT-2026-11-03.md): run 45562483554, first/second cold 80.995/46.846s, generation 46.654s, attempted warm 28.601/35.733s. Both restore attempts explicitly failed with hardware unable to load default_boot and fell back. AVD 3271 MiB, deleted locally, no repository cache written. Read-only [inventory 45567406861](https://github.com/gamesbyian/Chunks/actions/runs/45567406861): 252 caches occupying 9,595,954,150 bytes, zero pilot-owned keys. No deletion necessary. #2096 retires persistent AVD cache route; retain cold baseline with local cleanup. Do not create a persistent cache until a changed configuration actually restores successfully and net time/storage economics are proven.

Other measured candidates: the two Universal Add remote daemon preparation steps took 26–38s each in #2091/#2094, versus 401–537s for emulator journey invocations; avoid destabilizing real fixtures for the limited potential savings. Foundation combines unit tests and compile (551–638s sampled); APK packaging completed about 155–218s into those runs, then the JVM suite itself took roughly six more minutes. Combining it with the installed producer would serialize consumers behind that test phase; its unit-test compilation cannot be replaced by a debug APK. Keep the previously qualified two-fork configuration (#883); any further worker experiment must independently measure complete suite parity and net benefit. R8 shrinker build (327–479s sampled) is a distinct variant and remains independent. Magnet preview has a distinct package/version suffix. No speculative cache writing, timeout increase, broad path pruning, protected-host changes or visual atlas work.

## Acceptance boundaries

Route selectors preserve the old per-lane patterns, match complete PR file metadata and select all lanes on incomplete/changed metadata. Same-run artifacts are tied to source SHA, tree and strict two-APK checksums. Preserve independent real installed and negative witnesses, attempt-aware artifacts and exact gate identities. Cheap YAML parsing and independent offline failure groups reduce diagnostic round trips. No assertion removed to obtain a green workflow. A skipped redundant producer is expected but is not accepted test evidence. No physical or protected live acceptance is claimed. No independent human/agent review was available during this CI-only session.
