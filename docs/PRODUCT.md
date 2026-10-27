# Chunks canonical product contract

Reconciled 2026-10-26 against live code and owner intent. Owner-approved **vision** and engineering **proposals** are distinguished in DECISIONS.md. Read CRITICAL-REVIEW-2026-10-24.md before implementing any mutation. This document is the product authority; WORK-QUEUE.md alone owns task priority.

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

## Transfer access privacy and authentication (owner requirement, 2026-10-27)

Offer an optional privacy lock that protects **viewing and interacting with local transfers** and, independently, each remote rTorrent profile. Once enabled, the default is **one Android authentication per app process session**; configurable choices allow never, each foreground return, a five-minute background timeout, or each entry. Expose an immediate **Lock now** action. Use Android's supported native biometric interface (fingerprint and other eligible strong biometrics) with device PIN/pattern/password fallback by default; capability-aware alternatives must not strand users. Lock the interface and redact private transfer data without stopping authorized background downloads or seeding. Server login is separate: remember rTorrent connection credentials by default after first authenticated setup, provide opt-out/forget, and protect persistent credentials using reviewed Android Keystore-backed storage. Additional authentication may be required for sensitive actions without replacing exact destructive-operation confirmation. **Requirement only; not implemented or tested.** Full behavioral contract: [TRANSFER-PRIVACY-LOCK.md](TRANSFER-PRIVACY-LOCK.md).

## Approved Android Downloads behavior (2026-10-27)

Use the **Android platform's standard primary Downloads location**, normally the same shared Downloads area Chrome uses under its default configuration, as the reference parent for the local **Chunks Downloads** folder. Do **not** read, infer or depend on Chrome's private download preference: Chrome may instead target an SD card or prompt for each file, and Android has no public cross-app API that authoritatively returns Chrome's configured location. Let users select another supported destination (including removable storage when feasible), explicitly save it as Chunks' default, and reuse that choice independently of Chrome. Keep the per-transfer destination Change/recent choices obvious but optional. Folder creation is lazy, on device only, and permitted only through a platform-compliant, durable read/write approach compatible with libtorrent's random-access/resume/verify/seed requirements; do not request broad storage authority as a shortcut or mislabel an app-private fallback. No Chunks folder is ever created on remote servers. Tests and engineering gates: [ARCHITECTURE.md](ARCHITECTURE.md), [WORK-QUEUE.md](WORK-QUEUE.md). **Approved behavior, not current shipped functionality.**

## Transfer details: copy/move between local and remote (owner, 2026-10-27)

On each applicable transfer-details screen show a prominent **Copy to phone / Copy to server** action (and **Move** where safe) with clear target and destination selection. Distinguish **add same torrent/metainfo or magnet to other engine** (new transfer may download bytes again) from **copy verified payload data** (actual file transfer, safe target mapping, hash verification and resume) and **move** (copy, independently validate target seeding/resume, then explicitly authorize source removal). Show bytes, estimated space, existing-target conflicts, bandwidth implications, transfer progress, cancel/retry and source seeding consequences. Never promise migration that rTorrent RPC/remote filesystem capabilities cannot execute; offer metadata-only Add or a supported external transfer option. No source deletion by default; never silently stop seeding. Source and target can coexist and retain separate infohash/transfer identity and aliases. Capability-gate remote filesystem access, local storage permissions and integrity readback. See ARCHITECTURE, SECURITY, UX, WORK-QUEUE. **Planned, not shipped.**

## Remove transfer: extracted archive cleanup (owner, 2026-10-27)

Beside **Remove transfer** (keep data) and **Remove transfer & data** (delete torrent-backed bytes), provide a discoverable **Delete archive after extraction** option when supported. Offer it in transfer details/removal confirmation **and** storage/cleanup UI. A matching filename or existing extracted folder alone is insufficient: verify complete extraction provenance/manifest, expected entries, sizes and content hashes/readback where feasible, destination ownership and no later modifications. Show exactly which archive(s), which extracted outputs will remain, estimated reclaimable physical bytes and whether torrent hash checking/seeding would fail. Deleting torrent-backed archive bytes generally breaks the torrent even if extracted outputs survive: state this visibly and require distinct, explicit acknowledgement; never execute by default or quietly after Remove transfer. If server privileges cannot safely delete exact files or relationships are unverified, disable/refuse and explain. Keep source torrent/session removal distinct from archive cleanup, and do not imply extracted outputs replace seedable originals. **Planned.**

## Non-negotiable user outcomes

### 1. First-class rTorrent remote, no mandatory Companion

Multiple server profiles, authenticated encrypted connections, clear status, trustworthy lists and filtering, adding magnets and .torrent files with an automatically resolved default or explicitly chosen destination, transfer start/stop, per-file selections/priorities, trackers, speeds, errors, appropriate queue settings and expert details **as capabilities are demonstrated**. If a server disallows an operation, explain why and present safe alternatives.

