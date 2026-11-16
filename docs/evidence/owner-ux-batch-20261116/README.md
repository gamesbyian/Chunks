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

## Claude reconciliation audit (2026-11-16)

#1785 head `c3a439e` merged with main `cf94c78` (clean; settings registry, #1787 docs and takeover handoff preserved) on `ccr-80bc6676-160j9f`. Local JVM campaign on the merged tree: 2,585 tests, 19 failed; 3 were workspace-only (JDK 21 Security Manager, non-UTF-8 `sun.jnu.encoding`, one libtorrent timing case) and absent from CI's 15. Each remaining failure was classified before any edit:

| Disposition | Failure → resolution |
| --- | --- |
| **Regression, fixed in source** | Duplicate `Options` pane title (search field re-declared the root's pane title) broke `ScreenPaneTitleTest`, `CombinedSearchContinuityTest`, three `LocalToolReturnTest` cases: removed the duplicate. Offline tools (ZIP/snapshot inspect, Create) were reachable only after starting **+ Add connection**: restored on the roster. Roster Manage action repeated its disambiguation twice in its spoken name: fixed. |
| **Regression, fixed in source + stable tag** | Roster **View transfers** lost the `View transfers on current verified server` name (now names the client). Ten installed tests (native Add atlas/media, two-server, delete-data, magnet, Seedhost read-only) located it by that copy: new `ChunksSemantics.CONNECTION_VIEW_TRANSFERS` on the verified session's action; tests use the tag. |
| REWRITE (design-obsolete observer) | Details' client is the short compact origin (identity-first Details): `CombinedDetailSessionCapabilityTest`, `CombinedSharedDetailNavigationTest`, `CombinedTransfersScreenTest`, `RepeatedAndroidIntakeTest` derive it with `compactServerLabel`. Removed visible Options/Transfers headings: assert pane title / `TRANSFERS_SCREEN`. Zero-profile purple intro asserted before the form opens. 200% font test uses `TRANSFERS_FILTER_SORT` (the toolbar has had no "Filters" text). |
| REWRITE (natural artwork) | Row art for movie/TV is now POSTER role: revoke-preserves-cache reads the preferred role; Details may reuse the already-held row bitmap (identity check) while collection is off, and the **no new HTTP** assertion is unchanged. |
| KEEP | All exact profile/hash, no-mutation, consent, credential, stale-session (probe from an old session still cannot populate a new one) assertions unchanged. |

## Campaign status

Not run at this checkpoint. Planned consolidated local static/docs/JVM/Compose/build/lint/instrumentation compilation, followed by assembled CI native/R8/API29/two-server/delete-data gates and representative render inspection. Physical Pixel, human TalkBack and protected candidate-host evidence must be reported separately. The managed Linux workspace has Gradle 9.3.1 and Android API 37 SDK; no hardware KVM device is available.
