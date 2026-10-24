# Chunks canonical product contract

Updated after critical review 2026-10-24. Owner-approved **vision** and engineering **proposals** are distinguished in DECISIONS.md. Read CRITICAL-REVIEW-2026-10-24.md before implementing any mutation. This document is the product authority; WORK-QUEUE.md alone owns task priority.

## Product identity

**chunks. Your downloads have places to be.**

An Android-native, delightful, deeply capable BitTorrent client and first-class rTorrent remote. Move through the app without needing to understand pieces, hash checks, session files or SCGI. Technical depth remains accessible. Attractive colour, irreverent personality and generously sized native controls should coexist with an information-dense expert mode.

A torrent client is a data-integrity tool: none of its charm is worth lost bytes. The user's mixed library is valid; do not demand movies and TV live in separate top-level directories.

## Three nouns, five different identities

- **Transfer**: one engine-managed BitTorrent download/seed, scoped to a specific engine and server/device. Its torrent info hash does not equal a physical file path.
- **Place**: a physical destination as accessible to a particular local engine or rTorrent server, not an arbitrary pathname string. Includes capability, volume, allowed root and human display alias.
- **Collection**: user-recognizable semantic relationship such as Star Trek, Spider-Man, a season, franchise, music, comics or ebooks. Many-to-many with Transfers, independent from Place.
- **Display name**: a user-editable Chunks-only label for a Transfer or file; changing it does not change torrent metadata, the original filename, the download directory or seeding data. User aliases are scoped by server/engine identity and infohash, not a mutable file path.
- **Backing file / published library copy**: respectively the bytes controlled by the engine and an independently managed file exported for a user library. They have different ownership, path and mutation rules.

Tags, category, collection membership, destination suggestion and filesystem mutation authorization are distinct states. A Transfer may belong to multiple collections; a file can be represented in several collections without duplicating data.

## Non-negotiable user outcomes

### 1. First-class rTorrent remote, no mandatory Companion

Multiple server profiles, authenticated encrypted connections, clear status, trustworthy lists and filtering, adding magnets and .torrent files with explicit destination, transfer start/stop, per-file selections/priorities, trackers, speeds, errors, appropriate queue settings and expert details **as capabilities are demonstrated**. If a server disallows an operation, explain why and present safe alternatives.

Server management continues with or without a companion. Chunks does not require cloud sign-in. Real seedbox variants must be verified before claiming compatibility. Transport support is measured per installation, not inferred from its stated release alone.

### 2. Destination selection whenever a transfer begins

Start/Add places destination choice prominently within the Add journey. Show device/server and full human-readable destination, with recent/favorite folders and a new-folder action only when supported. Validate destination scope, permissions and collisions before submission; show pending/unknown server response honestly. The action is not complete merely because an RPC returned success.

### 3. Move any time it can be done safely

Allow move requests before, during and after downloading. **Guarantee no silent data loss and no misleading success; strive for uninterrupted torrent session and continued peers.** No universal guarantee of uninterrupted disk throughput during cross-device copying or active-file handoff. When a server/filesystem cannot safely relocate active data, the UI offers a deferred operation, an independent library copy when appropriate, or an explicit refusal.

### 4. Rename without breaking torrent integrity

Three deliberately separate actions:
1. Rename displayed title only (always reversible, metadata-only).
2. Rename the engine-backed file only where verified supported and safely synchronized.
3. Create a renamed library copy, preserving the original seeding file. Do not publish incomplete writable data as a stable media library asset; hardlinks are shared-inode views, not snapshots.

Show actual outcome and whether the original seed path is retained. A torrent's original engine-reported title, Chunks-only queue alias, backing file path and renamed exported library copy must remain separate. Queue alias editing and resetting should always be available without filesystem privileges or remote RPC writes; it must never change .torrent metainfo, actual downloaded filename, destination, pieces or seeding. Show the original title as well as the alias; allow searching by either and sort by the displayed title. Alias persistence is scoped to server/account and infohash, not mutable filename or queue position, and is device-local unless separately designed.

**Smart naming outcome:** offer editable, explained offline filename-cleanup suggestions, not automatic applied names. The source `Celebrity.Jeopardy.S04E02.720p.HEVC.x265-MeGusta.mkv` can suggest `Celebrity Jeopardy S04E02` or `Celebrity Jeopardy · Season 4, Episode 2`; a user can instead type `Celeb Jeopardy s04e02` or `Celebrity Jeopardy Timothy Chalamet episode`. No tool should claim the guest is inferred when the filename has no such information. Preserve known titles and episodic tokens, remove only confident technical/release clutter, and abstain on unknown extensions or ambiguous parsing. Always keep manual labels, allow original-name reset, never auto-apply, and provide a clear explanation of why a candidate was suggested.

**Delivery plan:** (A) in-card editing, local persistence, original-name search, alias-aware sort and offline suggestions: candidate [#69](https://github.com/gamesbyian/Chunks/pull/69), not merged/accepted until CI and native evidence; (B) queue-wide recommendation discovery, optional user-approved batch cleanup, preferences for naming styles, per-item review, provenance and reversible changes; (C) optional external metadata for accurate guest/episode identification with explicit opt-in, source, confidence and privacy controls. Future per-file aliases, cross-device sync and automatic application require separate decisions; **none** of these layers authorizes changing engine-backed bytes or paths.

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

**Organization beta:** non-mutating sorting and display-name suggestions (the independent alias slice can ship earlier), collections and approved plans; actual remote moves only behind proven filesystem capabilities and failure recovery.

**Standalone-client beta:** local BitTorrent engine with verified download/seeding, storage permissions and Android lifecycle behavior.

**Release:** all core flows physically tested, independently audited for destructive changes, security/credential story resolved and installer/distribution documented. “Full featured” is a destination, not permission to ship untested engine options.

## Deferred, not forgotten

Cross-machine migration, Plex/Sonarr/Radarr integration, large-scale background automation, advanced bulk naming, streaming-related interactions, automatic external metadata services, additional remote engine adapters and premium multi-server dashboards are exploratory until they preserve the core safety and usability proposition.

## Explicit exclusions

No mandatory accounts, opaque tracker/content search, piracy promotion, unexplained AI uploads, background tracking by default, guessed file operations, or reliance on unapproved extra server privileges.
