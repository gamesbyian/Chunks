# Repository Hygiene field procedures

Operational reference for [H1–H8](../SKILL.md). Open only the relevant section. Pathfinder's [periodic hygiene plan](https://github.com/gamesbyian/Pathfinder-Game/blob/main/docs/periodic-repository-hygiene.md) is optional documented prior art, not a required live dependency or a host-project policy.

## F1. Establish the baseline without exhaustive reads

1. Read host onboarding, permissions, current documentation authority, protected/immutable data, known concurrent work and any canonical hygiene tooling. Capture default-branch SHA and inspected-head SHA. Treat current code as implementation evidence, not as authority to discard an unimplemented owner requirement.
2. If a checkout is available, inspect `git status --short`, `git log --first-parent --oneline -n 30`, `git diff --name-status BASE..HEAD`, `git ls-files`, `git grep`, and relevant package/workflow manifests. Prefer existing safe inventory scripts but inspect script effects before running; do not assume a shell, Git, `rg`, `npm`, or any particular host command exists.
3. With only a repository API, fetch repository metadata, exact source files, changed filenames/diffs, current workflow/PR history and known manifest paths. Establish whether code search is indexed. A zero-hit query is `UNKNOWN` unless the searched population is proven complete; use tracked tree, known-directory and dependency traversal alternatives.
4. Choose a relevant time window anchored to the last hygiene pass, a significant completed migration or a bounded sample of recent changes. For CI use representative comparable full/scoped runs, noting queue, runner and cache differences. No speed claim from one lucky run.
5. Record `surface | current purpose/owner | recent change | known consumers | size/time if measured | evidence/ref | candidate?`. Keep the inventory compact; references and counts instead of copying whole registries.

Go deeper when a source changed, a current instruction contradicts another, a campaign closed, an essential evidence artifact is inaccessible, a costs trend worsened or a real consumer mismatch emerges. Otherwise stop reading that domain.

## F2. Authority, non-core planning and agent context

Classify surfaces as `LIVE_AUTHORITY`, `CURRENT_REFERENCE`, `MACHINE_REGISTRY/QUERY`, `PROPOSAL/DEFERRED`, `REPORT`, `FROZEN_EVIDENCE`, `COMPATIBILITY_POINTER`, `ARCHIVE`, or `OBSOLETE`. Do not infer priority solely from recency or filename.

Classify dispersed knowledge *before deduplicating*:

| Actual relationship | Appropriate treatment |
|---|---|
| Multiple writers for one mutable current fact | One owner; other displays derived |
| Duplicated invariant/algorithm | Shared primitive if cost warrants |
| Read-only aggregation of legitimate sources | Derived view with provenance |
| Similar but different concepts | Keep separation or narrow adapter |
| Frozen evidence plus current specification | Retain both, route ordinary work to current owner |
| Open research/policy question | Preserve uncertainty instead of forcing a fixed schema |

For active non-core plans/proposals, retain the precise open question, conditional reopen trigger, stop condition, current disposition and evidence pointer. Before archival or compaction, move **live** obligations, safety boundaries, deferred nominations or compatibility commitments into their rightful current owner. Move chronology to existing reports/PRs rather than building a shadow queue. Use [Operational Documentation Integrity D1–D4](../../references/operational-documentation-integrity.md) for edits, including its `NO CHANGE` branch.

Probe a representative ordinary-change route, cross-cutting-change route and specialist task. Distinguish mandatory agent context from optional drill-down. Count competing mutable authorities, not just bytes. If the host has documentation budgets, measure UTF-8 consistently; distinguish a preferred target from a larger actionable maintenance trigger. An optional `targetBytes` / `compactAtBytes` hysteresis pattern avoids constant trimming but is **not** mandatory. Below-trigger growth alone is no defect. Above-trigger restructuring should create meaningful headroom by ownership/routing changes, not sentence shaving. Do not make unrelated PRs fail for historic size debt when a causally scoped PR ratchet plus full periodic audit preserves the invariant.

## F3. Completed-campaign reverse consumer sweep

Start at a completed UI redesign, migration, research line, temporary workaround, validation campaign or architecture replacement. Independently reconstruct the surrounding population from **current code and current authorities**, not just the implementing diff:

