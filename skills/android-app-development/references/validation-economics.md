# Validation economics: batch changes, preserve fast feedback

**Activate when:** a development queue contains multiple compatible changes, CI/device/provider runs are expensive, a project repeatedly performs full validation after local edits, or the director asks to maximize completed work before a shared test session. **Do not activate** for a single trivial correction with an already-cheap focused check.

**Rejection condition:** changes cannot be safely combined because of incompatible baselines, ownership, a shared unstable dependency, irreversible effects, or an explicit project gate. Revert to independently verified slices for the incompatible items, not an indiscriminate full-suite-per-edit policy.

**Inputs:** current main/head, active agent ownership, queued change candidates, their file and runtime dependency paths, expected outcomes and oracle, risk/reversibility, test inventory with measured duration and flake rate, available environments and CI constraints. If timing data are absent, label estimates unmeasured and instrument the next run.

**Output:** a bounded *implementation batch* and a separate *validation batch*, their dependency/interaction graph, check schedule, acceptance matrix, stop triggers, exact-head receipts and failures assigned to owners.

**Evidence and counterexamples:** [historical validation-cost audit](validation-case-audit.md), including UR-Recomp non-exercising Windows runs, Expository post-copy Compose teardown, Chunks stalled reboot, precompleted magnets, conflated Compose waits, and visual atlases. The audit distinguishes actual inspected logs from PR-reported claims and unmerged proposals.

## Decision procedure

1. **Inventory candidates and existing evidence.** Gather A–F or however many actual changes exist, including negative-path requirements. Describe each as entrypoint → state/side effect → observable result. Identify already-passing baselines, affected code and regression oracles. Avoid writing fictional changes to fill a batch.
2. **Partition by dependency, risk and blast radius.** Build a lightweight matrix for feature interactions, shared files/state, platform/version/provider, and test environments. Prefer changes sharing a costly setup but having separately identifiable assertions. Separate a likely-moving foundation from downstream UI when an early foundation result could invalidate the later work. Coordinate shared ownership rather than racing another agent.
3. **Evaluate the information and cost of each check, not its size.** First require the test invocation to actually reach the changed code, runtime mode and consequential postcondition. Ask what distinct uncertainty it resolves, whether initial fixture state can make it falsely green, what evidence already exists on the exact tested revision and how a failure will be localized. Then compare repeated full runs against one setup plus a combined run, including expected fault-localization cost, reruns, failure probability, queue time and integration conflicts. Prefer batching when shared setup dominates and changes have independent or distinguishable oracles. Prefer early checks when failures would propagate, consequences are high, or diagnosis becomes ambiguous. Never present invented estimates as measurements.
4. **Choose an explicit three-level schedule:**
   - **Edit-local:** static analysis, syntax/type/compile checks, deterministic unit tests or tiny contract tests at natural checkpoints. These are fast fault-isolation tools; run whenever a relevant assertion could invalidate dependent work. Do not mandate one per keystroke or commit.
   - **Batch boundary:** one exact-head build and the smallest set of integrated tests proving all batched outcomes, including shared-state interactions and negative controls. Reuse one installed app, emulator boot, provider login, fixture setup and screenshot atlas where safe. Carry all candidate assertions in a manifest so a common setup does not erase per-feature evidence.
   - **Release/merge boundary:** full risk-appropriate matrix on the integrated revision, with physical handset, destructive recovery, remote/provider, signing or upgrade gates only where genuinely applicable. Keep required branch protection and independent review intact. Do not re-run unchanged expensive suites only to create redundant receipts; do run again when their evidence no longer covers the merged head.