Server management continues with or without a companion. Chunks does not require cloud sign-in. Real seedbox variants must be verified before claiming compatibility. Transport support is measured per installation, not inferred from its stated release alone.

### 2. Visible, optional destination choice at Add

**Owner clarification (2026-10-27):** Every Add flow must clearly display the resolved destination and offer **Change location**, but never require a folder-picker interaction merely to Add. Start with the environment's existing default. For on-device transfers the intended Chunks default is a lazily created **Downloads/Chunks Downloads** folder within the device's user-visible Downloads location, subject to Android storage permissions and verified seekable/direct-write support; do not silently substitute app-private storage while claiming the public destination is active. On remote rTorrent profiles use the server's configured default location and **never create a Chunks Downloads folder on the server**. Recent successfully used destinations should appear directly in Add, scoped to each device/remote profile, with readable labels and safe validity checks. A previously chosen per-transfer location must not quietly change the default for future Add operations. If a default is unavailable or cannot be verified, explain the limitation and ask for a valid destination rather than manufacturing success. Android's modern scoped-storage restrictions require explicit engineering validation before claiming public Downloads/libtorrent writes work. This overrides earlier language treating destination choice as mandatory. See [ARCHITECTURE.md](ARCHITECTURE.md), [UX.md](UX.md), and [WORK-QUEUE.md](WORK-QUEUE.md).

### 2. Destination selection whenever a transfer begins

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

## Storage budgets, cleanup and archive extraction (owner request)

Chunks must offer optional per-device-volume/Place storage caps for downloaded/backing and derived data, with sensible adjustable warning thresholds, low-device-space protections, and an accessible compact usage/reclaim dashboard. Caps never authorize deleting data; when enforcement is supported, safely throttle/pause affected local download writes while preserving seeding. Remote storage quotas are advisory until a verified server-side capability exists. Clearly distinguish logical torrent size, actual allocation and independently extractable storage.

Offer a compact cleanup wizard prioritizing files that can be safely reclaimed without damaging sharing; make affecting-seeding actions a clearly separated opt-in path with exact preflight impact and explicit confirmation. Deleting a torrent-backed archive after extraction typically breaks the affected torrent's pieces and seeding; warn in meaningful on-screen text. Do not automatically remove seeding originals. Session-only removal, payload deletion, extracted outputs, independent library copies and cache cleanup are separate permissions and outcomes.

Provide manual **Extract now** and easy opt-in **Auto-extract when verified complete** for supported archives, with destination, expansion-space estimate, safety bounds and clear status. Auto-extraction is off by default and preserves source archives for sharing. Full behavior and release gates: [STORAGE-AND-EXTRACTION.md](STORAGE-AND-EXTRACTION.md). Tracked as [#70](https://github.com/gamesbyian/Chunks/issues/70); these are requirements with partial implementation: bounded manual SAF ZIP extraction is merged (#119/#120), whereas real quota enforcement, physical cleanup deletion and automatic extraction remain open.

## Friendly transfer aliases and suggestions

Users can save an arbitrary Chunks-only display alias for a Transfer independently of the engine-reported torrent name, downloaded file paths, torrent metainfo, filesystem location and seeding. The original torrent title remains available and searchable; alphabetical sorting uses the displayed alias. A reset removes the override. The first implementation is device-local, scoped by the connected endpoint/account and infohash, not shared across clients. Backing-file rename remains separately safety-gated. A **limited finished-local-transfer SAF library copy** (#147) is implemented and preserves original seeding bytes; arbitrary remote copy or file mapping rename is not.

Offer local, explained, editable clean-name proposals from filename facts. For `Celebrity.Jeopardy.S04E02.720p.HEVC.x265-MeGusta.mkv`, good source-derived proposals are `Celebrity Jeopardy S04E02` or `Celebrity Jeopardy · Season 4, Episode 2`; a manual alias like `Celebrity Jeopardy Timothy Chalamet episode` must work without the app inventing a guest from absent filename data. Suggestions never save automatically, and unknown extensions should abstain. Queue-wide first-100 proposal review and conditional Undo are merged (#98). Naming styles, scalable paging, external opt-in media metadata, per-file aliases and synchronization are distinct future gates. Pre-alpha implementation merged in [#74](https://github.com/gamesbyian/Chunks/pull/74) with emulator-level acceptance; unverified physical-handset/accessibility and real hosted-server integration remain release blockers.

## Pre-alpha queue-wide alias review acceptance (2026-10-24)

[PR #98](https://github.com/gamesbyian/Chunks/pull/98) merged the first queue-wide alias preview with conservative local filename proposals, explicit selection and per-name edits, a second confirmation, atomic scoped local save and conditional Undo protecting any subsequent manual changes. Limited to 100 proposals from the current server view; existing manual overrides are never included. Android/JVM and native emulator passed at source `f3f6b0238f71c5e167cf9b679d00db708a609f43`, with screenshot inspection. This is neither physical data rename, server-side synchronization, external metadata enrichment nor an unlimited queue batch engine. Style rules, metadata consent and high-scale paging remain future acceptance gates.
