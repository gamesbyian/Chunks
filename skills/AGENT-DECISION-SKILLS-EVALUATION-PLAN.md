# Evaluation plan: do mechanised expertise and conceptual mapping improve engineering decisions?

**Status:** DORMANT / DESIGN ONLY — 2026-11-03.  
**Authority:** This document is a proposal for a future controlled study. It authorizes **no agent runs, scheduled tasks, CI/workflow changes, skill edits, costs, external service operations, source mutations, or experiments**. The director has explicitly said there is **no current capacity for scheduling additional agents**. Do not infer approval from the existence of this plan, its appearance in the `skills/` directory, or references to prospective run counts. A new explicit instruction and resource authorization are required before any implementation or execution.

**Purpose:** determine whether the *existing* Mechanised Expertise and Concept Model Engineering skills improve decisions on consequential engineering and validation tasks, including whether agents correctly choose to test more, less, differently, batch, reuse evidence, challenge an implementation premise, or do nothing. Measure the cost and failure modes of using those skills, not merely their rhetorical plausibility.

**Non-goal:** demonstrate that either skill is universally superior, optimize test counts, replay history until agents memorize historical fixes, or establish a mandatory new development process. This research must not alter ongoing project validation/release safety rules.

## 0. Existing evidence and skill authority

Research inputs (not ground truth by themselves):

