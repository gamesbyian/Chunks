# Chunks interaction and accessibility contract

**Purpose:** one intentional interaction system for a native Android BitTorrent client and rTorrent remote, not a chronological log of feature requests. **Authority:** [PRODUCT](PRODUCT.md) owns promises; this file owns cross-surface UX rules; [UNIFIED-ENVIRONMENTS](UNIFIED-ENVIRONMENTS.md) and the linked focused contracts own domain-specific identities and capabilities. [SPEC-COVERAGE](SPEC-COVERAGE.md) and [QUALITY-LEDGER](QUALITY-LEDGER.md) alone own implementation/evidence status; [WORK-QUEUE](WORK-QUEUE.md) owns execution order. The [unchanged original UX notes](archive/UX-2026-10-30-pre-reconciliation.md) are archived for traceability and are non-normative.

## Philosophy and priorities

1. **Visible choices, effortless defaults.** The ordinary Add/transfer journey should work with no optional configuration when a verified, authorized default exists. Expose important options through a readable current-value summary plus contextual **Change**, not a compulsory setup interview or a hidden maze of settings. Enthusiasts retain granular control; there is no forked novice/expert application.
2. **A skipped choice still has a comprehensible outcome.** For every optional option, define the effective default, what happens if untouched, how the result is observed, what happens on interruption/restart and where it can be adjusted later. A user should never need to reconstruct the initial Add flow to recover a file or change a supported setting. Consequential acts that have no verified safe default **hold that act**, preserve progress/draft and ask only for the missing choice.
3. **One experience, exact scope.** Phone-only, remote-only and combined multiple-server usage share vocabulary, layout and interaction patterns. The currently visible environment subset is a view/filter, not an Add destination, a setting target, a rule scope or a command broadcast. Every material action says which exact transfer, Place, file and server it affects. Names and persistent palette colours help recognition; text and accessible semantics are still mandatory.
4. **Tell the truth about activity and outcomes.** Separate *requested*, *acknowledged*, *observed*, *verified*, *refused*, *partial* and *uncertain*. A started daemon flag is not verified file progress; zero speed is not proof of an error or lack of demand; an RPC reply is not proof that remote bytes moved; a disconnected phone doesn't prove the server stopped. Explain what is known, what remains unknown, when it was observed and the nearest safe next action.
5. **The same control belongs to the user's task.** A contextual **Why?**, **Change** or **Settings affecting this transfer** opens the **single authoritative** scoped setting or effective-rule evaluator, retains user context, and returns without losing focus, scroll, search, filter or draft. Advanced diagnostics stay accessible under detail instead of replacing the normal task vocabulary with protocol jargon.
6. **Protect bytes and consent before polish.** Job-only Copy/Move Transfer, backing-file relocation/rename, file publication, extraction, cleanup and actual payload copies are separately named and authorized. A friendly UI never overrides native non-clobber, per-host authority, privacy, uncertain-command or destructive-action gates. Missing proof leads to an explicit limitation and supported alternative, not an optimistic success banner.

## Shared interaction grammar

| Question users need answered | Mandatory UI behavior |
|---|---|
| **Where am I?** | Show the chosen phone/server subset, exact source name/ID and distinction between a Transfer, physical Place, file, Collection or optional Task. Single-environment users do not need redundant selectors. |
| **What is the setting?** | Show readable label, effective current value, how it was obtained (**Automatic**, **Using your preference**, **Suggested**, **Override**, **Unknown**), scope (**App**, **On phone**, **[profile]**, **This transfer**), and a same-context **Change**. |
| **What will it do?** | Preview affected jobs/files/profiles, permissions, network or storage side effects, seeding consequences, and whether it changes only this item or future defaults. Do not use the selected view filter as a mutation target. |
| **Did it work?** | Show **Requested / Acknowledged / Verified / Partial / Refused / Uncertain**, observed timestamp/freshness and individual results for batches. Never auto-repeat an ambiguous mutation. |
| **What next?** | Provide **View transfer**, **Check status**, **Change destination**, **Review sources**, **Open / Show files / Get on phone** or a supported recovery path in the object's own screen. Preserve exact target and drafted work after reconnect or process recreation. |

A primary status line should tell the person **what is happening**, and a nearby contextual action should explain **why** or provide a safe next step. Raw peer/tracker/piece/RPC counters remain available under technical details. Differentiate **Waiting for torrent details**, **Waiting for a queue slot**, **Checking data**, **Paused by you**, **No activity observed (cause unknown)**, **Destination unavailable**, **Remote offline**, **Locked**, **Still confirming**, and **Downloaded · Sharing** only from actual evidence. Never infer a particular cause merely from zero rates or a short sample. If a policy wins, show which policy and environment; if unsupported, show **Cannot enforce here**.

## Ordinary Add without a configuration tax

Magnet, Android Open/Share, .torrent, URL, manual Add and optional Discovery feed into the same reviewed Add surface. Display relevant, compact, accessible rows, not a wall of esoterica: **On phone / [server]**, **Save to [verified default] · Change**, **Files: All available / Waiting for metadata · Choose**, **Sharing: Automatic [effective rule] · Change**, **Order: Normal · Change**, **Start after verified Add · Change**. A meaningful default plus the prominent **Download / Add** action requires no optional tapping. Wrap these rows at large text sizes rather than hiding them behind a horizontally-only strip.

A phone default is **Downloads / Chunks Downloads** only where actual native backing storage is verified writable and safe; otherwise explain the real supported alternative. Remote uses that exact server's default and never invents a Chunks folder. With multiple plausible destinations, use an explicit saved Add target or request the missing choice; the last viewed profile and the combined list never choose for the user. A one-off folder, seeding, file-priority or ordering choice does not silently change the environment default.

Automatic read-only checks of existing verified metadata, observed storage capability, applicable approved policies and status are welcome. Ignoring a control does **not** authorize DHT, third-party enrichment, index publication, new external network discovery, automatic intake/Start beyond saved consent, destructive cleanup or file moves. If metadata arrives late, recompute the affected summary while retaining user overrides and asking permission for any newly consequential act. After Add, expose exact Place, environment, observed Start/progress, **View transfer**, and a supported file-access path; never imply remote Add downloaded files onto the phone.

## Filter and settings findability

**Transfers** always shows current search, chosen environment subset, active filters and a reachable **Show all / Clear filters** action. Distinguish **No transfers here**, **No matches for these filters**, **Loading**, **Some sources unavailable**, **Locked**, **Stale results** and **Refresh failed**. Show **N hidden by filters** only when count is supported by a consistent authorized inventory. A verified Add hidden by the current filter gets an exact **View transfer** route and explanation; clearing a filter must never delete a job or choose a different server. Combined sorting/filtering is over one union, not individually sorted lists concatenated. All user-selectable sort keys support both directions; grouped views retain the shared secondary sort and direction.

