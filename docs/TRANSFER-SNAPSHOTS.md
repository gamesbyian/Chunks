# Portable transfer snapshots (owner requirement, 2026-10-27)

**Status: transport foundation implemented (#295), user-facing snapshot export/import still planned.** Bounded ZIP member packaging, deterministic SHA-256 member receipt and strict no-filesystem-extraction reader passed Android/JVM [40594814560](https://github.com/gamesbyian/Chunks/actions/runs/40594814560) and were merged at `1431b1a846284046efec65c46d89cf6c90e81f7e`. The container does NOT yet parse/validate manifest schema or metainfo identity, connect to either engine, or perform export/import UI or payload recovery. Canonical product requirement for Chunks local and remote rTorrent inventory portability. Product/architecture/security/UX decisions should refer here; priority remains solely in WORK-QUEUE.

## Outcome and scope

From a local Chunks engine or a selected rTorrent profile, **Export transfer snapshot** creates one portable, user-owned `.chunksnap` file. Importing it into a selected local engine or different rTorrent profile reconstructs the chosen torrent **definitions** and supported per-transfer settings, **not downloaded payload bytes**. Intended example: preserve a seedbox's 250-torrent inventory before shutdown, connect a replacement seedbox, preview the snapshot, remap download destinations, and selectively restore without requiring the old server to remain online.

Snapshots are for manual and optionally repeatable inventory backup. Never automatically migrate, delete, stop, reannounce, or alter source transfers. The exporting server is read-only. A successful import means the target engine has accepted and independently listed matching identities, **not** that it has received or verified any payload bytes or established peer connectivity.

## Archive structure and versioning

- Single ZIP-format container with extension `.chunksnap`, containing `manifest.json` (UTF-8), optional `torrents/<stable-id>.torrent` raw metainfo, optional portable Chunks-only alias/collection records in the manifest, and `checksums.json` with SHA-256 digest and size for each stored member. No payload files or rTorrent session files.
- Top-level `schemaVersion: 1`, `producer`, creation timestamp (UTC), source kind (local/rTorrent), **redacted user-facing source label** (default omit server URL/username), counts by recovery capability, and items. An item's scoped identity is original v1/v2 infohash identity where verifiable; content path is never identity. Preserve original name and original infohash, plus the source's optional directory suggestion, current complete percentage and state **only as historical context**.
- Prefer exact complete `.torrent` metadata with verified hash and appropriate announce information. A valid magnet with complete infohash and required retrieval parameters is a separately labelled fallback. **Hash-only entries are inventory records, not automatically restorable torrents.** Never fabricate a magnet that is guaranteed to retrieve metadata, trackers, peers, or content from an infohash alone.
- Preserve file selection/priorities, selected trackers, tags/collections, local-only display aliases and start-paused preference when retrievable and translatable; capability-report any unrepresentable setting. Do not copy credentials, passkeys in connection URLs, cookies, authorization headers, private device tokens, rTorrent scripts, XML-RPC commands or arbitrary session state.
- Explicitly distinguish public tracker URLs from private tracker announce URLs. Private-tracker passkeys are often embedded in legitimate `.torrent` announce URLs, so **a full-fidelity archive can contain secrets**. Default export must show a clear sensitivity warning and require deliberate selection of full-fidelity tracker preservation; a sanitized export must mark torrents whose tracker removal may prevent their restoration/use. Never describe an exported snapshot as categorically free of secrets.
- Fixed size/member limits, bounded JSON/parser depth and string lengths, duplicate-name rejection, normalized paths/no traversal/no links, exact member allowlist, manifest checksum validation, and archive-version rejection before any writes. Verify metainfo hashes and identity congruence, including mixed v1/v2 where supported. Avoid ZIP bombs and giant torrent lists; stream into bounded temporary storage and atomically publish through Android document picker.

## Extraction of exportable definitions

1. Snapshot inventory into an immutable, source-revision-stamped read-only view; fetch full metainfo via supported **local engine APIs** or a verified, restricted remote metainfo export capability (for example, independently authorized read-only filesystem access). Ordinary rTorrent XML-RPC inventory methods do **not** universally expose raw `.torrent` bytes.
2. For a remote profile without verified metainfo retrieval, derive only what the RPC actually supplies, e.g. verifiable infohash and tracker data. A magnet is valid as a fallback only when the necessary identity and usable magnet context really exist. Explicitly classify per entry: `complete-metainfo`, `magnet-fallback`, `inventory-only`, `failed`.
3. Show an export review **before** file creation with total items, restorable items by strategy, omissions/failures and privacy warning. Provide a share/save flow using Android's document provider, working offline after successful generation. Do not falsely mark 100% recoverable because 100% of names/hashes were listed.
4. Individual entry failures do not erase the rest. Export report lists exact failures and mitigation without exposing sensitive URLs.

## Import and restoration workflow

1. Choose `Restore snapshot` from transfer-list menu or profile tools; open a user-selected `.chunksnap` (or explicitly supported plain `.torrent`/magnet list later). Perform all parsing, hash verification, version checking and inventory matching **before** contacting the target with mutations.
2. Show destination engine/profile, counts for already-present exact identities, restorable/new entries, unsupported/unsafe entries, and capacity limits. Default selection **missing transfers only**; do not duplicate entries or unexpectedly modify existing target torrents.
3. Offer per-entry and bulk selection, default target directory or reviewed mapping from old directory roots to verified target Places, never auto-create a Chunks server folder. Old absolute paths are **suggestions only** and never silently sent to a new host. Respect scoped recent destinations and custom parent selector. Unknown or absent destination defaults block only affected items, with an actionable prompt.
4. Default to **add paused/stopped**, unless the user explicitly selects start-after-restore. Existing target transfers remain unchanged unless separately and explicitly confirmed. Apply supported file priorities after creation and recheck every exact hash and saved settings. Distinguish imported, duplicate/skipped, unsupported, failed, and uncertain remote acknowledgements.
5. Persist a journal of pending imports and target-scoped completed hashes to survive process death. On timeout, reconcile by fresh inventory before deciding whether to retry; never blindly reissue an uncertain remote Add. Support safe resume and export of results.
6. The result summary clearly states `torrent definitions imported; payload data not transferred`, warns that old completion percentages are historical, and explains the user may re-download or independently transfer existing payloads and recheck them on the new server.

## UX/accessibility

- Local and remote transfer list overflow: **Export snapshot**, **Restore snapshot**. Also expose Export for selected transfers from bulk selection and profile settings. Import destination must always be explicit.
- Export progress: gathering inventory, resolving metadata, checking entries, writing archive, done/partial. Clear distinction between unreachable source, missing metainfo, weak magnet and file save error.
- Preview shows a concise restorable count, source date and privacy warning; expandable per-item diagnostics and a default missing-only selection. Long lists support search, type/status filters and accessible bulk actions; 48dp touch targets, TalkBack status, 200% font scaling and no decorative reliance on color.
- Optionally provide a human-readable manifest/report, but the full snapshot remains one archive; no external cloud service is required.
- Never force an archive passphrase as a prerequisite. Explore optional password-encrypted export using independently reviewed authenticated encryption, not ZIP's legacy weak encryption. A normal unencrypted archive must remain clearly labeled sensitive.

## Architecture and acceptance slices

A. Introduce a portable, bounded `SnapshotManifestV1` model and pure serializer/validator with deterministic fixtures: valid mixed assets, missing metainfo, malformed manifest, path traversal, duplicate ZIP entries, ZIP bomb, wrong checksum, inconsistent infohash, private tracker passkeys, unsupported version, and interrupted writes.

B. Implement local export against the exact active engine, proving exported raw metainfo/hashes and persistent alias/collection attachments. Test process restart and Android SAF create-document/readback with no loss of torrent payload or seeding.

C. Implement rTorrent read-only inventory export and capability-aware metainfo acquisition. Verify at least two server configurations: RPC-only limited coverage and a fully authorized metainfo read adapter; no assumption that `d.get`/XML-RPC can return session `.torrent` data. Explicit coverage counts are release gates.

D. Implement dry-run preview/dedup/destination remapping, local/remote import adapters and post-Add exact-hash confirmation with timeout fencing. Test against disposable rTorrent, native Android API35, interrupted batch, partial success, duplicate import and two profile defaults. Physical Android/real Seedhost acceptance separately gated. No downloaded-payload claim until actual download/recheck verification.

E. Wire accessible export/import entry points and completion reports; add `.chunksnap` sample fixtures with synthetic/public torrents only, no embedded credentials or private tracker tokens. Incorporate into SPEC-COVERAGE, PRODUCT, ARCHITECTURE, UX, SECURITY and WORK-QUEUE when those shared files are next safely reconciled.

## Non-goals for v1

No downloaded data copy, no replacement server provisioning, no arbitrary remote filesystem access, no restoring active peers/ratio or exact historical completion, no general rTorrent session-state transplant, no implicit tracker passkey stripping that breaks a private torrent, and no guarantee that magnets can retrieve metadata. This feature composes with, but is independent of, local↔remote verified payload copy/move.
