# Add destination identity, inline operation outcome and environment colour parity

**Status:** Owner-approved UX specification, 2026-11-13. **Implementation:** pending; not a shipped or tested claim. This is a focused companion to [compact Transfers](TRANSFERS-COMPACT-LIST-FIRST.md), [remote directory browser](REMOTE-DIRECTORY-BROWSER.md), [unified environments](../UNIFIED-ENVIRONMENTS.md), [UX](../UX.md), and [feature experience contract](../FEATURE-EXPERIENCE-CONTRACT.md). The existing [WORK-QUEUE](../WORK-QUEUE.md) controls order; this document is not an independent backlog.

## One identity everywhere

- The permanent environment identity is `local` or an immutable remote profile ID; the display name is a mutable UI alias, never an RPC endpoint, account identifier or mutation target.
- User-facing compact labels on **Add to**, **Adding to** and the Transfers scope/row are **Local** for the built-in environment and the saved **custom connection name** (e.g. **Wood**) for a remote profile. Where a name is absent, use the connection's existing human-readable fallback from Connections. Do not construct a long string from username, hostname or ruTorrent HTTP-RPC path, and do not expose a private endpoint in ordinary primary UI.
- The full, safely redacted connection/account/endpoint details remain discoverable under Connections settings and targeted diagnostics. Duplicated remote names remain legal; distinguish otherwise ambiguous profiles with a concise secondary disambiguator in the selector, confirmation and accessibility name (never by silently redirecting a command).
- Use a shared source of truth for `environmentId -> displayName/colour`, updated after rename/colour change and across reconnect, list scopes and draft restore. Never derive exact command targets from labels, colours or currently filtered scope.

## Transfers card colour rule

The card's leading vertical stripe, environment label and adjacent dot use the **same effective environment hue** resolved from the profile's colour token, in every list scope and snapshot state. Do not permit a pale stripe paired with a vivid label, or vice versa, for the same environment. If readability requires contrast treatment, adjust text foreground/backplate while retaining a visibly matching source hue for the rail and dot; provide independent text labels, semantic content descriptions, dark/light/high-contrast checks and no colour-only authority. The global Show environment colours preference still suppresses environment-specific hues consistently and must not remove textual source identity, selection or status cues. Remote colour is configured in Connections; Local colour in its equivalent settings; global switch in Options. This refines, not replaces, the compact Transfers design.

## Universal Add: target selector

Below the input/source controls, show **Add to** followed by one compact horizontal row of selectable, 48dp-minimum hit targets: **Local | Wood | [other saved names]**. Avoid stacked giant controls, generic “This device”/“On Server” prefixes and multi-line URL buttons. The row can scroll horizontally or wrap adaptively while preserving focus, TalkBack names, distinct selected state, legible text, stable target identity and a reachable path to all profiles. A compact selected chip may use the environment hue but selected state also needs a non-colour cue.

Show only eligible, configured target environments. Offline/unavailable/restricted targets retain truthful disabled or explanatory state as required by the existing Add capability contract; do not imply that a coloured chip grants Add permission. A selection updates **only the draft**, never submits or transfers payload bytes. **Add transfer** remains the sole submission action. When switching targets preserve the source, alias and safe common draft fields; re-resolve per-environment Place, capabilities, Start rules and validation without silently reusing a local folder as a remote folder or vice versa.

## One in-place Add operation outcome surface

The current **Adding to: [full endpoint]** panel and separate **Operation outcome** card become one stable-position, compact, accessible **operation status** region. Before submission this region may be absent. Once submitted, it stays in the same place near the submission/review controls, remains visible on completion (scroll/focus into view without losing the editable form or fixed footer), and updates **in place** rather than stacking additional status panels. Examples of authored copy:

| State | Primary text | Supporting text and actions |
| --- | --- | --- |
| Submitting | **Adding to Wood** | Submitting transfer… No duplicate tap/retry; identify exact frozen target |
| Acknowledged, verifying | **Checking Wood** | Server accepted the request; verifying exact transfer identity and Start outcome |
| Verified added and started | **Added to Wood** | Transfer started on rTorrent. Peer availability and payload progress remain unverified; **View transfer** |
| Verified added, stopped | **Added to Wood** | Transfer is present but not started; **View transfer** |
| Refused/failed with confirmed no mutation | **Couldn't add to Wood** | Specific actionable error; user can correct input and deliberately resubmit |
| Uncertain | **Couldn't confirm Add on Wood** | Read-only reconciliation/check status; **never** blindly resubmit or report absence without evidence |

The status must distinguish **requested**, **acknowledged**, **verified**, **refused**, **uncertain**, including timeouts and auth expiration; never infer verified payload download from a verified job admission, nor erase a valid verified outcome when the environment becomes stale. Bind the display label, immutable submitted target ID, infohash and actual readback as a single operation receipt, so changing the draft's selected target during or after submission cannot relabel its outcome. Keep full technical diagnostics in expandable details, not the main status surface. Present errors with non-colour semantics, correct spoken announcements, useful retry/reconcile actions and no destructive automatic retry. A fresh Add draft resets this component deliberately, without mutating an existing transfer.

On verified success offer **View transfer**, opening the canonical full Details route for the exact environment + hash; if the job is no longer observable, show an explicit read-only unavailable/reconciliation state rather than opening another profile. Keep the four-root footer fixed.

## Progressive information disclosure and density

Keep the default Save to summary concise: a valid concrete default where known, or **Server default (confirm after adding)** where the exact absolute remote path is unresolved. Hide lengthy rTorrent working-directory/relative-path technical explanations behind contextual **About server folders** / info affordance. Surface a concise inline warning only if it changes the user's current decision; unknown is not evidence of invalidity. Preserve the blank optional folder path and the true remote browse/New folder capability when supported, per the directory-browser contract. No speculative absolute paths.

Do not bury the primary source -> target -> Save to -> Start -> Add transfer path beneath tutorial paragraphs, repeated status cards or debugging strings. Use concise, direct primary labels; advanced descriptions remain available on demand. On the owner Pixel 6 Pro the key actions and status should be reachable without the current long-scroll cost; preserve safe IME/insets, font scaling, TalkBack, screen-reader live announcements and reduced motion.

## Acceptance scenarios and ownership

1. Screenshot/Compose: stripe, label and dot match for two Wood cards in All and Wood-only; another profile differs; recolour, rename, global colours off, dark/light and high contrast behave consistently.
2. Add selector: Local + one/two/three remotes, duplicate names, absent name fallback, disabled/offline profile, multi-line long name truncation plus accessible full text, selected state, one-handed Pixel layout, keyboard and TalkBack.
3. Draft-only switching: no network call on selection; source remains; per-target Place and capability refresh; final submission targets only selected immutable profile.
4. Submitted outcome: submitting -> verification -> verified started/stopped or refused/uncertain **in one region**; no old Adding card plus second Outcome card; no automatic retry on timeout/401; status remains bound to submitted target despite subsequent draft edits; footer remains visible.
5. View transfer navigates exact profile+hash; duplicate hash on two remotes never cross-routes. Verified success copy does not assert payload received or peers connected.
6. Folder information is on demand; blank remote default still admits when allowed; real remote browse/new-folder flows are not removed. Screen visual capture and semantics must be reviewed, with functional and experience acceptance recorded separately.

**Implementation placement:** integrate into the existing R1 Universal Add/Transfers/Connections UX work, coordinating the same composables and shared identity tokens. No new backend or CI lane, no redundant design-system rewrite. Docs-only work should run documentation-specific local checks, not Android/remote acceptance suites.
