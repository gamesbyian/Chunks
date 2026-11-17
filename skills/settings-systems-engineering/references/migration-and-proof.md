# Migration, regression and evidence for settings

## Migration protocol

1. List *actual* previous keys, serialized types, schema versions, storage files and populated values; include explicit false, unset, empty, unknown enum, removed profile and legacy aliases. Inspect release history or fixture snapshots rather than imagining old data.
2. Build a mapping: old key + scope + value + provenance -> new definition + owner + scope + value. State whether the old preference remains readable, migrates once, maps to a new domain, or must be reviewed by user. Preserve explicit opt-outs and security constraints.
3. Specify default changes separately for **fresh installs** and **existing installations**. A new enabled-by-default feature must not silently override an existing user rejection merely because the old field was null or renamed.
4. Define atomicity and concurrent writes; multiple storage formats may require a journal or idempotent checkpoint rather than pretending a multi-store transaction exists. Test interrupted upgrade and replay. Do not alter Room/Proto versions or modify credential migration on an unrelated menu move.
5. When moving a control between subpages with unchanged semantics, *prefer zero storage migration*: keep key, type, store and writer; change page manifest, navigation aliases and tests only.
6. Preserve removed/unknown values as required for downgrade/forward compatibility. Do not treat a missing capability as consent to delete a saved choice. Reset always targets the chosen scope, not a shared default or every client.
7. For independently valid controls choose one writer and observable state stream; if a second screen is kept temporarily, make it a read/write projection of that same owner, not a second store.

## Risks that deserve targeted evidence

| Risk | Discriminating cheap check | Shipping-path proof when material |
| --- | --- | --- |
| Duplicate setting ID or missing registration | Static inventory / registry uniqueness test | Search/deep-link finds precisely the intended control |
| Type or domain mismatch | Typed definitions and invalid-value cases | UI refuses invalid complete value, shows reason |
| Wrong inherited value | Resolver tests for global/client/item and reset | Actual named client/item remains independent |
| Lost consent/explicit OFF | Old-data migration fixture | Installed upgrade retains chosen OFF |
| Disconnected UI | Repository observer test | Toggle affects real consumer and survives relaunch |
| Async write inversion | Deterministic delayed/reordered writes | Rapid toggle sequence ends at last intended choice |
| Hidden/disabled control drift | Pure capability/dependency tests | TalkBack/search/nav agree with visibility state |
| Remote false success | Domain adapter negative/error test | Real host readback or truthful uncertain feedback |
| Incorrect category relocation | Page composition snapshot/ID test | User can find moved option without duplicate controls |
| Visual regression | Focused screen render at required geometry | Actual screen inspection and accessibility traversal |

## Test economics

- Before expensive tests, reverse-trace existing assertions to current owner contract. KEEP, REWRITE, REPLACE-EVIDENCE, RETIRE or BLOCK. Never retire a security, accessibility or destructive-operation oracle just because a deliberate layout changed.
- Batch a set of compatible settings/menu changes before one expensive emulator/installed-app run; use cheap isolated schema/resolver tests during implementation. For a risky migration or credential operation, an earlier focused gate may be justified.
- Verify on exact current head/commit, with real storage and service/provider where claimed; report which tests actually executed. A green mock-only test does not establish remote or physical behavior.
- Do not mandate a full historical migration matrix for a fresh, non-distributed app with no preserved installs. Preserve the *actual* owner's migration expectations and existing compatibility consumers.
- All docs-only work uses local docs/link checks, not automatic Android CI. A plan, screenshot or source diff is not shipping acceptance.

## Minimal independent acceptance walk

A reviewer selects one setting ID without the implementing agent's narration and:
1. opens the canonical Options page or context shortcut;
2. identifies the exact global/client/item target and current effective provenance;
3. changes it, sees pending/commit outcome and meaningful error if forced;
4. verifies effect in the real consuming feature;
5. navigates elsewhere and back, then restarts/reloads to confirm persistence;
6. resets the *correct* scope, confirms inherited value and untouched other scopes;
7. follows search/deep-link and accessibility path to the same identity.

Select risk-relevant steps, not a universal seven-step suite for every trivial edit. Report objective evidence tier and untested conditions.
