# Chunks canonical product contract

> **Authority:** binding owner product outcomes. Detailed feature rules live in the linked canonical contracts; feature completion and evidence live in [SPEC-COVERAGE](SPEC-COVERAGE.md) and [QUALITY-LEDGER](QUALITY-LEDGER.md). This document does not set implementation priority.

## External application access to files

Files in transfer trees and accessible local or remote Places MUST offer capability-grounded Android **Open/Open with**, **Share/Send** and explicit download/export actions. Format-agnostic on-demand reading, including seekable large remote files, is the preferred experience where authorized server access and receiving-app compatibility permit; a full download MUST NOT occur silently. rTorrent RPC control alone does not prove access to file bytes. Canonical rules, limitations and acceptance: [EXTERNAL-FILE-ACCESS](EXTERNAL-FILE-ACCESS.md). This is planned, not verified shipped functionality.


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

## Unified environment experience (binding owner direction, 2026-10-28)

Local-only (the default offer), remote-only, and combined local/one-or-more-rTorrent-profile configurations MUST all be first-class. The on-phone manager and every remote profile use one consistent navigation, Transfers list/detail, Add, controls and semantics, with divergences only where independently verified capabilities require them. A multi-environment Transfers view clearly identifies the selected phone/server list; transfer details, destructive dialogs and settings show their affected environment. Environment switching is navigation only, and disabling a mode cannot silently remove jobs/files or forget credentials. Settings distinguish application-wide, exact environment and per-transfer scope, with explicit preview for any multi-environment operation. [Binding detailed contract and acceptance](UNIFIED-ENVIRONMENTS.md). Existing implementation evidence does not establish complete parity.

> **Owner correction (2026-10-28):** Cross-engine **Copy Transfer** and **Move Transfer** operate on torrent **jobs only**, never downloaded file bytes. Move adds and verifies the target job, then separately confirms session-only removal of the source job, leaving all source files untouched. Destination downloads independently. Prior text proposing payload transfer, file migration or waiting for target seeding under these names is superseded by [the authoritative transfer-job contract](CROSS-ENGINE-TRANSFER-JOBS.md).

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

Cross-machine migration, Plex/Sonarr/Radarr integration, large-scale background automation, advanced bulk naming, streaming-related interactions, automatic external metadata services, additional remote engine adapters and premium multi-server dashboards are exploratory until they preserve the core safety and usability proposition.

## Explicit exclusions

No mandatory accounts, opaque tracker/content search, piracy promotion, unexplained AI uploads, background tracking by default, guessed file operations, or reliance on unapproved extra server privileges.

## Pre-alpha queue-wide alias review acceptance (2026-10-24)

[PR #98](https://github.com/gamesbyian/Chunks/pull/98) merged the first queue-wide alias preview with conservative local filename proposals, explicit selection and per-name edits, a second confirmation, atomic scoped local save and conditional Undo protecting any subsequent manual changes. Limited to 100 proposals from the current server view; existing manual overrides are never included. Android/JVM and native emulator passed at source `f3f6b0238f71c5e167cf9b679d00db708a609f43`, with screenshot inspection. This is neither physical data rename, server-side synchronization, external metadata enrichment nor an unlimited queue batch engine. Style rules, metadata consent and high-scale paging remain future acceptance gates.
