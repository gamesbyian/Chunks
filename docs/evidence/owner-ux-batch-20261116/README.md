# November 15 UI/UX batch: implementation and verification receipt

Implementation PR: [#1785](https://github.com/gamesbyian/Chunks/pull/1785). Specifications [#1783](https://github.com/gamesbyian/Chunks/pull/1783) and [#1784](https://github.com/gamesbyian/Chunks/pull/1784) merged after resolving additive documentation conflicts and the cross-PR missing link. Baseline `77deff20696d3665a4fc190e91c6c249e7c745c4`.

## Pre-campaign test-contract audit

Completed before the first implementation test campaign. Authority: [batch handoff](../../design/CODEX-BATCH-UX-HANDOFF-20261115.md) and its linked November 15 contracts. Searched JVM, Compose, instrumentation, provider fixtures, native atlas producer/collector/manifest and workflow selectors for removed headings/Back/artwork card, square video assumptions, control geometry, split Connections, onboarding, environment vocabulary and excluded summary.

| Disposition | Contract and observers |
| --- | --- |
| REWRITE | Visible Details title → `DETAILS_SCREEN` plus preserved pane title; root Transfers title → `TRANSFERS_SCREEN`; footer return in full Details navigation tests; lower media card → independent identification/artwork actions. Shared/local/remote Details tests retain exact key, observation, file facts and no-mutation assertions. |
| REWRITE | Connections intro/presentation/IME/draft tests now enter setup through Add. Duplicate-name tests use stable profile IDs and separate read-only inventory in Manage from explicit Connect. Installed setup, Disconnect and atlas observers follow roster/Manage. |
| REWRITE | Summary asserts selected-client count separately from live-rate exclusion. New scope tests cover empty/single/multiple scope, locked/offline/empty/missing clients and search; no rate-exclusion calculation is relabelled. |
| REWRITE | Artwork repository tests require natural proportions, source pixels and offline persistence. Old pinned-cache fixture explicitly seeds the old key to verify migration. Provider-bitmap screenshot witnesses use uniform contain geometry. |
| REWRITE | User-facing client vocabulary and accessibility strings. Technical identity keys, preference names, secret environments, hashes, RPC methods and role enums retain their meaning. |
| REPLACE-EVIDENCE | Existing native atlas filenames remain stable and represent current UI; no approved pixel goldens exist. Added JVM 412dp and 320dp/200% control captures, preserving selected bounds and 48dp targets. Native atlas producer/consumer inventory remains intact. |
| KEEP | Exact profile/hash authorization, lock/no-leak checks, credential isolation and restored-draft password fence; Start/Stop readback, Add blank remote default, directory capability checks, Remove/data consent and verified outcomes; artwork override/cache/consent revocation, offline inventory, search/filter/state restoration. No safety test is deleted or skipped for this redesign. |

Remaining Back controls in Add results, alias review and snapshot screens are separate surviving navigation contracts. Square provider fixtures, explicit SQUARE role alternatives and square music/software artwork remain valid; they do not mandate square movie presentation. Native screenshot capture is evidence, not owner visual approval.

## Campaign status

Not run at this checkpoint. Planned consolidated local static/docs/JVM/Compose/build/lint/instrumentation compilation, followed by assembled CI native/R8/API29/two-server/delete-data gates and representative render inspection. Physical Pixel, human TalkBack and protected candidate-host evidence must be reported separately. The managed Linux workspace has Gradle 9.3.1 and Android API 37 SDK; no hardware KVM device is available.
