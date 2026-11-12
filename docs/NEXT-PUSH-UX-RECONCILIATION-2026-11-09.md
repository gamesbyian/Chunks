# Next-push UX/media specification and execution contract

**Instructions:** Apply these owner-approved requirements when editing the UI and media pipeline. Check [WORK-QUEUE](WORK-QUEUE.md) for current priorities and the current owner dispatch plus [concurrency guidance](AGENT-LANES.md); confirm live main and PR heads before implementing. Do not treat the historical source audit or old PR list as acceptance. Put run histories in [QUALITY-LEDGER](QUALITY-LEDGER.md).

## Decisions that must agree everywhere

### One Transfers destination and one full Details experience

- The **Combined list implementation is the starting point** for **all** list scopes. `All environments`, `Transfers on this device`, and each named server are **filters of one catalog, row component and detail navigation**; never launch the legacy phone/server list format simply because a single scope is selected.
- Every transfer row is compact, source-labelled (name and colour), status/progress-oriented and opens the same dedicated **All Details / full Details** route using exact `environment/profile + infohash`. Do not expand entire tools, files and media editors inside the list card. Retain all existing capabilities in Details or context menus with legitimate current/authenticated mutation gates; no data or action may disappear during consolidation.
- Preserve stable filters/scroll/restoration, accessible root-to-root navigation and genuine stale/offline/locked visibility; opening one server is a scope change, **never** an implicit command target or session switch. Duplicate hashes across profiles remain independently addressable.

### Persistent navigation and horizontal space economy

- One **single-row** footer always visible, including nested Details and IME/keyboard states: **Transfers | + Add | Connections | Options** in exactly this order. Replace the existing width/font based two-row fallback in `AuthenticatedTransfersFooter.kt` for ordinary phone layouts. Prefer compact icons **with** labels, not ambiguous icon-only controls. Keep effective >=48dp touch targets, accessibility names, proper focus, contrast and responsive typography. At 320dp and 200% type, prove all four are reachable and readable via an explicitly tested accessible treatment; do not silently truncate required labels or create an unreachable horizontal carousel.
- Across all screens, replace gratuitous vertically stacked full-width action buttons with **two across by default** and **three across for naturally short labels**. Full-width is exceptional (e.g. destructive confirmation, a dominant primary action, or labels genuinely too long). Keep safe spacing between destructive and ordinary actions, natural reading/focus order and all capabilities. Use a reusable Compose action-grid/flow pattern, not dozens of bespoke layouts.
- Measure gained usable transfer-list viewport height; do not steal the saved space with always-expanded search/status/options sections.

### First-install, Connections, footer state and Universal Add

