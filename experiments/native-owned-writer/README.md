# G0 native non-clobbering writer: executable experiment

**Experimental. Not linked to the Android app or libtorrent.** This module investigates the actual #568 writer-level boundary. Direct public Downloads and SD are targets to retain, not functionality to permanently abandon.

Run: bash experiments/native-owned-writer/test.sh --sanitizers

The earlier 22-case prototype passed a local C++17 and ASan/UBSan run on October 30. The current tree defines **26 regression scenarios** across two native suites, including subsequent ownership-token and non-mutating read protections. **The 26-case exact-head result is not yet confirmed; its GitHub CI is pending.** Cases include an already-existing foreign file, symlink parents, live file/root/ancestor replacement, hardlink alias, corrupt receipt, invalid path, overflowing offset, receipt collision, concurrent same-path Create (exactly one winner), and durable reopen. The multi-file suite exercises multi-file piece offsets, cross-file preflight, padding that never creates a file, preserved foreign contents, resumable absent-file reservations, file-as-parent alias refusal and readback from durable receipts. This is not BitTorrent traffic, Android FUSE, API29/API35, or physical-device evidence.

## Reason for the native seam

The current checked Start and Java path guards are time-of-check observers. Native libtorrent can still open and overwrite names after the checks. P0 #715 and #733 reproduce these failure modes. libtorrent 2.1's actual extensibility point is the session-level disk_interface configured via session_params.disk_io_constructor, not a Java wrapper around default mmap/POSIX async_write.

Binding check: libtorrent4j 2.1.0-39's upstream swig/libtorrent/session_params.i explicitly ignores disk_io_constructor. Its %extend session_params currently exposes only set_posix_disk_io_constructor() and set_default_disk_io_constructor(). The 2024 upstream issue aldenml/libtorrent4j#269 requesting a custom disk_interface is still open (checked 2026-10-30). Merely defining a second Android .so cannot change the already-linked libtorrent4j session's native constructor; a maintained rebuilt JNI/SWIG binding/fork is necessary for direct integration.

Upstream contracts:
- https://www.libtorrent.org/reference-Custom_Storage.html
- https://libtorrent.org/reference-Session.html
- https://libtorrent.org/upgrade_to_2.0-ref.html
- https://libtorrent.org/upgrade_to_2.1-ref.html

## What the prototype does

- New payload: pins root + parent directory descriptors and opens the file with openat(O_CREAT|O_EXCL|O_NOFOLLOW). It refuses any pre-existing file, parent symlink, unknown layout or multi-hardlink file.
- Receipt: commits a private, synced record bound to transfer/file mapping commitment and exact root/parent/file device + inode. It claims publication with linkat rather than a replacing rename. On uncertain publication, it leaves bytes untouched for recovery.
- Stopped Add before first file: durably reserves prospective relative paths in private pending receipts without creating payload files. After process restart, such a file can be created only when the pending claim matches the original directory identities and its physical path is still absent. A planted foreign file is refused by exclusive creation without touching bytes.
- Restart: requires the original private receipt, the caller's exact mapping commitment, matching stable inode/device identity **and an independent 256-bit random creation token stored in a persistent user xattr**. An inode number may be recycled following deletion. Missing/changed xattr, incompatible filesystem, stale receipt or unknown permissions refuse rather than assuming ownership. Never infers permission to adopt by matching file names, sizes or hashes.
- Read-only file checks and seeding cannot create missing payload or publish receipts. Only an independently admitted write can use exclusive creation from an existing reservation.
- Read/write/flush: uses the original pinned file FD. Revalidates the named root, intermediate parent directories and actual file inode before and after IO; refuses unrelated path substitution. No payload deletion or automatic repair.
- Piece-offset routing: each cross-file operation opens all affected files before the first piece write. Padding files consume logical byte offsets and read as zeros without creating physical payload. Zero-length metadata entries do not receive writes. This is a lightweight mapping experiment, not a full libtorrent piece cache, v2 hash engine or SAF implementation.
- Inputs: strict relative paths and lowercase 64-hex identifiers. The caller MUST cryptographically derive receipt ID and mapping commitment from environment, exact infohash, verified Place and path. The primitive only checks their shape. The receipt directory MUST be private. Direct writes to Android public Downloads/SD require actual persistent-xattr capability, durable directory fsync, non-replacing operations and device acceptance. Otherwise the safe route is app-private staging plus separately verified no-replace publication without losing the user's selected destination.

## Still required for production

1. Implement a complete, maintainable native libtorrent4j/NDK extension for libtorrent 2.1 disk_interface (including async read/write/hashing/check, priority, sparse/part files, move, abort and callbacks on the proper thread); do not delegate an unprotected write to the default backend. Evaluate ABI, fork maintenance and distribution before changing production.
2. Prove durable receipt/journal and inode identity for every file on Android public Downloads, Android 10 permission boundary, scoped Android/FUSE, supported SD Places and verified random-access providers; fail closed on unsupported filesystems. Treat concurrent hostile hardlink creation as a separate unsolved adversarial case, not proved safe by these point checks.
3. Reproduce #715/#733 real-peer destructive witnesses against the new custom native implementation with independently verified foreign SHA-256 unchanged, and positive payload SHA-256, CACHE_FLUSHED, re-seeding, process death and reinstall. Run exact-head API29/API35, actual handset, permission loss, provider and no-space failures.
4. Reconcile #748/#750/#755 provenance and active-priority work. The private-only candidate #765 is containment, not the final product design. Keep issue #568 open until integrated Android writer-level proof and independent review.

The experiment is intentionally isolated from the engine/Compose lanes to avoid interfering with agents or claiming release readiness.