5. **Implement across the planned batch.** Land logically distinct commits/patches with flags or isolated components as needed, maintain a tested baseline, and record short per-item expected postconditions. Never claim that untested work passed. If a change touches a high-risk boundary, insert a focused gate *before* dependent implementation proceeds.
6. **Run the shared validation session.** Test the **actual shipping paths** for every member, with distinguishable IDs, fixtures and per-item assertions. Use one common setup, but independently read back each relevant result. Include selected cross-feature interaction tests; combinatorial exhaustive pairs are unnecessary unless risk justifies them. Parallelize only tests that do not corrupt each other's fixtures/accounts/devices.
7. **Diagnose once, retest proportionately.** Classify a red result as production regression, integration conflict, harness, environment, flaky or unknown, and cite the *last executed assertion*. Distinguish side-effect postcondition, UI presentation and test-rule teardown, which can disagree. Once a repeated full journey consistently fails at a narrowed stage, perform a smaller **discriminating experiment** before another full replay. A passing negative control only excludes its scoped hypothesis; it never repairs the original red. Run the smallest reproducer after a fix, then the affected dependency closure; reserve a repeat whole-batch/full run for changes that invalidate the shared acceptance evidence or must close an unresolved complete-journey gate. Preserve the first failure and exact SHA, and do not turn a red into green via silent retries, skips or reduced assertions.
8. **Reconcile and stop.** Report each item as implemented / focused-verified / integrated-verified / release-verified / blocked, with exact SHA, evidence tier, assertions executed, artifacts, and still-untested environments. Update the next batch from actual elapsed time and failure localization observations. Stop expanding the batch when the marginal change is riskier or costlier than running the planned shared gate.

## Push cadence is part of validation economics

**Activation:** a protected branch or pull request runs expensive checks automatically on every pushed update, or several agents/API actions are producing intermediate commits. Include *CI dispatch frequency* in the batch plan, not just which tests each job runs.

- Prepare compatible edits, focused local checks, and reviewable logical commits **before a single stable push/checkpoint**, when the environment permits. Multiple local commits can be pushed together; do not squash useful history solely to save CI. Where an API writes one file/commit at a time and triggers a push for each, prefer an atomic multi-file commit mechanism when available and appropriately verified. If unavailable, deliberately accept the extra cheap checks rather than disabling relevant protection.
- A push that supersedes an expensive running workflow can waste source checkout, SDK/emulator boot, SDL/toolchain provisioning and partial acceptance. Configure safe PR-head concurrency cancellation where appropriate, but **avoid starting costly workflows on known-incomplete states** when an integrated candidate can be prepared first. Never cancel independently valuable scheduled/release work or assume that a cancelled run was free.
- Retain early *focused* remote checks when the next several changes depend on an uncertain native/platform/identity invariant. Waiting until the final push can increase diagnosis and rework. Distinguish draft-PR reviewability, source versioning and coordination from the frequency of heavyweight tests.
- Preserve normal required branch protection, final exact-head checks and trusted-main scheduling. Never skip a required final workflow because earlier intermediate commits were green.

