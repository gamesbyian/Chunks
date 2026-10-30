# Libtorrent 2.1 JNI bridge: executable contract slice

**Integration status:** this stacked experiment adds a checked decoder for libtorrent's `peer_request` (piece/start/length) and an **unlinked C++ adapter candidate** implementing all 19 required `disk_interface` method signatures. The adapter routes reads/writes and SHA-1/SHA-256 hashing through the owned writer and returns explicit failures for unsafe rename/move/priority/deletion operations; it does not delegate to the default writer. It is compiled only in a dedicated exact-upstream-header ABI check, never installed into the Android app. This remains experimental, synchronous on the network thread, with missing partfile/priorities/move/rename semantics and no verified real network traffic. The parent ownership primitive is PR #774.

## Exact pinned upstream baseline

- libtorrent4j 2.1.0-39 uses native libtorrent submodule `arvidn/libtorrent@24a3adf35d3e689bde700371337aad61b160bafa`.
- `swig/libtorrent/session_params.i` deliberately ignores `disk_io_constructor` and exposes built-in default/POSIX disk constructor methods only.
- A supported extension must build a **replacement libtorrent4j JNI distribution** with the custom session-level `disk_interface` constructor wired before `SessionManager.start()`. It cannot safely mutate native `session_params` from an unrelated second .so sharing a duplicated libtorrent runtime.
- The constructor's owner receipt directory must be app-private and persistent. Each torrent's physical Place and exact metadata must be internally bound to path-independent transfer commitments before it is authorized to write.

## Required callback behavior (all 19 interface methods)

| Callback | Native ownership requirement |
|---|---|
| `new_torrent` | derive physical path claims, reject aliased or foreign paths, never assume resume means adoption |
| `remove_torrent` | retire native handles and jobs without deleting payload implicitly |
| `async_read` | use only existing receipt-verified physical files; never create a file or receipt to serve a seed/hash read |
| `async_write` | decode request, enforce piece size/ranges, reserve every crossed file before writing, then write pinned FDs only |
| `async_hash`, `async_hash2` | hash actual receipt-owned bytes and v2 block/pad semantics, post real read/error outcome |
| `async_move_storage` | no-replace, journaled transfer across Places; refuse instead of delegating unsafe default move |
| `async_release_files` | await/flush queued I/O and release handles only after completion |
| `async_check_files` | validate resumed ownership and piece readback; unknown state must demand a full check or refuse |
| `async_stop_torrent` | drain writes, flush file data and report uncertainty to the engine |
| `async_rename_file` | journal and rebind the exact path claim with no-replace semantics |
| `async_delete_files` | require separately explicit verified ownership and user authorization |
| `async_set_file_priority` | synchronize partfile reallocation/zero-priority transitions without bypassing ownership |
| `async_clear_piece` | synchronize failed piece writes before retrying |
| `update_stats_counters`, `get_status` | report truthful bytes and open handles, not optimistic completion |
| `abort`, `submit_jobs`, `settings_updated` | bounded draining, correct io_context callback delivery, configurable queuing |

All callbacks must dispatch completion on libtorrent's network thread. Unsupported operations must surface explicit errors and never invoke built-in disk backends. A fail-closed skeleton is not release-ready merely because it compiles.

## Request translation

`DecodePieceRange` accepts libtorrent's signed piece number/start/length plus the authenticated torrent's piece length and total bytes. It rejects negative/zero geometry, overflow, cross-piece access, out-of-bounds final pieces, and misaligned or oversized write blocks before I/O. Only valid requests become an absolute offset and length passed to `OwnedTorrentStorage`. The current 16-case positive/negative test suite is a geometry proof, not an active network callback proof.

## Release gates

1. Build replacement Android arm64/x86_64 libtorrent4j 2.1.0-39 JNI with pinned libtorrent and custom constructor. Verify clean Android NDK API29 and API35 linkage and JNI/R8.
2. Execute #715/#733 native foreign-byte witnesses through actual libtorrent traffic, including Stop/restart and restored/adopted paths, and verify foreign SHA256 unchanged.
3. Real torrent progress/completion, v1 and v2 verification, seeding, file priorities/unskip, data migration and public Downloads/SD access revocation. API29/API35 emulator then physical handset acceptance.
4. Keep private-only #765 as a temporary fallback until direct native writer or verified no-replace publication is demonstrated. Do not merge this bridge or close #568 based on pure unit tests.

Reference: https://www.libtorrent.org/reference-Custom_Storage.html and https://www.libtorrent.org/upgrade_to_2.1-ref.html

## Adapter candidate and ABI validation

- Source: `bridge/owned_libtorrent_disk.cc`. Its factory `MakeOwnedDiskConstructor(private_receipts)` is suitable only for a future rebuilt libtorrent4j SWIG extension; nothing invokes it today.
- `new_torrent` derives independent physical path receipt IDs from the destination root and relative path; mapping commitment separately includes the torrent v1 hash, file index and declared length. Full v2 identity and renamed-files overlay must be added before accepting v2-only torrents.
- `async_check_files` never adopts linked pre-existing files; fresh jobs make durable prospective claims, restored jobs require existing receipts and demand a full hash check. Unknown file identity refuses.
- `async_read`, `async_write`, `async_hash` and `async_hash2` use owned-storage range operations. Final v2 partial blocks are bounded to the actual transfer length. Completion is posted through the libtorrent I/O context.
- Rename, move, priority changes and payload deletion return explicit refusal in this prototype. That is essential safety containment, but these operations remain P0 incomplete. `async_stop_torrent` flushes synchronously, lacks a direct error callback, and records failures for future operations. There is no bounded job queue, thread pool, or backpressure yet; synchronous writes and hashing on the network thread are not production-acceptable.
- CI includes syntax-only compilation of every override against **the exact 24a3adf3 libtorrent source embedded by libtorrent4j**, in addition to standalone geometry tests. A clean syntax check proves API compatibility only, not successful JNI linking or BitTorrent operation.

The production plan is to build a maintained replacement libtorrent4j JNI artifact, expose the constructor in its SWIG session params and select this custom backend before any session starts. Do not attempt to inject it via a second, separately linked libtorrent .so.