Offer **Search transfers** and **Find files across selected transfers** side by side. File-search results are paged/virtualized and carry exact profile, hash, file index, metadata freshness and unknown values. Finding an entry in torrent metadata does not prove its bytes exist, are readable or can be renamed; multi-file batch mutation requires separate scoped capability review. Support plain-language setting search and contextual routes for **Why isn't this starting?**, **Why no upload?**, **Where are the files?**, **Will it stop sharing?**, **Why is this hidden?** and **Did that save?**. Do not duplicate settings storage or evaluator logic across entry points. When a numeric value like zero has engine-specific meaning, show **Unlimited**, **Inherited**, **Disabled**, or the verified exact meaning instead of exposing a misleading bare 0.

## Non-negotiable action, continuity and accessibility patterns

A **Copy Transfer / Move Transfer** operation moves/copies torrent **registrations only**; target Add is verified first, source job retirement is separately confirmed for Move, and original payload bytes remain. **Copy files**, **Save library copy**, **Move backing files**, **Rename actual files**, **Extract**, **Delete original archive** and **Remove transfer & data** are different scoped operations. Each has its own consequence preview, permission, readback, and uncertain-state recovery. No silent file overwrite, no inferred ownership from a path and no data deletion simply because extraction succeeded. Stale selections and profile switches invalidate mutation confirmations while preserving safe navigation state.

At **320×700** and **412×892**, 200% font, TalkBack, keyboard/D-pad, high contrast, reduced motion, screen insets/rotation and colour-vision differences, all core actions remain reachable. Minimum 48dp-class targets; no critical action depends on a horizontal-only scroller, hidden long press, icon-only meaning or inaccessible banner. Selection, scroll, focus and review survive recycling/re-entry when valid, but a privacy lock redacts content and inhibits commands. Notifications, recents and accessibility trees must not leak protected metadata. Test state semantics and layout with automation, then separately with a physical handset and human comprehension.

Use an uncoached **40-second teach-back** as a future acceptance test: a casual user should explain which device/server a transfer is on, where files go, whether it is downloading or sharing, what a skipped Add option does, why a paused/queued/uncertain item needs attention, and how to change a choice later. A power user should reach expert details without knowing their original settings category. Test skipped-all-options, same-hash two-server views, filtered-away new jobs, stale profiles, pending metadata, revoked Places, disconnected remote, uncertain Start, process restoration and wrong-scope batch operations. These are acceptance goals, not evidence of physical testing.

## Focused contract index

**Identity and cross-profile:** [UNIFIED-ENVIRONMENTS](UNIFIED-ENVIRONMENTS.md). **Effective sharing and queues:** [SMART-SEEDING-RULES](SMART-SEEDING-RULES.md), [INTAKE-QUEUE-AND-POLICIES](INTAKE-QUEUE-AND-POLICIES.md), [EFFECTIVE-POLICY-EXPLANATIONS](EFFECTIVE-POLICY-EXPLANATIONS.md). **Adding and creating:** [SMART-REMOTE-CONNECTION](SMART-REMOTE-CONNECTION.md), [CREATE-AND-SHARE](CREATE-AND-SHARE.md). **Files and bytes:** [STORAGE-AND-EXTRACTION](STORAGE-AND-EXTRACTION.md), [CROSS-ENGINE-TRANSFER-JOBS](CROSS-ENGINE-TRANSFER-JOBS.md), [CROSS-SEED-AND-PAYLOAD-COPY](CROSS-SEED-AND-PAYLOAD-COPY.md), [EXTERNAL-FILE-ACCESS](EXTERNAL-FILE-ACCESS.md), [TRANSFER-FILE-RECOVERY](TRANSFER-FILE-RECOVERY.md), [PLEX-FILENAME-REPAIR](PLEX-FILENAME-REPAIR.md). **Optional layers:** [TRANSFER-ARTWORK](TRANSFER-ARTWORK.md), [TRANSFER-PRIVACY-LOCK](TRANSFER-PRIVACY-LOCK.md), [TASK-OUTCOMES-AND-CONTINUITY](TASK-OUTCOMES-AND-CONTINUITY.md), [EVENT-AUTOMATION](EVENT-AUTOMATION.md), [EXTERNAL-DESTINATION-DELIVERY](EXTERNAL-DESTINATION-DELIVERY.md), [PER-TRANSFER-HOME-WIDGETS](PER-TRANSFER-HOME-WIDGETS.md). **Visual reference:** [source sheets](design/reference-images/README.md) are inspiration, not proof; approved icon SVG and assets follow [AGENTS](../AGENTS.md).


## Navigation and finding what matters

### Visible options, automatic defaults and nondecision continuity (binding owner direction, 2026-10-30)

