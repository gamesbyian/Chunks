---
name: repository-hygiene
description: Evidence-triggered periodic or event-based repository hygiene, including authority, completed campaigns, tooling, workflows, CI, evidence, naming and editability. Begin with cheap inventory; deepen selectively; change only authorized surfaces. Not a universal preflight.
metadata:
  version: "0.1.0"
  status: "instructional; effectiveness unmeasured"
---

# Repository Hygiene: reduce the cost of the next change

**Deliverable:** a scoped, evidence-backed repository health disposition and, when authorized, concrete entropy reductions checked at the actual final revision. Optimize future comprehension, editing, validation and recovery cost, not superficial file deletion.

## Activation and boundaries

**Use as primary skill:** explicit periodic/repository-wide hygiene requests, cross-domain tooling/CI/documentation entropy audits, and completed-campaign or major-migration cleanup. A host may set a cadence; this skill creates no recurring jobs.

**Use only as a bounded handoff:** another task exposes a consequential stale consumer, obsolete campaign guard, unowned current fact or unjustified recurring check. Fix the specifically affected surface through the task's normal procedure; do not start a full repo audit.

**Skip:** ordinary reversible edits, a single test failure, a direct request to update current plans/specs (use [Operational Documentation Integrity](../references/operational-documentation-integrity.md)), or a healthy unchanged area. `NO CHANGE` can be correct. An audit-only request forbids editing, closing, deleting, triggering expensive workflows, merging, or archiving.

Host AGENTS, security and permission rules, director decisions, protected history and required CI supersede this skill. Never import Pathfinder-specific budgets, commands, folders or authority into another project.

## Inputs and candidate record

Establish `AUDIT_ONLY`, `AUTHORIZED_REPAIR` or `CLOSEOUT`; identify requested scope, permissions, current default-branch/head SHA, simultaneous work, owner decisions and current specs/queues, existing maintenance procedures, recent changes and completed campaigns, tracked file/tool/test/workflow inventories, CI timing and artifact access. Mark evidence `OBSERVED`, `UNSEARCHED`, `INACCESSIBLE` or `UNKNOWN`; an empty unindexed search cannot prove absence.

Record candidates in an existing PR/issue or short working note:
`domain | symptom/revision | current owner and claim | consumers | risk/cost | proposed disposition | independent check | authorization/status`.
Do not establish a permanent parallel ledger without a host need.

## H1–H8: executable procedure

1. **Baseline and cheap routes.** From current authority and revision, assemble recent material merges, completed campaigns, known maintenance debt, representative agent reading routes, tools/workflow inventory and comparable CI run summaries. Select [field procedures](references/field-procedures.md) according to available tooling. Do not execute uninspected repository scripts.
2. **Breadth-first census.** For *each* domain below assign `CLEAR`, `CANDIDATE` or `UNKNOWN` with a short source or gap. Begin with diffs, manifests, short searches, run history, file-size statistics and existing inventories. Escalate only for churn, changed dependencies, inconsistency, substantial cost, retirement residue, inaccessible necessary evidence or growing maintenance burden. **Cover all domains; do not read all files.**
3. **Authority and lifecycle.** Classify current contract, reference, machine registry, proposal, report, immutable evidence, compatibility pointer, archive or obsolete material. Determine the owner of each mutable fact; legitimate distributed knowledge is not automatically duplication. Call [Documentation Integrity D1–D4](../references/operational-documentation-integrity.md) to reconcile affected plans, specs, onboarding, queues and handoffs.
4. **Reverse-sweep finished work.** Start from a completed migration, UI redesign, research experiment, temporary bridge or CI campaign; follow definitions through imports, workers, transports, CLIs, workflows, fixtures, checks, registries, historical readers and agent docs. For each surviving consumer recover its **current** requirement and independent oracle, not merely its existence. Before compacting plans, preserve live reopening conditions, safety boundaries and consequential failed evidence.
5. **Choose a safe disposition.** For each candidate select `KEEP`, `REPAIR`, `CONSOLIDATE`, `REHOME`, `ON_DEMAND`, `ARCHIVE`, `RETIRE`, `BLOCK` or `NO_CHANGE`. Evaluate actual unique consumers, error prevented, maintenance economics, compatibility commitments and protected history. No deletion from grep alone, no new abstraction for healthy separate concepts.
6. **Evaluate CI claim before optimizing.** Name bad merge/current invariant detected, owner, claim class, appropriate cadence, semantic invalidators, selected population, actual execution topology and unique detection. Then assess setup/critical path, warm/cold cache, bulk-change scaling and repeated computation. Use [Validation Economics](../android-app-development/references/validation-economics.md) for batching and test-execution decisions. Never weaken required release/security/merge proof to improve a timing metric.
7. **Authorized repair in bounded slices.** Prioritize proven recurring harm, coordinate overlapping agents and high-churn files, trace consumers, preserve rollback and negative controls. Prefer reusing a current owner to creating new guards. A new guard needs a demonstrated bypass case, correct invalidation scope, useful cadence and lower lifecycle cost than the problem it prevents.
8. **Second pass and hostile closeout.** Once a repair exposes a reusable failure pattern, inspect adjacent instances *of that pattern* once. Reconcile current docs/queue/tool discovery, exceptions and evidence. Integrate latest main when safe; verify affected behavior and the **actual final head**. Use [F7 hostile closeout](references/field-procedures.md#f7-final-hostile-closeout); do not claim pending CI passed.

## Domains to consider

| Domain | Initial probe |
|---|---|
| Agent reading and live authorities | Mandatory context, duplicated current facts, historical narratives in instructions |
| Plans, proposals and queues | Closed items still actionable, shadow work queues, lost deferred gates |
| Naming | New durable names implying false identity, exactness, causality or persistence |
| Tools, aliases and dependencies | Reachability, discoverability, duplicate wrappers, indirect consumers |
| Workflow lifecycle | Closed campaign triggers, unused artifacts, repeated setup, unrecoverable stages |
| CI cost and routing | Claim owner/cadence, selected population, warm/cold and large-change cost |
| Tests and ratchets | Outdated requirement witnesses, brittle proxies, overbroad guard scope |
| Evidence and research | Discoverability, exact provenance/joins, retained intermediate proof |
| Compatibility and history | Expired bridges, stale exceptions, immutable evidence misrouted as live |
| Editability | High-churn giant files, boundary inversions, dead code, generated-as-source |
| Discovery infrastructure | Stale paths, broken aliases, missing true CLI invocation tests |
| Guard lifecycle | Unsound lexical checks, non-pruning exemptions, disproportionate maintenance |

## Oracle, reporting, and stop

A reviewer should reconstruct from the *resulting tree* rather than trusting the implementation diff: can a new agent find the binding current requirement and execute it? Does a deliberately bad input still fail? Can a completed program's evidence be retrieved without its obsolete machinery running automatically? Did a compatibility path or protected check disappear unintentionally? Do current docs and queues reflect actual state?

Report `mode/scope | baseline/final SHA | all-domain census and gaps | changed/retained/deferred items | exact tests/workflows actually run | measured before/after costs only if observed | CI/concurrency state | next owner or NO CHANGE`.

**Stop** after all domains have cheap dispositions and authorized high-value candidates are fixed or explicitly deferred; do not expand indefinitely into unrelated feature development, exhaustive archives or speculative CI. Practice [adversarial decision cases](references/decision-cases.md) and apply [instruction completeness](../references/operational-completeness-audit.md). This is procedural guidance, not evidence of improved agent performance.
