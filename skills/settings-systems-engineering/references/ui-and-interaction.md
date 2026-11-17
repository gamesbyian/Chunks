# UI, organization, accessibility and discoverability

## First classify placement by user task

1. **General, infrequently changed default:** central Options.
2. **Frequent contextual control:** alongside the affected task (transfer sort/filter, playback action, one-off output destination).
3. **Contextual override of a global default:** near the task *when explicitly permitted*, labeling the override and its scope; optionally link to global owner.
4. **One-time action or destructive operation:** command/control with proper review and execution semantics, not a settings registry boolean.
5. **Complex entity configuration:** purpose-built editor that may reuse control primitives, not a pile of independently committing fields when the whole object must remain coherent.
6. **System-managed permission or OS preference:** explain status and send the user to the supported system controls, never imitate a successful platform permission change.

The distinction follows Apple Human Interface Guidelines' global-versus-task-specific settings and Android's organized preference hierarchies. Do not inflate Options merely because a control has a state.

## Declarative composition

- Keep *settings definition* distinct from *page composition*. A page manifest identifies groups and stable setting IDs; it does not own the values, re-declare defaults or call persistence directly.
- Renderers should accept a resolved typed state and callbacks. Reuse switch, choice, slider, number/text validation, folder picker and read-only status components. Make custom controls only when standard components misrepresent the interaction.
- Multiple appearances must remain synchronized by observing the same backing repository/effective-value stream. Do not create per-screen cached writable preference state.
- Search indexes stable ID + localized label + meaningful synonyms and can navigate to the canonical page/focus anchor. Preserve originating query, scroll, unsaved valid draft and navigation history. Search must not expose hidden secure controls or imply unsupported options are available.
- Empty, loading, unknown, error, permission/capability unavailable, inherited, policy-locked, pending-write and reset states need observable presentation when reachable.
- If a setting is moved, preserve semantic deep links and aliases, and reconcile current navigation and automated selectors. The new parent category must be truthful: a subpage label is not proof of ownership.
- Never add a dead switch for a planned feature to make the menu appear complete.

## Commit semantics and correctness

Choose per-setting commit mode based on actual effects and owner-approved product behavior:
- Independent binary/select choices: immediate apply and persist, with observable pending/failure.
- Sliders: defined continuous or release-point commit; throttle only if necessary; don't make stale asynchronous writes win.
- Typed text: maintain local draft while incomplete/invalid, validate on focus loss or defined debounce, then persist; don't send half a hostname/path to a remote service.
- Composite credential/remote config: explicit coherent transaction, review/Connect/Authorize where demanded by protocol or risk.
- Destructive effects or unsafe capability changes: separate confirmation and verified operation, never an innocuous toggle pretending success.

On failed persistence show effective truthful value and recoverable error, or explicitly identify unsynced intent. For remote-apply uncertainty, read-only reconciliation comes before any retry. Do not mistake a local preference write for applied engine state.

## Accessibility acceptance

Apply project targets and platform requirements: Android generally recommends at least 48dp interactive targets, readable text and font scaling, contrast, accessible state semantics, useful labels, focus order and keyboard/TalkBack support. A switch's stable label describes what it controls; the on/off state is separately announced. Color alone never identifies a client, error or on/off state.

Walk at the smallest supported width, 200% font size where contract requires, portrait/landscape/IME, keyboard navigation, TalkBack, reduced motion and focus after a toggle/search/deep link. Ensure no focusable controls are clipped under fixed footers or nested scroll containers. Do not mark a form visually accepted based on code review or generated screenshots alone.

## Output checklist

For each touched page: placement and reason; stable IDs and semantic labels; value origin/scope; enablement/hidden reason; commit and failure feedback; reset; search/deep link; keyboard/TalkBack focus and reachable small-screen geometry; user-facing proof. For internal-only storage changes, record experience impact as none rather than creating pointless screenshot work.
