# Effective policy and rule conflicts (MF31)

> **Authority:** planned binding feature contract. This document extends rather than replaces [SMART-SEEDING-RULES](SMART-SEEDING-RULES.md), [INTAKE-QUEUE-AND-POLICIES](INTAKE-QUEUE-AND-POLICIES.md), [UNIFIED-ENVIRONMENTS](UNIFIED-ENVIRONMENTS.md) and the [Task outcomes contract](TASK-OUTCOMES-AND-CONTINUITY.md). **No implementation is claimed.**

## Purpose and authority

Answer in human language **Which settings actually govern this transfer in this environment, why, what conflicts, and what happens if I change them?** Every explanation is computed from the same deterministic policy evaluator used for application, not a separate UI heuristic. A **conflict** means overlapping applicable policy definitions propose incompatible values or actions, cannot be enforced together, are unresolved due to missing facts or capabilities, or produce a risky consequence. Overlap alone is not an error; two compatible constraints may combine.

Existing Smart Seeding Rules are authoritative for ratio/duration/upload presets, matching conditions, manual override priority, and environment-scoped enrollment. **Do not silently change their precedence:** per-transfer override > highest-priority matching rule > environment default; ties use persisted rule order with flagged conflicts. Global network/engine hard caps and OS constraints are **independent upper-bound constraints**, not secretly overridden by a per-transfer choice. A group budget is not a per-job budget. Permission, tracker constraints and enforceability may prevent an effective setting without changing the definition or matching winner.

## Effective-policy explanation as a pure read

For exact transfer identity and exact environment, produce **EffectivePolicyExplanation**:
- Snapshot revision and freshness of rules, transfer metadata, session/manual overrides, capability probes, external constraints, and engine-observed settings.
- Matched/considered rule IDs with editable safe display names, scopes, individual match predicates and status **Matched**, **Not matched**, **Pending metadata**, **Inactive here**, **Excluded**, or **Unverifiable**.
- Winning rule and ordered precedence trace, per-field **source** (manual override, rule, environment default, engine/global cap, OS/network boundary), proposed vs observed value, units, granularity (per-transfer vs shared cap), and **Can enforce / Unsupported / Waiting / Uncertain / Drift**.
- Explain whether constraints compose as minimum caps, hard stops, all/any seeding conditions, or mutual exclusions. Do not call a lower effective cap an error if deliberate global throttling is in force.
- Conflicts with stable type and severity: `priority_tie`, `scope_overlap`, `incompatible_stop_conditions`, `missing_metadata`, `unsupported_capability`, `global_cap_interaction`, `manual_override_shadow`, `policy_drift`, `unsafe_seeding_reduction`, `environment_disabled`, `unknown_tracker_obligation`. A tracker obligation is externally sourced advice **not automatically authenticated**. No guaranteed satisfaction of private tracker rules.
- A deterministic **why** explanation and optional **Try change** simulation showing exact environments, affected transfer IDs/counts, winning-source change, possible start/stop impact, unsupported commands, readback confidence and whether explicit consent is required.

The evaluator's output is **not** evidence an engine accepted settings. After explicit Apply, record requested, acknowledged, and observed effect with environment/profile and readback. A failure in one remote engine must not be represented as a global success.

## User-facing flows

- **At Add:** show concise, editable **Seeding group**, detected/unknown tracker, environment scope, and plain-language resolved outcome. A **Why this policy?** affordance reveals matched predicates, precedence and conflicts. Metadata pending is clearly pending; never claim source/tracker certainty or enforce indefinite sharing as a silent fallback.
- **Transfer details:** **Effective rules** shows actual ratio, speed and schedule sources side-by-side, with “Manual override,” “Rule …”, “Engine limit” or “Waiting for metadata.” One tap to **Try a change** or view evaluation history without triggering mutations.
- **Group/rule editor:** **Applies to** always shows exact phone/remote profiles. **Check conflicts** evaluates examples, all eligible current jobs and pending/unknown results without altering live transfers. Rule names remain generated/editable but never serve as identity or match criterion.
- **Apply to existing / bulk:** dry-run before commit; group affected jobs per environment and severity; expose any potentially reduced seeding, save/restore snapshots where supported, confirm scope. Rule edits still default to future jobs only. Cannot execute “All environments” as a broad hidden write.
- **Automatic intake:** evaluate against immutable metadata snapshot, rule revision and exact target before auto-start; fail according to existing safe fallback if policy cannot be enforced. On metadata arrival re-evaluate with explicit safe-change policy; sticky manual overrides remain sticky.
- **Task outcomes MF02/MF04:** a Task may attach a **Policy enforced** milestone only after readback verified for the exact transfer/environment. The receipt references explanation revision; rule changes do not retroactively falsify historical successful enforcement but can invalidate current compliance and surface **Needs review** at the milestone. Task resumption must not auto-apply a revised rule.

## Safety, storage and architecture

Pure deterministic evaluator with stable rule order and fixed units; no network access during matching. Snapshot-based dry-run is read-only, bounded and cancellable. Use an optimistic revision check before apply, replan if jobs/rules/capabilities changed, then perform per-environment authorized writes with idempotent operation IDs where supported. Partial failures yield per-transfer receipts, never one fabricated success.

Privacy: redacted tracker host/passkeys and full announce URLs absent from screenshots/logs/crash/export. If full tracker host is sensitive, offer masked labels; rule names are user-defined and can themselves reveal identity, so redact in notifications when lock enabled. No background scraping or forced discovery. A dry-run is not a promise about future peer demand, ratio attainment or OS uptime.

## Acceptance

1. Two equal-priority tracker rules with incompatible ratio stop values: show both matches, persistent tie-break order, winning value, conflict warning and affected exact environment; no mutation on preview.
2. Manual per-transfer unlimited seeding shadows a matching ratio-1.00 group: “Manual override” wins and why is explicit; global upload cap remains a hard cap, not a contradiction.
3. Shared group 24 KiB/s budget on one engine and unsupported group cap on another: clearly distinguish group total from each-job cap; never fake total by independent caps.
4. Metadata-pending magnet with several tracker hints: no forged identity, known fallback or hold, later explain why the winning rule changed; no silent mass stop.
5. Editing a rule defaults to future adds; **Apply to existing** lists counts, names, scopes, changed stop conditions, permission and partial-result cases.
6. Disconnected rTorrent profile: compute qualified preview from last known values, show stale/uncertain enforcement, do not send writes or silently act on another engine.
7. A changed rule revision during preview triggers replan before Apply, not application against an outdated scope.
8. Conflicting time/ratio policy and private-tracker minimum: show external requirement uncertainty separately; don't promise compliant sharing.
9. TalkBack and 200%-text can read winner, overridden rules, units, exact environment, warnings and apply/undo state without color-only cues.
10. Property tests for precedence, tie stability, condition composition, cap units, add-time safety, cross-profile isolation, sticky overrides, deterministic explanations and partial mutation receipts.

## Explicit non-goals

No replacement rule engine, no new tracker account or API requirement, no bypass of engine capabilities, no guarantee that trackers grant upload credit, and no unattended destructive “repair” of conflicting rules. Scope remains **planned** pending implementation and independently verified acceptance.
