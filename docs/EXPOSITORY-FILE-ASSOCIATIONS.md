# Chunks → Expository Transfer read-only seedbox file association feed

**Status:** binding future integration design, not implemented or validated. Companion contract: `gamesbyian/Expository/docs/android-transfer/TORRENT-AWARE-FILES.md`. Chunks remains fully functional with no Expository installation. This feature does not supersede live rTorrent Add/Start/Stop, inventory, physical device or API29/35 P0 gates.

## Purpose

Expose a **consented, read-only**, efficient snapshot of exactly which server files are referenced by known rTorrent transfers so Expository Transfer can annotate matching FTP/FTPS/SFTP files, filter file lists and warn during potentially disruptive file operations. Files in stopped torrents remain known. A missing/stale/incomplete Chunks inventory cannot prove a file is unassociated. No transfer or file is modified merely to synchronize metadata.

## Producer contract

Maintain a durable local index derived from *actual* authenticated rTorrent inventory on each configured exact profile: immutable environment/profile ID, server fingerprint and numbered instance identity, torrent infohash including v1/v2 where applicable, state, path/base directory, file-relative paths, expected sizes, optional completed/verified evidence, observation time and complete/partial inventory coverage. Use bounded rTorrent multicalls where supported with explicit response/readback verification, paging/capabilities as available, and per-profile isolation. Avoid path/torrent-title inference, blind retries, tracker secrets, XML-RPC credentials or raw metainfo exports. File membership and changing torrent state have separate freshness/generation indicators. Record when metadata is incomplete or file lists could not be loaded.

Expose a versioned, constrained Android read-only ContentProvider (or independently reviewed IPC equivalent) with an explicit user-mediated pairing grant per Expository app identity and Chunks environment. No public export of arbitrary torrent inventory, grant-less third-party access, writable IPC or cross-app commands. Permit unlink/revoke and cache purge. Document schema and compatibility negotiation before implementation; handle app absence, upgrades, provider crashes, permission expiry, locked Chunks and offline seedbox. Avoid continuous keepalive services or forced synchronization solely for UI badges.

## Path, privacy and operation invariants

Chunks supplies rTorrent data paths as observed and exposes only scoped metadata approved for the linked client. It does **not** assert that an FTP path denotes identical underlying file bytes: Expository owns per-root mapping and may infer tentative path-size matches with ambiguity displayed. Multi-torrent paths, symlinks, hardlinks, renamed payloads, single-file layouts, case/Unicode mismatches and multiple remote profiles require explicit treatment. The mapping must never silently join distinct accounts or numbered ruTorrent instances.

Only publish a negative 'no known association' result when a **complete, fresh inventory for the paired root** supports that claim. Never mark the whole filesystem 'safe to delete'. Expository's delete/move/rename/Replace/Smart Merge review stays authoritative for those operations; this index only supplies contextual hazards. `d.erase` stays session-only; the feed gives no new file deletion authority.

## Design / validation

Expository list badges: textual Seeding / Downloading / Stopped / Known-stale / Unknown, optional filters, per-folder aggregate covered-file counts, per-file **View in Chunks** deep-link with exact environment+hash and safe fallback. Colors cannot carry meaning alone; support TalkBack, 200% text, 48dp touch, 320x700 and 412x892. Local indexed joins; no per-file rTorrent/FTP requests, no expensive recursion on folder open; update visible rows when snapshot generation changes. Cache retention bounded and revocable, no content or tracker credentials in diagnostics.

Test real same-seedbox rTorrent/FTP observations first, then negative/edge fixtures and installed Android IPC. Require exact profile isolation, unauthorized-app denial, revoked grant denial, incomplete inventory, background absence, stale feed, renames/hardlinks, large listings, no spontaneous network or write operations, and measured latency/jank rather than claiming zero lag. Preserve honest receipt categories (real seedbox vs emulator vs physical).

## Sequencing

1. Finish foundational trustworthy per-infohash/per-file remote inventory and lifecycle.
2. Draft and test shared schema/IPC security fixtures across both repos, with no provider write side effects.
3. Implement Chunks optional snapshot producer and covered-root completeness evidence.
4. Implement Expository explicit pairing, mapping, cached join and informational UI.
5. Add independently reviewed hazard warnings/Smart Merge integration and real-device + seedbox QA.

No automatic background monitoring, file migration, payload removal or scheduling is implied.
