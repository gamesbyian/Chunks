# Transfers: compact list-first screen (owner-approved binding UI contract, 2026-11-13)

**Status:** owner-approved target. Implementation staged in the coordinated Transfers presentation PR: exact shared rail/dot/label hue, per-client count chips, 4+ All threshold, header live totals, colour preference controls moved to Connections and Options, old bottom chooser/scope/totals removed. **Merged:** [PR #1703](https://github.com/gamesbyian/Chunks/pull/1703) places flexible Search beside 48dp Filters/Sort and Batch disclosure icons, retains the existing safety-gated panels, and moves nondefault-view descriptions inside Filters. Exact-head JVM foundation, R8, native Add, Android 10 Downloads, two-real-server and lifecycle checks passed before merge. Pixel physical visual acceptance is still required. **Still pending:** review of remaining redundant bottom diagnostic prose, full device/accessibility visual acceptance and installed multi-profile/colour-off verification. A committed UI slice is not proof of installed/owner acceptance. This is a design/specification, not evidence that the APK already behaves this way. Applies to the single canonical Transfers browser in All, Local and named-remote scopes. [UX](../UX.md), [unified environments](../UNIFIED-ENVIRONMENTS.md), [Options](../OPTIONS-INFORMATION-ARCHITECTURE.md), [feature experience contract](../FEATURE-EXPERIENCE-CONTRACT.md), [work queue](../WORK-QUEUE.md) and [coverage](../SPEC-COVERAGE.md) inherit this placement contract.

## Information hierarchy (top to bottom)

1. Existing compact Chunks wordmark and **Transfers** heading without dashboard-like dead space. Do not add a welcome hero when real transfers exist.
2. A **single compact horizontal environment-selector row** of accessible selectable chips. Display **Local (N)** and each user-assigned remote name with count, e.g. **Wood (151)**. Names and counts are live; counts are total transfers known to that environment, not narrowed by current text search/status filters. Distinguish unavailable/stale inventory: do not represent cached counts as currently live or zero out unknown counts. Keep selection recognizable independently of hue.
3. A **single row** containing a flexible name/hash Search field plus small icon controls for **Filters / Sort** and **Batch tools**. Both functions remain fully usable via accessible menus/sheets; no permanent oversized Filters or Batch Tools buttons. Search and filters must remain distinct operations even if launched from one combined filter/sort affordance; preserve existing sort keys, status filters, selection and bulk safety gates. Icon hit areas >= 48dp, visible keyboard focus, TalkBack labels/actions/states. At Pixel 6 Pro widths, do not force these controls into a second line. At accessibility text sizes, adapt without loss of operation.
4. **One small, immediately visible summary line ABOVE the first transfer card** showing count for the current visible result set and aggregate live download/upload speeds for the selected environment scope. Suggested: `49 transfers · ↓ 0 B/s · ↑ 664.5 KiB/s`. Keep names/count/units localizable; handle 0, loading, stale and disconnected cases without invented live activity. An All/multi-scope total deduplicates no jobs merely because hashes match: job identities include environment. Individual chip counts do not change with search/status filtering.
5. **Canonical compact transfer cards**, consecutive in one scrollable list and with the existing Details/expansion/verified action model. Preserve artwork, progress, per-job rate/ratio, source identification where needed, source-account authority and touch access. The final transfer is the end of the list, not the beginning of a secondary dashboard.
6. Existing always-present **one-row four-root footer**: Transfers, + Add, Connections, Options.

## Toolbar implementation and interaction contract

The search field stays editable and keeps its own explicit Clear action and IME focus. Two adjacent, fully labeled 48dp icon controls expose **Filters and sort** and **Batch tools**. Both are disclosures, not actions that can submit a transfer command. Their Expanded/Collapsed state is spoken independently; an active filter/sort is indicated without relying on colour. All existing filtering, grouping, sorting, exact-key batch confirmation, and refusal pathways remain inside those disclosures. At 320dp with 200% font scale, no button may become an off-screen horizontal target, and the source search must remain operable.

The default list omits repeated Viewing/filter-help paragraphs and oversized reset CTAs. Nondefault scope/filter details and Reset to all move into Filters; the empty-filtered state must retain a discoverable independent recovery path. Do not suppress genuine offline/locked/incomplete source warnings or invent a live aggregate rate from stale inputs.

## Environment scope behavior

- Count **configured environments including Local** for the visibility threshold, not online-only or nonempty environments. Local is included even if its engine is currently unavailable/offline. Remote profiles retain their order/identity through disconnect and reconnect.
- **1, 2, or 3 configured environments:** show only their individual chips; **do not show All**. **4+ configured environments:** additionally show **All** (without a bracketed count), alongside individual chips. Horizontal scrolling with legible labels and an apparent scroll affordance is allowed and preferable to squashed chips.
- Each individual chip shows `Name (N)`; **All** never shows brackets or count. Scope availability must never be inferred from count zero. Labels must not say `On this device`, `Only this device`, `Only connected server`, or expose raw endpoint addresses as routine selector copy.
- **Default scope is combined/all environments**, including with <=3 configured and no visible All chip. Keep combined scope accessible with <=3 via a clear reversible multi-select model: tapping an individual chip toggles inclusion and selected chips indicate all included; selecting the last deselected chip returns to combined. Prevent an empty selection (reject a would-be last deselection or restore All with explicit feedback). When 4+, All chooses all; individual chip selections may still represent any nonempty subset. Preserve selected subset across navigation as existing contracts require.
- Counts come from the canonical environment inventories, remain stable under search, sort and view filters, and refresh when inventories change. Offline/stale/unknown environments show an honest state (e.g. `Wood (151)` with stale/offline semantics or `Wood (—)` if count unknown), not a fabricated accurate number. The summary rate is aggregated only from known live active data within the selected scope; never include offscreen/unselected environments just because they exist.
- No selector action changes an Add destination, authenticates a profile, unlocks a locked source or grants transfer mutation authority. Keep exact environment/account/infohash attribution and per-action capability gates.

## Remove from Transfers (relocate only where useful)

- Remove the **bottom-of-list live-total** readout and move its compact count and aggregate-speed information into the summary above the first row. Do not duplicate it at the bottom.
- Remove persistent **Environment colours…** from Transfers, the **Only this device / Only connected server** big buttons, **Open device transfer controls** dashboard CTA, and the floating up-arrow used for that obsolete dashboard section. Retain legitimate local engine controls under the appropriate Local environment/device settings route, reachable from Connections or Options, not a footer dashboard.
- Remove verbose `Viewing: Server · Name · host/path · All transfers` banners, `Show all transfers` full-width CTA, `New downloads may be hidden…` explanation and similar filter-help prose from the default view. Existing filter clear/reset remains available inside Filters with meaningful active-filter indicators and an accessible empty-filtered state.
- Remove redundant status blocks that repeat transfer-card data outside the cards. Do not remove necessary progress, safety, error, empty/offline or action feedback.
- Preserve permanent footer navigation. No new top-level destination.

## Colour ownership

- Each **remote connection's configuration** in Connections (and the same configuration authority reached from Options > Connections) exposes **Environment colour** palette choice and **Restore automatic colour**. The Local environment has equivalent settings through Local/device configuration in Connections/Options.
- **Options > Appearance & accessibility** exposes one persistent global **Show environment colours** switch, **enabled by default**. Turning it off suppresses environment-derived colours across selector chips, transfer rails/source labels and applicable app surfaces, while keeping semantic progress/success/warning colours and selected/unselected focus states intact. It does not erase per-environment colour selections. Re-enable restores choices unchanged. No second independent colour storage.
- Hide the old standalone Transfers `Environment colours…` action. Never rely exclusively on colour to convey environment, selection, availability or authorized source.

## Visual/interaction acceptance

- Review on the owner's Pixel 6 Pro with the approved visual references and real rendered screenshots, including start/top, scrolled end/bottom and the fixed footer. Verify first card is high above the fold and no explanatory blocks or large controls intervene.
- Configure Local-only; Local+one; Local+two; Local+three; and more remotes. Confirm **All absent for counts 1–3 and present at 4+**, never numbered; chip brackets show per-environment inventory; overflow works; All/multi-selection retains a nonempty usable combined view.
- Exercise nonzero/zero inventories, stale/offline reconnection, same hash on multiple environments, active search/status filters and clear/reset, sort, batch mode, and changing inventory. Summary count and rates must correspond to actual scope, filter semantics and live data, not text copied from one card.
- Confirm per-profile colour edit and restore, Local colour edit, global off/on preserving selections and contrast; verify TalkBack, keyboard/focus, IME, large text, tappable >=48dp controls, and expanded transfer/Details navigation.
- **Documentation-only change:** run documentation/static validation only when appropriate; do not trigger emulator/native integration builds just to change this specification. Capture behavioral and visual acceptance separately when implementation lands.

## Wireframe (logical order only)

```text
chunks.
Transfers
[ Local (3) ] [ Wood (151) ]               // <=3 envs: no All
[ Search names or hashes ... ] [filter/sort] [batch]
49 transfers · ↓ 0 B/s · ↑ 664.5 KiB/s
┌ Transfer card / Details ┐
├ Transfer card / Details ┤
└ ...                     ┘
[Transfers] [+ Add] [Connections] [Options]

4+ environments:
[ All ] [ Local (3) ] [ Wood (151) ] [ Other (N) ] ...  ↔
```

Numbers above are illustrative, never hardcoded. The reference is about *hierarchy and placement*, not exact pixels.
