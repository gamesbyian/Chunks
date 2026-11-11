# Audit coverage, limitations and future evaluations

## Snapshot and methodology

Initial metadata enumeration through GitHub REST pages on 2026-10-31/11-01 observed:

| Repository | PR metadata rows enumerated | Separate issue records | Notes |
|---|---:|---:|---|
| gamesbyian/Chunks | 881 | 29 | PR numbers 11–910; some numbers belong to issues; live repo changes continuously |
| gamesbyian/Glimpse | 156 | 13 | PR numbers 2–169 |
| gamesbyian/Expository | 363 | 15 | PR numbers 1–378; Android work is only under android-transfer/ |

Total: **1,400 PR metadata records, 57 distinct issues** in this snapshot. These counts are **not** 1,400 reviewed diffs, 1,400 successful merges or 1,400 individually read CI histories. A first issue page contained PR objects as well; totals distinguish the REST issue objects from pull_request objects. Snapshot dates and concurrent commits mean counts may change. The historical casebook inspects selected primary issue/PR descriptions and repository ledgers in more depth.

## Pending exhaustive work

1. Capture a reproducible paginated, versioned machine-readable PR/issue inventory with number, created/closed/merged timestamps, title, labels, base/head, disposition and original links. Guard against API caps, movement during scan and missing pages; redact credentials and sensitive personal test material.
2. Read *each* relevant full diff, review thread and linked issue before asserting root cause; connect defect -> initial hypothesis -> failing attempt -> revision -> integrated exact head -> next regression. Track reverted/superseded as carefully as green.
3. Audit representative red/green/skipped/cancelled GitHub Actions runs including actual JUnit counts, artifacts, matching SHA, emulator, OS/provider and secret-scope, not just PR status. Separate synthetic and physical.
4. Build a tagged case index (category, confidence, original/corrected behavior, cost/risk, exact references, current acceptance). Weight repeated cross-repo errors and costly detours higher than isolated cosmetic work.
5. Evaluate this skill against historical PR decision points **without showing the answer**: would the agent pick the conventional API, identify source/identity early, choose a better gate and reach a positive path sooner? Run control vs skill prompts on matched scenarios; score correctness, regressions, risk, wall time, CI minutes and documentation overhead.
6. Pilot in one *real new task* for each Android repo and record whether instructions were actually loaded, which decisions changed, exact accepted behavior, cost and side effects. Add pin/drift checks only after measured benefit. Do not prematurely install changes across repos as part of this private knowledge-base PR.

## Minimum counterfactual regression tests

- Chunks #568 vs #809: can the agent avoid both blanket non-clobber and unreviewed destructive publication?
- Chunks #891 vs #898: can the agent identify the production caller still missing auth challenge evidence after test-only success?
- Glimpse #141/#143: can it avoid app-private overwrites *and* design for interrupted CREATE_NEW partial file?
- Glimpse #72/#149: can it distinguish temporary same-run signing from true cross-run owner upgrade?
- Expository #222/#292: can it recognize a picker-locator failure as pre-operation without gutting the OS-picked journey?
- Expository #366/#367: does it cover rename-out when GitHub skips the entire workflow due to top-level filters?
- Glimpse #166/#168: does it avoid auto-closing an unfinished issue?

## Maintenance rules

Keep source links, no confidential data, explicit provenance labels and brief decision rules. Revise the main SKILL.md only when case evidence demonstrates a generalizable intervention. Do not turn the skill into an enormous work queue or a substitute for the three projects' current specs. Changes need a structural/link check, adversarial counterexample review and tagged version. No external skill code is executed automatically.

## Reproduction utility added after initial survey

The read-only [snapshot_history.py](../scripts/snapshot_history.py) script enumerates PR/issue metadata through paginated GitHub API calls and records whether main changed during enumeration. Its offline [unit tests](../tests/test_snapshot_history.py) exercise pagination, duplicate refusal, PR-versus-issue filtering, the body-omission boundary and atomic local output. This enables a repeatable future inventory; it does not analyze full diffs, reviews or CI.

## Operational evaluation added in v0.3

The skill now includes an offline 16-scenario [decision regression corpus](../assets/decision-regressions.json), an [emitter/scorer](../scripts/evaluate_decisions.py) and [evaluation protocol](skill-evaluation.md). This is a smoke test for **decision fidelity**, not an independent blind study or an assessment of actual code correctness. Historical source pointers remain essential and exact-head real Android behavior must still be independently tested.
