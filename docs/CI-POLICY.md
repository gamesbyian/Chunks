# Lean CI and evidence economy

## Actual-transfer acceptance policy (2026-11-01)

[REAL-TRANSFER-ACCEPTANCE](REAL-TRANSFER-ACCEPTANCE.md) is the required evidence standard for ordinary torrent operations. Mocks/simulations are **secondary** to verified real .torrent and magnet behaviour with real rTorrent/libtorrent, peers, piece/byte growth, and the shipping Android entry path. Run cheap deterministic/adversarial checks for iteration, but **do not designate a remote/local feature user-accepted on mocked ACK, synthetic magnet, started/active flag or screenshot-only evidence**. For affected protocol/lifecycle PRs, qualify the smallest already available disposable actual-engine/peer acceptance at exact head; for UI path changes, add installed emulator proof. Always distinguish an unavailable real integration gate from a passing executed one. Do not trigger credentialed protected-host mutations automatically; use explicit scoped approved manual workflows. The queue owns which gaps to implement; preserve path-scoped light CI and no redundant fan-out.

**Installed intake journey:** the existing API29 shared-Downloads and API35 lifecycle jobs also run `tools/phone_intake_journey.sh` after their original checks. Both reuse the already booted emulator; no new workflow or runner is added. The harness starts a cold process while preserving package data and MediaStore ownership, requires an empty verified inventory before Add, and refuses physical devices before stopping the app. Data-clear/reinstall ownership is a separate scenario. Share and magnet each require a genuine phase-A self-kill plus one executed phase-B pass and matching download/restore/seeding digests. The full ordinary API35 suite skips these two opt-in phases, and still runs the existing intent/engine coverage. Script/fixture edits retain both runtime triggers; cheap docs validation runs the negative controls and selector tests. [Offline controls](../tools/test_phone_intake_journey.sh).

> **Native research run cancellation (2026-10-31):** The non-shipping owned JNI link matrix, writer experiment and offline overlay have workflow-level concurrency isolated by workflow and PR number. Only superseded **pull-request** revisions cancel; each manual investigation uses its unique run ID and remains independent. Checks for both ABIs and all existing safety probes remain unchanged. The policy saves runner work only when overlapping PR revisions occur; do not count hypothetical saved minutes as measured. See the machine-readable all-workflow inventory for residual ungrouped font/SVG lanes.


> **R8 real-case receipt (2026-11-02):** The existing [R8 minified smoke](../.github/workflows/minified-smoke.yml) still builds the qaMinified APK/test APK and runs the genuine Android API35 emulator journey; its previous inline JUnit assertion trusted self-declared `tests=1`/zero failures even if no `<testcase>` node existed. It now invokes [the source-bound JUnit digest](../tools/ci_failure_digest.py) and an [exactly-one executed passing-case gate](../tools/require_minified_smoke_digest.py), with offline adversarial tests before SDK provisioning. Report inconsistency, missing cases, duplicate cases or missing source/run provenance refuse green. In addition, the exact source SHA and GitHub run ID must match the current workflow environment, and the single passing case must be the intended fully qualified `MinifiedBuildSmokeInstrumentedTest.nativeEngineMovesExactBytesAndComposeRenders` method; another passing test or a stale report cannot stand in. The digest runs on prior failures without overwriting the first red cause and is retained alongside existing R8 mapping/results in the same artifact; no extra emulator or artifact upload job is added. A green run remains emulator-level evidence, not physical-device approval.

**JUnit declared-count integrity (2026-11-02):** The read-only `ci_failure_digest.py` now cross-checks optional JUnit `tests`, `failures`, `errors`, and `skipped` summary attributes against actual contained test cases (including aggregate `testsuites`). Missing declarations remain permitted, but malformed or contradictory totals mark the report invalid and refuse a supposedly green required-evidence gate. Existing executed-test, exact-source, first-failure, real-engine and device acceptance contracts remain unchanged; reports never publish exception bodies or protected data. Offline positive/negative controls cover consistent and forged summaries.

**Required JUnit positive evidence (2026-10-31):** The Android foundation and API35 native Add jobs now require the JUnit digest to show actual executed non-skipped tests, valid report XML and zero test failures on an otherwise green job. The check script is always invoked even if earlier steps fail and preserves their original red cause. API35 also retains raw JUnit XML and HTML report folders with its synthetic screenshots, without copying protected credentials. The flag and negative controls were separately checked in #879; the workflow change is accepted only after exact-head Android/JVM and emulator checks pass; no signing or physical-handset proof is implied.


