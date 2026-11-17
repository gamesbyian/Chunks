# Setting registry, identity, scope and effective values

## Decision before design

Inspect app-scale and existing mechanisms. For a simple app with three toggles, continue its stable repository and explicit Compose controls. A schema-driven registry earns its complexity only when settings must be reused, searched, scoped, migrated, composed or audited across screens. A native AndroidX Preference hierarchy, declarative framework library or existing repository may already satisfy that requirement. Never implement JSON Schema solely because this document uses the word *schema*: a typed Kotlin model is often sufficient.

## Five distinct objects (never conflate)

1. **Definition:** semantic stable ID, declared value type, constraints, documented default, text/aliases/localization, privacy, supported scope kinds, dependency or capability predicate, and value-owner adapter.
2. **Stored scoped instance:** ID + immutable scope identity (global, client/profile/account, transfer, document, etc.) + provenance + explicit override value/version. IDs for the same setting under different clients identify distinct instances.
3. **Effective value:** result of a *specified* precedence chain; expose value, source tier, whether overridden, supported/read-only reason and reset target. A missing override is not identical to an explicitly stored false, empty string or null.
4. **Presentation placement:** page ID, group, order, label variation if justified, render hint, focus/search anchor and expected context. Multiple placements can reference one ID, and none should create a second writer.
5. **Effect/operation:** application of a saved configuration, a domain operation, remote RPC, permission prompt or migration. A persisted preference is not evidence that a separate remote effect occurred.

## Mechanical inventory

For each existing setting search (a) all UI labels and click handlers; (b) key constants, serialized field names and default constructors; (c) every read path and feature service; (d) migration and backup/restore; (e) selectors, screenshots and CI; (f) search/deep-link entries. Capture both directions with actual paths and line numbers. Record stale aliases and whether one value has different defaults depending on entrypoint.

Suggested per-setting record:

| Field | Required meaning |
| --- | --- |
| id | Semantic stable identity, never a transient display label |
| kind | persisted preference / ephemeral UI / domain action / derived information |
| valueType, choices, validation | Strongly typed domain, bounds, cross-field constraints |
| defaultPolicy | New-install default; old-install migration policy; explicit opt-out behavior |
| owner, storage | Authoritative writer and repository; credential isolation |
| permittedScopes | Global, per-client/profile, per-item or relevant domain scopes |
| resolution | Exact precedence and fallback, not an implicit search through maps |
| capability, dependency | Reasons visible/disabled/inapplicable; fail-closed for unsupported operations |
| placement(s) | Canonical page and optional contextual shortcuts |
| changeMode | Immediate, valid-on-focus-loss/debounce, composite explicit commit |
| outcome | Observable effect, pending/failed/uncertain handling and reset semantics |

Do not synthesize values from page appearance or labels. Store stable internal IDs even when owner-approved wording changes.

## Scope resolution

Draw the host-specific precedence chain explicitly; do not assume universal per-transfer > client > global. Some settings prohibit overrides entirely, and some remote-host values are authoritative rather than inherited defaults.

Example **only where approved**: per-transfer explicit override > exact remote client override > global preference > documented built-in default. Return a result containing the effective value *and its provenance*. Reset a per-transfer override removes that override; it must not write the global default into the per-transfer record, and must not erase another client's value.

Where an override is unsupported, show the inherited value and route to its owner (or omit edit affordance) rather than invent a shadow write. Bind all per-client edits to stable exact profile identity at input time; names/colours/endpoints are not reliable keys.

## Dependencies, policy and privacy

- Represent only real dependency edges, such as network enrichment requiring global enrichment enabled *and* network permission. Separate visibility from enablement from applicability from a user's stored value.
- A disabled control must convey the relevant reason; hidden controls must be absent from keyboard, accessibility and search results where opening would be misleading. A dormant value remains preserved if disabling a master preference does not authorize deletion.
- Detect cycles in setting dependencies when using a declarative graph; make capability checks pure (no I/O during rendering), and resolve asynchronous capability changes outside composables.
- Define privacy classifications. Consent/remote-network access may need explicit durable *opt-out* state distinct from installation default. Credentials, tokens and keys stay in established secure storage with separate access semantics.
- Neither schema nor UI should have authority to perform destructive work. Route effects through established permission, ownership, identity, audit and postcondition checks.
- Localization changes labels and descriptions, not IDs. Deprecate aliases without breaking legacy persisted keys or deep links until migrated.

## Architectural selection guide

Choose the least powerful approach that covers actual requirements:
- Explicit controls + existing store: few options, limited reuse and scope.
- AndroidX Preference: conventional hierarchy and automatic preference widgets where it fits the app and design.
- Compose + typed definition registry + repository/DataStore: multi-page reusable controls and reactive state with custom visual language.
- JSON Schema + UI schema renderer: data-driven cross-platform/editor use when genuine runtime schema interchange is required. JSON Schema describes data constraints; layout and interactions generally need a separate UI layer.
- Dedicated entity editor: connection profiles, rich rules, credential workflows, multi-field transactions and irreversible commands.

Reject a bespoke generic renderer if its exceptional cases outnumber ordinary rows, if it duplicates existing storage, or if it creates dynamic string casts and silent failures in place of type safety.
