# Chunks forensic engineering audit, pass 2 — 2026-11-25

**Status:** Independent, evidence-based audit on a **full checkout** (history to 2026-10-26). Advisory evidence only: not a normative specification, not a second backlog, not a release certificate. It completes §9 of the [2026-11-23 audit](../archive/FORENSIC-ENGINEERING-AUDIT-2026-11-23.md) (which had no checkout) and revalidates that audit's findings against live source. No production code, tests, workflows or specs were modified. No owner PC or Desktop Commander was used.

**Audited commit:** `c9b2d7f010c74f832178dbfcd48ab787ac1182af` (main, 2026-11-25 00:33 -06:00, merge of #2088). History fetched to depth 1,500 (2,540 commits reachable, 2,312 non-merge).

## 0. How to use this document

Findings are `AUD-nn`. Each carries severity/confidence/priority, exact evidence, consequence, remediation and verification. FE-nn refers to the 2026-11-23 register; section 5 states what happened to each one I could revalidate. Anything labelled **Measured** was computed from the checkout by the scripts described in §2. Anything labelled **Suspected** was not demonstrated. Read §2 "Limitations" before relying on a number.

## 1. Executive assessment

**Chunks is healthier at the domain level than at the structural and process levels, and the fastest-growing cost is not code; it is the status paperwork and CI surface around the code.** Continue on the current foundation. No rewrite is justified.

Strengths that are real and should be protected:
- The remote-command safety model (exact environment+infohash identity, typed outcomes, requested/acknowledged/verified/uncertain separation) is enforced in code, not only documented. `rpc/ExactProtocolRead.kt` rejects wrong/ambiguous exact-read replies and duplicate JSON keys before use; `rpc/` has ~89 `CancellationException` references (explicit rethrows). The two highest-severity defects from the previous audit (FE-01, FE-03) are fixed on main.
- Security baseline: `network_security_config.xml` denies cleartext except literal loopback; credentials are Android-Keystore AES-GCM encrypted; `allowBackup=false`; all providers and the service are `exported=false`; only `MainActivity` and one alias are exported and their intake is review-gated. The public-source HTTPS fetch uses a pinned-DNS OkHttp transport (`PinnedTorrentLinkTransport`), wired in both debug and release source sets (FE-19 resolved).
- No framework sprawl: no DI framework, Room, Navigation library or DataStore; one Gradle module; 11 `implementation` declarations (Compose, coroutines, OkHttp, libtorrent4j ×3).

Principal weaknesses, in order of demonstrated cost:
1. **Status documents are the most-edited files in the repository** (AUD-01). This is measurable and ongoing cost.
2. **One 2,866-line composable owns the application** (AUD-02), and two more screens exceed 1,000 lines. Concentration, not logic, is the problem.
3. **CI has 102 workflows (10,100 lines)**, about 80 of them one-shot, probe, font, SVG or experiment workflows (AUD-03). Cost is unmeasured here; maintenance surface is not.
4. Semantic duplication in credential storage, blocking bridges, and ~1,100 lines of production code reachable only from tests (AUD-04 to AUD-06).

Is continued feature development likely to compound problems? **Yes for the UI host and for documentation; much less so for the engine/rpc layer.** Each new feature that lands in `MainActivity.kt` or `CombinedTransfersScreen.kt` pays the hot-file tax (MainActivity: 233 of 2,540 commits). Each new feature or fix also pays a documentation tax (below).

| Dimension | Assessment (reasoned, no score) |
|---|---|
| Architecture | Sound domain seams (`rpc/`, `engine/`, `model/`, `storage/`); UI host is a monolith. |
| Maintainability | Hotspot-concentrated. 45 functions >100 lines, 13 >200, 7 >300 (incl. composables). |
| Correctness | No new confirmed defect found. Prior top defects fixed. Remaining risk is in lifecycle ownership, not wire parsing. |
| Reliability | Deliberate uncertainty handling. Blocking bridges (27 `runBlocking` calls) are a structural, not demonstrated, risk. |
| Security | Good baseline; no transport or storage flaw found. Not a full security review. |
| Performance | Nothing measured. Polling cadences are explicit (2 s service tick, 5 s/60 s Combined). |
| Testing | Large and substantive; ratio ~1:1 JVM:prod LOC plus 30 k instrumented. Flake risk signals exist (AUD-08). |
| Extensibility | Adding a protocol client is mostly additive in `rpc/`; the UI host still needs edits per feature. |
| Dev efficiency | Weakest dimension: documentation and CI maintenance load (AUD-01, AUD-03, AUD-09). |

## 2. Methodology, evidence and limitations

**Executed:** full recursive file inventory; per-package LOC; a brace-matching function-size/branch-token parser over all 2,784 production functions (`/home/claude/audit-tools/metrics.py`, not committed; heuristic, nested local functions mis-scope, verified by hand on the top hits); a lexical declaration-reachability scan over 1,060 production types/functions across `main`, `test`, `androidTest`, `sharedTest`, `debug`, `release` (`dead.py`); a suspend-function catch/runCatching scan with manual triage (`cancel.py`); `grep` counts for coroutine, storage, dispatcher and security APIs; workflow inventory; `git log`/`--name-only` churn over 2,540 commits; reading of `AGENTS.md`, `build.gradle.kts`, `AndroidManifest.xml`, `network_security_config.xml`, and the key files cited below.

**Not executed:** Gradle build, unit tests, lint, emulator, live engines or Seedhost (package/network access is not available in this audit session; and none of those were required to substantiate the findings below). Therefore **no runtime, memory, battery, startup, APK-size, flake-rate or CI-time measurement exists in this report**. Anything about performance is Suspected.

**Known scanner limits:** the dead-code scan is name-based; it first flagged `PinnedTorrentLinkTransport` as unreferenced because it is wired from the `src/debug` and `src/release` source sets. I corrected that by checking all source sets; the final candidate list was re-verified in all of them. A test-assertion-density heuristic I tried matched only 7 of 3,633 tests and was discarded; **no claim about assertion-free tests is made**. Cancellation triage: 34 candidates scanned; I read the 8 highest-risk in context and all had the cancellation rethrow in an earlier catch or were non-suspend; the rest are `JSONObject` parse guards by line text. This is partial triage.

### Inventory (Measured)

| Surface | Files | Lines |
|---|---:|---:|
| Production Kotlin (`app/src/main`) | 446 | 78,304 |
| JVM tests | 602 | 78,177 (3,130 `@Test`) |
| Instrumented tests | 186 | 30,321 (503 `@Test`) |
| Python tools / Python tests | 115 / 75 | 17,084 / 9,381 |
| Workflows | 102 | 10,100 |
| Markdown docs (docs + root) | 315 | ~24,200 lines; 3.75 MB root docs |
| SVG assets | 947 | not counted in APK |
| `.git` | | 25 MB (shallow); working tree 80 MB |

Production LOC by package: `rpc` 9,870 · `media` 6,971 · `model` 6,928 · `snapshots` 6,130 · `engine` 5,517 · storage 1,214 · seeding 1,132 · create 1,111 · privacy 966 · **root package 37,943 (48.5%)**.

### Audit coverage record

| Area | Depth |
|---|---|
| Inventory, size, hot files, churn, workflows count, manifest, network config, build file | Thorough |
| rpc exact-read, cancellation handling, credential stores, seeding/finish-in-order bridging, pinned transport wiring | Thorough for stated questions |
| Dead/test-only production code | Thorough lexically; no runtime/reflection check beyond string-literal grep and manifest |
| `MainActivity`, `CombinedTransfersScreen`, `LocalEngineScreen` | Structure and size measured; not read end-to-end |
| `LocalTorrentEngine` (2,691 lines), `RTorrentRemote` (2,613), snapshots, storage/SAF, media, privacy | Partially examined or not examined (names/sizes only) |
| Compose recomposition, startup, memory, JNI | Not examined |
| Tests: quality of individual assertions, flake rates, mutation sensitivity | Not examined beyond counts and sleep/assume usage |
| Workflow run history and timings | Not examined (no run data this pass); prior audit's step timings are cited as theirs |
| Git blame/co-change beyond file touch counts | Partial |

## 3. Actual architecture (as implemented)

```
Android intents (VIEW magnet / .torrent, SEND) ──► MainActivity.kt  (5,014 lines)
                                                    └─ ChunksApp()  composable, lines 320–3185
                                                       owns: routing/tabs, connection+credential sessions, add-draft,
                                                       inventory pumps, policy ticks, Options, details
   ┌──────────────────────────────┬─────────────────────────────┬──────────────────────────────┐
   ▼                              ▼                             ▼                              ▼
 Add flow                    Transfers views              Details/operations              Settings/Options
 VerifiedAddScreen (1,589)   CombinedTransfersScreen      TransferLifecycleControl,       SettingsRegistry (test-only)
 ProtocolAddScreen (640)       (2,739)                    RemoteHashRecheckControl, …     SeedingRulesScreen …
 UniversalAddCoordinator     LocalEngineScreen (4,451)
   (model/, 463) ← real shared transaction, one-shot, typed outcome
   │                              │
   ▼                              ▼
 model/  (domain: identity, catalog, outcomes)  ── engine-independent, well factored
   │
   ├── engine/  LocalTorrentEngine (libtorrent4j JNI) + resume DB + Finish-in-Order + foreign-partial recovery
   │            LocalEngineService (foreground, 2 s tick)
   ├── rpc/     TorrentClient interface → RTorrentRemote/Adapter, TransmissionClient, QBittorrentClient, DelugeClient
   │            ClientOperations (typed outcomes), ExactProtocolRead, RemoteWriteOwnerFence, XmlRpcCodec
   ├── storage/, snapshots/ (SAF/MediaStore, export/restore), media/ (artwork + online lookup), privacy/, seeding/
   └── credentials: RememberedRemoteLogin + RememberedRemoteProtocolProfiles + RememberedRemoteProfileRoster  (3 parallel Keystore stores)
```

Dependency direction is mostly healthy: `model/`, `rpc/`, `engine/` do not depend on the root UI package for their core contracts. The root package is the problem: it contains 48.5% of production lines and holds routing, session state, I/O dispatch, scheduling and presentation together. Single Gradle module, so boundaries are by convention only; there is nothing preventing UI-to-engine reach-through.

Authoritative components: exact identity = `EnvironmentId` + verified infohash (`model/`); one shared Add transaction = `UniversalAddCoordinator`/`UniversalAddSubmission`; remote command semantics = `ClientOperations`. Competing owners remain at the UI host layer (FE-07).

## 4. Findings register

Severity: **S1** can mislead/lose user intent; **S2** material cost or latent risk; **S3** modest. Priority: P0 now, P1 before further feature work, P2 alongside, P3 optional.

### AUD-01 — Status/evidence documents dominate repository churn and agent onboarding cost
**Class:** demonstrated inefficiency (Measured). **Severity S2, confidence high, priority P1.**
- Evidence: `docs/QUALITY-LEDGER.md` is 1,058,524 bytes and was modified in **422 commits (16.6% of all 2,540)**; `docs/WORK-QUEUE.md` in **463 (18.2%)**; `docs/SPEC-COVERAGE.md` 207; `docs/NEXT-PUSH-HANDOFF-2026-11-08.md` 100; `AGENTS.md` 85 (28,177 bytes, 3,229 words, six stacked dated "OWNER PRIORITY OVERRIDE"/authority blocks). The two most-edited files in the repository are status trackers; the most-edited source file (`MainActivity.kt`) has 228 touches. **646 of 2,312 non-merge commits (27.9%) touch only markdown/docs.** `docs/` has 122 top-level markdown files and 9 `*HISTORY*` snapshots; `AGENTS.md` requires reading a chain of >10 documents at every session start.
- Root cause: every acceptance claim is recorded in several overlapping places (ledger, queue, coverage, handoff, AGENTS.md, issue, PR). Documentation was added to satisfy gates ("record exact-head …"), not retired.
- Consequence: agents spend context and CI/merge cycles on bookkeeping; conflicts concentrate on a few files; stale guidance ("Former 10–11 November stacked PR guidance is historical") accumulates inside the instruction file itself.
- Remediation: (1) make `QUALITY-LEDGER.md` append-only per dated archive file and stop editing it in feature PRs (evidence lives in the PR/run and a one-line pointer); (2) fold AGENTS.md to ≤ ~4 KB of binding rules + pointer list, move dated override history to `AGENTS-HISTORY-*`; (3) one status owner (`WORK-QUEUE`), remove status duplication from `SPEC-COVERAGE`/handoff; (4) have `tools/check_docs.py` fail when a feature PR edits more than N status files. Benefit: fewer merge conflicts, smaller session preamble, less drift. Effort 1–2 days; risk low (docs only; keep the existing docs checker green). Dependencies: none. Verification: ledger/queue touch rate per week, AGENTS.md size, `check_docs.py` 0 errors.
- Do not weaken the owner's evidence requirements; relocate where evidence is recorded.

### AUD-02 — A single 2,866-line composable owns app-wide state, I/O and orchestration
**Class:** structural weakness (Measured). **Severity S2, confidence high, priority P1 (before large UI expansion).**
- Evidence: `MainActivity.kt:320-3185` `ChunksApp` = 2,866 lines, brace depth 14, **604 branch tokens**. `CombinedTransfersScreen.kt` main composable ≈1,000 lines (`:1100-2147`; local `stillCurrent` at 1151 is nested inside it). `LocalEngineScreen.kt` 4,451 lines. `SnapshotRestorePreviewPanel` 1,069 lines. Counts in `main`: 193 `LaunchedEffect`, 86 `rememberCoroutineScope`, 226 `Dispatchers.IO`, 0 `Dispatchers.Main` use, 5 files with ViewModel, 19 `StateFlow`. State is lifted by callback, not by an owner type. Hot-file co-change: `MainActivity.kt` in 233 commits, `LocalEngineScreen.kt` 169, `CombinedTransfersScreen.kt` 125.
- Root cause: screen-first evolution (FE-06). The shared domain contracts exist; their lifetimes are owned by composition.
- Consequence: every feature edits the same lines; lifecycle bugs (rotation, process death, forget/reconnect) are structurally possible anywhere in 2,866 lines; recomposition/effect behavior cannot be reasoned about locally. This is also why AGENTS.md needs a "Hot-surface discipline" paragraph.
- Remediation: extract by ownership, in order, each a separate qualified PR: (a) connection/session registry; (b) add-intake/draft owner; (c) inventory observation; (d) policy scheduler; (e) pure render state. Use `ViewModel` + `StateFlow` already present; **no DI framework, no module split yet**. Benefit: smaller blast radius, parallel agent work. Effort 5–10 days staged; regression risk medium-high (retained state, process death). Verify: unchanged installed five-client journeys, rotation/process-death negative controls; `ChunksApp` < 800 lines; touches-per-PR on `MainActivity.kt` falling.
- Leave alone: the already-extracted pure helpers and the `UniversalAddCoordinator`.

### AUD-03 — CI surface is large and mostly bespoke one-shot/probe/asset workflows
**Class:** structural weakness; cost **Suspected** (no run data). **Severity S2, confidence medium, priority P2.**
- Evidence (Measured counts): 102 workflows, 10,100 lines. 100 have `workflow_dispatch`, 73 `pull_request`, 21 `push`, 30 start an emulator, 56 invoke Gradle, 100 of 102 declare `concurrency`. Groupings by name: **28 `seedhost-*`**, **28** font/SVG/reference-asset workflows (`chunks-*`, `reference-*`, `svg-*`, `pages-*`), **18** native experiment/probe/repro/pilot (`native-*`, `*-experiment`, `*-probe`, `avd-snapshot-pilot`, `*-repro`). 20 workflows combine `pull_request` with an emulator. `experiments/native-owned-writer` (240 KB C++) is not referenced by Gradle and is exercised only by two workflows.
- Consequence: By name, 74 of 102 workflows are one-shot/probe/asset/experiment workflows rather than the standard acceptance path (to be confirmed per workflow); each is a maintenance surface and a source of "which one counts" ambiguity. Path-trigger fan-out is already being managed (only 3 emulator workflows list `MainActivity.kt` as a path trigger; AGENTS.md CI branch-sync rule is sound).
- Remediation: classify every workflow KEEP-gate / KEEP-manual-evidence / ARCHIVE using the last 60 days of run history (not available to me); move retired probes to `docs/archive/workflows/` with their last passing run id; consolidate the 28 Seedhost one-shots into a parameterized `seedhost-run` dispatch with a scenario input where their steps overlap. Benefit: smaller surface, faster triage. Effort 2–4 days; risk low-medium (manual evidence workflows may be cited by acceptance receipts; check `QUALITY-LEDGER` references before removing). Do not remove a workflow referenced by a current acceptance receipt.
- Explicitly **not** a recommendation to remove installed-journey verification or to reduce Android matrix coverage.

### AUD-04 — ~1,100 lines of production code reachable only from tests, plus four unreferenced composables
**Class:** confirmed (lexical) cruft. **Severity S3, confidence medium-high, priority P2.**
- Test-only production types (references in `main` = declaration only; zero in `debug`/`release`/`sharedTest`; no string-literal reflection): `engine/ImmutableNativeResumeV2.kt` (328 lines, first commit "research(native): …"), `routing/SmartRoutingEvaluator.kt` (162), `model/MediaEnrichmentDispatchPlanner.kt` (108), `discovery/DiscoveryGate.kt` (89), `model/StorageCleanupPreview.kt` (89), `SettingsRegistry.kt` (88), `storage/SafReadOnlyIdentityInspector.kt` (72), `storage/RelocationPlanner.kt` (65), `storage/IndependentReadbackWitness.kt` (41), `snapshots/SnapshotDocumentWriterV1.kt` (28) ≈ **1,070 lines** (1.4% of production). Several are documented as intentional foundations (`SmartRoutingEvaluator`, `SettingsRegistry`: ARCHITECTURE.md, WORK-QUEUE) and are therefore **Optional/Deferred decisions, not accidental**. The "research(...)" ones (`ImmutableNativeResumeV2`, `SafReadOnlyIdentityInspector`, `IndependentReadbackWitness`) have no docs reference.
- Unreferenced anywhere: `LocalZipExtractionRunning` (in `LocalZipExtractionPanel.kt`), `TransferMediaArtworkButton`, `MediaLookupConsentDialog` (`media/ui/TransferMediaRoutes.kt`), `TransferAutomaticMetadataToggle` (52-line file). The last two are mentioned in docs.
- Remediation: owner decision per item: wire, move to a `research/` source set outside `main`, or delete with their tests. Verify with a clean `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `qaMinified` smoke, and a grep over manifest/proguard/docs. Realistic removal ceiling from this finding is small (~1.1–1.3 k lines); this is not where the project's weight is.

### AUD-05 — Three parallel Keystore AES-GCM credential stores
**Class:** structural weakness (confirmed duplication). **Severity S2, confidence high, priority P2.**
- Evidence: `RememberedRemoteLogin.kt` (220 lines), `RememberedRemoteProtocolProfiles.kt` (237), `RememberedRemoteProfileRoster.kt` (267) each build their own `KeyGenParameterSpec`, `KeyStore.getInstance("AndroidKeyStore")`, `Cipher "AES/GCM/NoPadding"`, 12-byte IV packing and `GCMParameterSpec(128, …)`; separate aliases (e.g. `…other-remote-profile-roster-v1`). 724 lines, three erasure paths.
- Consequence: any fix (key invalidation, corruption recovery, backup exclusion, rotation) must be made three times; forget/reauth semantics can diverge (FE-13).
- Remediation: extract one `KeystoreSealedBlob` (seal/open/erase by alias) and keep **aliases and on-disk formats byte-compatible** so no migration or re-login is needed; leave profile semantics in each store. Effort 1–2 days; risk medium (credentials; no data migration allowed). Verify: golden-ciphertext tests per store (existing blobs open), failed-decrypt, revoked key, concurrent forget/save, process death.

### AUD-06 — Blocking bridges and polling loops live in UI-adjacent code
**Class:** structural weakness (Measured counts), cost **Suspected**. **Severity S2, confidence medium, priority P2.**
- Evidence: 27 `runBlocking` call lines, 25 in `seeding/RemoteSeeding.kt` (adapts suspend remote operations to the synchronous `SeedingController` interface) and 2 in `FinishInOrderController.kt:211,232` (comment: "service poll lane is a plain worker thread"). 12 `Thread.sleep` in production; 56 `while(true|isActive)` loops; `RemoteSeeding.kt:112` swallows with `catch (_: Exception)` inside `runBlocking`. Cadences: `LocalEngineService.TICK_MS=2000`, Combined `TICK_MS=5000`/`HEALTHY_REFRESH_MS=60000`, Finish-in-Order `MIN_INTERVAL_MS=10000`.
- Consequence: blocked IO threads under slow servers; cancellation passes through `runBlocking` only via the owner-fence mechanism, which is non-obvious. Cost not measured.
- Remediation: make `SeedingController` ports `suspend` where the caller is already a coroutine; gate periodic work when no policy is enabled (FE-10); measure calls/minute before/after. Effort 2–4 days; risk medium. Do not rewrite the native synchronous port.

### AUD-07 — Cancellation safety relies on manual convention, not a mechanical check
**Class:** suspected weakness (no defect confirmed). **Severity S3, confidence medium, priority P2.**
- Evidence: 525 broad `catch (…: Exception|Throwable)`, 187 `runCatching` in `main`; 72 files mention `CancellationException`. In `rpc/` (~89 references) the pattern is consistently "catch Cancellation → rethrow" before broad catches; my scan plus manual reading of the highest-risk candidates found no swallowed cancellation. `media/TransferMediaController.kt:626` wraps non-suspend `service.lookup` in `runCatching` (safe).
- Remediation: add a small offline repo test (similar to `tools/check_local_terminology.py`) that fails when a `suspend fun` contains a broad catch/`runCatching` without a nearby rethrow, with an allowlist of reviewed sites. Effort 0.5 day; risk none. Benefit: converts reviewer vigilance into a gate.

### AUD-08 — Test suite is large and meaningful, but expensive-by-wall-clock patterns exist
**Class:** suspected (no flake data). **Severity S3, confidence low-medium, priority P2.**
- Evidence (Measured): 3,130 JVM and 503 instrumented tests; test code ≈ production code (78.2 k vs 78.3 k lines) plus 30.3 k instrumented; 166 `Thread.sleep` in JVM tests and 78 `sleep`/`delay` in `androidTest`; 202 `assume*` calls (conditional skips can produce green runs that did not execute the assertion; `skipped ≠ passed` per AGENTS.md); 0 `@Ignore`; no Mockito/MockK (14 hand-written fakes: realistic fixtures, a strength). Largest test file `LocalTorrentEngineTest` 2,288 lines.
- Not examined: assertion strength, mutation sensitivity, flake history.
- Remediation: (1) report skipped-by-assume counts per CI run; (2) replace fixed sleeps with condition waits in the newest slow suites first; (3) keep the real-engine and installed tiers. No test deletion without requirement mapping (AGENTS.md pre-execution authority).

### AUD-09 — Release build is not shrunk; shrinking is validated only on a debug-signed twin
**Class:** structural/unresolved migration (Measured from build file). **Severity S3, confidence high, priority P3.**
- Evidence: `app/build.gradle.kts` `release { isMinifyEnabled = false }` with comment that shrinking waits for the `qaMinified` smoke; `qaMinified` exists (R8 full, debug-signed) and `minified-smoke.yml` exercises it. libtorrent4j ships arm64 + x86_64 native libraries in all builds. APK size not measured.
- Remediation: when the smoke is green at the release candidate, enable minify+shrink on `release` using the same rule files; compare APK size before/after. Effort 0.5 day; risk medium (JNI), covered by the existing smoke. Defer until functional completion is accepted.

### Revalidation of FE-nn (state at `c9b2d7f`)
| FE | Status at this commit |
|---|---|
| FE-01 exact read vs absence | **Fixed** (`rpc/ExactProtocolRead.kt`: only empty or one exact-hash row; else `IOException`). |
| FE-03 cancellation translated to fallback | **Largely fixed** in `rpc/` (≈89 references, mostly explicit rethrows); not re-proven by test in this audit. |
| FE-19 pinned public HTTPS transport | **Fixed and wired** (release factory `PinnedTorrentLinkTransport()`; debug factory defaults to the same with an in-memory test seam). |
| FE-17 release shrinking | Open as AUD-09. |
| FE-13 parallel credential stores | Confirmed, AUD-05. |
| FE-06/FE-07 root hosts and Add owners | Confirmed and quantified, AUD-02. |
| FE-10 policy scheduler/blocking | Confirmed structurally, AUD-06. |
| FE-02, FE-04, FE-05, FE-08, FE-09, FE-11, FE-12, FE-16, FE-18, FE-20 | **Not revalidated** in this pass; treat the 11-23 text and the live queue (R0) as authoritative. |

## 5. AI-assisted development evidence (concrete only)

- **Parallel/superseded implementations:** three Keystore stores (AUD-05); research prototypes living in `main` and referenced only by tests (AUD-04); `native-add-shared-build-experiment`, `native-owned-writer-experiment`, `avd-snapshot-pilot` workflows beside the production gates (AUD-03).
- **Process artifacts as primary output:** 27.9% docs-only commits; a 1 MB append-style ledger; an instruction file with six stacked overrides (AUD-01).
- **Not demonstrated:** no evidence of tautological tests, wrapper-per-fix layering or defensive code hiding defects was found in the portions examined; do not infer them. Untested: assertion quality across the 3,633 tests.
- **What is notably good for a multi-agent history:** the typed outcome model survived hundreds of agent edits intact; the pinned-DNS transport was done with an established library rather than bespoke HTTP; fixes cite exact-head runs.

## 6. Simplification and deletion inventory

| Item | Size | Confidence | Verify before removal |
|---|---:|---|---|
| 4 unreferenced composables | ~100 lines | High | Compile; docs references (2 are cited) |
| `ImmutableNativeResumeV2`, `SafReadOnlyIdentityInspector`, `IndependentReadbackWitness` + tests | ~440 lines | Medium (owner may want as foundation) | Owner decision; `QUALITY-LEDGER` receipts |
| `SmartRoutingEvaluator`, `SettingsRegistry`, `DiscoveryGate`, `MediaEnrichmentDispatchPlanner`, `StorageCleanupPreview`, `RelocationPlanner`, `SnapshotDocumentWriterV1` | ~630 lines | Low (documented foundations) | Do **not** delete without owner roadmap decision |
| Credential store duplication | ~300 lines saved | High | Byte-compat tests |
| Retired probe/experiment workflows | up to ~40 files | Needs run data | Receipt references, last-60-day runs |
| `experiments/native-owned-writer` | 240 KB | Medium | Confirm abandoned vs future native owner path |
| Status doc consolidation | 1+ MB of ledger prose out of active path | High | `check_docs.py` link integrity |
| SVG library (947 files, design) | not in APK | n/a | Keep: owner reference assets |

Total realistic removable production code from this pass: **~0.5–1.3 k lines (≤1.7%)**. Removable maintenance load is much larger in docs and workflows than in code. Not measured: unused resources, dependency weight.

## 7. Prioritized roadmap

1. **Immediate correctness/security:** none confirmed from this pass. Confirm FE-02/04/05 (#2026 residual) against R0 as already tracked.
2. **High-return, low-risk:** AUD-01 doc and AGENTS.md consolidation; AUD-07 cancellation check; AUD-04 unreferenced composables.
3. **Accelerates all later work:** AUD-02 first extractions (session registry, intake owner); AUD-03 workflow classification.
4. **Targeted consolidation:** AUD-05 credential blob; AUD-06 suspend ports and idle-policy gating.
5. **Larger/migration:** completing AUD-02 (inventory observer, policy scheduler); AUD-09 release shrinking at RC.
6. **Defer:** module split, DI framework, visual atlas, restructuring `LocalTorrentEngine`/`RTorrentRemote` without measurements.
7. **Leave alone:** typed outcome/exact-identity model, `UniversalAddCoordinator`, `rpc/ExactProtocolRead`, native resume/immutable-journal internals, pinned transport, Keystore usage (consolidate code, not formats), real-engine and installed tiers, Compose-free pure helpers.

**Parallelizable sets:** {AUD-01, AUD-07, AUD-04 composables, AUD-03 classification} are independent and touch disjoint files. AUD-05 and AUD-06 are independent of each other. AUD-02 must serialize on `MainActivity.kt`; start after AUD-01 reduces conflict, and do not run concurrently with active UI-heavy PRs. **Before further feature work:** AUD-01 (cheap) and the first AUD-02 extraction. **Safe alongside features:** AUD-03/04/05/07/09.

## 8. Counterfactual architecture

From scratch today: single app module with explicit package boundaries (`core-model`, `clients/<protocol>`, `engine-local`, `storage`, `ui`) possibly as Gradle modules; a `ClientSessionRepository` owning credentials and connection lifetimes; `ViewModel`-per-feature with `StateFlow`; Compose screens as pure functions of state; one WorkManager/foreground-service scheduler for periodic policies; a shared `TorrentClient` capability model; a parameterized adapter contract test suite.

Compared with now: the `model`/`rpc`/`engine` layering and typed outcomes already match that design. Justified migration: extracting session/intake/inventory/policy owners from `ChunksApp` (AUD-02) and one credential repository (AUD-05). Preference only: Gradle module split, DI framework, Room/DataStore for stores already working. Not worth changing: native engine internals, XML-RPC codec, snapshot formats. No rewrite recommended; incremental extraction is clearly viable because the domain seams already exist.

## 9. Final synthesis

- **Most unnecessary complexity:** the documentation/status apparatus and the workflow zoo, then the root UI host.
- **Removable without losing function:** ~0.5–1.3 k production lines, ~300 duplicated credential lines, a large share of status prose and likely dozens of retired probe workflows.
- **Costliest architectural decisions:** screen-owned lifetimes (AUD-02); status-as-code-review-gate (AUD-01).
- **Fragile/hard to reason about:** `ChunksApp`, `CombinedTransfersScreen`, `LocalEngineScreen`; blocking bridges in seeding/Finish-in-Order.
- **Wasted effort:** keeping the ledger/queue/coverage/handoff/AGENTS.md in sync (28% docs-only commits); onboarding through a 28 KB instruction file.
- **Greatest compounding benefit:** AUD-01 then the first AUD-02 extraction.
- **Better than it looks:** exact-read validation with duplicate-JSON-key rejection, consistent cancellation rethrow, pinned-DNS transport with a debug seam that still uses production code, Keystore-only credentials, strict network config, hand-written fakes instead of mocks.
- **Do not refactor for aesthetics:** the typed outcome/uncertainty model, per-protocol adapters (real protocol differences), native resume/journal code, SVG/design assets.
- **Essential vs avoidable complexity:** not measured quantitatively. Qualitatively, the engine/rpc/model code is mostly essential domain complexity; the 2,866-line host and the process artifacts are mostly accidental. I will not give a percentage without a method.
- **Fundamentally healthy enough to finish on current foundations?** Yes, provided the UI host extraction and documentation consolidation are done before the next large feature wave.

## 10. Reproduction

Scripts were run from `/home/claude/audit-tools` against a checkout at the SHA above (not committed; they are heuristic and described in §2). Key commands: `git log --format= --name-only | sort | uniq -c | sort -rn | head`; `grep -rn runBlocking app/src/main`; line-count of `ChunksApp` via `awk 'NR>=320 && /^}/ {print NR; exit}' MainActivity.kt` (→ 3185). A follow-up should re-run them after each remediation and record deltas.
