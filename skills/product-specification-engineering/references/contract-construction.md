# S1–S9 contract construction: executable product and architecture specification

## 1. Start with authoritative, versioned facts

Create requirement ledger entries with fields ID, actor, evidence ID/version, authority class, job/outcome, scope/mode, allowed action, prohibited action, status and unresolved discriminator. For each fact read the original owner message/source/code, not a secondary summary when available. Use stable IDs in existing project documents. A feature suggestion from an unrelated product is PROPOSED, not automatically CONFIRMED. "An API is documented" is neither "we integrated it" nor "a user wants it."

## 2. Atomize user outcomes

For each actor and job answer these exact questions: What starts the journey? What object is selected? What permission is required? Which user-visible state changes? What durable result proves success? What should remain unchanged? Which failure still leaves the user's original data intact? Split combined outcomes where one may pass and another fail. Example: importing a photo, placing a marker and authorizing a share are three outcomes, not one untestable "photo tool works."

## 3. State and variant exploration

Start with a state-transition table:

| Transition ID | From state | Trigger | Authority/guard | Operation/data owner | To state | UI feedback | Recovery | Acceptance evidence |
|---|---|---|---|---|---|---|---|---|

Enumerate known state types only where reachable: absent/uninitialized, loading, prepared, empty, edited/dirty, authorized, attempted, uncertain, persisted, verified, failed, cancelled, revoked and superseded. Ask which of these changes the outcome; avoid imposing every state on every feature.

Define variant axes: role, provider/account, source type, permission state, device/viewport/input mode, offline/online, first install/upgraded data, catalog version and simultaneous operation where applicable. Choose combinations with shared-state or destructive interactions first; pairwise coverage only when dependencies warrant it. Write a reason for excluded combinations.

Use a reverse journey: from success outcome backward to required source, permission, control, data identity and prior state. If a link cannot be established, classify unknown, omitted source, missing edge or contradictory requirement; route to Concept Model Engineering if relationships are complex.

## 4. Contract each control and service

**User-facing control:** stable ID, label, visibility, enablement, selection identity, default/override, confirmation/consent, cancel, immediate feedback, after-restart feedback and state holder. For accessibility, require equivalent meaning/operation via touch, keyboard/assistive tech and supported text scales.

**Data entity:** stable ID, source revision, producer, validator, owner-of-truth, persistence and deletion, privacy and grants, migration, transfer to external systems. A temporary URI is not durable ownership.

**Adapter/API:** input schema, caller identity, precondition, authorization, side effects, attempted-versus-confirmed semantics, timeout/cancellation, idempotency, return/error, reconciliation and real accepted postcondition. Exact external environment/account identity must survive async boundaries.

**Architecture option:** module boundary, dependency graph, reused resource and version/license, option A/B, hard constraints, failure modes, estimated implementation plus maintenance cost with evidence, cheapest falsifier and accepted/conditional status. No fake certainty from hypothetical cost numbers.

## 5. Derive acceptance from user outcome

For each required outcome, write a given/when/then whose THEN observes independent bytes, state, device behavior or user goal instead of implementation existence. Use positive normal path plus relevant negative/interrupted paths; map each test to requirement ID, exact environment, real call path and evidence tier. Mark unexecuted tests PLANNED. Select test batching and risk gates through [Validation Economics](../../android-app-development/references/validation-economics.md).

## 6. Completeness checks and decisions

- **Forward coverage:** each confirmed user goal has a reachable entry, visible control/operation, authoritative state, failure/recovery and assertion.
- **Reverse coverage:** each module/side effect maps to a confirmed goal, necessary infrastructure or explicitly labelled exploratory scope. Anything else needs a reason, removal or decision.
- **Contradiction:** compare permissions, shared IDs, defaults, mobile/platform modes, loss/retry behavior and concurrent mutations across features.
- **Source contradiction:** do two independent claims actually disagree under the *same* actor, product/version/state? If not, split scope.
- **Choices:** for each unresolved product dimension enumerate real alternatives, consequences, recommendation basis, current owner status and defer. Present only consequential human leverage.
- **Stop:** no remaining assumption could materially reverse the next ready workstream without being explicitly marked; unrelated unknowns may remain open.

## Deliverable layout (may be sections of existing files)

1. Scope, user jobs, source/authority ledger.
2. Prior-art and qualified reuse decisions (with source links).
3. Feature-by-journey / state and variant coverage.
4. UI and data/adapter contracts, privacy and accessibility.
5. Architecture alternatives and dependency map.
6. Decisions required/optional/deferred.
7. Acceptance IDs, evidence tiers, rollout/migration, verification costs.
8. Readiness per workstream and first authorized slice.

## Negative controls

Known UI string fix: reuse current contract; no new spec dossier. Unverified plugin claims production API behavior: RESEARCH NEXT. Three conflicting "defaults" across feature docs: reconcile precedence before coding. One failure state has no exit: BLOCKED until recovery/preservation specified. A 25-feature inventory with unresearched alternatives: PROPOSED not confirmed. A handoff with customer-site images needs actual recipient consent independent of visualization preference.
