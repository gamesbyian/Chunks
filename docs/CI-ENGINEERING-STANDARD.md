# CI efficiency, verification intelligence and agent diagnostics standard

**Status:** normative engineering standard. **Scope:** all GitHub Actions, build/test automation, CI scripts, runners, caches, artifacts, and verification reporting in Chunks. **Authority:** [AGENTS](../AGENTS.md) governs agent conduct, [CI-POLICY](CI-POLICY.md) governs currently applicable selection and release gates, [WORK-QUEUE](WORK-QUEUE.md) orders active work, [QUALITY-LEDGER](QUALITY-LEDGER.md) records actual evidence. This standard governs how CI is audited, improved, and maintained; it does not claim that the desired mechanisms already exist or replace any product safety contract.

## Objective and governing principle

**Maximize trustworthy, actionable engineering evidence per unit of elapsed time, runner time, compute, money, and agent attention.** Every workflow dispatch must produce necessary evidence unavailable through a safe, cheaper route. Every test must address identifiable risk. Every failure must give a fresh agent the shortest feasible path to reproducing and fixing the originating problem.

Never trade coverage, safety, security, reproducibility, trustworthy provenance, or correct required-check semantics for an attractive duration metric. Treat **time to first actionable failure**, **time to safe merge decision**, **aggregate runner consumption**, and **diagnostic latency** as distinct objectives. Optimize measured bottlenecks, not intuitions.

## Mandatory repository-wide investigation

Before changing topology, inspect current main and all workflows, reusable workflows, composite/custom actions, triggers, path filters, conditions, matrices, job dependencies, permissions, environment protection, runner choices, concurrency groups, retries, timeouts, and artifacts. Include Gradle, JDK, Android SDK/NDK, Kotlin, Compose, JVM, JNI/CMake/Ninja/libtorrent, instrumented tests, emulators, remote tests, font-lab isolation, packaging, releases, scheduled/manual and privileged workflows.

Review recent **and historical** workflow runs, PRs, issues, incidents, reversions, cancellations, skipped checks, flaky tests and infrastructure failures. Record queue time, dispatch frequency, runner minutes, setup/compile/test duration, cache behavior, artifact transfer, test value and the actual critical path. Distinguish observed values from estimates; collect baseline evidence before claiming gains.

Inventory each workflow as an event-to-evidence mapping: triggering event, exact commit/ref, risk addressed, required/optional tier, dependency, shared outputs, observed cost, redundancy candidates, trust boundary and downstream consumers. Dispatch because a specific verification decision needs fresh evidence, never because a broad event happened.

## Dispatch minimization and correctness

Eliminate duplicate push/PR executions, redundant workflow chaining, overlapping matrices, obsolete checkers, avoidable scheduled/manual fan-out, duplicate checks on unchanged code and unnecessary heavy jobs on documentation-only changes. Use accurate changed-file and dependency impact, not naive filename globs. Preserve fork PR semantics, merge queues, release refs, branch protection and stable required-check contexts; path-filtered required workflows must not hang indefinitely or silently pass by omission. Confirm each trigger condition and cancellation group on representative events. A static inventory must attribute `paths` and `paths-ignore` to the actual event scope: push-only filters never demonstrate a filtered `pull_request` or `pull_request_target` gate. Review candidates are not proof of CI savings.

Cancel only superseded work that provides no unique needed evidence. Never cancel another branch/PR's verification or different release/security obligation. Preserve meaningful diagnostic artifacts from interrupted/failed work when feasible.

## Verification tiers and risk-aware selection

Implement explicit, testable change-impact rules separating:

1. **Fast preflight:** Markdown/link checks, workflow/schema validation, formatting, static consistency and affected deterministic unit tests.
2. **Affected-subsystem:** necessary compilation, unit/contract/integration checks for the changed components and transitive dependencies.
3. **Cross-system:** shared model, persistence, UI/service lifecycle, storage, networking, Android and native boundary regressions where implicated.
4. **Full assurance:** high-risk broad changes, ambiguous impact, release/merge-queue demands, periodic assurance and intentionally sampled comprehensive verification.

Map every suite to defect classes, modules, transitive dependencies, platform, cost, historical failure yield and evidence limitations. Explicitly protect native data integrity, root/symlink/overlapping-writer safety, JNI/libtorrent behavior, Android scoped storage/SAF, resume durability/recovery, remote auth/identity/idempotency, profile isolation and critical user flows. Respect the current user-authorized regular-file collision policy; do not revive superseded #568 assumptions.

Treat changed tooling, selectors, shared fixtures, generated code, file renames, unknown or multi-subsystem edits as high-risk unless proven otherwise. Full verification remains accessible, with mandatory escalation where impact is uncertain. Mocked/unit success never substitutes for actual native, emulator, installed handset or live remote evidence.

Remove truly redundant tests only with proof that surviving tests detect equivalent or greater faults; preserve high-value positive, negative, cancellation, concurrent and recovery cases. Fix weak assertions, excessive mocking, unstable assumptions and needlessly expensive fixtures instead of simply deleting slow tests.

## Dependency graph, parallelism and reusable computation

Model the complete run as a DAG. Measure the critical path, first failure path, startup overhead and resource contention. Optimize job fan-out/fan-in, matrix sizing, test shards, Gradle configuration/cache/worker limits, Kotlin daemon, Java/NDK and CMake/Ninja workers, emulator boot/reuse, CPU/memory/I/O and artifact transfer. Tune empirically for actual runner capacity; more workers/jobs are not inherently faster or cheaper.

