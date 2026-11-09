# Chunks universal navigation, Add, Connections and transfer disclosure

> **BINDING 2026-11-09 ROUTE/DENSITY UPDATE:** [binding 2026-11-09 next-push UX reconciliation](NEXT-PUSH-UX-RECONCILIATION-2026-11-09.md) supersedes any conflicting inline expanded-card or two-row-footer guidance below. **Exactly one canonical compact Combined-derived transfer row**, same in All/device/server filters, tap to **dedicated full Details**; no complete tool bank in list entries. **One-row fixed footer Transfers | + Add | Connections | Options** across all routes/IME; action groups two or fitting three across by default. Preserve every existing capability, safe exact-account mutation and narrow/large-text accessibility. Add's default-checked artwork checkbox appears only when global feature enabled; never in active Details.


**Status:** owner-approved normative UX direction, 2026-11-05; implementation is incremental and acceptance must distinguish current/target. Read with [UX](UX.md), [PRODUCT](PRODUCT.md), [UNIFIED-ENVIRONMENTS](UNIFIED-ENVIRONMENTS.md), [ADD-SOURCE-INTAKE](ADD-SOURCE-INTAKE.md), [TRANSFER-ARTWORK](TRANSFER-ARTWORK.md), [TRANSFER-PRIVACY-LOCK](TRANSFER-PRIVACY-LOCK.md), and [cohesion campaign](UX-COHESION-AND-AUTO-ARTWORK-2026-11-05.md). Execution priority remains only in [WORK-QUEUE](WORK-QUEUE.md). This contract supersedes descriptions of separate permanent phone/server Add screens, repeated connection editors, or permanently expanded row toolboxes.

## Primary navigation

The durable destination model is **Transfers | Add | Connections | Options**. The footer must always expose **Add** while the app is usable, regardless of active phone/remote/Combined browsing. The same Add route is used after an Android Open With/Share of magnet, .torrent content or supported HTTP(S) .torrent URL. The footer destination must not silently infer a different Add target from the current filtered inventory. Details is a *drill-down* from an exact transfer, not a fifth permanent tab. Preserve navigation context, drafts, keyboard focus and scroll across destination changes where applicable. An unlocked transfer list may render while automatic media enrichment runs in the background.

### Genuine empty setup

When **no local transfers exist** and **no remote environment is connected or saved**, show an explanatory primary empty state, not an empty row list, protocol diagnostics or compulsory remote configuration:

> Your transfers will appear here. Start a download on this phone, or connect a remote BitTorrent client. You can use Chunks with either, or both.

The message has two accessible **text links**, **Add a torrent** and **Connections**, navigating directly to those routes. No remote login, local engine startup or Add mutation occurs by merely opening the state. A saved but disconnected remote profile is *not* first-launch: show its status and a contextual reconnect action. An empty *filtered*, stale, loading or privacy-locked inventory is not first-launch: distinguish each condition and preserve filter/lock recovery. The empty state must work on the owner's primary Pixel, large text, TalkBack and keyboard; effective link targets remain at least 48dp.

## Universal Add: one destination, one draft

1. A single screen owns the draft's source, exact destination environment/session, effective **Place**, automatic start choice and optional advanced actions. Incoming Android intents enter this same screen with a source populated but **never** execute an Add automatically. At most one user-authorized execution action, without a compulsory second review page, submits the draft; only materially missing, invalid or consequential settings need resolution.
2. Sources include magnets, local .torrent content from picker/Open With/Share, HTTP(S) URLs containing .torrent metainfo (including extensionless URLs) and eligible foreground clipboard prefill into *empty* fields. Existing draft and higher-priority explicit input always win. Validate first; source previews may show name/files/size before execution.
3. **Environment** is an explicit selector for phone, current signed-in remote, and safely reconnectable configured remotes. Disabled/unverified/locked targets cannot inherit another session's authority. An environment can be saved as a preference, but filtering the Transfers list cannot change it. If a remote target needs authentication, preserve the draft while directing the user to Connections and return to Add after verified sign-in.
4. **Place** shows the selected environment's verified current default, with one-time Change and recent/known target-specific locations. Phone default is Android Downloads/Chunks Downloads; rTorrent default is the server's configured directory. Never silently send a phone path to rTorrent or infer server write permission from an observed path.
5. **Start automatically** has an explicit effective default and an accessible switch. A stopped Add must remain startable. Optional transfer display alias, actual file/folder rename, file priority, selected-file selection, torrent speed/seeding policies and collection/organization are under progressive **More options** and offered only when capabilities and safe preview/authorization exist. Display alias and actual on-disk name mapping are distinct; the latter must not be simulated.
6. On submit, use existing authorized per-engine Add implementations, verified outcomes, uncertain-action readback and data integrity gates. Shared UI does not mean a new bespoke torrent executor. Verify the exact environment+hash result, show it in Transfers, and retain the prior draft if refused or uncertain. No blind retries.