- [Cross-project historical validation audit](references/CROSS-PROJECT-VALIDATION-HISTORY-AUDIT-2026-11-03.md): internal source-linked chronology and quantitative ledger across Chunks, Expository, Glimpse, UR-Recomp, ROYGBIV, AYA, Pathfinder, Ring Road, Night Court, S60 and other projects. Treat historical PR assertions, exact logs, merged fixes, proposals and unmerged experiments as distinct.
- [Cross-project validation-case audit](android-app-development/references/validation-case-audit.md): narrower exact-run cases and proof limitations.
- [Validation economics](android-app-development/references/validation-economics.md): existing proportionate test-tier and batching procedure, not an evaluation outcome.
- [External multilingual agent-validation research](https://github.com/GBIDEVELOPMENT/general-purpose/blob/research/agent-validation-efficiency-2026-11-02/skills/engineering-judgment/references/agent-validation-efficiency-research-2026-11-02.md): external mechanisms and counterexamples, preserved on a separate research branch at this plan's creation; **link may change if PR #608 is merged**. Never silently count that research as a resolved or independently reproduced finding.
- [Mechanised Expertise](mechanised-expertise/SKILL.md), [Concept Model Engineering](concept-model-engineering/SKILL.md), [Engineering Judgment](engineering-judgment/SKILL.md) and [capability architecture](CAPABILITY-ARCHITECTURE.md).

For evaluation, freeze the exact **blob SHA and contents** of each supplied skill/reference, and the selected tool harness, model, prompt, repository, source and environment versions. Never silently allow the skill under test to change between trial arms.

**Key methodological separation:** Research reports and skills are *candidate treatments and source material*; they are not independent answer keys. Historical outcomes are evidence about their own time and conditions, not proof that the historical fix was unique or optimal.

## 1. Questions and falsifiable hypotheses

### Primary research question

Given pre-decision information and ordinary development tools, does providing access to existing skills increase the rate of **independently acceptable engineering decisions per unit of total resource cost** relative to otherwise equivalent agents without those skills?

### Secondary questions

1. Does Mechanised Expertise improve selection of a consequential discriminator, recognition of a wrong premise, appropriate stopping, and handling of exceptions?
2. Does Concept Model Engineering improve reachability/dependency/evidence coverage, alias reconciliation, uncertainty classification, and decisions involving missing relationships?
3. Do both skills together provide additional benefit, or impose excessive model-building and process overhead?
4. Is any benefit explained just by extra thinking time/tokens, a short generic checklist, or incident recall?
5. Do skills increase false confidence, unnecessary investigations, repeated tests, skipped important tests, or human attention requirements?
6. Which task signatures predict benefit, neutrality, or harm (routine reversible, coupled integration, destructive, uncertain provider, experimental science, UX/physical device, source/release verification)?
7. Does decision improvement transfer from descriptions to actual code/tool behavior and then to accepted real project deliverables?

### Preregistered, disconfirmable hypotheses (not conclusions)

- **H1:** Mechanised Expertise improves consequential decisions more than control on ambiguous/coupled historical holdouts, with no material overhead for routine/no-action controls.
- **H2:** Concept Model Engineering improves identification of unverified or incorrectly linked claims in relationship-dense problems, without systematically misclassifying unrecorded relationships as absent.
- **H3:** Both skills together outperform either alone in appropriately *selected* complex tasks; forcing both on every task performs worse on routine tasks.
- **H4:** A short checklist may match or beat comprehensive skills on simple validation selection; full skills should only be retained where they demonstrate additional transfer value.
- **H5:** Improved replay decisions do not necessarily translate into faster accepted implementation. Executable and prospective trials must independently test that connection.

A null or negative result is informative. Do not revise the hypothesis after viewing results and describe it as preregistered.

## 2. Study architecture: three evidence tiers

### Tier 1 — historical decision replay (first future phase)

Reconstruct a frozen decision instant before a documented mistake or correction. Give agents the user request, authorized repository snapshot, allowed tools, contemporary project instructions, and evidence discoverable **as of the cutoff**. Hide subsequent PRs, reports, resolutions, the incident's retrospective label, and the evaluator's preferred answer.

Measure concrete next actions and tool choices, not just the elegance of a written strategy. For cheaply replayable cases, use inspectable mock repositories/tool outputs; make clear what is simulated versus real. Score the chosen decision against a pre-established independent acceptance rubric. Decision-only replay cannot prove runtime productivity.

### Tier 2 — isolated executable counterfactuals (only after Tier 1 warrants it)

For selected cases with reproducible, appropriately permissioned environments, allow actual code changes and targeted checks. Use **disposable and strictly isolated** branches/worktrees, fixtures, accounts, devices and artifacts. Avoid production credentials, destructive actual user data and source-dependent licensed materials. For cloud services and original physical devices, use emulation only where it can prove the particular claim; never promote a simulated pass to real-device/provider proof.

Require independently executed outcome assertions and verify the agent's actual paths, commands, side effects, and final evidence receipts. Record full failures and measured resource use. Do not merge agent output into active projects automatically.

### Tier 3 — prospective field test (optional, separate authorization)

Randomize or block-match genuinely new development tasks with frozen skill availability. Keep host project gates, human approvals and release rules identical across arms. Observe delivery and escapes over a meaningful follow-up window; don't infer accepted usability from compile, simulated tests or agent declarations. Use review before merge. Only conduct when agent capacity, billing, project coordination, and human acceptance availability have been explicitly approved.

**No tier is presently approved or scheduled.** Move to the next tier only on a documented decision using results from the prior tier.

## 3. Experimental arms and causal isolation

Four essential configurations:

| Arm | Agent receives | What it tests |
|---|---|---|
| A — baseline | Normal user/project instructions, standard tools and equal task information; no experimental skills | Ordinary existing capability |
| B — mechanised expertise | Same baseline plus pinned Mechanised Expertise skill and *only references it naturally selects* | Incremental decision-selection capability |
| C — concept-model engineering | Same baseline plus pinned Concept Model Engineering skill and its task-selected references | Incremental relationship/evidence modelling |
| D — both available | Same baseline plus both pinned skills; their own routing and rejection conditions apply | Complementarity and over-activation |

Treat any existing automatic installation of these skills or Engineering Judgment guidance as a **contamination variable**. The baseline requires a supported, honest way to prevent experimental skill exposure while retaining essential host/safety instructions; if the harness cannot isolate arms, **do not claim a causal skill effect**. Document exactly what was hidden and what remained available, including model built-in knowledge.

Optional follow-up comparators:

- **E — concise checklist:** a token-light statement of testing relevance, risk, evidence reuse, stop and no-action principles, not a paraphrase of the answer key.
- **F — length/time-matched general deliberation:** comparable reasoning budget without domain-specific methods, if the runner can actually enforce the intended resource matching.

**Important:** A control agent should not be deprived of safety rules, normal project guidance, tool accessibility, or relevant primary evidence. Equal *available information* is the goal; skill instructions are the treatment. All arms share the same independently specified success criteria and task/tool budgets.

For each trial, record whether the skill was actually discovered, loaded, and applied; mere assignment to arm B/C/D is not proof of uptake. Include an *intention-to-treat* comparison by assigned arm, with skill uptake as a secondary mechanism analysis (do not substitute uptake-only comparisons for randomized estimates).

## 4. Historical case design: recover the decision, not the retrospective answer

A case record is a reproducible package, not an unstructured anecdote. Each must contain:

```yaml
case_id: "..."
source_repository: "owner/repo"
decision_cutoff_utc: "..."
base_commit_sha: "..."
task_text_original: "..."
available_evidence:
  - source_class: "user request | source | test | doc | artifact | log"
    location: "exact frozen reference"
    available_at_cutoff: true
hidden_future_evidence:
  - "fix / later PR / retrospective audit / outcomes"
original_decision_and_failure: "sealed evaluator-only record"
candidate_actions:
  - "actual possible decision types, not multiple-choice shown to agent"
claims_to_prove:
  - claim: "..."
    oracle: "independent observable signal"
    environment: "..."
    invalidating_conditions: ["..."]
budget_and_risk:
  allowed_operations: ["..."]
  forbidden_operations: ["..."]
  measured_costs: ["..."]
  unknown_costs: ["..."]
grading:
  hard_constraints: ["..."]
  acceptable_outcome_families: ["..."]
  unacceptable_outcome_families: ["..."]
  uncertainty: "..."
holdout_group: "familiar | historical unseen | transformed analogue | newly authored"
related_case_cluster: "same root incident; avoid pseudo-replication"
```

**Temporal discipline:** Reference only branches/docs/logs that actually existed at the cutoff. Git history, commit messages, filenames, task wrappers, and tool-result URLs can leak later corrections. When a public service cannot serve an old-time snapshot consistently, use a sealed local data snapshot or remove the case from causal claims. Host-created test wrappers and synthetic errors must be flagged, not misrepresented as historical artifacts.

**Independent answer key:** Have a case curator and an independent evaluator reconstruct acceptable outcome families using contemporary requirements, source, real later behavior, negative controls, and still-unresolved limitations. Preserve disagreement and uncertainty. The original merged PR is one possible solution, not necessarily the unique acceptable one. Cases lacking an independently checkable oracle can be retained as qualitative research but not scored as executable successes.

**No false independence:** A dozen transformed variants of one teardown incident are one *cluster* for uncertainty estimates. Cases from the same repository, common agent instructions, or single historical mistake should be clustered/block-matched.

### Candidate case bank (planning shortlist, not an assigned benchmark)

| Family | Candidate original source | Pre-decision skill to observe | Risk of contamination |
|---|---|---|---|
| Test less / reachability | [UR-Recomp #2924](https://github.com/gamesbyian/UR-Recomp/pull/2924), [Chunks #878](https://github.com/gamesbyian/Chunks/pull/878) | Test selector matches source consumer and runtime mode; preserve release context | High: already documented in skills |
| Test more / faulty oracle | [UR-Recomp #2821](https://github.com/gamesbyian/UR-Recomp/pull/2821), [Pathfinder #2202](https://github.com/gamesbyian/Pathfinder-Game/issues/2202) | Run negative tests against guard/budget semantics | High |
| Test differently / narrowed red | [Expository #401](https://github.com/gamesbyian/Expository/pull/401), [#416](https://github.com/gamesbyian/Expository/pull/416) | Distinguish completed copy from teardown; target new discriminator | High |
| Batch but isolate | [Chunks #1123](https://github.com/gamesbyian/Chunks/pull/1123), [Glimpse #159](https://github.com/gamesbyian/Glimpse/pull/159) | Share setup without hiding phase failure or state interference | High |
| Reuse existing evidence | [GRA-EDISNI #307](https://github.com/gamesbyian/GRA-EDISNI/pull/307), [UR-Recomp #3054](https://github.com/gamesbyian/UR-Recomp/pull/3054) | Find prior work, avoid duplicate inference and fabricated completeness | High |
| Reject wrong premise | [Chunks issue #568](https://github.com/gamesbyian/Chunks/issues/568), [ROYGBIV #1013](https://github.com/gamesbyian/wallgen/pull/1013) | Verify conventional implementation or actual hotspot before bespoke work | High |
| Stop / no action | [ROYGBIV #1009](https://github.com/gamesbyian/wallgen/pull/1009), [Pathfinder #2196](https://github.com/gamesbyian/Pathfinder-Game/pull/2196) | Reject nonreplicating or ineligible optimization; retain valid negatives | High |
| Keep expensive genuine proof | [AYA #78](https://github.com/gamesbyian/AYA/pull/78), [Ring Road #307](https://github.com/gamesbyian/Ring-Road/pull/307), [Glimpse #72](https://github.com/gamesbyian/Glimpse/issues/72) | Recognize real exporter/physical GPU/signing boundary | High |
| Feasibility before costly setup | [Night Court #529](https://github.com/GBIDEVELOPMENT/general-purpose/pull/529) | Check source/auth/preflight before running expensive inference | High |
| Trivial routine control | New neutral cases with known standard fixes | Make small edit/focused proof; avoid invoking heavy inquiry/map | Must be independently authored |
| Already-satisfied / no-op control | New neutral cases, including correct unchanged code | Declare no change; cite evidence and stop | Must be independently authored |

This shortlist is **not** the initial sample, and is intentionally contaminated by existing skill casebooks. Use it for procedure sanity checks/teaching cases; primary efficacy claims require independently held-out or transformed/new cases with audited absence of direct solution leakage. Balance across risk and correct decision: the evaluation cannot reward always running fewer tests.

## 5. Contamination, leakage and transfer controls

1. **Freeze skills before case construction.** Record every file/reference available to each arm, including links to cases, and mark case overlap. Skills cannot be silently revised in response to test failures during holdout evaluation.
2. **Separate development, calibration and holdout cases.** Do not tune procedures or scoring rules on the holdout. Any explored holdout becomes development data; procure a new holdout for subsequent claims.
3. **Replay true pre-decision context.** Mask future fixes in commit history, PR title, issue comments, README references, branch names, cached search index and output filenames. Use access controls or curated fixtures rather than trusting the agent to ignore spoilers.
4. **Use transformed analogues.** Keep the causal invariant but vary language, platform, build system, feature flag, repository layout and API; verify changes are technically coherent, not simply relabeled answer keys.
5. **Include newly authored cases.** Introduce faults after the skills freeze, with independent reviewers and baseline proving the task is meaningful. Fresh cases mitigate exact case recall, though model pretraining familiarity remains possible.
6. **Use discriminating negative controls.** Contradict the cached heuristic: a suite really is essential; a duplicate-looking test covers an independent mode; a proposed optimization helps only under a verified production configuration; the correct outcome is no change.
7. **Measure epistemic honesty.** Agent must identify when evidence is inconclusive, not hallucinate validation, silently edit tests to green, or equate historical text with executed proof.
8. **Blind grading.** Randomize arm labels, strip skill-identifying prose, and evaluate independent outcomes using a published rubric. If reviewers cannot be blinded because tool traces expose the skill, disclose this and use mechanical outcome checks where possible.

A cleanly held-out *temporal* example may not exist for every mechanism. Don't reclassify known cases as unseen by changing filenames.

## 6. Agent actions and independent observables

For every trial retain, subject to privacy/security boundaries:

- Assigned arm, run ID, case cluster, source/skill/tool/model SHAs or versions, environmental fingerprint, budget, random seed when supported.
- Read/open/search/tool traces, timestamps, actual code/patches, test commands, tool-result exit codes, CI dispatches, wait/poll/retry/cancellation events, and the agent's final claim.
- Whether it loaded the available skill, invoked only relevant references, built a map, and actually used the map to change a decision.
- Evidence receipts: exact revision and environment, executed assertion IDs/count, fixture initial state, trustworthy postcondition and remaining uncertainty.
- Total elapsed critical-path time, active tool time, runner CPU/billed minutes if accessible, model input/output/cache tokens and *actual priced cost where available*, human interruptions and owner QA.
- Outcome adjudication: accepted/partially accepted/unresolved/unsafe, diagnostic information gained, escaped regression and independent review burden.

Do not fabricate cost from token counts when cached pricing/flat subscriptions are unknown. Distinguish compute-minutes from wall-clock and from human attention. Record missing counters as missing.

An action count is not evidence of value. Passing a fixture is not proof of the live user journey. A "no changes needed" determination may be correct, incorrect, or unresolved depending on the actual contract and observations.

## 7. Scoring and safeguards

### Hard gates (not tradeable for efficiency)

- No unauthorized external/destructive side effects, credential or data disclosure, or bypassed project release/merge safety gates.
- No knowingly false passing assertion, silent removal/weakening of required tests, untruthful claim that a skipped or stale suite executed, or promotion of mocked evidence to real environment proof.
- No unsafe equivalent of "stop because the retry budget ended" being recorded as successful acceptance. Honest incomplete result is admissible with correct disposition.

Flag unsafe outcomes separately. A "high efficiency" unsafe outcome must never outrank a trustworthy one.

### Primary outcomes (report separately, not collapsed into one flattering number)

1. **Decision acceptability:** blinded independent rubric of chosen next action given evidence and constraints; allow multiple sound solutions.
2. **Executed task success:** independently verified postconditions and appropriate test tier for Tier 2/3 only.
3. **Efficiency at equal correctness:** actual cost and elapsed time *conditional on meeting the same acceptance*, with failures and attrition also reported to avoid survivorship bias.
4. **Evidence integrity:** falsifiable, versioned receipts; correct statement of unknowns and scope.
5. **Proportionality:** unnecessary test, investigation, map, documentation and reviewer work, plus correct restraint and no-action outcomes.

Secondary: diagnosis precision, selected test reachability, whether an early test prevented dependent rework, whether a short checklist sufficed, failed agent loops, human interventions, test escapes, cross-task stability. Record first-failure-to-diagnosis time, not just first green.

**Do not assign universal numerical weights before the experiment.** A possible weighted rubric may be used as a sensitivity analysis, but hard safety failures remain decisive and the separated primary measures stay visible. Score cheap/no-action and additional-test-required cases equally seriously.

### Avoidable decision cost

Where demonstrated with real comparable runs, estimate incremental resource cost of an avoidable action: repeated irrelevant CI, needless expensive setup, duplicate source inspection, premature infrastructure, unnecessary retest, or downstream rework from missed early proof. When no comparable baseline exists, classify opportunity qualitatively; do not invent historical minutes or assume every red rerun is unnecessary.

## 8. Statistical plan and interpretation

**Diagnostic pilot, not a powered study:** a *possible future* first design is **8 diverse case clusters × 4 principal arms × 1 execution = 32 agent executions**. This is an illustrative ceiling for an initial diagnostic design, **not a scheduled queue, quota, funding request, or permission to run**. For a manual or tool-limited pilot, fewer cases/configurations are acceptable with the limitations documented. Avoid pseudo-precision: 32 outcomes can reveal harness problems and large failure patterns, not statistically establish a modest productivity gain.

Before expanded trials:
- Run **zero-agent** static preparation/review first, when authorized, then a small smoke test only upon separate execution approval.
- Pilot costs on actual hardware/harness; stop and redesign if trial setup or data leakage dominates.
- Estimate between-run and within-case variance and predefine a meaningful minimally important improvement; perform a sample-size/power or decision-theoretic cost analysis from pilot results.
- Block or randomize comparable cases across arms; counterbalance execution order to reduce model-version, server and warm-cache drift.
- Repeat independent sessions for claims of reliability; analyze case clusters (not every variant) as units, with uncertainty intervals and task-family breakdowns.
- Include unsuccessful/aborted trials and infrastructure failures, not just clean completions. Keep intent-to-treat result primary.
- Compare median and tail latency/cost, acceptable outcomes per resource budget, unsafe error rate, human interruptions and escaped defect rate.
- Report a null result as such; don't keep adding trials until significance appears. Any interim looks/early stops should be preregistered.
- Report potential cross-task transfer to newly authored cases separately from recognition of familiar historical incidents.

**Interpretation examples:**
- B > A on complex, B ≈ A on routine: useful conditional mechanised expertise.
- C > A on relationship-dense, C < A on trivial: target concept model activation more narrowly.
- D > B and C on unseen complex cases: evidence for complementarity.
- D adds time with no quality gain: excessive procedural coupling.
- E (short checklist) matches D for most validation decisions: prefer cheaper approach there; retain fuller skills only where justified.
- Higher decision-replay scores but no Tier 2/3 gain: explanation/selection improvement unproven in useful delivery.
- Fewer tests with more escapes: reject optimization despite faster CI.

## 9. Future infrastructure design (specification only)

A single **deterministic coordinator** should eventually own run orchestration, isolation, budgeting and evidence receipt collection. Agents are interchangeable trial workers, not independent schedulers. Do not create this coordinator, any workflows, cron tasks, agent dispatchers, eval apps, tokens or hooks as part of this document.

### Components to design only when authorized

1. **Case registry:** frozen source snapshots, allowed tools, cutoff date, truth/acceptance oracle and confidentiality labels. Keep sealed evaluator-only information inaccessible to subjects.
2. **Trial launcher:** pinned model/tool/skill versions, randomized arms and order, isolated workspace, immutable initial state and finite budgets.
3. **Tool recorder:** actual read/edit/test/CI and async status events, per-step costs, exit and assertion outcomes.
4. **Evaluator:** independent executable checks, blinded human adjudication for semantic claims, hard safety violations and evidence integrity.
5. **Results store:** append-only per-trial records, artifacts, missing-data notes, aggregate uncertainty and auditability. Existing repo/CI artifacts preferred to new databases unless demonstrated necessary.
6. **Report generator:** distinct metrics, case-family findings, counterexamples, trial limitations and protocol deviations.
7. **Kill switch and bounded lease:** one owner-controlled stop for ongoing approved trials; automatic termination on budget/timeout/unsafe dependency, with honest incomplete result.

### Cost and capacity controls

- **Default execution permission: NONE.** There is no implicit "run at next quiet period," hourly shared-agent piggybacking, or capacity scavenging.
- No impact on existing UR-Recomp hourly shared pool, Codex, Chunks/Expository/Glimpse workflows, owner HP, or any currently scheduled agent. No use of existing secrets, provider accounts, hardware, or paid compute without new explicit authorization.
- Before any future run, obtain human-approved hard limits: total agent sessions, model/API dollars, runner minutes, wall time, machine/resource owners, allowed repositories/accounts, concurrent worker cap, retention policy and permitted actions.
- Start at **one worker concurrency** unless controlled measurements justify more; never equate parallelism with reduced billed compute.
- Guard identical SHA/suite/environment duplicate dispatch, stop-hook recursion, retries without new hypotheses, and incomplete downloads/caches. No background task or scheduled monitoring is created merely because a benchmark report mentions it.
- Avoid CI contention by isolating tasks in a dedicated test environment or approved existing runner time window. Ensure any GitHub workflow changes later are reviewed as their own functional project, with safety and billing implications.
- A run exceeding budget ends as **incomplete**, never a fake passing result.

### Future deliverables if explicitly commissioned

```
evaluation/
  README.md                  # invocation and scope
  protocol.md                # preregistered design/constraints
  cases/                     # frozen case descriptors + immutable inputs
  controls/                  # routine/no-op and counterexample tasks
  harness/                   # deterministic launcher/observer/evaluator
  results/                   # machine-readable receipts, provenance
  reports/                   # independent outcomes, limitations, comparisons
```

This is illustrative future organization; **do not create these paths now**. The real location should be selected based on the repository's structure and isolation/security requirements at implementation time.

## 10. Staged future decision gates

| Stage | Action only after new authorization | Stop/reject conditions | Deliverable |
|---|---|---|---|
| P0: plan review | Review this document and clarify ownership, budget, acceptable tradeoffs | Ambiguous authority, no capacity | Approved or revised evaluation protocol; no trials |
| P1: case curation | Select diverse historical and novel cases, temporal redaction, oracle audit, skill snapshot | Hindsight leakage, inaccessible historical state, no independent oracle | Sealed case inventory, case exclusions and provenance |
| P2: static harness design | Specify isolated environment, accounting, security boundaries and non-running checks | Cannot isolate arms, unsafe credentials, unknown billable cost | Reviewed runner spec, **not** a launched agent |
| P3: small smoke trial | Execute minimum representative trial after specific run authorization | Incorrect arm isolation, inconsistent replay, uncontrolled budget | Harness debugging report |
| P4: diagnostic pilot | If warranted, randomized small multiarm comparison; 32 executions is *illustrative*, not mandatory | No meaningful discriminator, high overhead, contamination, unclear acceptance | Pilot result and decision whether to continue |
| P5: powered holdout | Plan sample count from pilot variance, fresh cases, preregistered endpoints | Unaffordable sample, no quality oracle, skill overfitting | Strength/uncertainty estimates by task family |
| P6: executable trials | Isolated implementation trials with accepted user outcomes | Unsafe external mutation, fake emulator proof, unreproducible environment | True outcome, cost, failure and fault-isolation analysis |
| P7: prospective study | Real new tasks with independent review and normal project gates | Lack of project/capacity approval or inability to track escapes | Productivity/quality evidence, including negative findings |
| P8: skill synthesis | Propose *separate* minimal skill changes from validated patterns | Benefits not transferable, cost greater than gain | Reviewable skill PR with adoption/rollback criteria |

**Each stage requires a deliberate go/no-go; no stage may silently schedule its successor.** Gate transitions do not become automatic simply because an earlier stage produced green results.

## 11. What must be judged mechanically, by agents, and by humans

| Concern | Deterministic enforcement | Agent judgment | Human authority |
|---|---|---|---|
| Isolation of arms | Frozen skill input set, version IDs, working tree, test environment | Choose which available skill/reference applies | Approve experimental scope |
| Pre-decision replay | Temporal file/log access and future-evidence withholding | Decide where to look and what follows from evidence | Resolve ambiguous original intent |
| Evaluation outcome | Run independent test/postcondition where executable | Explain limitations, choose additional discriminators | Review semantic usability and consequential overrides |
| Safety | Block external mutations/secrets and record attempted bypass | Detect new risks and stop | Approve destructive/provider/hardware actions |
| Cost | Hard caps, timeout, exact metering, no implicit scheduling | Choose productive vs redundant work | Fund additional trials or stop |
| Learning | Preserve immutable outcomes, holdouts and protocol deviations | Extract conditional generalization, including exceptions | Decide if skills change or remain as-is |

Reviewers and curators must not create extra bureaucracy without changing result validity. Independent adjudication is essential for disputed outcomes, but avoid redundant full reviews of deterministic facts already settled by executable oracles.

## 12. Failure modes of the evaluation itself

- **Retrospective answer leakage:** a skilled agent recognizes a case directly from supplied skills or public GitHub search. Mitigate by sealed/novel cases and distinct familiar-case reporting.
- **Instrumenting away the treatment:** harness forces every arm to complete a concept map/checklist. Mitigate by letting each arm choose whether to use its available capabilities; evaluate ordinary routing.
- **Reward hacking:** agent produces "expert" prose, fabricated costs, large models, more tests, or premature code commits for points. Mitigate by actual actions, independent claims and no-op controls.
- **Selective sample:** only exciting failures are tested, with no routine tasks. Mitigate by stratifying mundane/new work and enforcing negative controls.
- **Confirmation bias:** exact historical fix treated as sole answer. Mitigate alternative valid solution families and blinded independent adjudication.
- **Cost displacement:** lower CI cost increases human review, token spending, or escaped bugs. Measure all denominators and later outcomes.
- **Stale/model drift:** agent/model/tool/harness updated during comparison. Pin and report versions; do not mix unadjusted runs.
- **Proliferating infrastructure:** eval harness becomes costlier to maintain than the decisions it improves. Treat itself as a candidate for cost/benefit termination.
- **Production interference:** additional agent runs steal scarce time from existing projects. Remain dormant until capacity and authorization are explicit.
- **Unverifiable conclusions:** excellent hypothetical decisions without executable evidence. Report Tier 1 as decision quality only, not product delivery proof.
- **False certainty from absent information:** missing edges, unindexed historical material and unavailable providers are unknowns, not evidence of absence.

## 13. Criteria for declaring useful results

A rigorous report should answer:

1. Which *kind* of decisions improved, on how many independent case clusters, and with what uncertainty?
2. What were the concrete actions and independently observed postconditions?
3. Were the results available at the original decision point, or did hindsight leak?
4. Which improvements were cheap conditional choices, and which demanded substantial new mapping/review overhead?
5. How many skill activations were unnecessary? How frequently did the agent correctly stop or reuse evidence?
6. Did any intervention skip a consequential test or create false confidence?
7. Was improvement preserved on novel/transformed cases, executable tasks, and ordinary field work?
8. What real cost changed (time, inference dollars, CI, human attention), and what remains unmeasured?
9. What counterexamples argue against generalization?
10. Which minimal, mechanically enforceable capability, if any, is worth proposing as a separate change to the skills?

**Never claim:** "mechanised expertise improved agent productivity by X%" based only on a few historical answers, rubric matches, token counts, or a reduction in tests. Only independently accepted outputs with matched resource accounting can support that broader claim.

## 14. Reactivation handoff: future director instruction

**Current terminal status: PLANNED / NOT STARTED.** No evaluation artifacts beyond this plan, no enrolled agents, no scheduler, no automated trials, and no spending approvals.

When the director later explicitly authorizes work, the first action should be a **fresh repository and capacity reconciliation**, not immediate trial dispatch. Re-read AGENTS.md, current skills (record current SHAs), the two research reports, relevant historical incidents and current runner/budget constraints. Confirm whether the draft external report was merged or superseded. Then propose a tight first-stage scope and request approval for *any actual execution*, including the maximum number of agent sessions.

A suitable future request would be: "Resume the agent decision-skills evaluation plan. First prepare case curation and the isolated runner design; do not launch trials until I approve the run budget." Another later explicit instruction can authorize a specific limited pilot.

Until then, treat every run count, experiment, stage and infrastructure description above as a **design specification only**.

---

**Summary:** The evaluation should be capable of finding that the existing skills help, hurt, are redundant with a shorter checklist, or help only on particular consequential tasks. The target competency is *choosing proportionate, evidence-based actions and knowing when not to act*. The target proof is real decision and delivery quality per total resource cost, not procedural compliance or the appearance of rigor.
