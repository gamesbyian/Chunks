---
name: decision-synthesis-closure
description: Convert complex, conflicting project evidence into a complete, concise and traceable set of human decisions, collect responses efficiently, interpret premise corrections, and reconcile authorized consequences. Use for release-blocker reviews, specification conflicts, roadmap adjudication, multi-issue owner consultations, or decisions dispersed across documents. Skip when no substantive human decision is needed.
metadata:
  version: "0.1.0"
  origin: "Original synthesis of project release-blocker and human adjudication workflows"
---

# Decision Synthesis and Closure

**Deliverable:** the smallest *complete* set of consequential questions that genuinely need human judgment, with recoverable evidence and a path from each answer to authorized action. Optimize human attention **subject to** correctness, full material coverage, independent safety constraints and owner authority. This skill is advisory and grants no permission to mutate another project.

## Trigger and rejection

Use when the owner requests an actionable audit, multi-question adjudication, release decision, specification reconciliation or batch feedback on technically complex findings. Start from the requested outcome and authoritative project documents. Do **not** invoke for a routine reversible fix, a factual report with no decisions, or matters the existing owner instructions already settle. If everything is answerable by inspection, report or execute within existing authority rather than manufacture owner questions.

## Operational procedure

1. **Scope and authority.** Identify the exact intended outcome, release target if any, acceptance criteria, project instructions, prior owner decisions, active implementation, observed tests and documentation cutoff. Mark stale, contradictory and missing sources. A queue item is not automatically a blocker; a passing test is not automatically proof of a usable feature.
2. **Harvest and classify evidence.** Read relevant specifications, code paths, work queues, issues, PRs, CI artifacts and prior decisions where accessible and proportionate. Keep a provenance ledger with `finding ID | source/revision | claim | evidence tier | affected capability | owner authority | unknowns`. Distinguish confirmed failure, credible conditional risk, unverified hypothesis, optional enhancement and already resolved issue. If exhaustive coverage cannot be established, state its boundary plainly.
3. **Consolidate by cause without hiding obligations.** For shared-failure hypotheses, execute [C1–C6 causal issue consolidation](references/causal-issue-consolidation.md) before asserting common causes. Link symptoms to underlying capabilities, causes, dependencies and acceptance checks. Merge duplicates into one decision where the same answer genuinely resolves them; retain distinct requirements and source IDs underneath. Do not merge merely because issue titles resemble each other. A simple issue-to-capability matrix usually suffices; consult [Concept Model Engineering](../concept-model-engineering/SKILL.md) only when disputed relationships make a material difference.
4. **Identify the actual decision boundary.** For each obstacle ask: Can existing evidence resolve this? Is implementation already authorized? Is it a technical investigation, reversible engineering choice, irreversible risk acceptance, product preference, or a genuine owner decision? Auto-resolve or queue work within the host project's permission boundaries. Ask the human only where different plausible answers produce materially different legitimate actions. Never delegate safety-critical verification as a preference poll.
5. **Make a human-readable decision card.** Stable ID; “We cannot [outcome] because [specific causal obstacle]”; status/confidence; one clear question only when owner authority is required; plausible choices and material consequences; default when legitimately established; source references; affected issues and acceptance checks. Prefer one or two short sentences in the visible summary. Expose technical details by reference, not by forcing the human to absorb them.
6. **Choose interaction proportionately.** One unresolved choice: ask conversationally. Several independent choices: use numbered cards with response fields and one batch submission when supported. For dependencies, stage questions rather than requiring premature answers. If interactive form controls are unavailable, present an answerable numbered text template. Stable IDs must survive reorder, grouping and later revisions; send the IDs *with* answers. Never create inert or misleading submit controls.
7. **Interpret the *speech act*, not just the words.** Classify each response as approval, rejection, new constraint, correction of a factual premise, challenge to a risk model, request for investigation, delegation, deferral or ambiguity. A remark like “make sure safe means sane” triggers re-examination of the threat model, not automatic removal of a safety condition. Check the requested change against evidence and independent constraints. Clarify only consequential unresolved ambiguity.
8. **Reconcile impact in both directions.** From every answered ID, trace back to all supported findings and forward to specifications, designs, grouped implementation work, acceptance criteria, issue status and related decisions. Preserve unaffected constraints and independent risk controls. Separate `owner decided`, `docs updated`, `implemented`, `verified` and `released`; no stage implies the next. Use authorized project tools for writes, and never treat an answer form as automatic authority to weaken security or erase data.
For authorized decisions that materially change operating instructions, use [Operational Documentation Integrity D1–D4](../references/operational-documentation-integrity.md) as the **document editing** subroutine after establishing authority: reconcile current spec/plan/queue/handoff claims in place, keep important decision rationale in existing evidence/decision records and do not promote historical commentary into requirements. This does not add an approval stage or make documentation correctness proof of implementation.

9. **Close with a verifiable receipt.** Report decisions applied, grouped work remaining, evidence and verification tier, unresolved decisions, and exact changes/links. Stop investigation when additional evidence cannot change the decision or its acceptance check. Reopen a decision if its causal premise is refuted by new evidence.

## Required outputs when activated

- **Coverage ledger:** all material findings, including those consolidated, independently resolved or excluded, with source and disposition.
- **Decision interface:** only meaningful owner choices, each with stable identifier, concise causal statement, scope and consequence.
- **Reconciliation ledger:** response ID → interpreted intent → changed premise/constraint → affected source records → authorized action → verification status.
- **Completion receipt:** what changed, what has *not* changed, what remains blocked, and any unverified claims.

## Quality checks / independent oracle

Verify against authoritative source records rather than the elegance or length of the summary. Sample each proposed blocker against the actual outcome and acceptance criterion; reverse-trace each required capability to ensure no obligation vanished during consolidation. Confirm every human question changes a possible action and every owner response has complete downstream impact mapping. Check controls: (a) no questions needed, (b) several symptoms one cause, (c) similar symptoms independent causes, (d) uncertain danger with legitimate user operation, (e) premise correction, (f) ambiguous answer, (g) unverified release claim. Measure **missed material blockers, false blockers, duplicate questions, needless owner questions, interpretation errors, lost safety constraints and human response burden**, not just card count.

**Limits:** No specific UI framework is required. A chat text box and submit button are a presentation choice, not the skill itself. An owner response can revise requirements but cannot retroactively establish observed behavior. Do not infer release readiness from resolved paperwork. Planned historical evaluations remain dormant until separately authorized; see [the existing evaluation plan](../AGENT-DECISION-SKILLS-EVALUATION-PLAN.md).

**Adjacent interaction capability:** A release-blocker audit may use a batched form, but [Human-Agent Interaction Engineering](../human-agent-interaction-engineering/SKILL.md) covers the broader situation where a human requests choices at any time, an agent offers an optional evidence-backed feature survey after research, or the exchange is an observation, critique or review. Use that skill as primary when there is no comprehensive blocker/closure task. A preference or unchecked box never implies authorization.

**Worked example:** [Release-readiness decision synthesis](examples/release-readiness.md).
