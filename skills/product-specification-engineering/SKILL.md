---
name: product-specification-engineering
description: Compile researched user goals, accepted decisions, concept relations and qualified reusable technology into traceable, complete product and architecture contracts before substantial development. Audit states, variants, interfaces, ownership, permissions, failure paths and acceptance. Skip routine changes with settled contracts.
metadata:
  version: "0.1.0"
  status: "editorial procedure; effectiveness unmeasured"
---

# Product Specification Engineering: construct a contract, do not just declare one

**Job:** turn source-backed intentions and solution evidence into a coherent specification that an implementation agent can execute without silently inventing product decisions. This skill applies to complex product inception, major redesign and unfamiliar feature systems. It is not a universal requirement before bug fixes. No skill grants product or external-data mutation authority.

## Inputs and trigger

Start with owner goals/limits, current source and approved contracts, [Evidence Acquisition](../evidence-acquisition/SKILL.md) results, [Engineering Resource Discovery](../engineering-resource-discovery/SKILL.md) dispositions, and any [Concept Model](../concept-model-engineering/SKILL.md) or current screen/architecture. Missing inputs remain explicitly missing. Use this skill if more than one consequential user journey, state owner, interface or option needs agreement before broad implementation. Otherwise extend the existing specification locally.

## S1–S9 specification compilation

Apply the [contract construction protocol](references/contract-construction.md) and the [hypothetical Retrofit example](examples/retrofit-specification-slice.md). The artifacts can be sections in existing project docs; no mandatory database or duplicate authority.

1. **S1. Pin authority and scope.** Recover current source/PRs/owner decisions and label each statement CONFIRMED, DOCUMENTED BEHAVIOR, OBSERVED RESULT, PROPOSED, INFERRED, DEFERRED, CONFLICTED or UNKNOWN. Record exact source, version and affected actors. Reject unsourced features disguised as requirements.
2. **S2. Convert jobs to atomic user outcomes.** For each actor and goal, write entry/precondition, trigger, one observable success postcondition and explicit non-goals. Split compound sentences where different pieces can pass/fail independently. Trace each required outcome back to owner authority or user research; preferences are not permission for unapproved side effects.
3. **S3. Enumerate journeys and variant axes.** Write normal start-to-finish journey and paths for first-use/empty/loading/error/permission denied/cancel/retry/restart/undo/accessibility as reachable. Cross with relevant actors, accounts, modes, platforms, network states and data provenance. Use pairwise/risk-selected combinations rather than cartesian explosion; for each excluded variant record why it cannot change behavior. Preserve an exit route from any nonterminal state.
4. **S4. Define feature states and controls.** For each visible operation specify when control appears/enables, what exact object/identity it affects, user confirmation, progress/error/uncertainty feedback, draft persistence, allowed reversal, default, optional alternative and resulting navigation. Mark unknown product-value choices for [Human-Agent Interaction Engineering](../human-agent-interaction-engineering/SKILL.md), permitting deferral where independent work can proceed.
5. **S5. Define data and integration contracts.** Name each entity, stable identity, source-of-truth owner, storage lifetime, format, privacy classification, permission, migration and external sharing boundary. Draw producer → validation → state transition → consumer → observable acknowledgment for each API/adapter. Distinguish accepted, attempted, uncertain and verified remote effects. Prohibit unowned duplicate state by design.
6. **S6. Choose architecture with qualified alternatives.** For each costly module boundary compare existing platform/reuse disposition, one credible alternative when consequential, performance/compatibility, upgrade and testability, plus disconfirmation conditions. No architecture decision is final when its required API/resource remains uninspected; schedule a bounded spike instead.
7. **S7. Derive acceptance and verification economics.** Turn each outcome and reachable failure boundary into specific given/when/then observations through the actual shipping path. Name exact source/input/target, fixture, environment and expected visible/state result. Choose the cheapest valid evidence tier, reuse shared setup, include positive and decision-changing negative cases, and flag independent physical/provider/human proof when required.
8. **S8. Perform bidirectional completeness and contradiction checks.** Trace every required goal through UI → operations → data/integrations → state → acceptance, then trace every proposed feature and side effect backward to an authorized goal. For each transition ask what happens on cancellation, stale callback, reboot and ambiguous result *when reachable*. Inspect cross-feature conflict, permissions, global defaults versus per-item overrides, inaccessible controls, competing source owners, orphan data and unsatisfied dependencies. Label holes rather than filling them from preference.
9. **S9. Issue readiness by workstream.** State READY / RESEARCH NEXT / DECISION NEXT / SPIKE NEXT / BLOCKED against actual obligations, with source, unresolved decision, irreversible assumptions, acceptance oracle and next slice. Use [Project Inception readiness standard](../project-inception/references/readiness-standard.md); research need not block unrelated ready work. Do not equate a finished specification with observed working software.

When an accepted decision or specification correction changes a current requirement, reconcile its authoritative operational representation via [Operational Documentation Integrity D1–D4](../references/operational-documentation-integrity.md): replace the superseded statement, preserve essential rationale/evidence separately, and update only real downstream work/acceptance consumers. Never use a chronological status addition as a substitute for correcting the governing requirement. Skip document churn when no meaningful contract changed.

## Failure and stop conditions

- **Owner intent conflicts with discovered technical reality:** retain both with source IDs and route a consequence-bearing decision, never silently reinterpret.
- **Feature described but no user goal:** justify as enabling infrastructure or remove from committed specification; do not generate requirements for its own sake.
- **One state has two owners:** choose authority or mark BLOCKED; do not add a sync layer by reflex.
- **Product value cannot be determined by code:** present grounded options, defaults where legitimately authorized and defer path; avoid forcing the user to decide everything now.
- **API experiment not performed:** mark candidate architecture provisional; only an independent ready slice may proceed.
- **Infinite combination space:** prioritise reachable transitions, shared-state intersections and highest consequence; mark excluded variants and ensure all required distinct states retain an acceptance oracle.
- **Simple fully specified fix:** edit and test existing behavior, skip project-wide specification regeneration.

**Independent oracle:** choose one requirement at random and reproduce its trace from original authority through design and acceptance; reverse-trace one implementation module/side effect to an authorized user job. Run a contradiction challenge using a held-out journey or excluded operating mode. A syntactically valid spec cannot prove semantic completeness.

**Receipt:** source and authority snapshot | required and proposed outcomes | variation/state matrix | controls and data ownership | candidate/reuse architecture | decisions and deferrals | testable acceptance | uncovered contradictions | workstream readiness and next safe slice.