1. Recover the original obligation, actual implementation and newest owner decision; mark each claim `CURRENT`, `HISTORICAL`, `TRANSITIONAL`, or `UNKNOWN`. Green CI is not proof a requirement remains current.
2. Trace definitions, producers, writers, transports, workers, readers, persistence, exports, CLI arguments/aliases, workflows and triggers, fixtures/screenshots, tests/validators, current docs, agent routing, registries and queues. Check hidden indirect consumers and at least one true production-shaped invocation path.
3. For each survivor identify its *current* claim, its unique failure detector, a real observing path and any independent witness. Assign `KEEP`, `REWRITE`, `REHOME`, `REPRO_ONLY`, `RETIRE` or `BLOCK/UNKNOWN`.
4. Before deleting a supposed orphan, search dynamically resolved references, package aliases, external users if applicable, historical replay and recovery obligations. No deletion from grep alone. Before retiring a test, show whether its underlying normative behavior was replaced or truly removed; maintain accessibility, data-safety and negative oracles.
5. Extract compact permanent invariants out of finished campaign shells, demote reproduction-only checks to safe manual/scheduled use when justified, remove obsolete automatic triggers, and verify links/actual consumers on the final head.

Example: a removed Add-screen confirmation step leaves a required screenshot. If the hash and destination still appear inline, replace the obsolete screenshot witness while retaining the user information requirement. Neither restoring unwanted UI nor deleting the disclosure assertion is justified.

## F4. Tooling, naming, file boundaries and exception ratchets

For tools, include package scripts, executable entrypoints, indirect imports, workflow invocations, readers of generated artifacts and documentation discovery. Categorize `ACTIVE_DIRECT`, `ACTIVE_INDIRECT`, `ON_DEMAND_REPRO`, `SUPERSEDED`, `UNKNOWN`. A useful disconnected tool is either integrated at the narrow place its evidence matters or honestly marked on-demand. Library tests do not prove CLI argument/file/side-effect wiring; retain at least one real invocation witness for a correctness-bearing executable.

Since the last naming audit, inspect new durable exports, commands, paths, fields, schemas and vocabulary. Ask whether a name implies stronger equivalence, causality, persistence, exactness, authority, ownership or ancestry than implementation proves; whether a conversational nickname escaped into a machine identity; and whether a retired word reappeared ambiguously. Classify `MISLEADING_DURABLE`, `AMBIGUOUS_QUALIFIED`, `PROSE_ONLY`, `FROZEN`. Rename only when net semantic benefit exceeds migration/compatibility cost. Normalize historical inputs at a reader boundary, never rewrite frozen evidence for cosmetic uniformity.

Rank difficult-to-edit files by size *and* churn, merges, coupling and responsibilities. Split along real ownership seams, preserve public facades only when necessary, inspect resultant import cycles and inverted dependencies, run the owning smoke. A line count is a prompt for inspection, not an automatic refactor.

For suppressions, allowlists, temporary compatibility exceptions and grandfathered ceilings, identify debt, permitted population, owner, expiration or reopen condition and detector. Prefer self-pruning monotonic rules: old debt remains exempt without permitting growth; once the old debt disappears, the exception itself is forbidden. Exercise `existing debt allowed`, `new debt rejected`, `debt removed -> stale exception rejected`. Avoid a global PR gate for unrelated pre-existing debt if changed-surface enforcement and a full periodic audit preserve safety. Conversely, cross-file merge-result invariants must not be hidden behind per-file incremental checks.

## F5. CI and workflow claim economics

Build one row per materially different recurring check:
`check | concrete bad merge/current claim | claim class | owner | semantic invalidators | cadence | unique detector | selected population | real topology | observed cost | disposition`.

Work in the following order:

