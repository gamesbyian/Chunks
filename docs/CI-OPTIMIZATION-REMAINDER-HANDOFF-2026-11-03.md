# Chunks CI optimization remainder: evidence and fresh-agent handoff

**Snapshot:** November 3, 2026; live `main` was `8ed770af99c3cd2fa8c1a05664b1c8a379fff936`. This is a **handoff**, not a competing work queue. Recheck live main, PRs, issues and tests before acting. Priority belongs to [WORK-QUEUE](WORK-QUEUE.md), standards to [AGENTS.md](../AGENTS.md) and [CI-POLICY](CI-POLICY.md), and evidence to [QUALITY-LEDGER](QUALITY-LEDGER.md).

## Already shipped: avoid redundant implementation

- [#1138](https://github.com/gamesbyian/Chunks/pull/1138): consolidated API35 visual-atlas Gradle instrumentation. Existing four real screenshots and three exact method receipts retained ([green run 41580103037](https://github.com/gamesbyian/Chunks/actions/runs/41580103037)).
- [#1139](https://github.com/gamesbyian/Chunks/pull/1139): expensive two-shard native Add API35 testing deferred on draft PRs, automatically executed on review-ready and subsequent ready-state changes.
- [#1148](https://github.com/gamesbyian/Chunks/pull/1148): same review-ready batching for R8 minified smoke and native visual atlas.
- [#1153](https://github.com/gamesbyian/Chunks/pull/1153): read-only `tools/ci_runner_cost_report.py` accounts actual job timestamp minutes and canceled runs. Historical UX draft #1132 generated 35 workflows, ~172 aggregate runner-minutes, ~55 on 21 canceled runs. These are observed job minutes, not billable or all avoidable.
- [#1155](https://github.com/gamesbyian/Chunks/pull/1155) established build-once immutable app/test APK reuse across two hardware-accelerated API35 shards; [#1159](https://github.com/gamesbyian/Chunks/pull/1159) promoted to **production** native Add workflow; [#1162](https://github.com/gamesbyian/Chunks/pull/1162) made the old experiment manual-only. [Production run 41597519488](https://github.com/gamesbyian/Chunks/actions/runs/41597519488) passed 348 test identities (320 executed, 28 intentional skips), 24 genuine PNGs, and exact outcome parity with [original Gradle run 41595260256](https://github.com/gamesbyian/Chunks/actions/runs/41595260256). Observational comparison: 19.25 vs 13.29 aggregate runner-minutes and 10.07 vs 8.52 minute job window; sources and conditions differed, so **no causal speedup claim**.

## Remaining proposals, in priority order

### 1. Integrate existing measurement fix, then measure real follow-up revisions

[#1164](https://github.com/gamesbyian/Chunks/pull/1164) was **open** at snapshot, head `24ccc07af9722e4f6df14545e881056eaa9d042e`, with [documentation validation green 41599203672](https://github.com/gamesbyian/Chunks/actions/runs/41599203672). It adds true **job activity window** (earliest actual job start to latest completion), distinguishing it from `workflow.updated_at - created_at` lifecycle delay, with null values for all-skipped runs. **Check live status; do not recreate.** Integrate only after the appropriate review.

Use existing read-only report and workflow inventory on a complete recent multi-revision PR. Compare workflow dispatch counts, cancellation occupancy, actual job window, total runner-minutes, artifact retention, diagnostic usefulness, and draft-to-ready transitions. Control for changed source/runner conditions and absent data. The answer **“no further optimization supported”** is acceptable. Do not run new emulators merely to obtain benchmark data.

### 2. Restore useful instrumentation per-method timing, if feasible

The build-once `tools/parse_instrumentation_stream.py` preserves real AndroidJUnitRunner test IDs/outcomes, but its generated JUnit lacks Gradle/UTP per-test durations. Investigate whether existing real instrumentation status data can provide defensible durations, or explicitly mark `unknown`. Avoid inventing zero-duration tests, adding a second emulator campaign, recording raw protected server details, or re-running each test. Exercise parser negative and incomplete streams offline before proposing promotion. Value is diagnostic only until measured.

### 3. Identify residual *actual* workflow fan-out, setup, and retention costs

Examine `tools/ci_workflow_inventory.py`, live Actions runs/jobs/artifacts and GitHub events, particularly static flagged font/SVG/self-trigger candidates. Static inventory flags are **leads, not proof**. Calculate actual cost, cause and users before changing any event filter, artifact expiry, cache or Android SDK provision step. Preserve latest requested review APK, failure receipts, necessary screenshot evidence and signed release outputs; do not retain obsolete review APKs without use. Evaluate Android emulator provisioning, Gradle caching, duplicate setup or dependency downloads using *measured step timings*, then choose only a bounded improvement with a falsifiable result.

Potential cross-workflow build reuse is an **experiment, not a promise**: debug, R8/minified, API29 storage, API35 lifecycle, test instrumentation and signed release builds may differ in variants, permissions, inputs and identity. Require exact-source, variant, signing and receipt parity; weigh artifact upload/download and trust boundaries. If no clear match exists, do nothing.

### 4. Avoid increased cost from duplicated or misplaced tests

Keep all full review-ready checks and manual diagnostic dispatch available. Do not add a parallel always-on “experiment” after a successful promotion. Batch independent compatible UX/feature edits before a single coherent test campaign, but run early targeted actual-engine, storage and transport checks when failures are consequential or cheaper to locate early. Preserve real installed Android API29 Downloads/SAF, native Add API35, R8, accessibility screenshots, local and rTorrent bytes/peer progression, process death/reboot and exact-head coordinator receipts as applicable. Compare test identities and genuine artifacts, not just green badges.

### 5. Branch governance blocker

Live branch API reported `main.protected=false` at snapshot; [issue #10](https://github.com/gamesbyian/Chunks/issues/10) remains open. Recommend a PR-only main ruleset with an **always-emitted** required integration check compatible with path-specific suites and draft skips. No available GitHub connector setter was established. Document exact admin steps and verify actual protection after a maintainer acts; policy prose is not enforcement. Do not globally require a skipped optional job.

### 6. Product acceptance is distinct from CI optimization

Preserve [#1113](https://github.com/gamesbyian/Chunks/issues/1113) (owned-data Remove vs keep-data across local/rTorrent), [#1041](https://github.com/gamesbyian/Chunks/issues/1041) (genuine fixture rotation), [#901](https://github.com/gamesbyian/Chunks/issues/901) (Compose teardown failure), [#9](https://github.com/gamesbyian/Chunks/issues/9) (integration QA), [#5](https://github.com/gamesbyian/Chunks/issues/5) (engine/lifecycle), and [#569](https://github.com/gamesbyian/Chunks/issues/569) (physical/OEM/Doze/VPN). A green native Add emulator does not authorize deletion of data, certify real remote servers or establish physical phone acceptance. Do not conflate these proof tiers.

## Practical optimization decision contract

For each candidate: (1) cite real evidence and distinguish queue/runner/latency/storage/human costs; (2) explain the existing test-evidence contract and what would change; (3) state when earlier testing is worth the cost; (4) use cheap negative controls first and a single targeted exact-head acceptance if required; (5) compare observed outcomes including any weaker coverage or slower feedback; (6) merge a qualified independently reviewed change and document the measured result, or reject the idea as unjustified. **No speculative all-workflow redesign or weakened real transfer acceptance.**

## Fresh-agent continuation contract

1. Inspect live `main`, all open PRs, open issues, `AGENTS.md`, `docs/INDEX.md`, `docs/AGENT-LANES.md`, `docs/WORK-QUEUE.md`, this report, `docs/CI-POLICY.md` and `docs/QUALITY-LEDGER.md`. Reconcile newer merged changes before touching files.
2. First establish whether #1164 still needs review/integration. Avoid duplicate PRs or unsupported self-approval.
3. Measure one recent complete PR development cycle using the existing accounting/inventory tools and Actions API. Produce a ranked list of real waste vs useful testing, with run IDs and contrary evidence.
4. Choose the highest-leverage independent bounded change or explicitly record why none is justified. Avoid P0 app files and active UX ownership. If implementing, use a clean branch, offline tests first, and smallest real acceptance needed.
5. Update the canonical CI policy/evidence/work queue only for actual new findings. Resolve or document red checks; don't produce repeated prose inventories. End with exact PR/run links, source identities, verified acceptance tier, measured impact and remaining blocker.

**Highest-confidence next move:** reconcile #1164, then measure *actual* residual workflow waste. Per-method timing, font/SVG fan-out, retention and broader APK sharing remain hypotheses until evidence justifies an implementation.