> **CI dispatch and diagnostics consolidation (2026-10-31):** The radio harness PR workflow now executes Doze, interruption and network-flap fake-adb negative controls together; the two older dedicated workflows are manual-only historical repro entry points. It retains actual shell checks, but no longer starts redundant runners for overlapping interrupt changes. The Android foundation and native Add instrumented workflows emit bounded JSON/JUnit-count job summaries even when tests fail, never copying exception bodies or protected integration data. A successful job without JUnit evidence does not certify tests executed. Offline event-routing and digest tests run under the documentation validation check. Treat the new check topology as a proposed branch-protection migration until any private protection rules are independently confirmed; manual historical jobs do not replace acceptance. Baseline evidence and scope limits are in the quality ledger.


**Workflow inventory diagnostic accuracy (2026-11-02):** `tools/ci_workflow_inventory.py` identifies exact `main` in both inline (`branches: [main]`) and indented branch-list push filters, with offline negative controls for other refs, quoted entries and `branches-ignore`. The report's self-trigger entries remain **static review candidates**; they do not measure actual billed minutes, simulate GitHub event semantics, establish branch protection or authorize removal of a main post-merge gate. Preserve the independent real-engine and installed-app checks.

**Artifact retention inventory (2026-11-02):** The offline all-workflow inventory now counts real `actions/upload-artifact` steps and distinguishes explicit `retention-days` from steps inheriting the repository default. The latter are **review candidates**, not proof of excessive retention or actual storage cost; compare the relevant workflow run's artifact bytes, required failure evidence, and release/download consumers before changing retention. This check does not remove any artifact or relax exact-head acceptance.

**Tracked torrent metadata guard (2026-11-02):** The dedicated [tracked-metainfo workflow](../.github/workflows/tracked-torrent-hygiene.yml) runs a cheap, read-only Git-index check for PRs that change raw `.torrent` paths, `.gitignore`, or the guard itself, and for matching direct main pushes. Both push and PR path filters use GitHub-supported bracket character classes so upper/mixed-case extensions dispatch the guard, while `.gitignore` also covers them. It rejects *all* tracked raw torrent metainfo regardless of filename case, including forced additions bypassing `.gitignore`. It never displays metainfo filenames or contents, preventing the diagnostic from reproducing private-tracker names and passkeys. Test torrents are sourced through reviewed external public fixture inputs instead of checked-in raw metainfo. A new exception policy for any future deliberately vendored public fixture requires explicit review, provenance, and dedicated tests. This check does not replace credential rotation/history remediation after a prior leak and is not real-engine acceptance.

**Repository cache cleanup (2026-11-02):** A recursive exact-main tree audit identified four checked-in CPython 3.13 `.pyc` files (65,988 bytes total) under `tools/__pycache__` and `tests/__pycache__`. The maintenance cleanup deletes these generated bytes from the current tree and ignores Python interpreter/test caches. Python sources, fixtures and the ordinary executed-test gates are unchanged; removed tracked bytes are a source-control baseline, **not** measured Actions storage savings or a history rewrite.

**Engineering-wide optimization standard:** [CI efficiency, verification intelligence and agent diagnostics](CI-ENGINEERING-STANDARD.md). Apply that standard to CI audits/redesign; this policy remains authoritative for actual currently applicable checks and release selection.

> **Active gate reclassification (2026-10-31):** The strict native same-path regular-file non-clobber receipts and validators discussed in dated sections below reflect the **superseded** pre-[D-LOCAL-COLLISIONS-20261031](DECISIONS.md) premise. Keep their historical failure evidence, code quality and optional research checks, but do **not** use a legitimate, user-authorized, exact torrent-relative regular-file overwrite as an active G0 rejection or justify a compulsory custom JNI owner-writer. Current native acceptance is the [local torrent collision policy](LOCAL-TORRENT-COLLISION-POLICY.md): correct piece-checked reuse/replacement, root containment, symlink/special-file/overlap refusal, metadata integrity #865 and real device functionality. These validators must be retired or reclassified for release gating by focused changes with tests; this note does not itself change CI executable behavior. A red historical checker under its old premise is not automatically proof of a current product defect. See [requirements rationality](REQUIREMENTS-RATIONALITY-AUDIT-2026-10-31.md).