**Migration rule:** the existing LocalEngineScreen Add, VerifiedAddScreen, clipboard/URL, SAF and remote Place implementations remain working adapters until their presentation is successfully consolidated. A temporary target chooser is not acceptable as the final universal Add destination, nor may a universal header merely lead users to duplicated independent full forms. The common draft and completed action remain authoritative.

## Universal Connections

One app-wide **Connections** destination owns all saved/active remote-client profiles and per-profile edit, add, delete, connect, disconnect, test and authentication actions. It preserves existing numbered ruTorrent paths, verified tenant identity, TLS, Basic/Digest and the remembered-login/biometric policies. Each row exposes connection state with text and optional color, and a contextual overflow/details editor for advanced transport/auth options. Selecting a saved profile for an Add never silently changes the currently authenticated account. Connection recovery returns to the caller's draft/list and cannot submit a torrent automatically. Future Transmission/qBittorrent/Deluge adapters use the same screen and show capabilities instead of pretending all backends support identical options.

## Transfer row disclosure, consistently across environments

- **Collapsed by default:** small square or aspect-preserving poster tile; recognizable title and exact environment label/colour; current state; recency-preserving segmented progress; pertinent download/upload rates and ratio. Include bounded, truthful unknown/offline indicators. Compact text and no permanent bank of details/actions. The entire row, except independently interactive controls, is the expansion target.
- **Tap row:** expand **in place** to approximately twice the normal height (content and font scaling take precedence over a literal height). Add the next useful facts such as size, peers, remaining/ETA *when evidence permits*, or relevant file summary. Include no more than a few frequent scoped controls, such as a **priority** adjustment or **turtle** speed-policy toggle, only when supported and with current state/effect clearly exposed. A tappable **All details** entry is always reachable. Tap again collapses; selection and screen-reader semantics distinguish expanded/collapsed. Don't make row expansion accidentally start/stop/remove a transfer.
- **All details:** dedicated exact-environment, exact-hash screen for full progress/status, files/priorities, trackers/peers, limits, seeding policies, activity/diagnostics, per-transfer renaming, metadata, artwork selection/override, Similar Transfers and uncommon operations. Organize by tabs/sections; deep controls are contextual. Changing provider artwork should not appear as a permanent global-list control.
- Respect installed status/permission freshness, protection locks, 48dp actionable targets, readable text at enlarged font, keyboard/TalkBack/reduced-motion preferences and existing native command readback. Preserve per-transfer expanded state sensibly without resurrecting action authority from a stale session.

## Vertical slices and acceptance gates

A. **True-empty journey:** first launch into the transfer inventory, link to Add and Connections; assert no remote calls or torrent mutation, distinguish saved disconnected and filtered empty. This is the first incremental slice, independent of unresolved Add architecture.

B. **One navigation owner:** centralize footer and Android intent routing into durable Transfers/Add/Connections/Options destinations. Keep existing engine executors behind the destination without duplicate full-page navigation. Test phone-only, remote-only, multiple connected/saved profiles, restoration and a private magnet containing a passkey (no persistence/log leaks).

C. **Universal Add data and control:** move both current Add surfaces under one draft/state/validation UX. Verify source precedence, actual defaults/Place overrides, auto-start, chosen environment, optional naming/priority capability gates, canceled/failed/uncertain outcomes and actual successful phone/rTorrent Add.

D. **Universal Connections:** use one profile list+editor for disconnected, connected, locked, saved and future backends. Preserve credentials and reconnect/readback protections, and return to a pending Add draft.

E. **Three-level transfer rows:** collapsed/expanded/All details in phone, rTorrent and Combined; screenshot whole screens against owner references, use approved SVG/illustration assets, then validate device, 200% text/semantics, tap targets and genuine running/paused/unknown state. Avoid rendering unsupported speed policies as active controls.

All slices require their own exact-head build/tests and credible installed screenshots. Do not mark the four-item mandate complete because the empty-state message landed. Do not merge or waive failing unrelated CI silently.