Identify repeated checkout, dependency resolution, toolchain installs, Kotlin/native compilation, emulator setup, fixture creation, static checks, packaging and upload. Reuse **results**, not just YAML, where safe: content-addressed caches, trustworthy immutable build artifacts and dependency-aware downstream consumers. Compare rebuild costs against upload/download costs; cache invalidation includes toolchain, dependency, build configuration and relevant code state. No mutable or untrusted cache/artifact may become authoritative across privilege boundaries. Minimize repeated work without building a bespoke CI framework when stock tooling suffices.

## Diagnostic contract: immediately usable by agents

Every meaningful run SHOULD publish a concise GitHub job summary; failed runs MUST expose the earliest actionable failure, with full underlying evidence retained where available. Produce a stable, versioned machine-readable failure digest (JSON, plus standard JUnit XML/SARIF where appropriate) containing:

- schema version; workflow/run/job/step identifiers and URLs; trigger; exact commit/ref; environment/toolchain; outcome and whether required evidence actually executed;
- failing phase/class (workflow/harness selection, provisioning, dependency, compilation/linking, emulator/runner, test assertion, external integration, timeout/cancellation or unknown);
- subsystem, first root-cause candidate, failed test/task, source file/line if known, expected vs observed, concise bounded log excerpt and authoritative complete log/artifact links;
- reproducible command/parameters where possible; deterministic/flaky/environmental classification marked **observed**, **inferred** or **unclassified**; recommended next debugging action;
- multiple independent failures without allowing a secondary teardown/reporting failure to hide the original fault.

Emit useful reports even when provisioning fails before tests start. Expose counts of executed/failed/skipped/unexecuted tests, not misleading green summaries. Use actionable annotations and hyperlinks. Redact tokens, credentials, protected endpoints and private payload data. A new agent should identify its first useful debugging action without downloading enormous raw logs.

## Flakes, historical problems and security

Classify and repair failures at their source. Track first-attempt failure independently of eventual retry success. Allow only bounded, justified, visible retries; no blanket `continue-on-error`, routine whole-workflow retries, hidden quarantine or falsified status. Historical reversions, cache failures, emulator boot timeouts, shell/pipefail accidents, asynchronous UI races, bad proxy assertions, invalid gates and misleading stale-head success must shape regression controls.

Audit least-privilege tokens, third-party action pinning, workflow injection, fork privileges, `pull_request_target`, secrets, cache poisoning, artifact provenance, reproducibility, dependency drift, release signing and cancellation semantics. Preserve strict separation between untrusted PR execution and credential-bearing workflows. Exact-head green CI does not prove a different commit, physical-device behavior or protected host result.

## CI must test its own selection logic

Implement deterministic offline tests for dispatch and gate selection covering docs-only, Kotlin, Compose, JNI, native/storage, Gradle/toolchain, workflow edits, shared code, multi-subsystem, renamed/unknown changes, fork PRs, merge queues, releases, cache misses, setup failure, cancellations and superseded runs. Assert expected workflows/jobs/suites **and** required-check outcome behavior. Whenever feasible, replay historically defective changes to detect selector false negatives. Failure to classify an impact MUST default to sufficient verification, not silence.

## Evidence, scorecard and improvement loop

Maintain lightweight, auditable before/after metrics: dispatches per change, redundant dispatch count, runner minutes, queue and wall-clock latency, time to first actionable failure, first-pass flake rate, per-step timing, cache hits/misses, shard imbalance, artifact sizes, diagnostic completeness and coverage by defect class. Compare representative warm/cold and change-type samples; do not generalize a lucky run. Do not add telemetry infrastructure whose cost outweighs its benefit.

Prioritize: **P0** truthful gates/security/selection; **P1** eliminate redundant dispatch; **P2** shrink measured critical path and total compute; **P3** actionable diagnostic output; **P4** historical failure/flake regression controls; **P5** long-term telemetry and documentation. Reorder if an actual dependency or severe defect requires it.

## Implementation and review protocol

This standard requires implementation, not a ceremonial audit. Inspect open CI PRs/issues first and avoid duplicate work. Establish baseline, rank improvements by cost/impact/risk, implement the smallest coherent foundations, test workflow syntax and event selection, exercise representative exact-head CI, inspect actual evidence, then reconcile and merge safely under existing review/branch rules. When blocked by a long-running job, progress another independent optimization within the session. Do not invent successful runs, waive gates, or create competing active queues.

Each CI change must explain: what wasted work/diagnostic failure it corrects; baseline; affected event and trust boundaries; the new execution graph; impact on required contexts and test coverage; negative selector tests; measured tradeoffs; fallback/rollback. Update [CI-POLICY](CI-POLICY.md) for the **actual current** topology, [QUALITY-LEDGER](QUALITY-LEDGER.md) for observed evidence, [WORK-QUEUE](WORK-QUEUE.md) for active ranked work, and this document only when the durable standard changes. Preserve historical receipts as dated history, not current requirements.

## Completion and reporting

Aim for: each dispatch necessary; no duplicate expensive preparation without justified tradeoff; verified risk-aware tests and safe fallback; evidence-preserving parallelism; protected high-risk behavior; bounded flakiness; stable and immediately useful machine/human diagnostics; self-tested orchestration; no weaker required gates; credible cost and latency improvements; coherent documentation.

Report the baseline, highest-value historical findings, files/PRs/commits implemented, exact-head evidence, quantified before/after outcomes, remaining uncertainty, and next dependency-ordered tasks. Clearly distinguish **merged**, **pending**, **verified**, **hypothesized**, and **blocked**. Do not stop at a plan if implementation is possible.