## Current CI selection and failure diagnosis (supersedes retired #568 checker as release gate)

Follow the [engineering delivery protocol](ENGINEERING-DELIVERY-PROTOCOL.md): choose focused fast tests first, then exact integrated-head required workflows for the affected positive user path, security boundary and failure/recovery test. Treat a passing research fixture, test-only native link, or pre-main PR head as evidence only for that layer; never infer installed device behavior.

Classify every red check by **harness syntax/selection, emulator/runner infrastructure, compilation/linking, real test assertion or unavailable acceptance tier**. Examples in prior PRs: [#798](https://github.com/gamesbyian/Chunks/pull/798) hit `grep -m1`/`pipefail` SIGPIPE after successful JNI linking; [#822](https://github.com/gamesbyian/Chunks/pull/822) timed out before the emulator booted; [#860](https://github.com/gamesbyian/Chunks/pull/860) separated asynchronous Compose work from UI assertions; [#869](https://github.com/gamesbyian/Chunks/pull/869) switched from callback-count proxy to actually persisted resume event and restart/seed. Preserve each red receipt, fix its actual failure without weakening behavior, rerun the needed exact head and never call an unexecuted test passing. Documentation-only/README-only changes must not trigger NDK/emulator/font build fan-out.

The old strict foreign same-name regular-file non-clobber assertions below are **archival validator documentation**, not currently active release criteria under the owner-approved collision policy. If CI executable wiring still makes them required, reconcile the actual checker configuration and add regression tests rather than asserting an undocumented waiver. Genuine metadata, path escape, symlink/special-file, concurrent writer, account scope and wrong-target ZIP/library tests remain required when applicable.

## Historical (superseded as global G0): Post-Stop native restart non-clobber proof

The strict post-Stop [native receipt gate](../tools/require_post_stop_nonclobber.py) requires a *real executed* JUnit native collision witness with unchanged foreign SHA-256 bytes across priority change and restart, truthful negative replacement flags, and an explicitly **REFUSED** restart. An accepted `VERIFIED` restart against the unowned planted file is **not** safe merely because its bytes happen to remain unchanged at the final sample; delayed writes could still overwrite it after observation. An `UNCERTAIN` restart is likewise insufficient. The independent negative controls in [tests/test_require_post_stop_nonclobber.py](../tests/test_require_post_stop_nonclobber.py) refuse both. A valid positive transfer to a genuinely owned fresh path is tested separately, never by reclassifying a foreign-file collision as permitted adoption.

This is an *offline validator correctness rule*, not evidence that Chunks production native libtorrent has started refusing collisions. In particular actual [native witness run 41130188712](https://github.com/gamesbyian/Chunks/actions/runs/41130188712) still shows `restart_outcome=VERIFIED` and `foreign_file_replaced=true`; [#568](https://github.com/gamesbyian/Chunks/issues/568) **was historically classified G0 RED under the now-superseded blanket payload non-clobber premise**.

## Historical (superseded as global G0): Strict raw Add and magnet collision controls

`tools/require_add_magnet_nonclobber.py` consumes a **real executed** `LocalNativePostAddCollisionProbeTest` JUnit XML, independently of the native characterization workflow's green/red-neutral job result. Both raw Add-after-foreign-path creation and magnet-metadata-arrival witnesses must come from that exact test class and suite, with complete distinct SHA-256 before/expected/after values, unchanged foreign bytes, and truthful negative replacement flags. Raw collision must return an explicit `REFUSED` Start; deferred magnet must remain stopped in upload-only mode after metadata. `VERIFIED`, `UNCERTAIN`, auto-start or overwritten bytes are **not release-safety receipts** even when the underlying diagnostic is green. Counterfeit classname, borrowed unrelated-suite lines, missing/duplicate witnesses, failed/skipped tests and contradictory byte flags refuse acceptance.

Fourteen deterministic synthetic positive/negative controls live in `tests/test_require_add_magnet_nonclobber.py` and run only in the existing documentation validation job on scoped changes; **no new Android job, external host, real peer or protected credential** is scheduled. The checker is read-only and is **not** run as a blanket prerequisite on every PR. A green offline control tests the checker, not the native writer: independent actual native JUnit, scoped restore/post-Stop receipts, API29/API35 and physical-device nonclobber remain G0 gates under [#568](https://github.com/gamesbyian/Chunks/issues/568).

**Documentation-only changes:** validate repository-local targets offline with `python3 tools/check_docs.py` and its unit tests. Do not dispatch Android, native, host or font Actions merely to validate Markdown. A docs PR merged to main does not justify an unrelated font fan-out; retain path-specific workflow triggers and the [font-lab isolation contract](design/CHUNKS-FONT-LAB-OPERATIONS.md). See [documentation standard](DOCUMENTATION-STANDARD.md).

## Build pin consistency negative controls (independent QA, 2026-10-30)

`tools/check_gradle_toolchain_pins.py` checks the **declarations** for fixed Gradle `9.3.1` and JDK `17` in eleven build/acceptance workflows, fixed Android Gradle plugin/Kotlin Compose plugin/libtorrent4j versions, and disallows inspected dynamic dependency coordinates. Twelve deterministic offline controls reject a missing workflow, mismatched versions, dynamic coordinates or invocation of an unchecked wrapper. These checks run in the **existing lightweight documentation validation workflow** only when relevant scripts, app/root Gradle declarations, or those selected workflow definitions change. They don't create a Gradle build, new runner, nightly check or font workload; they also don't alter production dependencies.

This is **pin consistency, not artifact integrity or supply-chain provenance**: the repository still has no committed Gradle Wrapper distribution checksum/JAR and no resolved dependency verification metadata, and GitHub Actions references remain major-tagged actions unless separately audited. No downloaded Gradle/AGP/JNI artifact digest was independently verified by this test, and successful pin checks do not supply a signed-install or release receipt. [AUD-13](ANDROID-ARCHITECTURE-AUDIT-2026-10-30.md) and [#10](https://github.com/gamesbyian/Chunks/issues/10) remain open for proper Gradle Wrapper/distribution SHA-256 and dependency-verification adoption. In particular, do not claim these negative controls as a solution to the native G0 overwrite gate.

## Read-only, exact-head PR receipt checker (integration QA)

`tools/check_pr_exact_head.py` verifies GitHub Actions records for a **specified full PR head SHA**, optional exact base SHA, and explicitly named workflow/check contexts. It double-reads the PR refs to reject head/base movement during inspection; rejects missing, cancelled, skipped or failed required contexts and workflow runs belonging to another PR; and returns nonzero on incomplete/unavailable evidence. It is a **review helper**, not branch protection, an Actions job, proof that the test exercised the promised scenario, or authority to merge. It does not start workflows or access protected testing hosts.

For example, to check the narrowly scoped current collision PR at a known SHA (replace SHA values with the **live** PR refs before running):

```sh
python3 tools/check_pr_exact_head.py --repo gamesbyian/Chunks --pr 703 \
  --head ae30defb8071447c1d86ddabec01a1a4ba0c70b5 \
  --require-workflow 'Chunks Android foundation' \
  --require-workflow 'Android 10 shared Downloads instrumentation' \
  --require-workflow 'Chunks R8 minified smoke'
python3 -m unittest discover -s tests -p 'test_check_pr_exact_head.py'
```

Workflow names are exact and must be selected from the change's actual path-triggered gate policy; optional/manual/secret-bearing workflows are not blanket requirements. If R8 was cancelled before creating a job, the checker correctly refuses to count it as success even when all observed check-run jobs are green. A separate R8 rerun or explicitly reviewed gate disposition is required; do not silently promote the other successes. Use the named SHA to prevent stale evidence, review the actual patches/test logs, and continue recording emulator versus handset/host evidence separately in [QUALITY-LEDGER](QUALITY-LEDGER.md). Never publish `GITHUB_TOKEN` or API response secrets in logs. See [#9](https://github.com/gamesbyian/Chunks/issues/9) and [#10](https://github.com/gamesbyian/Chunks/issues/10).

## Source-audit guardrails and future checks (2026-10-30)

The [AUD-01–15 register](ANDROID-ARCHITECTURE-AUDIT-2026-10-30.md) and [WORK-QUEUE](WORK-QUEUE.md) place integrity and reproducibility ahead of cosmetic CI expansion. These are **required future acceptance criteria, not checks claimed already running**:

- **Native/storage P0:** independent before/after foreign SHA256; occupied files, symlinks and unknown-path negative cases; early/late magnet metadata, manual Start, persisted stopped upload-only restart; real peer and Android tiers. Passing an intentionally reproducing overwrite test is **negative product evidence**.
- **Protected workflows P0:** exact approved credential-to-endpoint origin/mount/ref; negative tampered URL/redirect/ref controls run offline with no live secrets in PR CI. A trusted-main manual live test is a separate tier, not required for docs.
- **Resume/service P1:** forced concurrent native resume writes, inaccessible inventory, interrupted fsync/rename, actual Doze/netflap/FGS timeout/reboot and status witness. Skip/inconclusive never becomes success.
- **Governance P1:** main was observed unprotected at the audited SHA; an authorized maintainer must enable appropriate review and consistently emitted required checks. Path-skipped/manual opt-in jobs cannot be blanket requirements. Exact-source candidate evidence and post-merge main proof differ.
- **Reproducibility P2:** version-compatible Gradle Wrapper checksums and dependency provenance, focused lint baseline, R8-native-device and signed-install proof before changing shrink/release claims. Avoid unnecessary toolchain upgrades or workflow fan-out.

Retain the historical/physical evidence tiers and cost controls below. Do not turn architecture audit into unrelated static-analysis tooling until there is a measured defect to catch.

Updated after Chunks critical review. Borrow Glimpse's native build proof, ROYGBIV's cheap focused gates and UR-Recomp's exact source/evidence accounting, without transplanting their expensive workflows.

## Font-lab exception: one quiet branch (owner, 2026-10-28)

All routine font development goes to [`font/lab`](https://github.com/gamesbyian/Chunks/tree/font/lab), with **no open PR** and **local first** CFF/kerning/specimen validation. Current font push triggers are restricted to `main`; PR checks trigger when a font-diff PR is opened. A lab edit must not routinely dispatch Actions or open new PRs. Freeze meaningful batches, submit one temporary promotion PR, and perform the full relevant exact-head checks only then. Keep independent Android/UI checks for app work. See [font lab operating contract](design/CHUNKS-FONT-LAB-OPERATIONS.md) and [font trigger details](design/CHUNKS-FONT-CI-TRIGGER-POLICY.md).

**Documentation dispatch integrity (2026-11-02):** The documentation-validation workflow protects its own event selection with fast offline controls: push and PR paths must be the same ordered set, entries must be unique, and each literal/glob entry must resolve to a tracked checkout location. The previous duplicated `release-apk.yml` path was removed from each event list (81 to 80 pre-new-tool entries). CI changes to the validator or its tests now trigger the same lightweight documentation job. This verifies selector integrity only; it does not replace Android/engine acceptance or claim runner-minute savings.

## Automatic CI

One PR Android build/test job when app/Gradle/toolchain sources change. Compile and source-verify the debug APK and run JVM tests on pinned, documented toolchain. Do not upload routine APK binaries: retain compact failure evidence and source identity. Only an explicitly requested, manually published test APK is downloadable, and publishing a successor retires older owner-test debug prereleases. Do not run both main-push and PR versions for identical source changes. Docs-only plans need editorial review, not a full Android runner.

Do not use Secrets in fork/PR builds. Never connect the CI runner to a private rTorrent host merely to validate a public pull request. Keep live server tests manually authorized, with sanitized result summaries.

**API35 instrumentation trigger (2026-10-28):** `native-add-instrumentation.yml` now also runs for `app/src/main/**` changes. Two production-only PRs the same day (#460 added a read-only RPC call; #463 renamed the Connect field) left the instrumented suite red on main because it only triggered on `app/src/androidTest/**`. The ~8-minute emulator run on app PRs is cheaper than diagnosing red main afterwards. Docs, font and design changes still do not trigger it.

## Strict checked-torrent restoration acceptance receipt (G0 QA)

The checked-Add engine restoration witness in [#733](https://github.com/gamesbyian/Chunks/pull/733) intentionally passes its native loopback **diagnostic** when restored libtorrent writes onto unrelated preexisting bytes. [Run 41121891456](https://github.com/gamesbyian/Chunks/actions/runs/41121891456) demonstrated exactly that: the foreign SHA remained unchanged during restoration, then a `VERIFIED` Start replaced it with expected torrent data. This is **G0 RED**, independently retained in [QUALITY-LEDGER](QUALITY-LEDGER.md#2026-10-30-g0-red-persisted-checked-add-restore-loses-foreign-file-protection).

`tools/require_restore_nonclobber.py JUNIT_XML` is a separate **strict release acceptance filter**, requiring authentic executed/unskipped JUnit test counters and one `LocalNativePostAddCollisionProbeTest.checkedStoppedAddLosesFirstStartCollisionFenceAcrossEngineRestart` case, exactly five unique structured `CHUNKS_RESTORE_COLLISION` witness lines, distinct foreign and torrent SHA-256, and independently consistent before/after restore/Start SHA and boolean flags. It accepts only a refused Start that leaves the restored handle stopped or an absent handle, with foreign bytes **unchanged both immediately after restoration and after attempted Start**. An uncertain or verified Start cannot establish absence of pending native disk writes. Missing, contradictory, duplicate, skipped or failed receipts are refused. The 16 deterministic offline counterexamples run only in the **existing cheap path-scoped documentation CI job** on validator/test/policy edits. No added Android runner, protected server, font job, native code mutation, user data or secret.

The offline tests prove that the receipt filter rejects those unsafe claims; **they do not themselves execute native code or establish that the current restored writer is safe**. For actual acceptance, apply it to the final integrated exact-source native JUnit XML and record true SHA timelines, then extend to persistent inode binding, positive-progress substitution and real API29/API35/physical handset/SAF/SD/protected-provider test tiers. [#568](https://github.com/gamesbyian/Chunks/issues/568) **was historically treated as release-blocking; this is superseded for authorized ordinary regular-file replacement**.

## Strict post-Stop foreign-byte acceptance receipt (independent QA)

`tools/require_post_stop_nonclobber.py JUNIT_XML` is a **manual acceptance filter** for the real native `LocalNativePostAddCollisionProbeTest.stoppedPartialJobThenForeignSkippedFileAndRestartCharacterizesNativeWrites` report. The diagnostic suite [#715](https://github.com/gamesbyian/Chunks/pull/715) intentionally remains green when a disposable foreign file is overwritten, because it is characterizing the existing flaw. A green diagnostic result is therefore **negative product evidence when bytes change**, never release acceptance. The strict filter requires one executed, unskipped named case; matching test counters; exactly four unique native witness lines; distinct synthetic foreign/torrent SHA-256; and `before_sha256 == after_priority_sha256 == after_sha256`, with both replacement flags false and verified first Start/Stop. It fails if bytes change during **file-priority change even before restart**. Uncertain priority readback is tolerated only if independent bytes remain identical. An implementation may refuse restart or safely allow it without changing foreign bytes.

The filter's 13 deterministic offline negative controls run in the already existing path-scoped documentation job when the filter or tests change. To collect an actual safety receipt, first obtain the applicable native diagnostic JUnit XML **at the exact reviewed source SHA**, then invoke the filter on that output and record both positive and negative results in [QUALITY-LEDGER](QUALITY-LEDGER.md). **The offline controls do not run native libtorrent or prove current source is safe**. A future green synthetic receipt cannot establish atomically owned native write handles, post-crash restored ownership, symlink substitution safety, arbitrary real files or physical API29/API35 handset proof. The former #568-specific payload-ownership matrix is superseded; apply current [local collision policy](LOCAL-TORRENT-COLLISION-POLICY.md), #865 for app metadata and #5 for tested Android capability.

## API29 real shared-Downloads safety trigger (2026-10-30)

The existing `android10-downloads-instrumentation.yml` remains a **single path-scoped emulator job**, not an all-Android-app fan-out. Changes to `LocalTorrentEngine.kt`, `LocalCheckedPayloadReservations.kt`, or `LocalPayloadCollisionSurvey.kt` now trigger its real API29 shared-Downloads tests even without edits to the instrumentation suite. Those production sources directly control checked Add, first Start, magnet metadata paths and same-path writer admission on user-visible Downloads. `tests/test_ci_trigger_contract.py` independently guards these trigger paths in the cheap foundation JVM workflow. Unrelated Compose screens, artwork, remote transport, docs and font changes remain excluded from the API29 trigger unless they match an independently justified existing path. For actual overlap, avoid dispatching additional manual duplicate runs.

**Evidence limits:** a path trigger proves only that CI is scheduled. Require the exact PR-head run's actual non-skipped permission/native peer/collision test results, and independently distinguish that emulator evidence from handset/SAF/SD, persisted writer ownership and external path-substitution safety. This change does not close [#568](https://github.com/gamesbyian/Chunks/issues/568), [#9](https://github.com/gamesbyian/Chunks/issues/9) or [AUD-01](ANDROID-ARCHITECTURE-AUDIT-2026-10-30.md).

## Opt-in dataSync timeout repro (#569, 2026-10-29)

`android-fgs-timeout-repro.yml` runs one class, `LocalEngineServiceTimeoutInstrumentedTest`, through `tools/fgs_timeout_repro.sh` on a disposable API35 emulator.
- **Setup:** it applies Android's documented test controls (`am compat enable FGS_INTRODUCE_TIME_LIMITS`, `device_config … data_sync_fgs_timeout_duration 15000`) and restores both on exit.
- **Triggers:** manual dispatch, plus PRs that touch the service timeout files, the test, the script or the workflow. It is not an app-wide fan-out.
- **Skipping:** the ordinary native suite skips the class through an instrumentation-argument assumption. The script fails if the test was skipped or did not report one pass.
- **Scope of evidence:** a pass proves shortened-limit handling on that emulator only. It does not cover the six-hour budget, Doze, OEM killers or handsets.
- **Sudden process death:** the same job then runs `tools/process_death_repro.sh` (`LocalEngineProcessDeathInstrumentedTest`).
  - **Phase A** seeds under the service and SIGKILLs its own process.
  - **Phase B**, in a new process, must find no leftover ongoing notification, the transfer restored stopped, unchanged bytes and a 100% recheck.
  - **Failure conditions:** the script fails unless phase A armed and actually died, and phase B passed without being skipped.
- **Device reboot:** `tools/reboot_repro.sh` (`LocalEngineRebootInstrumentedTest`) seeds under the service, then runs `adb reboot`.
  - After boot it requires that no Chunks process started by itself, and that there is no engine and no ongoing notification.
  - On opening the saved state, the transfer must restore **stopped** with unchanged bytes.

## Manual real-peer lifecycle modes (#568/#569)

`android-public-swarm.yml` is manual-only, needs no secrets and uses the sanitized Debian netinst. It has these exclusive modes:
- **default:** download a verified budget;
- `interrupt`: SIGKILL mid-download, then check restore and continuation;
- `doze`: forced deep idle during a foreground download;
- `netflap`: Wi-Fi and data off for 30 s, then require recovery.

A peer shortfall is reported as INCONCLUSIVE, never as engine acceptance. Each run is emulator evidence only.

## Targeted escalation (manual first)

- XML-RPC parser and transport changes: deterministic fixture and fuzz/size/error cases, plus a bounded integration matrix when justified.
- Server state mutations and idempotence: synthetic test service and disposable rTorrent host; verify real acknowledgement and no duplicate actions on response loss.
- Filesystem changes: independent fault injection using disposable datasets, multiple volumes and process kills at each journal checkpoint. Fail closed if recovery cannot be proved.
- Android UI changes: occasional targeted emulator screenshot/hierarchy atlas and real handset walkthrough, not a permanent broad screenshot job.
- Local engine background: device tests across Android background/service types and network conditions; do not use one green assembly to infer reliability.

## False-positive safeguards: physical installation and reboot

The physical Android APK preflight (`tools/physical_android_preflight.sh`) must pin one selected non-emulator ADB device, exhaust the `dumpsys package` output without `grep -m1`/SIGPIPE, reject missing or conflicting version identities, require explicit package-manager `Success` and an exact-activity launch receipt. Its fake-ADB regression is an offline negative control, **never** evidence of physical handset installation or publisher signing.

The opt-in `tools/reboot_repro.sh` must record a syntactically valid kernel `/proc/sys/kernel/random/boot_id` before phase A and require a **different** valid boot ID plus Android boot-complete before phase B. `sys.boot_completed=1` alone does not prove reboot. A mock-ADB suite rejects unchanged and invalid identities before any expensive emulator phase.

## Receipts and stopping rules

Each experiment records source SHA, build/test variant, environment, questions, inputs (redacted), what was actually measured, outcome and missing coverage. A run can be success, meaningful negative result, inconclusive or infrastructure failure. Stop when more experiments will not change an active design/release decision. One job summary plus a small reproducible result suffices; raw media/file inventories never enter public artifacts.

Normal branch protection should only require consistently present checks; never require an opt-in or path-skipped check. Release gates in QUALITY-LEDGER.md are independent of merge validation.

## Retained manual real-rTorrent experiment

`disposable-rtorrent-probe.yml` uses narrowly scoped pull-request paths and manual dispatch after the one-time successful real rTorrent 0.9.8 run [40060755245](https://github.com/gamesbyian/Chunks/actions/runs/40060755245). It installs rTorrent and nginx in an ephemeral runner and runs a strictly read-only Kotlin smoke test. The PR trigger now deliberately covers the gateway adapter, both real-engine acceptance tests and the workflow YAML itself (merged #153; both legacy and modern real-engine jobs passed at exact head `ecb029e7ecdd9eff7d5bc5c940da404303f1ae5c`, [run 40372755722](https://github.com/gamesbyian/Chunks/actions/runs/40372755722)). Do not broaden it to every app/Gradle edit without a specific compatibility reason and runner budget. The normal Android PR check remains the sole automatic job for app source changes.

## Modern compatibility gate exception (October 26, 2026)

PRs #150 and #153 use **path-scoped** pull-request triggers for `disposable-rtorrent-probe.yml` itself to obtain evidence on materially different Arch Linux rTorrent 0.16.x. Its legacy and modern tests pass on exact source `2b9d2c3e6291c95fa260bdaaf634527d14ba6186` ([40368534273](https://github.com/gamesbyian/Chunks/actions/runs/40368534273)). It is **not** a general automatic app-source fan-out; no secrets, user media or hosted server is involved. Alpine 0.15.2 remains excluded and tracked in #151 after repeated HTTP 502 SCGI failures.

## Smart Connect safety and provider acceptance (2026-10-28)

[SMART-REMOTE-CONNECTION](SMART-REMOTE-CONNECTION.md) and
[SPEC-COVERAGE](SPEC-COVERAGE.md) control read-only discovery tests.

- **Cheap deterministic PR gate:** URL/hostname normalization, provider
  candidate ordering, exact-numbered-tenant preservation, account versus
  HTTP-auth username distinction, bounded candidate count, malicious
  `userinfo`/query/fragment/path input, HTTP downgrade refusal,
  certificate/auth/timeout classification, fallback after missing RPC
  mount, and no retry after 401/403/certificate failure.
- **Transport fixture:** real Kotlin `RTorrentRemote` against disposable
  authenticated Basic/Digest/nginx SCGI/ruTorrent HTTP-RPC endpoints,
  including 200 HTML login page, 401/403/407, redirect, TLS wrong host,
  expired or self-signed certificate, unsupported introspection,
  incomplete view, duplicate hashes, oversized XML and unknown methods.
  Separate capability observations from mutating-operation guarantees.
- **Manual protected provider tests:** normal PRs, forks and unreviewed
  commits receive no live server secrets. `seedhost-endpoint-compatibility.yml`
  is manual-only, trusted-main-only and selects exactly one case-sensitive
  credential mapping at a time. Independently verify the three numbered
  HTTPS routes, then the direct-SCGI HTTPS *candidate* as a separate
  hypothesis. Record source SHA, workflow run, endpoint family (not
  password), method/list evidence, and inconclusive/negative results.
- **Actual Android acceptance:** API35 Compose login and at least one
  real physical handset with certificate validation, network changes,
  200% font, TalkBack, foreground/background reconnect, cancellation
  and per-profile privacy gating. No screenshot can include a password
  field, authorization challenge, passkey, session cookie or tracker
  token. Success of JVM planning alone cannot close this gate.
- **Budget:** path-scoped fixtures and one selected real provider profile
  per manual dispatch rather than broad hourly loops. No API mutation
  probes during discovery. Server write capability acceptance is a
  separately authorized, exact-target and readback-gated workflow.

An older tested commit passing CI does **not** validate a later merge or
doc/code head. PR #463's earlier Android/JVM [run 40760083435](https://github.com/gamesbyian/Chunks/actions/runs/40760083435)
and native visual [40760083286](https://github.com/gamesbyian/Chunks/actions/runs/40760083286)
were successful on head `8922760cf81a11fb299a96c0013ea833f2845896`.
This is source/emulator-tier evidence only, not a verified live provider
or physical handset connection; the rebased and supplemented head must
be checked independently.
