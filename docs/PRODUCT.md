# Chunks canonical product contract

## Optional external destination delivery (owner-approved, planned)

A locally executed torrent MAY deliver verified downloaded files to a separately authorized Google Drive/SAF or FTP-family destination without moving its execution environment. Use verified local staging and independently verified, recoverable destination publication; show download and delivery as separate outcomes. Keep original seeding and local retention by default; offer only separately consented post-verification cleanup. No required cloud-backed torrent engine or cloud seeding. This edge-case convenience remains deferred behind native integrity and primary release gates. [Canonical contract](EXTERNAL-DESTINATION-DELIVERY.md).



## Future platform reach: all practical remote backends and Android TV (binding, deferred)

Once rTorrent is release-quality and independently verified, Chunks shall expand remote control beyond rTorrent to all practical maintainable BitTorrent backends using isolated capability-aware adapters (initial targets Transmission and qBittorrent, then Deluge and BiglyBT/Vuze). Chunks shall also support an appropriately designed Android TV/Google TV app for on-device torrenting and remote-only management, sharing the core with the phone but using a D-pad-first television UI. Neither expansion may bypass present data-integrity, rTorrent or phone acceptance gates. Complete behavior, safety, legal reuse and acceptance: [Android TV and remote backends](ANDROID-TV-AND-REMOTE-BACKENDS.md); roadmap [#660](https://github.com/gamesbyian/Chunks/issues/660). **Planned, not shipped.**


> **Authority:** binding owner product outcomes. Detailed feature rules live in the linked canonical contracts; feature completion and evidence live in [SPEC-COVERAGE](SPEC-COVERAGE.md) and [QUALITY-LEDGER](QUALITY-LEDGER.md). This document does not set implementation priority.

## Intelligent defaults, visible options and downstream continuity (binding owner direction, 2026-10-30)

**Chunks shall be option-rich but decision-light.** Useful, context-relevant settings and features are readily discoverable in ordinary flows; a person may ignore them without degrading the result. Every optional choice has an intelligible and safe resolved default, clear scope, an automatic behavior where authorized and verifiable, and a convenient route to inspect or change its effect later. Making an option optional is an end-to-end promise, not merely allowing the initial form to be skipped. See the [interaction/acceptance contract](UX.md#visible-options-automatic-defaults-and-nondecision-continuity-binding-owner-direction-2026-10-30) and [40-second UX audit #779](https://github.com/gamesbyian/Chunks/issues/779).

- **Visible without being compulsory:** commonly useful controls may appear as compact, readable summaries beside the primary Add/list/detail actions: **Save to**, **Files**, **Sharing**, **Order**, and **Start** show the presently effective value with an adjacent **Change** affordance. Granular editing and technical evidence remain discoverable, but must not consume the initial journey's attention. Neither forced setup wizards nor hiding every choice in Advanced satisfies this requirement.
- **Automatic where safe:** resolve verified per-environment defaults, read-only metadata, file inventory, eligible ordering suggestions, supported capabilities, storage estimates and actual status automatically. Respect explicitly saved per-environment preferences. Do not infer a destructive target, guess a remote destination from the combined view, fabricate storage permissions, or present read-only/inferred information as a verified applied setting.
- **Consent and compatibility:** no automatic deletion, backing-file adoption, unauthorized write, remote/server switch, new public publication, unconsented DHT/IP disclosure, third-party metadata transmission or unsolicited cross-environment mutation. A genuinely unsafe or unsupported default must fail closed while retaining the person's source/draft and explaining one actionable repair. Safety-critical reviews remain visible even if every optional setting is ignored.
- **Future agency:** a user who used defaults can subsequently find their files, change the ordinary relevant options when supported, and see what effect a new choice would have on existing versus future transfers. Show safe copy, verified move or reconfiguration where available; never promise an arbitrary live move, silent in-place rename or retroactive policy when capabilities cannot support it. Distinguish **Change for this transfer**, **Make default**, **Reset to automatic** and **Keep my current setting** when those scopes matter.
- **Outcome continuity:** after an ignored choice, future transfer status, completion, sharing, retrieval, file opening, repair and removal must remain intelligible without reconstructing original setup decisions. Surface the next likely action at the point it becomes useful, rather than making a person discover the omission in Settings much later. Exact observed status and file locations always override optimistic labels.

The engineering gate remains [WORK-QUEUE](WORK-QUEUE.md). These are product/UX requirements, **not claims of current implementation or release safety**; [#568](https://github.com/gamesbyian/Chunks/issues/568) remains a blocking integrity issue.

## Combined transfer browsing and environment colour identity (owner direction, 2026-10-29)

A user may view, search, filter, sort and select transfers from **any combination of enabled local and remote environments in one list**, including All. Sorting and filters operate on the combined transfer union. Each row remains recognizably attached to its originating environment; duplicate torrent hashes on different environments remain distinct jobs. Every environment receives an automatically assigned, customizable Chunks palette colour (excluding black/white), retained with stable identity. Environment names appear in the associated colour wherever scope is displayed, with accessible textual origin and non-colour fallbacks. A combined view never changes the Add destination or implicitly broadcasts rules, settings or actions across environments. Authoritative requirements and acceptance are in [UNIFIED-ENVIRONMENTS](UNIFIED-ENVIRONMENTS.md), with presentation behavior in [UX](UX.md). This is a specification, not an implementation claim.


## Human task outcomes, continuity and effective-rule explanations (binding, planned)

Chunks offers optional **Task outcomes** (MF02) and **Continue task** (MF04), connecting a person's chosen goal to evidence from one or more exact local/remote transfer operations. Existing ordinary Add and transfer lists remain primary and task-free by default. The app must distinguish a sent/accepted command, verified job/data result, external steps needing confirmation, and user-marked completion. Resuming a human Task preserves context and prompts a safe read-only reconciliation, **never** replays a destructive or uncertain command or redirects it to another environment. Archiving or removing a Task never removes a transfer or its files. [Canonical task contract](TASK-OUTCOMES-AND-CONTINUITY.md).

**Effective-rule explanations** (MF31) must make the winning group or manual override, matched conditions, exact phone/remote environment, global constraints, conflicting rules, and impact of changing a setting understandable before applying it. Preserve existing deterministic Smart Seeding Rules precedence and per-environment enforcement; simulation is not proof of application. [Canonical rule explanation contract](EFFECTIVE-POLICY-EXPLANATIONS.md).

## Smart Seeding Rules and Seeding Groups

Provide simple named seeding groups with independently configured ratio stop, seeding duration and upload speed limits, deterministic tracker-based auto-detection and plain-language summaries. Show group/automatic matching alongside environment, destination and start-on-add on **every** Add route, and offer optional verified rule application for unattended auto-add/auto-start. Support identical user semantics across phone/rTorrent instances with honest per-engine enforcement and explicit scopes. No automatic deletion, invented tracker identity or silent retroactive changes. Binding contract: [SMART-SEEDING-RULES](SMART-SEEDING-RULES.md). Planned, not shipped.

## Find files to restore transfers after external moves or renames

Expose **Find files to restore transfer** for local or remote torrents whose backing content has been moved/renamed outside Chunks. Discover candidates in authorized Places, verify content against torrent hashes, and reconnect existing data to its original transfer without undoing user filenames, silently duplicating bytes or changing infohash. Keep engine-only path changes, immutable torrent metadata, physical files and Chunks display names distinct; distinguish mere candidates from verified, relinked and truly resumed transfers. Exact capability-gated workflow: [TRANSFER-FILE-RECOVERY](TRANSFER-FILE-RECOVERY.md). Planned, not shipped.

## Plex-compatible filename repair

**Fix for Plex** is a conservative analysis/preset for individual and bulk file rename or folder scans. It MUST preserve codec, resolution, source, release group, edition and other meaningful technical/provenance filename tokens unless an identified scanner defect specifically requires a limited edit. Prefer no change for already recognizable filenames; changes are reviewed and minimally scoped, never blanket cosmetic normalization. Display alias, torrent backing filename and independently published Plex-ready library copy remain distinct; an alias does not correct Plex-visible names. Canonical contract: [PLEX-FILENAME-REPAIR](PLEX-FILENAME-REPAIR.md). Implementation/physical acceptance remains open.

## External application access to files

Files in transfer trees and accessible local or remote Places MUST offer capability-grounded Android **Open/Open with**, **Share/Send** and explicit download/export actions. Format-agnostic on-demand reading, including seekable large remote files, is the preferred experience where authorized server access and receiving-app compatibility permit; a full download MUST NOT occur silently. rTorrent RPC control alone does not prove access to file bytes. Canonical rules, limitations and acceptance: [EXTERNAL-FILE-ACCESS](EXTERNAL-FILE-ACCESS.md). This is planned, not verified shipped functionality.


## Optional specialist workflows: five owner-approved capabilities

The following are **required product scope**, with **secondary opt-in interactions proportional to specialist demand**, not new primary tabs or mandatory setup. Every action uses explicit phone/named-remote scope; read-only inspection and suggestions are never mislabeled as mutation or verified success. Detailed feature contracts are authoritative and independent of implementation/CI status.

1. **More approachable file sharing:** Create & Share MAY offer an embedded, reachability-verified tracker, independently verified WebRTC/browser-compatible sharing, and explicitly chosen public-share subscription feeds under **More sharing options**. The ordinary account-free torrent path remains primary; a magnet or rTorrent server alone does **not** establish browser compatibility. [CREATE-AND-SHARE](CREATE-AND-SHARE.md#optional-advanced-sharing-embedded-tracker-browser-peers-and-subscription-feeds).
2. **Reuse the bytes users already have:** Optional verified cross-seeding candidates and a separately labelled **Copy payload files** operation between authorized Places, with resumable, readback-verified bytes, no automatic source deletion or torrent registration. **Copy/Move Transfer remains job only**, as already bound. [CROSS-SEED-AND-PAYLOAD-COPY](CROSS-SEED-AND-PAYLOAD-COPY.md).
3. **Inspect media rather than trusting filenames:** On-demand local/remote technical MediaInfo-style container, codec, track and subtitle details and optional constrained video thumbnails through **Properties → Media details**. No default bulk scanning, transcode/player tab or external content upload. [MEDIA-INSPECTION](MEDIA-INSPECTION.md).
4. **Event-based convenience without scripting:** Opt-in **Settings → Automation**, friendly `When → If → Then` rules, user-reviewed actions, saved filters and narrowly permissioned webhook/app handoffs. The [Smart Seeding Rules](SMART-SEEDING-RULES.md) editor retains exclusive ratio/group semantics; no generic shell/server-admin execution or promise of always-on Android execution. [EVENT-AUTOMATION](EVENT-AUTOMATION.md).
5. **Integrate services users already run:** Optional `Settings → Discovery → Sources → Connect existing service` (Prowlarr/Torznab-style verified adapters), plus secondary Place-based independently authorized storage synchronization. Discovery must respect its **master off switch** and source permissions; no mandatory Chunks cloud or first-class seedbox admin surfaces. [EXISTING-SERVICE-INTEGRATIONS](EXISTING-SERVICE-INTEGRATIONS.md).

These are **specified future outcomes**, not claims that the Android app, libtorrent engine, rTorrent server or connected provider already supports them.

## Product identity

**chunks. Your downloads have places to be.**

An Android-native, delightful, deeply capable BitTorrent client and first-class rTorrent remote. Move through the app without needing to understand pieces, hash checks, session files or SCGI. Technical depth remains accessible. Attractive colour, irreverent personality and generously sized native controls should coexist with an information-dense expert mode.

A torrent client is a data-integrity tool: none of its charm is worth lost bytes. The user's mixed library is valid; do not demand movies and TV live in separate top-level directories.

## Three nouns, five different identities

- **Transfer**: one engine-managed BitTorrent download/seed, scoped to a specific engine and server/device. Its torrent info hash does not equal a physical file path.
- **Place**: a physical destination as accessible to a particular local engine or rTorrent server, not an arbitrary pathname string. Includes capability, volume, allowed root and human display alias.
- **Collection**: user-recognizable semantic relationship such as Star Trek, Spider-Man, a season, franchise, music, comics or ebooks. Many-to-many with Transfers, independent from Place.
- **Display name**: a Chunks label for a Transfer or file; changing it does not change seeding data.
- **Backing file / published library copy**: respectively the bytes controlled by the engine and an independently managed file exported for a user library. They have different ownership, path and mutation rules.

Tags, category, collection membership, destination suggestion and filesystem mutation authorization are distinct states. A Transfer may belong to multiple collections; a file can be represented in several collections without duplicating data.

## Create & Share: account-free and connected publication (binding)

Chunks MUST let users create torrent metadata from accessible phone files/folders, generate and share .torrent/magnet outputs, and seed through a verified capable phone or remote environment. **Share by link** requires no indexer or account; **Publish for discovery** is a separate, explicitly approved action, with optional authenticated tracker/indexer publisher profiles and per-service status. Creation, announce, network availability and searchable listing are distinct claims. Never leak tracker passkeys or private metadata into public exports, and never imply remote seeding without remote payload bytes. The complete feature authority and acceptance gates are in [CREATE-AND-SHARE](CREATE-AND-SHARE.md). This is required product scope, **not current implementation**.

## Unified environment experience (binding owner direction, 2026-10-28)

Local-only (the default offer), remote-only, and combined local/one-or-more-rTorrent-profile configurations MUST all be first-class. The on-phone manager and every remote profile use one consistent navigation, Transfers list/detail, Add, controls and semantics, with divergences only where independently verified capabilities require them. A multi-environment Transfers view clearly identifies the selected phone/server list; transfer details, destructive dialogs and settings show their affected environment. Environment switching is navigation only, and disabling a mode cannot silently remove jobs/files or forget credentials. Settings distinguish application-wide, exact environment and per-transfer scope, with explicit preview for any multi-environment operation. [Binding detailed contract and acceptance](UNIFIED-ENVIRONMENTS.md). Existing implementation evidence does not establish complete parity.

> **Owner correction (2026-10-28):** Cross-engine **Copy Transfer** and **Move Transfer** operate on torrent **jobs only**, never downloaded file bytes. Move adds and verifies the target job, then separately confirms session-only removal of the source job, leaving all source files untouched. Destination downloads independently. Prior text proposing payload transfer, file migration or waiting for target seeding under these names is superseded by [the authoritative transfer-job contract](CROSS-ENGINE-TRANSFER-JOBS.md).

## Owner-approved integrated discovery and secondary server tools (2026-10-29)

Chunks SHALL include an integrated **torrent discovery** experience: search supported public and personally configured private indexers, combine clearly attributed results, offer useful filters and saved searches, support RSS/Atom subscription and conditional intake, and send selected items to the same local/remote Add review. It must function without a Chunks-operated service or mandatory account, while allowing user-managed provider credentials where legitimate. Discovery is **optional, disabled until enabled, and globally switchable off** in Settings → Discovery and from the discovery screen. Off must stop discovery-driven network requests, subscriptions, discovery-triggered automatic intake and notifications, and prevent reactivation on restart, while retaining configured providers and never stopping or deleting already accepted torrent jobs. Explicit manual .torrent/magnet/URL/QR intake, separately configured direct auto-load of local .torrent/magnet files and Create & Share publication remain independently usable. Automatic Add and automatic Start are separate opt-in controls. Canonical requirements, precise disable/re-enable semantics and acceptance: [INTAKE-QUEUE-AND-POLICIES](INTAKE-QUEUE-AND-POLICIES.md). **Owner binding; not implemented/verified by this requirement.**

Chunks SHALL provide **limited optional seedbox management** only as a **secondary** capability at **Servers → chosen remote profile → Server tools**. A remote profile may show authorized server health, storage/traffic quotas and service status; narrowly documented service Start/Stop/Restart is permitted only with separate per-profile consent, verified credentials/capabilities and explicit confirmation of the likely effect on active torrents. The main Transfers/Add/Details controls and screen space take precedence over all infrastructure tools. No admin-focused primary tab, pervasive dashboard cards or privileged shell/general RPC/package/user/firewall/host-reboot actions. A connected rTorrent profile does not imply host administrator access. Local-only operation remains entirely independent of this screen. Canonical rules: [REMOTE-INTEGRATIONS-AND-PREFERENCES](REMOTE-INTEGRATIONS-AND-PREFERENCES.md). **Owner binding; implementation/host acceptance pending.**

## Advanced intake, queue, networking, diagnostics and remote integrations

Chunks MUST make advanced torrent operations understandable without implying that every engine, provider, VPN or media integration supports the same actions. Three narrowly owned contracts define the behavior, defaults, unsupported states and acceptance gates for **58 previously under-specified capabilities** identified in the ecosystem research. [Intake, queue and policies](INTAKE-QUEUE-AND-POLICIES.md) covers URL/feed intake, queue control, liveness, bandwidth/time/network policies, optional source filters and notes. [Files, protocol and diagnostics](FILES-PROTOCOL-AND-DIAGNOSTICS.md) covers cross-file pieces, safe archives/link handling, peer/protocol/privacy controls, health telemetry and seek-driven preview. [Remote integrations and settings portability](REMOTE-INTEGRATIONS-AND-PREFERENCES.md) covers remote profile import, certificate/tunnel boundaries, authorized media/cloud adapters, customizable views, refresh and support.

The default is a functional phone torrent client or rTorrent remote without accounts, extra software, background cloud automation or unsafe privileged administration. Advanced and external integrations are opt-in; session/protocol/OS capability differences must be disclosed and verified before enforcement. Saving a preference is never proof of executing it. The owner-approved seeding-rule behavior remains separately governed by [SMART-SEEDING-RULES](SMART-SEEDING-RULES.md), including rule names, matching and explicitly selected environments. These contracts are product requirements/plans, **not feature-readiness claims**.

## Non-negotiable user outcomes

### 1. First-class rTorrent remote, no mandatory Companion

Multiple server profiles, authenticated encrypted connections, clear status, trustworthy lists and filtering, adding magnets and .torrent files with an automatically resolved default or explicitly chosen destination, transfer start/stop, per-file selections/priorities, trackers, speeds, errors, appropriate queue settings and expert details **as capabilities are demonstrated**. If a server disallows an operation, explain why and present safe alternatives.

Server management continues with or without a companion. Chunks does not require cloud sign-in. Real seedbox variants must be verified before claiming compatibility. Transport support is measured per installation, not inferred from its stated release alone.

### 2. Visible, optional destination choice at Add

**Owner clarification (2026-10-27):** Every Add flow must clearly display the resolved destination and offer **Change location**, but never require a folder-picker interaction merely to Add. Start with the environment's existing default. For on-device transfers the intended Chunks default is a lazily created **Downloads/Chunks Downloads** folder within the device's user-visible Downloads location, subject to Android storage permissions and verified seekable/direct-write support; do not silently substitute app-private storage while claiming the public destination is active. On remote rTorrent profiles use the server's configured default location and **never create a Chunks Downloads folder on the server**. Recent successfully used destinations should appear directly in Add, scoped to each device/remote profile, with readable labels and safe validity checks. A previously chosen per-transfer location must not quietly change the default for future Add operations. If a default is unavailable or cannot be verified, explain the limitation and ask for a valid destination rather than manufacturing success. Android's modern scoped-storage restrictions require explicit engineering validation before claiming public Downloads/libtorrent writes work. This overrides earlier language treating destination choice as mandatory. See [ARCHITECTURE.md](ARCHITECTURE.md), [UX.md](UX.md), and [WORK-QUEUE.md](WORK-QUEUE.md).

### 2a. Additional destination controls

Start/Add prominently shows the current default destination and an optional Change action; users need not select a folder to proceed when the default is valid. Show device/server and full human-readable destination, with recent/favorite folders and a new-folder action only when supported. Validate destination scope, permissions and collisions before submission; show pending/unknown server response honestly. The action is not complete merely because an RPC returned success.

### 3. Move any time it can be done safely

Allow move requests before, during and after downloading. **Guarantee no silent data loss and no misleading success; strive for uninterrupted torrent session and continued peers.** No universal guarantee of uninterrupted disk throughput during cross-device copying or active-file handoff. When a server/filesystem cannot safely relocate active data, the UI offers a deferred operation, an independent library copy when appropriate, or an explicit refusal.

### 4. Rename without breaking torrent integrity

Three deliberately separate actions:
1. Rename displayed title only (always reversible, metadata-only).
2. Rename the engine-backed file only where verified supported and safely synchronized.
3. Create a renamed library copy, preserving the original seeding file. Do not publish incomplete writable data as a stable media library asset; hardlinks are shared-inode views, not snapshots.

Show actual outcome and whether the original seed path is retained.

### 5. Organize and learn with consent

On-demand or optional automatic analysis can propose Tags, Collections, franchise/season hierarchy, destination folders and naming patterns across TV, movies, music, Star Trek, Spider-Man, ebooks, comics and future genres discovered from actual content. Show provenance and separate *content recognition confidence*, *destination confidence*, and *approved execution rule*. Explain and preview changes. Batch review; never silently mutate from classifier score alone.

Deterministic offline parsing should provide useful functionality without external metadata services. Network enrichment is optional, clearly consented and privacy-filtered.

## Android-wide torrent and magnet opening (owner requirement, 2026-10-27)

When Chunks is installed, Android must offer it as a capable handler when a user opens a `.torrent` file from Downloads, Files, a browser or another content provider, or activates a `magnet:` link in another app. Register appropriate Android `ACTION_VIEW` intent filters for `magnet` URI schemes and torrent file `content://` / supported `file://` sources, recognizing relevant MIME types including `application/x-bittorrent` and carefully handling providers that report generic MIME types. Support `ACTION_SEND` and text magnets as a complementary Share workflow, not a substitute for Open with.

Respect Android's resolver: present Chunks among available apps, let users select **Always** / their preferred handler where Android offers it, and permit direct opening when Android itself resolves Chunks as the sole/default handler. Do not force a Chunks-specific chooser or attempt to override the OS's preferred app. Accept the incoming item into a validated Add draft with optional local or remote engine choice, existing default Place, optional Change/recent locations and explicit user-reviewed Add (Start after Add follows existing defaults). Never automatically submit merely because the OS opened Chunks. Preserve incoming data across cold start, warm start, authentication/unlock and connection selection without leaking private source URIs or tracker passkeys; safely report malformed, revoked, inaccessible or oversized input. **Required end-to-end behavior; existing magnet/Share integration is only partial evidence, not proof of all file-opening routes.**

## Approved Android Downloads behavior (2026-10-27)

Use the **Android platform's standard primary Downloads location**, normally the same shared Downloads area Chrome uses under its default configuration, as the reference parent for the local **Chunks Downloads** folder. Do **not** read, infer or depend on Chrome's private download preference: Chrome may instead target an SD card or prompt for each file, and Android has no public cross-app API that authoritatively returns Chrome's configured location. Let users select another supported destination (including removable storage when feasible), explicitly save it as Chunks' default, and reuse that choice independently of Chrome. Keep the per-transfer destination Change/recent choices obvious but optional. Folder creation is lazy, on device only, and permitted only through a platform-compliant, durable read/write approach compatible with libtorrent's random-access/resume/verify/seed requirements; do not request broad storage authority as a shortcut or mislabel an app-private fallback. No Chunks folder is ever created on remote servers. Tests and engineering gates: [ARCHITECTURE.md](ARCHITECTURE.md), [WORK-QUEUE.md](WORK-QUEUE.md). **Approved behavior, not current shipped functionality.**

## Transfer details: copy/move between local and remote (owner, 2026-10-27)

On each transfer-details screen show **Copy Transfer to phone/server** and **Move Transfer to phone/server** for torrent-job handoff. Both add a new torrent job with no payload copying. Copy preserves original job and files. Move, after verified target Add and separate approval, removes only original source job, retaining all original files. See [CROSS-ENGINE-TRANSFER-JOBS.md](CROSS-ENGINE-TRANSFER-JOBS.md). **Limited automated implementation exists for single and batch directions; full real-device/host acceptance remains open.** The separately labelled, already shipped **Copy files to this phone** action for a completed server transfer (`ServerToPhoneCopyControl`, real-host verified in run [40756284173](https://github.com/gamesbyian/Chunks/actions/runs/40756284173)) is a distinct payload capability: it adds a phone job that fetches and hash-verifies bytes from the server's peer address, leaves the server job seeding, and must keep its own label and evidence.

## Batch cross-engine transfer (owner addition, 2026-10-27)

Local ↔ remote transfer-job Copy/Move must support multi-select batches from lists, search and Collections. Each job independently adds and verifies the target; Move then obtains separate approval to remove only its source torrent job. Show future download/storage estimates, duplicates, failures and durable per-item reconciliation; never copy or delete downloaded files. See [CROSS-ENGINE-TRANSFER-JOBS.md](CROSS-ENGINE-TRANSFER-JOBS.md). **Limited automated batch implementation exists in both directions; full production and physical-device acceptance remains open.**

## Multi-file completion order: Finish in Order (owner direction, 2026-10-27)

Chunks shall provide an optional **Finish in Order** scheduling policy for multi-file torrents, making earlier selected files *complete and usable sooner*, rather than merely setting a static High file priority. Offer natural-order suggestions for episodic seasons, ordered film series, audiobooks, lessons and numbered document sets; non-media torrents may also use custom order. This is a user-visible policy distinct from the engine's individual Low/Normal/High file priorities. Preserve standard BitTorrent scheduling as the default until users explicitly select a mode or opt into category defaults.

Offer **Normal**, **Balanced** (the suggested order-aware default), **Finish First**, and capability-gated **Sequential Playback** choices. The active file advances only after its selected content is hash-verified complete; allow modest next-file lookahead, opportunistic background progress, fallback if early pieces are unavailable, and visible explanation of inability to guarantee first-file completion. Natural order is editable, and filenames/directory structure are suggestions, not authoritative metadata. Extras, subtitles and dependencies may be grouped with an episode, with explicit selection/skip handling. Users may pin a file next, reorder or pause automated scheduling without altering original torrent identities, paths or contents.

On the Add review after metainfo is known, present order preview and optional preset; also allow per-torrent Files control and a global preference for supported categories, with no surprise side effects on existing torrents. Persist policy and ordering separately in Chunks-only metadata keyed by scope and verified infohash and restore only after identity/readback checks. Never silently override user-set priority, auto-delete, rename backing files, or promise that unseeded pieces will arrive. Explicitly disclose reduced swarm efficiency for aggressive sequential downloading. Phone-local and rTorrent remote support are separately capability-gated; unsupported remote methods receive honest fallback controls, never false claims of enforced ordering. See issue #415 and UX/ARCHITECTURE; no implementation or device acceptance is claimed by this contract.
**Artwork customization:** Square, portrait poster/cover and wide banner/hero are separate visual roles for different views. Every transfer can choose each image independently from an aggregated multi-provider grid, local upload or HTTPS URL, with role-specific crop/fit. Offer explicit related-transfer inheritance rules: TV show/season, music artist/album, books author/work/series, comics series/issue, magazines title/issue, games/platform, software package and other verified groupings. Preview affected items, preserve narrower overrides, and support existing-only or future matches. See [TRANSFER-ARTWORK.md](TRANSFER-ARTWORK.md).

**Cross-media enrichment expansion (2026-10-27):** Apply the same visual treatment to music, audiobooks, books, periodicals, comics/manga, games, apps/software, adult content and common document/archive types. Resolve metadata and artwork independently through multiple category-appropriate providers, moving to an optional sanctioned general image search (GIS) when catalogs fail and finally guaranteed bundled category artwork. Adult remote lookup/explicit thumbnails require distinct opt-in. No forced wrong cover; preserve user overrides. Detailed contract and provider candidate matrix in [TRANSFER-ARTWORK](TRANSFER-ARTWORK.md).

## Square media artwork on transfers (owner-approved, planned 2026-10-27)

Both local and rTorrent transfer lists and details should show recognisable square movie/TV artwork beside transfer progress and controls, in the style of the owner four-phone design mockup. Prefer native square key art, preserve default icons for unknown/nonmedia items, and never fabricate confident identification. External lookup is strictly opt-in; selection/correction and cache clear are available. Artwork is presentation-only and must not change torrent contents, remote session metadata, display aliases or locations. Full provider, privacy, UI, matching, failure and acceptance contract: [TRANSFER-ARTWORK.md](TRANSFER-ARTWORK.md), tracked by [#403](https://github.com/gamesbyian/Chunks/issues/403). **Planned, not shipped; below core transfer reliability in priority.**

Reconciled 2026-10-26 against live code and owner intent. Owner-approved **vision** and engineering **proposals** are distinguished in DECISIONS.md. Read CRITICAL-REVIEW-2026-10-24.md before implementing any mutation. This document is the product authority; WORK-QUEUE.md alone owns task priority.

## Friendly transfer aliases and suggestions

Users can save an arbitrary Chunks-only display alias for a Transfer independently of the engine-reported torrent name, downloaded file paths, torrent metainfo, filesystem location and seeding. The original torrent title remains available and searchable; alphabetical sorting uses the displayed alias. A reset removes the override. The first implementation is device-local, scoped by the connected endpoint/account and infohash, not shared across clients. Backing-file rename remains separately safety-gated. A **limited finished-local-transfer SAF library copy** (#147) is implemented and preserves original seeding bytes; arbitrary remote copy or file mapping rename is not.

Offer local, explained, editable clean-name proposals from filename facts. For `Celebrity.Jeopardy.S04E02.720p.HEVC.x265-MeGusta.mkv`, good source-derived proposals are `Celebrity Jeopardy S04E02` or `Celebrity Jeopardy · Season 4, Episode 2`; a manual alias like `Celebrity Jeopardy Timothy Chalamet episode` must work without the app inventing a guest from absent filename data. Suggestions never save automatically, and unknown extensions should abstain. Queue-wide first-100 proposal review and conditional Undo are merged (#98). Naming styles, scalable paging, external opt-in media metadata, per-file aliases and synchronization are distinct future gates. Pre-alpha implementation merged in [#74](https://github.com/gamesbyian/Chunks/pull/74) with emulator-level acceptance; unverified physical-handset/accessibility and real hosted-server integration remain release blockers.

## Transfer access privacy and authentication (owner requirement, 2026-10-27)

Offer an optional privacy lock that protects **viewing and interacting with local transfers** and, independently, each remote rTorrent profile. Once enabled, the default is **one Android authentication per app process session**; configurable choices allow never, each foreground return, a five-minute background timeout, or each entry. Expose an immediate **Lock now** action. Use Android's supported native biometric interface (fingerprint and other eligible strong biometrics) with device PIN/pattern/password fallback by default; capability-aware alternatives must not strand users. Lock the interface and redact private transfer data without stopping authorized background downloads or seeding. Server login is separate: remember rTorrent connection credentials by default after first authenticated setup, provide opt-out/forget, and protect persistent credentials using reviewed Android Keystore-backed storage. Additional authentication may be required for sensitive actions without replacing exact destructive-operation confirmation. **Requirement only; not implemented or tested.** Full behavioral contract: [TRANSFER-PRIVACY-LOCK.md](TRANSFER-PRIVACY-LOCK.md).

## Remove transfer: extracted archive cleanup (owner, 2026-10-27)

Beside **Remove transfer** (keep data) and **Remove transfer & data** (delete torrent-backed bytes), provide a discoverable **Delete archive after extraction** option when supported. Offer it in transfer details/removal confirmation **and** storage/cleanup UI. A matching filename or existing extracted folder alone is insufficient: verify complete extraction provenance/manifest, expected entries, sizes and content hashes/readback where feasible, destination ownership and no later modifications. Show exactly which archive(s), which extracted outputs will remain, estimated reclaimable physical bytes and whether torrent hash checking/seeding would fail. Deleting torrent-backed archive bytes generally breaks the torrent even if extracted outputs survive: state this visibly and require distinct, explicit acknowledgement; never execute by default or quietly after Remove transfer. If server privileges cannot safely delete exact files or relationships are unverified, disable/refuse and explain. Keep source torrent/session removal distinct from archive cleanup, and do not imply extracted outputs replace seedable originals. **Planned.**

## Storage budgets, cleanup and archive extraction (owner request)

Chunks must offer optional per-device-volume/Place storage caps for downloaded/backing and derived data, with sensible adjustable warning thresholds, low-device-space protections, and an accessible compact usage/reclaim dashboard. Caps never authorize deleting data; when enforcement is supported, safely throttle/pause affected local download writes while preserving seeding. Remote storage quotas are advisory until a verified server-side capability exists. Clearly distinguish logical torrent size, actual allocation and independently extractable storage.

Offer a compact cleanup wizard prioritizing files that can be safely reclaimed without damaging sharing; make affecting-seeding actions a clearly separated opt-in path with exact preflight impact and explicit confirmation. Deleting a torrent-backed archive after extraction typically breaks the affected torrent's pieces and seeding; warn in meaningful on-screen text. Do not automatically remove seeding originals. Session-only removal, payload deletion, extracted outputs, independent library copies and cache cleanup are separate permissions and outcomes.

Provide manual **Extract now** and easy opt-in **Auto-extract when verified complete** for supported archives, with destination, expansion-space estimate, safety bounds and clear status. Auto-extraction is off by default and preserves source archives for sharing. Full behavior and release gates: [STORAGE-AND-EXTRACTION.md](STORAGE-AND-EXTRACTION.md). Tracked as [#70](https://github.com/gamesbyian/Chunks/issues/70); these are requirements with partial implementation: bounded manual SAF ZIP extraction is merged (#119/#120), whereas real quota enforcement, physical cleanup deletion and automatic extraction remain open.

## Quick navigation in long transfer lists (owner requirement, 2026-10-27)

Local and rTorrent remote transfer lists must expose a discoverable one-tap **Back to top** control after users scroll approximately a screenful downward. The control is a visible, accessible, high-contrast 48dp-class target that respects bottom navigation/insets, never masks primary transfer actions, and hides when irrelevant (at top, short/empty list, privacy locked). Activating it returns only the current transfer list to its beginning without clearing search, sorting, filters, selection, connection scope, or transfer state. Respect reduced motion, large text, TalkBack and keyboard input. Detailed interaction and acceptance criteria: [UX.md](UX.md). **Implemented in #277 with source-level Android/JVM, native visual and API35 acceptance. Physical Pixel/TalkBack/large-text navigation acceptance remains open.**

## Smart Connect: simple cross-provider remote onboarding (owner, 2026-10-28)

Users should ordinarily connect rTorrent by pasting their hostname or ruTorrent browser URL and providing credentials. Chunks recognizes documented provider patterns, safely discovers a compatible authenticated HTTPS XML-RPC gateway and remembers its verified configuration for one-tap future use. Preserve self-hosted/custom RPC URLs, multiple independent instances and a fully manual advanced path. Never redirect credentials between hosts, silently use a different numbered instance, weaken TLS, or assume a successful server version query proves full transfer-list or write permissions. Discovery performs only read-only calls; user-approved torrent actions remain separately confirmed and read back. **Partial first slice:** bounded same-origin candidate planning, version + full `main` read, known SeedHost/Ultra/Whatbox/Appbox hints and generic paths, and encrypted verified-profile reuse. The complete provider catalog, diagnostics, capability scoring, certificates/SSO/VPN and QR pairing remain planned. Canonical contract and acceptance matrix: [SMART-REMOTE-CONNECTION](SMART-REMOTE-CONNECTION.md).

## Usability standards

- Primary journeys need at most a few natural interactions; no obscure Settings path for per-transfer Place.
- Default transfer screens express clear labels, error recovery and an easy way to find advanced controls.
- 48dp minimum interactive targets; generous default sizing, compact optional density, large text, high-contrast semantics, TalkBack, keyboard and alternatives to gesture-only commands.
- Light/dark colour systems remain proposed until visually evaluated as whole flows on Android, not approved because an illustration exists.
- Empathy for novice users means clear language, meaningful defaults, undo/recovery and explainable state, without concealing power.

## Experience scope and release slices

**First meaningful remote beta:** reliable authenticated rTorrent remote with real transfer inspection and everyday controls, including destination-aware Add. No client-side torrent engine required for that beta.

**Organization beta:** non-mutating sorting suggestions, collections and approved plans; actual remote moves only behind proven filesystem capabilities and failure recovery.

**Standalone-client beta:** local BitTorrent engine with verified download/seeding, storage permissions and Android lifecycle behavior.

**Release:** all core flows physically tested, independently audited for destructive changes, security/credential story resolved and installer/distribution documented. “Full featured” is a destination, not permission to ship untested engine options.

## Deferred, not forgotten

**Unrestricted** cross-machine payload migration, **broad privileged** seedbox administration, developer-hosted always-on automation, additional remote engine adapters and premium multi-server dashboards remain exploratory and require separate owner/product approval. **Bounded user-initiated verified payload copying** and **user-authorized conditional automation** are now approved within their narrow specialist contracts; neither grants broad server control, payload auto-migration or infrastructure hosting. **Narrow secondary service-status and individually authorized start/stop/restart controls are approved** under the server-tools contract. Optional Plex/Sonarr/Radarr handoff, cloud independent-copy backup, feed intake, streaming preview and advanced naming have now been given **bounded opt-in contracts**, but no implementation or independent compatibility is implied; activation stays subject to their explicit safety and acceptance gates in [REMOTE-INTEGRATIONS-AND-PREFERENCES](REMOTE-INTEGRATIONS-AND-PREFERENCES.md), [FILES-PROTOCOL-AND-DIAGNOSTICS](FILES-PROTOCOL-AND-DIAGNOSTICS.md), [PLEX-FILENAME-REPAIR](PLEX-FILENAME-REPAIR.md) and [EXTERNAL-FILE-ACCESS](EXTERNAL-FILE-ACCESS.md).

## Explicit exclusions

No mandatory accounts, **undisclosed or unstoppable** tracker/content search, piracy promotion, unexplained AI uploads, background tracking by default, guessed file operations, or reliance on unapproved extra server privileges. Integrated discovery must remain inspectable, consensual and immediately disableable through its master switch; optional secondary server tools cannot displace core torrent controls.

## Pre-alpha queue-wide alias review acceptance (2026-10-24)

[PR #98](https://github.com/gamesbyian/Chunks/pull/98) merged the first queue-wide alias preview with conservative local filename proposals, explicit selection and per-name edits, a second confirmation, atomic scoped local save and conditional Undo protecting any subsequent manual changes. Limited to 100 proposals from the current server view; existing manual overrides are never included. Android/JVM and native emulator passed at source `f3f6b0238f71c5e167cf9b679d00db708a609f43`, with screenshot inspection. This is neither physical data rename, server-side synchronization, external metadata enrichment nor an unlimited queue batch engine. Style rules, metadata consent and high-scale paging remain future acceptance gates.

## Per-transfer home-screen widgets (deferred)

Users may pin an independently movable/resizable Android home-screen widget for **one specific transfer**. Its artwork-backed progress/status and tap-to-open behavior are shared across local and remote environments. The owner wants the indicator gone automatically when the transfer job is removed; launcher-enforced widget removal limitations make this a documented unresolved compatibility/UX requirement, rather than a guarantee. The canonical [per-transfer widget contract](PER-TRANSFER-HOME-WIDGETS.md) covers identity, security, offline states and rollout after foundational gates.