- **First install / truly empty Transfers (#1512):** Header, persistent footer, **Welcome to Chunks**, concise local-or-remote explanation, Add and Connections actions. Suppress unused scope/search/batch/filter chrome only for genuinely no-transfer empty state; loading/offline/stale/filtered-zero are separate truthful states.
- **Connections (#1514/#1515):** Keep purple introductory card but populate it with **Connect to another computer or server (optional)** and the concise remote explanation; remove local-download promo and device section. Always show saved/available remote connections with management and Add tools. On verified connection creation, acknowledge then remain on Connections. Tapping a saved profile opens the **same Combined-derived Transfers** browser filtered by exact stable profile ID; never use an independent server-transfer page.
- **Footer (#1516):** Four destinations always in one row. Actual selected root indicator changes on Transfers, + Add, Connections and Options, including Back and deep links. Purple Add may remain a visually strong primary action but its persistent accent is not selected-state styling or TalkBack selection.
- **Universal Add (#1517):** Replace the two-target gateway with one source-first form: magnet/HTTP(S) URL input, Open .torrent and Paste at top, then exact phone/remote/eligible Smart Routing target, Save to/Change, Start when added and contextual collapsible files/priorities, alias, Keep/Archive/Delete local source, artwork choice and seeding/rule controls. Preserve source/target/Place/Start draft state and sole coordinator. Never require destination preselection, duplicate local/remote screens or review-before-submission. Follow [source intake](ADD-SOURCE-INTAKE.md), [source lifecycle/routing](SMART-ROUTING-AND-SOURCE-LIFECYCLE.md) and [UX](UX.md).

### Automatic media/artwork without manual identification chores

- **Options → Artwork & media information** holds the **global enabled-by-default feature preference** for automatic media interpretation, artwork collection and display, with clear subordinate controls as needed. Per later owner direction, sanitized online title lookup itself defaults ON on a new installation without a second opt-in dialog; show this plainly and preserve persistent opt-out, privacy lock, provider/network controls and no sensitive source disclosure. Disabling global collection stops new requests, and disabling display hides art without destructively clearing durable user choices.
- **Only in Universal Add**, and **only when global collection and display are enabled**, show **“Automatically identify and fetch artwork & metadata”** checked by default. This is a **one-time per-transfer Add decision**, committed against the exact environment/hash at admission. Unchecked means no future automatic lookup for that job through refresh/restart/reconnect. When globally disabled, **do not show the Add checkbox**. Do not create a second Add state owner or bypass verified source/Place/Start.
- **Never show that checkbox in an active transfer's Details.** Details shows the resolved media/artwork and offers **Change identification or artwork** for manual correction and poster/image choice. Preserve explicit overrides, and do not silently override them on a rescan.
- Automatically interpret the transfer name and available authorized file evidence, then, once permitted and network/provider-capable, perform bounded asynchronous lookup for all eligible visible authorized transfers (not only composed rows or opened details). **Confident** provider matches become durable assignments and posters across all lists and Details. An ambiguous/no-provider/offline/consent-blocked result remains honest and actionable without implying a verified title. A recognized filename title (“Looks like: Hackers · Movie · 1995”) does not itself constitute a provider-verified match. Never display an inexplicable “Not identified” plus a mandatory Identify workflow for routine eligible content.
- Verify why auto-enrichment is not happening: online lookup enabled/explicit OFF, global automation, per-Add exclusion, network metering/power-saver, provider configuration/cooldown, privacy authorization, scheduler coverage, stale inventory and artwork fetch. Do not assume one cause solely from screenshots. Never use raw private torrent names, passkeys, trackers, auth or paths in outbound requests.

## Live source review: verify implemented UX, do not repeat historical fixes

Before implementing or changing UI, inspect the **current** source, current owner decisions and [feature experience contract](FEATURE-EXPERIENCE-CONTRACT.md). The 9 November observations that motivated the canonical navigation, one-row footer, Add-only artwork choice and default-on lookup are **historical diagnoses**, not proof that these defects still exist:

- Verify that `MainActivity.kt` routes local, All and each saved remote profile into the canonical `CombinedTransfersScreen.kt` and `UnifiedTransferDetailPanel`; do not recreate old server/phone-only transfer lists.
- Verify `AuthenticatedTransfersFooter.kt` keeps the approved single-row four-root footer, correct actual-selected state and compact action layout on the primary Pixel. Secondary small-screen adaptation remains required but is not a universal P0 gate.
- Verify the auto-identification exclusion choice occurs in the one Universal Add form only when global enabled, never in active Details; preserve the exact-job preference and one coordinator.
- Default-on sanitized online lookup and the activity-lifetime media inventory host are merged. Check the remaining real-provider/startup-before-Transfers, revocation, zero-HTTP and offscreen evidence; do not dispatch an implementation of already merged code.
- If the user sees a local recognition hypothesis without a provider match, show an accurate stage/reason rather than asserting it was identified. Assess actual rendered compact row and full Details, including honest loading and error states, as part of implementation.

## Implementation domains and shared boundaries (not permanent agent lanes)

The following domains are examples of changes that can be worked together or separately, depending on the current dispatch. They are not fixed agent assignments; check current main before following historical implementation examples.

**Slice A (navigation/UX):** One-row footer + responsive shared action-grid utility and first representative screens; then unify All/phone/server list scopes onto Combined row/Details. Keep a screenshot/semantics action inventory, focus and 320/412dp/200% handling. Touch no Add coordinator or rTorrent RPC implementation.

**Slice B (Universal Add/phone):** Add-time artwork checkbox placement and conditional availability, commit durable exact-job opt-out once (including Share/Open With/restoration), preserve review/default/explicit source semantics. Coordinate concurrent Add composable and verified remote admission edits; do not modify both sides of a contested interface unilaterally.

**Slice C (authenticated rTorrent after active RPC PR):** Preserve authenticated exact-hash routing and readback. Provide authenticated exact-profile connection-save and remote Add/readback contracts for the current navigation/Add implementer; never route automatically to legacy server lists after setup. Avoid adding a new executor, ad hoc request loop, network consent or mutable remote action.

**Slice D (independent QA/integration):** Installed UX cross-scope, footer and media regression tests. Control shared test/CI and central docs, PR dependency order and merging. Qualification is source-exact, independent, all required checks actually executed; no merge from skipped native/R8, old head green, red failures, or an untested assembled tree. Recompute APK SHA-256 from **actual installed/tested bytes** only when source and peer/piece proof match.

**Media/artwork work:** Recognition, providers and poster scheduling form a separate technical slice, not an agent role. Any dispatched agent may undertake one or multiple slices. These are work categories only, not assumptions about currently active agents, specialties or leases.

## Existing PR review instruction

Inspect live open PRs and resolve source/CI dependencies before implementation. Start with #1442 (Options clipboard), #1443 (remote Recheck), #1444 (typed read-auth 401), #1449 (malformed Share extras), and #1450 (uppercase MAGNET VIEW) only if still open. Require genuinely executed exact-head gates before merge, preserve failed/skipped history in [QUALITY-LEDGER](QUALITY-LEDGER.md), and keep tested predecessor APK receipts distinct from current-tree acceptance. Do not copy a dated PR status table into this contract.

## Acceptance matrix for next push

1. **Navigation:** all four footer buttons in one row at 412dp and 320dp with normal/200% text, IME shown and hidden, every main/nested route; correct focus/semantics and no obscured content. Record usable viewport height.
2. **List parity:** all/phone/server each use same compact row and full `UnifiedTransferDetailPanel`; round-trip retains scope and scroll; duplicate hash on two accounts never merges state; stale/offline source cannot mutate; legacy `detailsOpen` not reachable.
3. **Action density:** exercise Add, remote/phone Details, Inspect files, Copy/Move transfer, Copy files, Remove, Options and Identify correction; prove two/three-across normal grouping without hidden/removed functions or dangerous adjacent destructive actions.
4. **Media:** first-install enabled vs explicit saved opt-out vs provider/network/lock restriction; conditional checkbox **Add only** checked by default, durable opt-out after restart; never in Details. Known movie/year and ambiguous-title fixture, provider configured/unavailable, network metered/offline, actual authorized provider+artwork response, manual poster override; consistent list/details persisted output on multiple environment scopes. Refuse blocked network calls.
5. **Cross-engine daily use:** installed source → Add → Transfers → full Details → reconnect → Stop/Start → Remove; real disposable authenticated rTorrent peers and independently verified pieces, phone HTTPS, exact Place/name and accepted readbacks, no blind writes. Collect per-case receipts/source Git tree, actual tested APK digest and failed-attempt history.
6. **Security and privacy:** no source replacement on Share ambiguity, consent never inferred, no data/payload delete on job-only Move, correct per-server auth and privacy locks, no expanded provider lookups on hidden scopes.

## Handoff and reading order

Read [AGENTS](../AGENTS.md) → [WORK-QUEUE](WORK-QUEUE.md) → [QUALITY-LEDGER](QUALITY-LEDGER.md) + [SPEC-COVERAGE](SPEC-COVERAGE.md) → this checkpoint → [PRODUCT](PRODUCT.md), [UX](UX.md), [TRANSFER-ARTWORK](TRANSFER-ARTWORK.md), [OPTIONS](OPTIONS-INFORMATION-ARCHITECTURE.md), [SMART-ROUTING](SMART-ROUTING-AND-SOURCE-LIFECYCLE.md), and [CI-POLICY](CI-POLICY.md). For previous integration context, see [2026-11-08 next-push handoff](NEXT-PUSH-HANDOFF-2026-11-08.md). [#1453](https://github.com/gamesbyian/Chunks/issues/1453) covers media, [#1454](https://github.com/gamesbyian/Chunks/issues/1454) covers universal presentation/navigation. Recheck live facts before beginning and keep one documentation/integration owner.