The owner-directed [visible intelligent defaults proposal #783](https://github.com/gamesbyian/Chunks/pull/783) is incorporated into **Philosophy**, **Ordinary Add** and **Non-negotiable patterns** above. Each optional choice must state its actual effective default, skipped behavior, consent, exact scope, recovery and later Change path. UI presence alone is not consent. No separate beginner mode and no hidden downstream traps.

### Findability and truthful outcomes in everyday torrent journeys (binding UX; research-informed, 2026-10-30)

The [multilingual friction research PR #795](https://github.com/gamesbyian/Chunks/pull/795) is incorporated into **Shared interaction grammar** and **Filter and settings findability** above: make statuses explainable, active filters resettable, newly added jobs discoverable, cross-transfer file metadata searchable and symptoms link to the *same* effective setting. Complaint examples are research leads rather than claims about Chunks bugs. Its detailed research report becomes available on merge of that PR.

### Mental model, first-run and navigation

- **Transfers**: what is downloading, completed, uploading, stuck and awaiting a safe move.
- **Places**: where the actual files reside, grouped by device/server, with recent/favorite folders and clear capability.
- **Collections**: semantic organization, optionally spanning folders/media/franchises without changing file placement.
- **Servers**: local engine plus separate rTorrent connections, secure status, diagnostics and explicit offline state.
- **Settings**: secondary controls, not a hidden destination picker.

First run offers **Download on this phone** and **Connect an rTorrent server** as equally intelligible standalone paths; where local backing or lifecycle safety is still unverified for a release, disclose the limitation honestly rather than making remote setup compulsory. Do not claim Downloads/SAF support until independently accepted.

### Single experience across phone and rTorrent (binding, 2026-10-28)

Apply [UNIFIED-ENVIRONMENTS.md](UNIFIED-ENVIRONMENTS.md) across onboarding, navigation and every transfer interaction. Offer phone-only as the normal standalone path, remote-only without unnecessary local setup, and combined views with a compact accessible any-subset environment filter. Reuse the same list, detail, actions and placement across adapters; show small persistent source identifier on every details page, current scope on each list and exact scope beside settings/confirmations. Switching preserves independent scroll/filter/sort/search while never carrying stale selected jobs into a new environment. Profile disconnection, disablement and privacy lock do not silently redirect commands or destroy torrents. Use text+icons (not solely colour), 48dp targets and 200%-font/TalkBack/keyboard checks; verify 412×892 and 320×700. Capability differences receive explicit, honest unsupported-state messaging rather than a forked interface. This is required behavior, not a shipped-UX claim.

> **Owner correction (2026-10-28):** Cross-engine **Copy Transfer** and **Move Transfer** operate on torrent **jobs only**, never downloaded file bytes. Move adds and verifies the target job, then separately confirms session-only removal of the source job, leaving all source files untouched. Destination downloads independently. Prior text proposing payload transfer, file migration or waiting for target seeding under these names is superseded by [the authoritative transfer-job contract](CROSS-ENGINE-TRANSFER-JOBS.md).

### Combined Transfers and environment colour language (binding, 2026-10-29)

The Transfers header offers **All environments**, individual environments, and arbitrary multi-environment selections through a compact multi-select filter, with the chosen scope visible and remembered. Treat selected environments as a view/query scope, never a mutation target or default Add destination. Show a unified transfer list whose search, filters and sorting (including progress, name, size and activity) operate across the complete selected union instead of concatenating per-server sorts. Show incomplete/offline/stale sources, unknown values and partial totals honestly. Keep duplicate infohash jobs separate with exact origin, and make cross-environment batch selection/actions explicitly scoped and capability-checked.

Every local/remote environment has a persistent palette colour, assigned automatically and editable from Environment settings; exclude black/white and avoid automatically repeating hues until needed. Render the **environment name** in that colour wherever scope is mentioned, including rules, setting targets, dialogs and Add. Combined transfer rows use a small coloured rail/dot and matching compact textual origin label, without sacrificing semantic status cues or transfer details. Do not depend solely on colour: meet contrast, TalkBack, large-text, light/dark and colour-vision accessibility expectations. Canonical stable-ID, persistence, action safety and acceptance details: [UNIFIED-ENVIRONMENTS](UNIFIED-ENVIRONMENTS.md). This is a design requirement, not shipped feature evidence.

### Transfer-list return-to-top control (owner requirement, 2026-10-27)

For **both on-device and every rTorrent server transfer list**, provide a clearly discoverable, accessible **Back to top** action when the user scrolls far enough that the first visible transfer is meaningfully below the beginning (initial implementation threshold: about one screenful). Use a compact, high-contrast floating up-arrow with the accessible name **Back to top** and minimum **48dp** touch target, positioned above bottom navigation/system insets and outside any bulk-selection, add-transfer, list-item or scroll affordance. The action is not dependent on a long-press or gesture. Hide it at the top, on empty/short lists and while transfer privacy lock obscures the list. If controls collide, prioritize transfer actions and move the top control rather than overlaying content.

A tap returns the **currently visible scoped list** to the first row/header without resetting filters, search query, sort, selected server, multi-select state, or the underlying transfers. Use the list's own lazy-list scroll state; do not rebuild or refetch the list purely to jump. Respect reduced-motion settings (instant jump) and otherwise use a brief cancellable scroll animation; repeated taps and user interruption must remain safe. If a sticky header exists, target the logical first item and preserve header behavior. Switching between local/remote lists must not accidentally scroll a different list. A screen reader announces the action and its result without spamming scroll-position announcements; keyboard/D-pad activation and 200% font size must work. Keep up-arrow icon plus readable semantics visually consistent with Chunks' bold, colourful, high-contrast design.

[Historical test receipts](archive/UX-2026-10-30-pre-reconciliation.md#transfer-list-return-to-top-control-owner-requirement-2026-10-27) remain archived; review [SPEC-COVERAGE](SPEC-COVERAGE.md) for open acceptance.


## Starting, adding and sharing

### Open with Chunks from Android (owner requirement, 2026-10-27)

- Opening a `.torrent` attachment or file in Android Files, Downloads, browser or another app must show **Chunks** as an eligible **Open with** destination; tapping a `magnet:` link must likewise allow Chunks as a handler. Use Android's own chooser/default-handler selection. When Android resolves Chunks as the only eligible or preferred handler, it should launch directly, subject to OS resolver policy. A Share action should also work, independently.
- Handle cold launch, an existing Chunks activity (`onNewIntent`), a locked app, no connected rTorrent profile, local-only configuration and multiple engines. Carry the inbound item into the Add draft without discarding the existing data or silently choosing a remote server. Show source/name/hash when verified, engine/Place, optional Change/recent and reviewed Add. Do not auto-start/add before user confirmation.
- If a provider grants temporary URI access, read promptly through ContentResolver under bounded size limits rather than treating `content://` as a filesystem path. Respect scoped storage, reject invalid files, handle revoked permission or unsupported hybrid/v2 torrents with readable recovery; do not expose private magnets, passkeys, or filenames while locked.
- Test API-level resolver matches for `magnet:` with `ACTION_VIEW` and `.torrent` from multiple providers with `application/x-bittorrent`, `application/octet-stream`, and missing/generic MIME where feasible. Verify **Open with**, **Always/Just once** where offered, direct default dispatch, Share, fresh/warm launch, lock/unlock, TalkBack, invalid data, and competing torrent apps on a real device. OS behavior determines whether the chooser is displayed, never an app promise.

### Local .torrent intake acceptance

Use standard Android OpenDocument rather than requiring a custom filesystem browser. Read at most 2 MiB; reject noncanonical bencode, v2/hybrid until supported, path traversal and malformed UTF-8; show verified v1 SHA-1 infohash, torrent name, file count and announced size. No automatic Add and no hidden path mutation. Exercise file pick, cancel, bad extensions with valid bytes, wrong file type, oversized metainfo, accessibility labels and state retention in screenshot QA. First implementation candidate #40 is a preview only and must not imply submission.

### Add destination: clear, never compulsory (owner requirement, 2026-10-27)

Earlier bounded remote Add evidence and account-scoped recent-folder prototype notes are retained [in the historical snapshot](archive/UX-2026-10-30-pre-reconciliation.md#add-destination-clear-never-compulsory-owner-requirement-2026-10-27). Current readiness is in [SPEC-COVERAGE](SPEC-COVERAGE.md); the normative experience follows.

In magnet, .torrent, Android Share, and local/remote Add flows, show **Save to: [resolved default Place]** prominently, beside **Change** and a compact **Recent locations** picker. The primary Add action must work without opening a location picker when the default is valid. Changing location applies to that transfer only; separate explicit settings change the default. The most recently **successfully used** destinations are accessible in one tap with readable volume/server and full-parent-path labels; maintain separate recent lists for local and each remote profile, deduplicate and cap history, and remove or clearly mark stale/unauthorized locations. Favorite destinations may remain distinct. Do not preselect a recent custom folder instead of the configured default without the user's explicit preference.

For the local engine, display **Downloads / Chunks Downloads** as the proposed default only when the folder can actually be created and used for ongoing torrent downloads/seeding on that Android version/provider. Create it lazily and idempotently; if Android storage policy or engine requirements prevent it, explain and offer a supported directory without falsely showing a public Downloads location. For a remote server, show its own existing default directory; Chunks must never automatically create a Chunks-branded directory remotely. A server with an unknown or unusable default needs clear recovery rather than an invented path. This updates older Add wording below that implied obligatory folder selection. Test narrow and large fonts, TalkBack, first-run, two remotes with different defaults, stale recents and interrupted Adds.

### Familiar Downloads location, independent of Chrome (approved behavior)

For the local default, show a readable **Downloads / Chunks Downloads** path in the phone's standard shared Downloads area when verified usable. Describe this as Android's standard Downloads location, not a detected Chrome preference. Chrome can use an SD card or a custom destination; do not assert that both applications always share a folder. Provide **Change default download folder** and remember the chosen supported location in Chunks, including SD card locations where storage integration is proven. The regular Add screen still shows the current default with a one-tap optional Change and scoped recent destinations; selecting a one-off Place does not rewrite the preference. If a chosen storage provider cannot satisfy torrent backing-store requirements, give the real reason, offer supported alternatives and retain the draft. An optional help link may explain where to find Chrome's own Downloads settings, but it must never be an authentication or onboarding dependency. Accessibility must announce device/volume, directory and whether a location is unavailable.

### Seeding groups in Add, transfer details and automatic imports

On every Add route, show destination environment, file location, **Seeding group (Auto-detect / choose / customize)** and Start on Add together. Show effective ratio, time and upload rate conditions in plain language, including explicit units and whether policy is verified, pending or unsupported. Auto-add/auto-start offers an independent opt-in to apply matching rules, with safe hold/notify when rules cannot be established or enforced. Expose group/override and "Why this group?" in transfer details, plus a visual rule editor and preview. Keep phone and rTorrent UX consistent and exact scope visible. Rule lists and editors MUST show editable **Applies to** environment selection with named phone and individual remote profiles, plus an explicit distinction between currently configured and future environments. Auto-generate clear human-readable rule names from conditions, allow manual edits without later overwriting them, and show per-environment policy/capability previews before saving or batch-applying. [Full interaction and safety contract](SMART-SEEDING-RULES.md). Planned, not yet implemented.

### One-step remote setup and connection repair (owner, 2026-10-28)

The initial remote form accepts a seedbox hostname, a ruTorrent URL or direct XML-RPC address. Do not require port/SCGI knowledge for common hosted providers. Show a clear read-only secure-discovery state, then verified connection or a specific, actionable failure; include cancellability, retry/repair, expert manual URL/path selection and per-profile credentials in the follow-up UX. Do not display raw auth challenges, certificates, tokens or trace dumps. The exact requested ruTorrent instance is part of the user's intent; never silently jump from `/rutorrent2/` to `/rutorrent/`. Keep the local phone torrent workflow visible without remote login. First slice adds bounded same-origin Connect discovery; richer progress, comprehensive provider chooser, security setup and multi-method capabilities are still planned. Follow [SMART-REMOTE-CONNECTION](SMART-REMOTE-CONNECTION.md) and privacy lock rules.

### Create & Share wizard (planned, binding)

Use the shared [Create & Share contract](CREATE-AND-SHARE.md), not a separate local-versus-remote wizard. The main Create action and Android file/folder Share entry show a reversible draft: file selection/preview → **Anyone with my link** or **Publish for discovery** → explicit seed environment (only show a chooser if multiple are available) → optional publisher destinations/accounts → recipient-visible metadata, privacy and rules review → Create/Seed/Submit → actionable results. Link sharing is the default; public indexing always requires affirmative consent. Connected publisher profiles are optional and managed separately from rTorrent server profiles. Show service sign-in/API token requirements and destination capability before submission, with per-service scope and authorization.

After submission present **Copy magnet**, **Share**, **Save .torrent**, **View transfer**, and detailed per-service progress: created, seeding, announced, upload accepted, awaiting moderation, rejected, listed, unknown. Do not conflate these states or suggest a magnet alone guarantees a reachable seed. Provide accessible error recovery/retry without duplicate posting, explicit redaction warnings for private credentials, and no silent cross-publishing. A remote-only user must be able to create a metadata draft without starting the local engine; uploading actual file bytes to a remote seedbox is separately labelled, consented and verified, and is never presented as job-only Copy/Move Transfer. Make the same controls available in expert mode for tracker tiers, metadata, compatibility/version, and private tracker restrictions. Apply existing 48dp, TalkBack, large-text and 320×700/412×892 checks. **No implementation or visual acceptance is claimed.**


## Managing transfer jobs, actions and outcomes

### Task journeys and their failures

#### Add torrent
From Android Share, magnet paste or torrent file: choose the engine; show its actual default Place and an optional Change/recent/favorite control, optionally create a folder only if capability allows, review file selection and Add. Display exact destination and whether the request has been acknowledged and observed on server. If unreachable, invalid magnet, destination rejected or auth expired, preserve draft intent and explain recovery. Avoid silently using an old default server/path.

#### Active move
On transfer details, choose Move → inspect current vs proposed Place, capacity, collisions and expected effect on live download → confirm → observe planned/copying/quiescing/verifying/complete or deferred/uncertain states. If the host can't do it safely, offer Move after completion or Make library copy rather than a dead control. Never show “Done” until verified. When disconnected mid-move, show last confirmed stage and a safe Check progress action; do not duplicate the command.

#### Rename
Present three concrete actions with outcomes: **Change label in Chunks**, **Rename torrent's backing file** (only where available), **Save a renamed library copy**. Show which preserves the seeding source, and never imply changing a UI label is a physical rename. For incomplete files, queue publication until stable or explicitly state that a separate incomplete copy isn't media-ready.

#### Smart organization
Offer *Why this suggestion?* with observed filename facts and uncertain inference. Show category/collection membership and separate candidate destination; allow review by group, review conflicts, apply one, apply selected, teach an approved limited rule, ignore and cancel. A Star Trek TV episode and Star Trek comic can share a Collection but have different labels and Places. User's mixed-media root is legitimate. Never auto-create sprawling nested folders merely because an external catalog supplies a hierarchy.

#### Measured remote download progress (implemented, limited #253)

For v1 rTorrent transfers with a started state or recently requested lifecycle action, **Measure download progress (3 samples)** is a cancellable, read-only per-transfer control. It separately displays the change in `d.down.total` received traffic and `d.completed_bytes` hash-verified completion over a measured interval, plus the peak reported connected peer count. It reports received+verified growth, received-only growth, verified-only growth that may be rechecking existing bytes, no observed positive growth, unsupported/missing counters and counter reset as different states. A brief non-growing observation is not proof that a server is permanently stuck; do not infer anything from a single nonzero speed sample or a Start flag. The exact-hash RPC guard and on-screen cancel do not restart, stop, move or delete a transfer. CI acceptance on synthetic fixtures, JVM and disposable rTorrent is recorded in QUALITY-LEDGER; actual Seedhost counter availability, large-font/TalkBack and physical handset display remain separate gates.

#### Optional tracker-history explanation (implemented limited #259)

When a transfer is started but read-only rTorrent activity reports zero connected peers, offer a separate **Diagnose tracker availability** button. The diagnostic only requests bounded, exact-hash tracker counts: configured/enabled, historical successful or failed announce attempts and, where supported, announced-peer count. Explain whether no trackers are configured, all are disabled, no success has occurred, announces have failed, or a prior tracker response reported peers without proving actual connections. Never request tracker URL, tracker messages, passkeys or peer addresses; fail closed if older rTorrent gateways cannot safely expose the counters. Pressing the button does not trigger a tracker reannounce or mutation. Source/JVM/disposable rTorrent acceptance: [QUALITY-LEDGER](QUALITY-LEDGER.md). Real-host counter availability, accessibility and handset display remain separate.

#### Remote failure and reconnect
Clearly distinguish Remote server offline, Auth failed, RPC unsupported, Filesystem unavailable, Permission denied, Operation pending and Operation uncertain. State whether this app, rTorrent, or file storage is still functioning. A server-side download may continue when the phone is offline.

### Controlled Start/Stop affordance

Transfers show one 48dp Start/Stop action per exact v1 hash, disabled when the list is stale. A confirm surface shows torrent display name and exact identity, and an explicit separate Confirm action performs one checked rTorrent lifecycle sequence. Preserve visible pending/uncertain/success state until user navigates away; never optimistic-toggle status. Refresh server view after the readback. A `Start state set` label is deliberately weaker than verified `d.is_active`: offer Diagnose download activity with open/active, connected peers, down rate and clear no-peers/no-traffic/unsupported explanations. On-phone cards prioritize **piece-verified completed bytes on disk** and expose missing peers or libtorrent upload-only mode; network payload counters are secondary because HTTP web seeds can finish with `allTimeDownload=0`. No auto-retries or background command loops; lifecycle stop must never imply payload deletion.

### Transfer details: cross-engine actions and archive-aware removal

Show **Copy Transfer to phone/server** and **Move Transfer to phone/server** as job-only actions. Show target engine/profile and Place, usable metadata, conflicts and expected new download. Explain files are not transferred. Move independently verifies target Add, then separately confirms removal of only the source torrent job; downloaded source files remain. See CROSS-ENGINE-TRANSFER-JOBS.md. The separately labelled, already shipped **Copy files to this phone** action for a completed server transfer (`ServerToPhoneCopyControl`, real-host verified in run [40756284173](https://github.com/gamesbyian/Chunks/actions/runs/40756284173)) is a distinct payload capability: it adds a phone job that fetches and hash-verifies bytes from the server's peer address, leaves the server job seeding, and must keep its own label and evidence.

On transfer details and the Remove confirmation surface, put **Delete extracted archive** alongside **Remove transfer** and **Remove transfer & data** whenever verified eligible, not exclusively in cleanup management. Represent it as a separate unchecked operation, never as a default side effect of removing a session. Preview precise archive and extraction destination(s), verified extraction status and estimated reclaim, with prominent text that removing the archive likely breaks torrent verification and seeding. Add explicit acknowledgement and independent source-data deletion confirmation. If extraction provenance is missing, allow a safe explanation/review path but do not claim an automatic safe deletion. Missing remote file authority disables action and explains why. Match keyboard/TalkBack/large-text and no horizontal truncation; don't hide warnings in tooltips. See PRODUCT, STORAGE-AND-EXTRACTION, SECURITY.

### Batch transfer handoff UX (owner addition, 2026-10-27)

Batch **Copy Transfer / Move Transfer** acts only on selected torrent jobs, never payload files. Preview scope, per-item target/profile/Place, metadata availability, duplicates, future download and storage, and per-job results. Move separately confirms source-job retirement after verified target Add, leaving original downloaded files. See CROSS-ENGINE-TRANSFER-JOBS.md.

### Finish in Order: multi-file completion UX (planned, #415)

**Observed implementation history:** Some phone and rTorrent ordering controls have passed bounded automated checks; those receipts and mode-specific limitations are preserved in the [archived section](archive/UX-2026-10-30-pre-reconciliation.md#finish-in-order-multi-file-completion-ux-planned-415). The following is the binding interaction target, not a release-readiness statement.

In multi-file Add review once metadata is present, show optional **Finish in Order** with editable detected order (episode numbers, movie sequel order if reliably established, numbered chapters). Distinguish original file order from inferred semantic order; ambiguous titles must not be fabricated. Preview includes selected files, extras/subtitles grouped alongside their primary media when applicable, skipped files, and a clear Normal/Balanced/Finish First/Sequential Playback mode explanation. Preserve manual choices and make automatic mode an explicit opt-in or clearly disclosed saved preference.

In torrent Files details show the active completion target, next files, completion state based on hash verification, file-level availability/blocked explanation when known, progress without speculative completion claims, and a one-tap **Finish this next** control. Reordering requires accessible Move up/down actions in addition to drag handles; allow pause automation, restore original normal scheduling, and review/edit conflicting individual priority overrides. On the phone's narrowest supported screen and at 200% text, controls must remain reachable with TalkBack, reduced motion and 48dp-class targets. Avoid continuous refresh animations.

For remote rTorrent show exact capability-verified modes, including **ordering preference only / best effort** when strict piece-level control is unavailable. On RPC uncertainty display last confirmed state and require reconciliation, not automatic retry. For magnets, wait for verified metadata before claiming episode ordering. Warn that sequential modes can reduce total throughput/swarm health, and first-file completion cannot be promised where missing pieces are unavailable. Show no invented availability or ETA. Source/test implementation remains pending #415.


### Task outcome and continuity interactions (MF02 / MF04)

[Task outcomes and continuity](TASK-OUTCOMES-AND-CONTINUITY.md) owns the complete states and acceptance. Add remains one-step and does not require a Task. Offer unobtrusive **View outcome** in transfer details when a goal is explicitly attached, and contextual **Continue task** on return to unfinished work; avoid a permanent top-level Task tab displacing Transfers. A receipt shows milestone label, exact environment, requested vs observed state, last verified time, what remains, and a prominent **What still needs you** explanation. Remote Add must never appear as phone-file delivery. For user-declared completion, say **Marked done by you**, not **Verified**. After restart/disconnect show preserved intent and a safe review action, not a replayed command. Archive/forgetting the Task must say **Your transfers and files will not change**. Honor transfer privacy lock, TalkBack, 200%-text and 48dp-class targets.

### Explain effective rules (MF31)

[Effective-policy explanations](EFFECTIVE-POLICY-EXPLANATIONS.md) owns exact precedence and examples. In Add, transfer details and rules editor show **Which rule applies?**, plain-language result, **Applies to: On phone / named rTorrent profile(s)**, source of each limit, and why an override or tie-break wins. An inline **Check conflicts** previews impacted counts, group-vs-transfer cap, unsupported environments and risky seeding stops without applying changes. Show **Pending metadata**, **Cannot enforce here**, **Stale remote state** and **Observed mismatch** distinctly. Multi-env actions require a scoped confirmation; existing transfer-list controls and default Add review retain prominent space.


## Places, file access, organization and cleanup

### Open files in other Android apps

Provide a consistent file action surface within transfer file trees and phone/remote Place browsing: **Open**, **Open with…**, **Share/Send**, **Download to device**, and **Properties**, capability-gated and scoped to the visible phone/server profile. Prefer an Android resolver and bounded on-demand remote reading over forced full local download; show when streaming, random seeking, credentials, source permissions or recipient compatibility prevent the operation. Never silently materialize a large file. **Opening .torrent/magnet links in Chunks is the separate inbound workflow** described below. Canonical [external-file-access UX and behavior](EXTERNAL-FILE-ACCESS.md) governs both source views.

### Restore transfer from relocated/renamed files

Broken/missing-file transfer details and eligible multi-select lists expose **Find files to restore transfer**. Open with expected source paths and precise phone/server scope, let the user choose an authorized search root, preview likely and hash-verified matches, show a zero-mutation path mapping plan and required engine/filesystem capabilities, and request separate approval for any link/copy operation. Preserve chosen names and distinguish found, verified, relinked and resumed. Unknown remote file access or insufficient per-file mapping yields an honest limitation, never simulated success. [TRANSFER-FILE-RECOVERY](TRANSFER-FILE-RECOVERY.md).

### Fix for Plex in rename and bulk-rename flows

Expose **Fix for Plex** as an optional preset within file rename, bulk rename and folder scan, operating on actual accessible local/remote files. Show original name, minimal changed spans, exact reason, retained technical/release metadata, confidence and sidecar impacts. Separate **No change needed**, **Safe minimal suggestions**, **Needs review** and **Unavailable**. Require explicit review before physical mutations; explain that Chunks display aliases do not change Plex's scan filenames, and distinguish engine-backed rename from independent library publication. [PLEX-FILENAME-REPAIR](PLEX-FILENAME-REPAIR.md) governs behavior.

### Storage and archive journeys (partially implemented, with open acceptance gates)

**Storage entry:** From Transfers/Places and low-space alerts, show per-volume cap, measured versus unknown usage, free/headroom, active seeding backing bytes, independent library copies, extracted files and staging, with adjustment and warnings suggested at 70/85/95 percent of a chosen cap. Explain that a remote server's storage is independent of the phone. At cap, local downloading may pause while seeding remains available; never pretend remote quota enforcement is supported without proof.

**Cleanup wizard:** A default 'Free space without affecting sharing' path and an explicit 'Include files needed for sharing' path. Inventory scoped to actual authorized Place and refreshed before mutation; inspect eligible objects and estimated freed bytes, select/batch review, then confirm exact targets and see verified results. Separate Remove transfer (keep files) from Delete backing data. Before deleting an extracted archive's original, show: 'This archive is part of a torrent. Deleting it will make affected pieces unavailable for sharing. Your extracted files will remain. Restoring or re-downloading the original bytes is required to seed the full torrent again.' Shared files, remote unsupported capability, stale state and interrupted deletion require distinct blockers/recovery UI.

**Extraction:** A per-transfer 'Extract now' action and 'Auto-extract when ready' off-by-default toggle. Show destination picker, estimated output size and headroom, content/collision preview and progress. Explain extraction keeps the original torrent data intact for sharing; show password, unsupported format, multipart-incomplete and safe cancellation states. No silent overwrite or source archive deletion. Actual local/remote capability and storage safety gates live in [STORAGE-AND-EXTRACTION.md](STORAGE-AND-EXTRACTION.md).

### Friendly queue-name editing and acceptance

Each transfer card offers Change/Edit list name, editable text, conservative explained suggestions, Save, Cancel and Use original. A suggestion only fills the draft; editing locally never changes torrent metainfo, backing files or seeding. The original name remains visible and searchable, alias-aware sorting uses the displayed name. Dated test receipts are in the [archived note](archive/UX-2026-10-30-pre-reconciliation.md#friendly-queue-name-editing-and-acceptance), with current verification in [SPEC-COVERAGE](SPEC-COVERAGE.md).


## Privacy, opt-in capabilities and future surfaces

### Privacy lock journeys (specified, not shipped)

During onboarding, explain and offer an optional transfer privacy lock; when enabled, authenticate once per app process session by default. Provide separate local-transfer and each rTorrent-profile policies: never, per session, on foreground return, after five minutes away, or each entry. The settings surface must give clear Android biometric/device credential selection and device capability/error explanation. Add **Lock now** to protected local and remote screens. Show a neutral locked screen with no title, alias, tracker, file path, collection, server identity, status detail or transfer controls leaking before authentication; guard actions as well as visuals, including back stack restoration, share/deep links, recents thumbnails, notifications and TalkBack. After unlock restore the user's context without forcing transfers to restart. Sensitive deletion, credential and policy-downgrade operations can require fresh verification without superseding precise confirmation. Remote login credential remembering is default-on with explicit durable opt-out and independent from this privacy gate (#232/#247). Android Keystore AES-GCM persists only an authenticated profile; disabling remembering atomically erases ciphertext and keeps the opt-out choice across restarts. Native API35 acceptance is not a physical fingerprint or optional transfer-privacy-lock implementation. Full owner requirement: [TRANSFER-PRIVACY-LOCK.md](TRANSFER-PRIVACY-LOCK.md).

### Specialist tools: deliberately secondary, consistent and accessible

The owner has approved five optional workflows but **no new primary navigation destinations**, main Transfers cards, permanent banners, compulsory discovery/setup steps or always-on network processes as their price of entry. Their exact journeys, failure states, local/remote boundaries and acceptance live in the five canonical contracts; this is only the shared information-architecture rule.

| User intention | Contextual entry (one or two steps from relevant object) | First action / default |
|---|---|---|
| Share to a browser or publish a feed | **Create & Share → More sharing options** | Account-free normal .torrent/magnet share; advanced panel collapsed |
| Find another torrent for existing bytes | **Transfer → More → Find compatible torrents**, or **Places → More** | Read-only search, with per-source authorization |
| Copy actual bytes between servers/phone | **Places → selected files/folder → Copy files to…** or transfer **More → Copy payload files…** | Manual source/target/Place selection and byte/cost estimate; distinct from **Copy Transfer** |
| See video/audio details or thumbnail | **File → Properties → Media details** | Read-only bounded inspection on demand |
| Make an event rule or webhook | **Settings → Automation** or transfer **More → Make a rule from this…** | Generated editable rule name and inert dry-run preview |
| Reuse Prowlarr sources | **Settings → Discovery → Sources → Connect existing service** | Read-only source/permission review; no queries while Discovery off |
| Synchronize owned remote files | **Places → folder → More → Copy / Sync → Set up synchronization…** | Copy once first; scheduling off, no deletion |

**UX invariants:** always identify exact phone/remote profile and file-vs-torrent-job identity; show **Configured / Requires access / Scanning / Candidate / Verified / Partial / Uncertain / Unsupported** accurately. Read-only suggestions never become Adds, renames, file copies, seeding changes, publication or webhooks without separate permission. A secondary screen returns to its parent's state and scrolled position. Enable Discovery remains the only global source search switch, with other source/auto-add/auto-start consents independent; non-discovery event rules and local-file auto-load remain independent. Server tools are still under **Servers → profile → Server tools**, with no new privileged entrypoints.

At **320×700**, **412×892**, **200% text**, TalkBack and keyboard, every secondary action must remain reachable without clipping or replacing Transfer, File, Place and Add essentials. An unsupported adapter does not leave a dead primary UI affordance; it explains what exact capability or authorization is absent. Domain contracts: [CREATE-AND-SHARE](CREATE-AND-SHARE.md), [CROSS-SEED-AND-PAYLOAD-COPY](CROSS-SEED-AND-PAYLOAD-COPY.md), [MEDIA-INSPECTION](MEDIA-INSPECTION.md), [EVENT-AUTOMATION](EVENT-AUTOMATION.md), [EXISTING-SERVICE-INTEGRATIONS](EXISTING-SERVICE-INTEGRATIONS.md).

### External destination on local Add (planned, secondary)

Keep the usual **On phone** Add and default `Downloads/Chunks Downloads` unchanged. An optional **Deliver files to…** selector can target a specifically authorized Google Drive/SAF or FTP-family Place; explicitly show **Downloaded on phone** and **Delivered to [destination]** as independent statuses. Show local staging space, remote permission/capability and whether destination verification is possible before approval. Delivery failure/restart offers Retry, Change destination, Keep on phone and Cancel delivery without changing the torrent job. Default seeding and retention persist; optional stop-seeding/remove-owned-staging control requires separate informed consent after verified delivery. Do not surface cloud seeding or direct random-write claims. [Full UX and failure matrix](EXTERNAL-DESTINATION-DELIVERY.md).

### Future Android TV and protocol-agnostic remote UX (binding, deferred)

Use a separate TV-first Compose presentation over the same transfer/core contracts; never ship a stretched touchscreen layout as TV support. D-pad focus/restoration, Select/Back navigation, readable high-contrast transfer rows and recognizable source/server identifiers, remote-friendly Add and file selection, focus-safe dialogs and independent TV accessibility/device acceptance are binding. Any supported remote backend must fit the same user-visible environment/select/sort/filter/details/Add/command vocabulary with accurate capability limits and no cross-server leakage. Full specification: [Android TV and remote backends](ANDROID-TV-AND-REMOTE-BACKENDS.md). This is not current implementation evidence.


> **Authority:** user-visible behavior, interaction state and accessibility requirements. Dated implementation notes describe bounded evidence only; the live [coverage map](SPEC-COVERAGE.md), [quality ledger](QUALITY-LEDGER.md) and [work queue](WORK-QUEUE.md) determine present status and next work.

### Individual transfer home-screen surfaces (planned, post-foundation)

The transfer menu offers **Pin to home screen**: one separately positionable widget per job, not a shared transfer grid. Compact icon-like progress/status, optionally artwork-backed, and expanded resize layouts follow the same explicit local/remote identity and privacy cues as transfer detail. Tap opens the bound transfer. Seeding and completion retain the widget; verified job deletion should ideally remove it without user cleanup, subject to Android launcher restrictions that must be honestly exposed and tested. See [canonical home-screen widget UX/acceptance](PER-TRANSFER-HOME-WIDGETS.md).


## Visual design and accessibility

### Information density and expressiveness

The [Chunks visual design references](design/reference-images/README.md) set the aspirational in-app direction: high-contrast colour, bold geometric surfaces, chunky segmented progress and expressive lettering/icons. Implement accessible native components, preserve the actual Transfers / Places / Collections / Servers model and do not treat mockup actions as delivered functionality.

**Owner direction (2026-10-29):** wording may change during the visual pass, the Chunks typeface may be used anywhere, and artwork/design elements may be chosen from the SVG library and reference sheets. **Technical specifications win** whenever a reference conflicts with them, and no feature is implemented merely because a mockup shows it (for example the sheets' Workspace/Storage/Discover tabs, cleanup wizard, auto-extract toggle or category storage split are *not* features).

**Visual-language target:** Near-black dark surfaces; confident pink, purple, blue, yellow, green, cyan and warning colours; text-safe lavender primary selection; strong contrast for foreground/background pairs; coloured fills with dark readable text. Display face may headline, while long paths and numeric values should remain comfortable at 200% scale. Segmented progress, redundant status text, stable icons and large action targets are expressive but subordinate to hierarchy and accessibility. The exact visual implementation and screenshots are historical evidence, preserved in the [archived original](archive/UX-2026-10-30-pre-reconciliation.md#information-density-and-expressiveness) and [QUALITY-LEDGER](QUALITY-LEDGER.md).

Generous default controls (at least 48dp), expressive colour with text/icons redundant status, readable progress, activity, per-server identity and search. An opt-in denser list mode can display hundreds of transfers without sacrificing navigation; deep peer/tracker/file details progressively disclosed. Distinct loading, empty, offline, access-denied, confirmation, destructive, deferred and uncertain states need designs. Irreverent copy is welcome on delight surfaces but should never replace factual errors.

### Square movie/TV artwork in transfers (planned, owner reference)

**Multi-format editor:** Transfer details → Change artwork opens Square / Poster / Banner tabs and a multi-source thumbnail grid with filters, crop/fit preview, Upload image, Use image URL, Search more, Reset and Undo. Role choices persist independently. An Apply to control defaults to this torrent only, with context-aware show/season, artist/album, author/series, comic issue/series, magazine title, game, app package and other evidenced grouping toggles. Show affected count, individual override exclusions, conflict/undo and future-match choice. User pins override inherited rules; preserve touch targets, TalkBack, offline and safe-content modes. Detailed acceptance in [TRANSFER-ARTWORK.md](TRANSFER-ARTWORK.md).

**Cross-category visuals:** Album art, book covers, comic issues, game covers, application icons, magazine issues and other recognized media use the same stable square slot as movie/TV. Aspect-preserving padding/gradient fills preserve legibility on rectangular source art. Unknown torrents get colorful deterministic bundled category art rather than empty boxes. Show artwork/metadata source and a searchable alternative picker in edit UI; allow category-specific lookups, manual image, disabling and retry. Adult artwork starts in a neutral tile/safe mode and requires separate deliberate permission for adult lookup and explicit images. See [TRANSFER-ARTWORK](TRANSFER-ARTWORK.md).


For phone and remote transfer rows use a stable square leading thumbnail (normal target 64–80dp when room permits), softly rounded and center-cropped without stretching, as shown in the owner reference mockup. Preserve title, accessible status chip, segmented progress, speed figures, sizes and 48dp-class pause/resume actions. Larger art in transfer details with Change/Remove/Correct match. On 320px and 200% type adapt the card layout; do not compress functional targets. During loading/unknown/offline use static category icons and maintain card bounds. Thumbnails are decorative to screen readers: expose media identification through text, keep logical focus order and high-contrast, and obey privacy lock. External lookups are opt-in with manual override and an image-disable setting. Mockup comparison, populated local/remote queues, 200% text, TalkBack and physical-device review are acceptance gates. See [TRANSFER-ARTWORK](TRANSFER-ARTWORK.md), [#403](https://github.com/gamesbyian/Chunks/issues/403). **Unimplemented.**

### Accessibility acceptance

Verify on actual Android handset and where appropriate tablet: TalkBack traversal and actions, large font 200%, reflow, minimum targets, touch reach, gesture alternatives, keyboard focus, reduce motion, colour-vision differentiation and screen rotation/cutouts. Automated semantics audit helps but cannot substitute for physical device or comprehension testing. Prototypes and reference illustrations must retain an explicit unapproved status until reviewed.


## Historical compatibility anchors (not current acceptance claims)

The original notes remain [archived verbatim](archive/UX-2026-10-30-pre-reconciliation.md). These short summaries preserve stable heading links without treating old PR/test snapshots as today's implementation status. Consult live [SPEC-COVERAGE](SPEC-COVERAGE.md) and [QUALITY-LEDGER](QUALITY-LEDGER.md) before claiming functionality.

### Android share-entry state

Open/Share/warm/cold intents carry a reviewed Add draft; an accepted magnet is never automatically submitted. Do not infer downstream download or grant persistence. [Original bounded note](archive/UX-2026-10-30-pre-reconciliation.md#android-share-entry-state).

### Android Open with .torrent and magnet: implemented subset (2026-10-27)

Use the Android resolver and validate v1 .torrent bytes before review. Broader MIME/provider/device support remains independently testable. [Original bounded note](archive/UX-2026-10-30-pre-reconciliation.md#android-open-with-torrent-and-magnet-implemented-subset-2026-10-27).

### Official launcher icon (owner SVG update, 2026-10-27)

The approved transparent SVG is canonical; generate and verify the Android VectorDrawable, preserving the original PNG as reference only. [Original bounded note](archive/UX-2026-10-30-pre-reconciliation.md#official-launcher-icon-owner-svg-update-2026-10-27).

### Early Add preview history versus current confirmed Add

The non-submitting Add preview was superseded by a checked stopped Add and explicit checked Start; coverage and exact evidence remain separate. [Original bounded note](archive/UX-2026-10-30-pre-reconciliation.md#early-add-preview-history-versus-current-confirmed-add).

### Destination-first confirmed Add implementation

Current stopped-Add and optional destination behavior is bounded; exact-host and physical acceptance remain separate. [Original bounded note](archive/UX-2026-10-30-pre-reconciliation.md#destination-first-confirmed-add-implementation).

### Native transfer confirmation survives scrolling (merged #89)

Review/attempt state survives list item recycling, while stale exact identity invalidates unsafe confirmation and interrupted commands become uncertain. [Original bounded note](archive/UX-2026-10-30-pre-reconciliation.md#native-transfer-confirmation-survives-scrolling-merged-89).

### Queue-wide local alias review (merged #98)

Batch alias suggestions are opt-in, source-explained, locally applied with review and conservative Undo, never a backing-file rename. [Original bounded note](archive/UX-2026-10-30-pre-reconciliation.md#queue-wide-local-alias-review-merged-98).

### Batch-name native accessibility and concurrent edit safety

Alias review must announce exact source and reject stale or concurrently edited batches, without truncating options. [Original bounded note](archive/UX-2026-10-30-pre-reconciliation.md#batch-name-native-accessibility-and-concurrent-edit-safety).

### Phone-storage Places preview (merged #104)

Read-only phone-space measurements are not evidence of app-owned reclaimable bytes or enforceable quota. [Original bounded note](archive/UX-2026-10-30-pre-reconciliation.md#phone-storage-places-preview-merged-104).

### Local ZIP review task (merged #110; extraction intentionally absent)

Read-only ZIP review is separate from verified extraction or permission to delete source archives. [Original bounded note](archive/UX-2026-10-30-pre-reconciliation.md#local-zip-review-task-merged-110-extraction-intentionally-absent).

### Current SAF UX capability boundary (2026-10-26)

SAF library copy and manually reviewed ZIP extraction do not establish SAF-backed torrent seeding or arbitrary SD/cloud filesystem support. [Original bounded note](archive/UX-2026-10-30-pre-reconciliation.md#current-saf-ux-capability-boundary-2026-10-26).

