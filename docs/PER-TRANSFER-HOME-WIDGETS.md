# Per-transfer Android home-screen widgets

**Status:** owner-approved future UX; deferred until unified transfer identity, lifecycle observation and persistence have passed their G1/G2 gates. One independent widget instance per transfer is the primary design. This document is the canonical feature contract; work ordering remains in WORK-QUEUE.

## User journey and presentation
- Transfer details / overflow menu: **Pin to home screen**. Use Android's standard widget pinning flow when supported, with normal widget picker fallback. Require affirmative user placement; never silently add icons. Allow multiple widgets for the same transfer and multiple independently positioned transfer widgets.
- Each instance represents **exactly one** transfer. Do not substitute a multi-transfer dashboard as the primary design.
- Compact icon-like widget, ideally 1×1 launcher cell where launcher permits, with progress ring, readable percentage and state symbol; optional transfer artwork behind high-contrast overlays. 2×1 shows label, progress, rate/ETA where known; 2×2 may expose richer controls. Handle host grid sizing gracefully; no guarantee of physical icon dimensions.
- Tap opens that exact transfer detail. Destructive and state-changing controls, if introduced, must obey live capabilities, authentication/lock, freshness and confirmed command semantics; tap must never mutate the transfer merely by opening it.
- Cover downloading, stalled, checking, queued, paused, seeding, completed, error, locked, offline/unknown and stale states; do not imply that the last known progress is current. Accessibility: TalkBack description with transfer and environment, redundant non-colour cues, contrast, 200% font fallback, reduced motion, variable widget size.
- Always show human-readable source identity, especially with matching hashes across local and multiple servers; use palette colour as secondary decoration, not sole identity.

## Identity, dataflow and cost
- Persist `appWidgetId -> immutable environment/profile ID + verified transfer infohash` using app-owned storage and process-death-safe restoration. No title/path/position-based lookup. Distinct remote profiles with identical hashes remain distinct. Avoid binding a widget to the currently selected Transfers filter.
- Observe existing unified transfer state rather than creating one connection, poller, or job per widget. Coalesce widget updates, use bounded cadence, respect background/Doze limitations; update on meaningful transitions and avoid promising frame-rate/live-second refresh.
- Remote connectivity failures show unavailable/stale and last observed time as applicable; never reconnect indefinitely just because a widget exists. Respect profile lock and privacy policy, including notification/widget visibility and redaction of media names/artwork.
- Renaming, sorting, changing metadata/artwork or restarting Chunks must preserve bindings. Widget removal by the user releases instance bookkeeping; multiple instances must remain independent. Verify snapshot restoration and invalid/missing identity behavior.

## Lifecycle and removal: owner intent and Android constraint
**Owner goal:** deleting/removing a transfer should require *zero additional home-screen housekeeping*. The widget should disappear with the job; completion or seeding alone does not remove it. Deleting the payload without removing the job does not automatically end its widget.

**Platform limitation:** an ordinary Android app cannot generally force a launcher to remove an arbitrary hosted `AppWidget`, nor guarantee that a pinned shortcut is removed. Do not promise automatic disappearance without a verified launcher capability; do not invent a launcher privilege or use deceptive behavior. On actual job deletion, release transfer observation, clear/redact sensitive cached content, and render a clean inert terminal/empty state when the host retains the widget. Do not preserve stale artwork or misleading controls. Seek and test legitimate host-specific removal support; report unsupported launchers plainly. A dead widget must not launch a different transfer, re-add a deleted job or trigger unwanted remote network work. If this fallback is too cumbersome in real use, treat the design as an open UX acceptance gap rather than silently declaring the owner goal met.

Distinguish confirmed job removal from temporary network loss, disabled profile, authentication lock, filtered-out row and incomplete snapshot. Never clear widget bindings solely because a remote transfer was absent from a stale or partial fetch.

## Dependencies and staged implementation
1. **G1/G2:** trustworthy exact environment-plus-infohash identity, verified job removals, shared transfer observation, restart/process death handling, protected state.
2. **Prototype/compatibility study:** Android API and launcher matrix (Pixel Launcher, common Samsung/third-party launchers where accessible); test 1×1 sizing, pinning, update throttling, deletion lifecycle and host control. Explicit go/no-go on the zero-housekeeping goal.
3. **Single read-only compact instance:** deterministic state renderer, deep link and privacy lock; JVM tests of binding/reducer and host instrumentation.
4. **Multi-instance + resize:** shared observable updates, expanded formats and optional controls only after exact-scope command contracts pass.
5. **Acceptance:** local and two remote profiles sharing a hash, remote offline/locked/stale, paused/seeding/completed, actual verified job removal, payload-only deletion, rename, process death/reboot, launcher restore, user widget removal, 200% text/TalkBack, low-power/Doze and no per-widget RPC or busy polling.

**Priority:** deferred P3 presentation/OS integration. No core-engine or remote hardening task should be displaced by this feature.