**Observed self-audit (2026-11-03):** [Skills PR #605](https://github.com/GBIDEVELOPMENT/general-purpose/pull/605) was assembled via sequential file-update commits. The history generated **eight Android integrity workflow dispatches and two Engineering Judgment dispatches** on intermediate heads, with some runs cancelled after later updates; the final two passed. Ten directly inspected job logs each spanned roughly **6–8 seconds**, approximately **70 seconds of aggregate logged job intervals**, with no verified billed-minute total or proof that all earlier checks were valueless. This is a deliberately low-cost counterexample: repeated dispatch is real but not necessarily worth optimizing aggressively when checks are cheap. The same update pattern around eight-minute emulator runs or ten-minute Windows packaging can have far greater marginal cost. Record *trigger count, job execution time, billed units if available, cancellation stage and information gained*, rather than treating every avoided job as a saved full test duration.

## The batching decision

Do not use a rigid number of features or a mandatory time box. Apply these qualitative thresholds:

| Candidate | Default | Why |
|---|---|---|
| Independent layout, labels, sorting affordances with stable interfaces | Batch, then one screenshot/accessibility/user-journey pass | Reuses app build, install and device setup |
| Local parser/algorithm modifications with cheap deterministic tests | Batch expensive integration; run narrow tests as needed | Millisecond feedback reduces later debugging cost |
| Android local and rTorrent remote operations with identical user-facing intent | Implement both behind separate adapters, then test in the same coordinated session **if** independent failures and exact per-environment identity can be observed | Shared acceptance planning; distinct side effects still verified |
| New underlying persistence model that several features depend on | Validate the contract before building all dependents | Otherwise one defect can spoil the entire batch |
| Deletes, overwrites, migration, authentication, uncertain remote mutation, signing, JNI memory boundary | Early focused positive **and negative** proof before high-risk expansion; still share later integrated testing | Delayed discovery can multiply damage or rework |
| Flaky UI automation with expensive emulator setup | Gather diagnostics and fix harness where justified; group compatible journeys into a common installed session | Avoid repeated boots without hiding product failures |
| Docs-only or CI-selector-only change | Relevant static/selector proof, no automatic device matrix | Expensive coverage adds no new evidence |

**Counterexample:** A six-feature batch that all rely on an unverified storage migration is a poor batch. A six-feature batch of independent Compose affordances with a single installed screenshot atlas is often excellent.

## Simple economic check

Let **S** be a costly common setup, **Vᵢ** the marginal verification cost for item i, **D** expected added diagnosis/rework due to deferring feedback, and **R** the incremental risk cost. Serial runs that repeat setup cost approximately `nS + ΣVᵢ`. A consolidated session costs approximately `S + ΣVᵢ + D + R` (plus any necessary focused gates). The consolidation helps only insofar as saved setups exceed added diagnosis and risk. These are conceptual estimates, not a promise that one batch always wins.

Also consider wall-clock latency versus total runner minutes: parallel jobs may shorten elapsed time while increasing compute usage. Measure both, plus human interactions, test reboots, red reruns, cycle time to accepted features, and escaped defects. Keep a short run ledger, and adjust policy using observed outcomes.

## Required manifest for a shared acceptance session

Use existing PR/issue/queue infrastructure; do not introduce another tracking system by default:

```text
Batch ID / base SHA / integrated head SHA / owner
Items: ID | intended behavior | changed entrypoint | risk | expected independent oracle
Shared setup: device/API, installed package, server/provider, fixtures, auth scope
Focused early gates: check | dependency blocked until green | result/SHA
Shared checks: check | covered item IDs | exact assertion / negative control | evidence tier
Interactions: item pairs or state transitions actually exercised
Failures: first red | last executed assertion | class | suspected owning change | follow-up
Disposition: per-item verified tier, missing proof, merge/release blockers
```

## Safeguards and falsification

- An aggregated green job does **not** imply every item was exercised. Require executed counts and coverage by item ID. Before dispatch, validate that the selected workflow *can reach* the changed code and selected mode (e.g., a Windows smoke not enabling a research flag cannot verify the research oracle).
- A real fixture that is already complete may make a fresh-download test vacuous. Check relevant initial counters/state and genuine observed delta, not just final byte parity.
- An earlier green on an old head does **not** prove a changed behavior on current head. Use impact selection and retest affected dependency closure.
- The human director should not become the default test harness. Automate replayable installed journeys, stable fixtures and objective oracles; reserve human review for perception, tactile usability and genuinely unautomatable decisions.
- Do not downgrade data-preservation, remote-side-effect or security acceptance merely to save CI. Batch the **setup**, not the correctness obligations.
- One failure can contaminate a later shared session. Reset fixtures between dependent journeys and preserve unmodified originals. Put per-subjourney deadlines and lifecycle milestones into any shared long-running script; one silent blocked reboot must not consume the entire batch budget. Explicitly distinguish shared session from shared mutable state.
- On a failure that invalidates subsequent evidence, stop that dependent portion, keep independently runnable work going, and preserve valid unrelated results.
- A measured increase in diagnosis time, escapes, conflicting edits, flaky reruns or owner testing reverses the batching choice; record the counterexample and change the partitioning rule.

## Positive and negative controls for skill evaluation

**Positive:** Six independent Compose UI changes share an install and 320dp/412dp screenshot/accessibility sweep; cheap compile checks catch syntax errors. The agent batches the installed checks, retains six assertion receipts and does not demand six emulator boots.

**Negative:** Six workflows all require a new persistence migration to preserve payloads during removal. The agent first proves preservation and interruption on the migration contract; it does not defer that foundational gate until all six dependent features exist.

**Mixed:** Local and remote deletion have distinct authorization, identities and irreversible effects. The agent may implement both before a shared session, but checks exact local retention and remote postconditions independently, includes failure/retry uncertainty, and never interprets one adapter's success as another's.

**Independent oracle:** Compare exact test dispatches, run timestamps, runner/device setup counts, elapsed minutes, changes-to-accepted-journey, failure localization and escaped defects against the previous baseline. A prose plan or green CI badge alone is insufficient proof of improvement.
