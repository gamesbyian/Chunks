---
name: settings-systems-engineering
description: Design or migrate multi-screen application settings systems, including scoped preference ownership, typed definitions, reusable Options controls, persistence, search and tests. Use when settings span screens/scopes, storage owners diverge or Options navigation is being reorganized. Skip isolated controls.
metadata:
  version: "0.1.0"
  status: "procedural guidance; effectiveness not yet measured"
---

# Settings Systems Engineering

**Job:** Make user configuration understandable, discoverable, persistently correct and safe to change, while allowing definitions, storage, rendering and navigation to evolve independently. Host product contracts, source and permissions outrank this portable procedure. A setting registry is not evidence that an app works.

## Activation / exclusion

- **Activate** when implementing several persistent preferences, reorganizing Options, adding per-account/client overrides, integrating settings search, consolidating duplicate controls/stores, or migrating setting semantics.
- **Skip or stay with the existing pattern** for one small, settled preference; no registry, serialization format or ceremony is mandatory.
- **Classify before proceeding:** a transient filter/sort, one-time action, per-task input, remote command, permission prompt and complex entity editor may belong near the task rather than in Settings. A feature may include both an action and a persistent preference.
- **Required inputs:** current owner decisions, shipping UI and consumers, stores and keys, actual supported scopes, platform/library, legacy populated data and affected tests. Missing facts are UNKNOWN, not inferred defaults.

## S0–S8 mechanical procedure

1. **S0. Pin authority and reality.** Inspect live main, owner contract, current entrypoints, persistence APIs, consumer path and test contracts. Mark each claim IMPLEMENTED / PARTIAL / PLANNED and behavior APPROVED / OBSERVED / PROPOSED / UNKNOWN. A mockup is not implementation proof.
2. **S1. Inventory and classify.** Record for each affected item: semantic ID, label and synonyms, preference/action/draft/derived classification, present screens, readers/writers, storage key, default, scope, override policy, runtime consumer, status and risk. Trace UI to consumer and stored key back to every reader/writer. Reconcile aliases and duplicate owners before editing.
3. **S2. Choose proportionate architecture.** Compare extending the existing platform and repository pattern with a bounded typed registry. Prefer existing simple patterns for small apps; use a registry when several pages/scopes/reuse/search justify it. Inspect AndroidX Preference, Compose with hoisted state and DataStore, or the host framework's native equivalent before inventing a renderer. A registry describes settings; it is never a second persistence engine.
4. **S3. Specify each setting.** Define stable ID, type/domain, canonical default and lifetime, validation, writer/owner, permitted scopes, inheritance and override resolution, reset behavior, privacy class, capability/dependency and change/failure semantics. Distinguish *definition*, *scoped stored instance*, *effective resolved value*, *UI placement*, and *side-effect command*. Use [registry and scopes](references/registry-and-scopes.md). Scope is not inferred from the page on which a control appears.
5. **S4. Compose UI, don't duplicate state.** When warranted, keep one registry, reusable typed controls and declarative page/group manifests referencing IDs. One ID can appear in multiple places without multiple stored values. Use dedicated editors for complex configurations. Follow [UI and interaction](references/ui-and-interaction.md). Keep frequent contextual actions in their task flow; link to global defaults rather than hiding necessary controls.
6. **S5. Connect edits to truth.** UI emits typed intent; state holder validates; owning repository commits or separately authorizes an operation; UI observes actual effective state and pending/errors. Keep Compose rendering side-effect free. Immediate-apply choices commit when independently valid; composite remote configuration can require explicit review/commit under host policy. Guard stale async completions and repeated fast changes.
7. **S6. Migrate without loss.** Map old key/value/default/scope/explicit-off states to new behavior. Prefer adapters around existing stores and incremental movement. Keep credentials and secrets in appropriate protected storage, not a general preferences map. Never silently reset existing choices or allow one client's settings to affect another. Use [migration and proof](references/migration-and-proof.md).
8. **S7. Right-size evidence.** Before expensive runs, trace impacted tests, screenshots, selectors and CI to current owner requirements; KEEP / REWRITE / REPLACE-EVIDENCE / RETIRE / BLOCK stale contracts. Exercise registry and effective resolver cheaply, then focused UI → persisted state → behavior, scoped negatives, restart, rapid changes, failed writes and accessibility. Test real remote behavior when claimed. Batch compatible costly Android/CI setup. Use [decision cases](references/decision-cases.md).
9. **S8. Reconcile and stop.** Update the project's existing settings inventory, Options specification, related queue and tests without adding competing authorities. Report affected IDs, storage/scope, migration, expected and observed functional/interaction results, exact test revision/tier, unresolved risk and next executable task. Stop at the agreed boundary instead of mass-migrating untouched controls.

## Inspectable output / acceptance oracle

Record, in an existing work artifact, rows of: **ID | state/owner/scope | effective resolution | placements | accepted behavior | evidence**. An independent reviewer must be able to say where the control is found, which scoped instance changes, what persists, which consumer changes behavior, what Reset does, and what failure looks like, by following the same ID through a real path. A green registry unit test alone cannot establish these outcomes.

**Negative controls:** One simple theme switch does not require a generic framework. Moving a global original-file policy out of Add must not leave a second Add-only setting. A per-client folder default must not change a one-off Add destination. Delete Files is a command, not a passive preference.

For a Kotlin/Compose illustration, consult [the typed registry and UI projection example](examples/kotlin-compose-projection.md). It is an illustrative boundary, not code to paste into another project.

For source audit and alternatives, read the [prior-art audit](research/prior-art-audit-2026-11-15.md) only when making a consequential architecture decision. All examples are illustrative; external libraries are not mandated.
