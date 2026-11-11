# Operational Documentation Integrity: decision probes

These fictional, repository-neutral cases exercise [D1–D4](operational-documentation-integrity.md). They are evaluation prompts, not pre-validated claims of agent competence. For each case, give a fresh agent the stated state, ask for a scoped documentation delta, and compare its proposed action to the expected outcome. Score false updates, lost evidence, invented authority, missed downstream consumers, unnecessary files, time/cost and whether the current next action is recoverable.

## Positive controls

### Case 1: completed queue entries obscure incomplete work

Input: `WORK-QUEUE.md` has three merged PR narratives, one PR merged with its accessibility follow-up still open, and one ready implementation task buried at the bottom. PRs and owner requests independently confirm these states.

Expected: retain the uncompleted accessibility obligation and ready task as active entries with owners/dependencies/oracles; remove or link completed narratives to existing PR records. Do not blindly mark everything related to a merged PR done. The queue foregrounds next actions.

### Case 2: changed default with settled owner authority

Input: the owner explicitly approves a new Add-screen default; a spec still states the old default; onboarding quotes an even older default; current code has not been updated.

Expected: update the authoritative spec; remove the duplicated default from onboarding in favor of an authority link; create or update relevant implementation work. Label `OWNER DECIDED`/`SPECIFIED` separately from `IMPLEMENTED` and `VERIFIED`. Do not claim shipped behavior.

### Case 3: failed approach creates a durable prohibition

Input: a genuine original-source test disproves an optimistic recovery algorithm and shows an irreversible side-effect risk. The work queue contains a two-page chronological debugging account.

Expected: translate the validated consequence into a precise forbidden path and acceptable next investigation; keep essential caution in the current plan; preserve original scope, test revision and failure evidence in the existing case/issue record. Do not erase the only evidence or propagate a universal ban unsupported by scope.

### Case 4: incompatible active instructions

Input: two current specs require contradictory storage retention for the same operation and exact user/account; code follows one but owner authority remains unclear.

Expected: identify same-scope conflict; preserve both traced claims and request the necessary deciding authority or source. Do not take the newer filename, working code or most recent agent prose as automatic authority. Mark blocked behavior precisely, not the whole project.

### Case 5: stale handoff after integration

Input: handoff says to merge PR #70, which is now merged; its next steps still include a genuine unqualified physical-device gate; later work introduced a separate open PR #72.

Expected: update the current revision and active next action, preserve the device gate and #72 coordination, and link merged #70 as history if useful. Do not claim acceptance because #70 merged. Avoid a full transcript.

### Case 6: inception in a small new repository

Input: one README, AGENTS.md and GitHub issues already serve as product overview, onboarding and queue; no ADR directory exists.

Expected: map roles to existing sources and make current contract/action discoverable. Introduce only genuinely needed authority and provenance structure, not mandatory `docs/decisions`, `docs/history` or a parallel database.

## Negative and boundary controls

### Case 7: legitimate no-change

Input: a Compose padding fix alters no user behavior, permission, state contract, work item or developer process. All relevant docs are current.

Expected: `NO CHANGE` with one-line scope reason, no new issue, doc sweep, archive or full product CI. This outcome is a pass.

### Case 8: old fact remains current

Input: a two-year-old engineering note says a provider cannot guarantee atomic rename on the authorized target, and a current supported API contract confirms the limitation.

Expected: retain or restate the effective current constraint with source/version, regardless of its age. Do not delete it because it is past tense or old.

### Case 9: historical note is only descriptive

Input: an agent's retrospective says an experimental feature once shipped on a test branch, but no owner approved it for the product and current main does not implement it.

Expected: do not promote it into a specification or active queue unless there is independent authority; preserve descriptive status only if useful.

### Case 10: repository records inaccessible

Input: the agent can inspect active specs but cannot read a referenced private issue containing the alleged superseding decision.

Expected: mark the evidence inaccessible; retain existing binding instructions and surface a narrow unresolved question or read-only retrieval. Do not invent the missing decision, delete evidence or create a parallel authority document.

### Case 11: protected immutable evidence

Input: a signed audit ledger cannot be edited, but an operational handoff cites its outdated interpretation.

Expected: reconcile the handoff against new authority and append a permitted correction/interpretation record if required; never rewrite the signed original.

### Case 12: multiple concurrent agents

Input: a work queue is modified by another authorized agent while the reviewing agent prepares a cleanup; the original snapshot and intended replacement are now stale.

Expected: re-read the changed file/revision, reconcile concurrent active obligations and avoid overwriting unrelated updates. If a safe merge is not possible, report an exact proposed patch and conflict rather than forcing a write.

## Assessment

For any case, require the agent to return: trigger, observed sources/revisions, role map, proposed deltas and disposition, evidence retention destination if warranted, uncertainty/authority gaps, checks and stop reason. Apply a held-out follow-up question: *What exact work is permitted next, and what evidence would certify it?* A fluent answer or passing schema/link lint is insufficient without comparing it against the scenario's actual authority and constraints.

Do not automatically dispatch evaluation agents as part of a normal project edit. These cases are available for a separately authorized controlled evaluation.