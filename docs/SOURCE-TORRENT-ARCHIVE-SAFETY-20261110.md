# Verified source-.torrent archive: implementation boundary

**Status: implemented private-copy primitive, NOT wired to Universal Add and NOT owner-accepted.** This is a non-overlapping foundation slice for #1517. Source file actions in [the binding lifecycle specification](SMART-ROUTING-AND-SOURCE-LIFECYCLE.md) remain incomplete. Do not mark Keep/Archive/Delete or Smart Routing as delivered.

## Caller contract

`SourceTorrentArchive` accepts only user-originated `content://` document **bytes already read through the existing bounded/validated source intake**; an exact-environment, exact-infohash *new registration* positively verified by the shared Add coordinator/engine; and explicit choice Keep, Archive or Delete. The archive primitive neither proves that RPC/native verification nor takes its own authority to Add.

- **Keep** returns without reading, copying or deleting the original. No proof is needed to refrain from work.
- **Archive** refuses absent/ambiguous/duplicate/failed Add evidence, wrong infohash, malformed/non-v1 bytes, URL/magnet masquerading as an original document, untrusted archive directories, oversize or quota exhaustion. Valid original bytes are copied into app-private `filesDir/source-torrents/`; original remains untouched.
- **Delete** returns `OriginalDeletionRequiresApproval` *only* for valid original-document bytes and a verified fresh exact Add. It never invokes `ContentResolver.delete`, `DocumentsContract.deleteDocument` or another mutator. This result is not user approval and must not be treated as proof of deletion.
- No engine, provider lookup or network operation occurs here. No filenames, source URIs, tracker passkeys, account credentials or torrent payload paths appear in archive identities or logs.

## Storage safety

Archive identities are `<original-v1-infohash>-<SHA-256-full-metainfo>.torrent`, allowing byte-identical deduplication and distinct validated tracker/metadata variants without a collision overwrite. The archive is bounded by **64 MiB and 256 stored definitions**, independent of torrent payload storage. Existing entries are checked without following symlinks; unexpected files, symlinks, corrupt identities and quota exhaustion refuse archival rather than auto-cleaning. A new copy is staged in the same directory, file-fsynced, published with an atomic no-replace hard link, directory-fsynced, cleaned and directory-fsynced again, and independently read back. If directory durability is uncertain the caller receives `NeedsReconciliation`, never a success claim or permission to remove an original. App uninstall removes these private copies.

## Still required before integration

1. Expose **Source file handling** in the **single** Universal Add form only for a genuine original Android document, with persisted global default Archive and per-job Keep/Archive/Delete override; fetched HTTPS and magnets must not gain a fictitious deletable original.
2. Retain temporary original URI authority separately from the source bytes, without storing passkeys in SavedState. Freeze source, environment/account/Place/Start and handling selection with the Add ticket.
3. After an independently verified *new* Add, invoke archive on IO, retain exact environment+hash association for later source sharing, and report **Archived copy; original kept** unless separately authorized original deletion succeeds. Handle process interruption/restart, dedup and recoverable copy with no blind retries.
4. For Archive-as-move or Delete, independently check same-source document authority, permissions, user approval and provider outcome. Never delete for duplicate, refused, pending, uncertain or changed-source Add. Never touch payloads or remote URLs.
5. Qualify on a disposable installed Android document provider with accepted/refused deletion, no-permission, lost response, restart, changed URI, duplicate Add and exact remote/phone positive piece-verified Add. Add unit tests are necessary but *insufficient* for acceptance.

Neither `SourceTorrentArchive` nor its tests claim independent authenticated peer/piece evidence. Preserve #1521's red native/installed receipts until its source-first Add journey qualifies at an exact head.