1. **Claim first.** Distinguish product correctness/security, process/merge governance, maintainability, generated freshness, research effectiveness, frozen reproducibility and historical reconfirmation. A frozen research result rarely belongs in *every* PR gate unless it also protects a current merge-safety invariant.
2. **Owner/cadence/invalidation.** Follow producers and downstream consumers to find which changes may falsify the claim. Inspect actual selected test groups. Unknown impact must fail safely; a PR label or directory name alone cannot certify no consumer impact.
3. **Value.** Determine which distinct bad result the check catches and whether another check already proves it. Never discard independently useful safety coverage merely because a check is expensive.
4. **Measure.** Use comparable recent full/scoped runs, `first-required-runner-start -> last-required-check-finish`, and separately queue, install/setup/cache, actual useful work, retries and shard tail. Sample warm and cold paths. Investigate if changing selection rules inflate full-check population or a common edit activates unrelated domains.
5. **Challenge bulk cardinality.** A cheap ten-file validator may be costly for a thousand files if it spawns/parses/checks out/downloads per file. Compare a recent large change or bounded synthetic population and batch setup without reducing invariant coverage.
6. **Review lifecycle and topology.** Identify stale campaign workflows, redundant automatic triggers, unrecoverable expensive work, ineffective exact-key caches, duplicate sparse materialization and repeated workflow fragments. Prefer shared helpers, callable test seams, reliable cache invalidation and bounded parallelism over fragmenting into more jobs without evidence.
7. **Scope safely.** Before narrowing CI, demonstrate explicit consumer/dependency closure plus representative fault/scoped routing proof and a full oracle where appropriate. Do not change branch-protection requirements, skip project-mandated release gates, falsify green state, or tune meaningful solver/research proof budgets to hit an arbitrary runtime.
8. **Failed-run learning.** Inspect a bounded failure sample and cluster by *root cause* rather than counting every red run: platform assumption, stale agent direction, brittle proxy, harness fault, silent data loss, routing bug or real product regression. Put the durable lesson in the smallest owner. Another always-on CI guard is not the default remedy.

For expensive/sharded data workflows, separate compute, stage, combine, validation and publish where useful. Retain intermediate results with exact source/run/config/transform identity, hashes and idempotent names; on a downstream bug replay stages without paying upstream compute again if integrity holds. A partial artifact never proves the final claim. Put bounded proof-bearing summaries in ordinary logs and preserve detailed artifacts for drill-down; test whether the next agent can actually retrieve them. Reharvest must not silently replace different bytes under the same immutable identity.

If runner/timing history is unavailable, label costs `UNMEASURED` and nominate the cheapest future measurement rather than inventing saved seconds.

## F6. Select and implement authorized repairs

Rank by demonstrated recurring errors or cost, frequency, affected consumers and severity versus change effort, merge/conflict risk, compatibility and new maintenance burden. Be qualitative if measurements are unavailable. A safety problem outranks cosmetic file size.

For each proposed change record `present failure | current owner | consumers | action | preserved boundary | rollback | independent positive/negative check`. Prefer reviewable slices with clear owner. Do not race another agent editing a shared authority. In `AUDIT_ONLY`, stop before mutation.

For a broad migration/cleanup plan, map definitions, producers, transports, workers, consumers, stores, current authorities, tests/workflows and executed topology. Claiming `all consumers` requires a reproducible census or explicit coverage limits. Reuse the host's planning-quality standard if one exists; do not require Pathfinder's plan manifest in unrelated repos.

Distinguish `NOT_RUN`, `FOCUSED_PASS`, `INTEGRATED_PASS`, `CI_PENDING`, `CI_PASS` and `BLOCKED`. A post-edit syntax check is not integration proof.

## F7. Final hostile closeout

Reconstruct the resulting state rather than trusting the author's diff, PR body or checklist:

- Is any current fact owned twice, or a retired plan presented as live? Is a long agent reading route avoidably mandatory?
- Did cleanup break a real worker, CLI, scheduled recovery job, generated artifact, compatibility reader or indirect consumer?
- Was a test retired by current requirement authority, preserving user-visible, accessibility, failure and security oracles?
- Did CI become faster by losing meaningful coverage or normalizing a research result into a per-PR correctness claim?
- Can a closed campaign still impose automatic PR validation? Can downstream publishing replay retained genuine evidence?
- Did cache warming conceal a cold-start failure, or tiny PR testing conceal a bulk-change scaling problem?
- Does each important artifact have trustworthy exact provenance and a discoverable proof-bearing summary?
- Does a ratchet fail for equivalent syntax, or a stale exemption permit new debt?
- Did splitting a file create new cycles, oversized coordination files or hidden dual owners?
- Does the new hygiene machinery cost more to run than the recurring problem it prevents?
- Did the first useful fix obscure an adjacent instance of the *same* mechanism?

Run proportionate affected checks and host-required validation against the **final actual head after integrating current main**. Distinguish pending, skipped, failed and passed. If mode is audit-only, give recommendations and proof gaps without claiming changes or validation.

Stop when every domain has a cheap classification, authorized findings are closed or explicitly deferred with owner/evidence gap, and new exploration is unlikely to change the next safe action.
