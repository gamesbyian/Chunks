# Chunks architecture and behavior contracts

Revised after CRITICAL-REVIEW-2026-10-24.md. **A design contract, not a claim of running implementation.** Product scope: PRODUCT.md; present work order: WORK-QUEUE.md; accepted evidence: QUALITY-LEDGER.md.

## Privacy/auth architecture boundary (planned)

Implement an in-memory process-session authorization coordinator keyed to local engine and specific remote profile identity, with policy generation, monotonic background-expiry timestamps, serialized BiometricPrompt transactions and explicit invalidation on process restart or policy changes. Enforce privacy both before screen composition and at protected command execution boundaries; do not rely on an overlay or persisted boolean. Use AndroidX BiometricPrompt and Android Keystore-backed secret storage behind Android-specific adapters, independently of typed rTorrent authentication transport. A locked UI may not tear down the independently managed native torrent engine/service or authorized background remote jobs. Redact recents/notifications/shortcuts/widgets/intents/TalkBack and reject stale callbacks, even through restored navigation. Unavailable biometric enrollment and invalidated keys must fail safely, with recoverable account/credential paths. See [TRANSFER-PRIVACY-LOCK.md](TRANSFER-PRIVACY-LOCK.md). **Design, not implementation.**

## Architecture: shared semantics, independent capabilities

Android Compose UI and share intents → application command coordinator → Transfer / Place / Collection / Operation domain model → explicit local engine adapter OR rTorrent RPC adapter. Organization produces **proposals**, not filesystem side effects. Storage providers and the optional Companion execute only authorized, capability-proven operations.

Start with one Gradle :app module and pure domain boundaries. Split when substantial implementation or independent tests justify it. Android lifecycle, persistence, secrets, file permission grants, RPC transports and native torrent libraries must not leak into portable classification logic.

A remote adapter must not claim parity with local Arvid libtorrent APIs. rTorrent uses the separate rakshasa libTorrent implementation. A generic RPC transport is an internal mechanism, never a user-facing arbitrary-method execution feature.

## Stable identity and path semantics

- Server identity: Chunks-generated server profile identifier plus verified endpoint fingerprint. Never persist passwords in a display name.
- Transfer identity: server/engine identity plus validated v1/v2/hybrid info-hash identity as actually reported by that engine. Treat duplicate hashes on **different servers** as different Transfers.
- File identity: Transfer ID plus file index/engine-relative path and a mapping generation. Renaming is a versioned mapping change; a display alias is not a backing file.
- Place identity: server/device ID, storage provider, accessible root and volume identity, human alias, allowed capabilities and path translation (host/container/mount). Never infer a host path from a container-visible path.
- Collection identity: user-defined semantic group. Membership is many-to-many and does not imply relocation.
- Published library copy: independent file object with its own path, data-integrity state, ownership, provenance and link/copy strategy.

A single torrent can have multiple individually completed files, hardlinked sibling representations and outstanding operations. Neither hash nor path alone provides enough information to authorize deletion.

## Capability negotiation

Each contemplated command consults an explicit capability decision:

- **SUPPORTED_DIRECT**: the exact server/version/build and current connection can safely perform the operation via allowed rTorrent methods.
- **SUPPORTED_ASSISTED**: a separately authenticated filesystem provider with matching root, volume and operation journal can perform it.
- **PENDING_PROBE**: unknown; no mutation offered until verified.
- **READ_ONLY**: server or storage mode cannot authorize mutation.
- **UNSUPPORTED**: no trustworthy implementation exists. Show a concrete reason and safe alternative.

Method enumeration is advisory; runtime arguments, actual response types, auth permissions, storage topology and operation-specific behavior decide support. Capabilities are scoped to server profile, version/build, RPC transport, filesystem, volume and currently authenticated principal; invalidate on restart, credential change, permission change or stale topology. Record check timestamp and distinguish lack of evidence from evidence of absence.

## rTorrent transport matrix and quality

Support traditional XML-RPC through authenticated, certificate-validated HTTPS proxy first, with JSON-RPC where actually available. Backward compatibility is a **matrix**, not a single version threshold. Test at least: legacy 0.9.x XML-RPC family; maintained 0.15.x or 0.16.x family; proxy/environment differences; constrained managed seedboxes. Different XML-RPC libraries differ in 64-bit integer/i8 encoding; no unconditional use of i8 for all integer requests. Inspect actual methods and encodings on real server builds.

- Authentication: verify Basic, Digest or other legitimate proxy schemes as demand warrants; never auto-fallback to plaintext remote HTTP or disable TLS certificate checks. Credentials must be isolated from logs and cross-origin redirects.
- Read-only baseline: system.client_version, optional system.listMethods, compatible d.multicall2 with explicitly checked view semantics. Main view is not guaranteed to represent all torrents on every server.
- Enumeration: devise bounded batches/paging or view-based partitioning compatible with observed RPC behavior; mark incomplete lists clearly. Never solve a 4 MiB response cap merely by unboundedly raising it.
- Control: allowlist individual operations, validate argument types, concurrency and observed acknowledgements, use command-specific retry policies. No unbounded command concurrency or rapid polling; apply backoff and server health information.
- Parse: reject DTD/XXE, deep nesting, huge arrays, malformed data, excessive strings, Unicode/path oddities and unexpected integer representations; validate in Android runtime as well as JVM.
- Secret retention: current prototype holds only volatile credentials; a durable account feature must introduce reviewed encrypted storage/Android Keystore and credential revocation before release.

Remote connection alone proves nothing about directory creation or host filesystem access. An rTorrent directory-setter changes metadata; do not infer moved bytes from it.

## Operation model: observed status is not command intent

Persist a durable Operation with immutable ID, requesting principal, target server/Transfer, exact source snapshot and content identity, requested destination, preview/approval receipt, capability and filesystem snapshot, expected engine mapping, lock/fencing token, cleanup stage and status.

States:

1. **Requested**: saved user intent, no effects.
2. **Planned**: fresh validated roots, collision, space, ownership and strategy.
3. **Prepared**: exclusive operation lease acquired, durable journal/fsync, destination reservation, source still matches.
4. **Copying / Repositioning**: mutation in progress; source remains protected and writes coordinated.
5. **VerifiedStaged**: source and stage have valid bytes/metadata for the selected completeness criteria.
6. **Committing**: engine I/O quiescence/handoff and path mapping change with reconciliation point.
7. **VerifiedCommitted**: engine reports and reads validated location, data/resume integrity matches, torrents can continue.
8. **Cleaning**: only now remove unused temporary/source data after ownership revalidation.
9. **Complete**: final filesystem result and engine mapping confirmed.
10. **Uncertain / NeedsRecovery / Refused**: explicit durable terminal-or-recovery states; never cosmetically convert timeout into failure then blind retry.

Stage boundaries may not all be atomic. Copying files across mounts is not a single transaction. Every stage must have a documented crash recovery decision, idempotency key and resumption or compensation semantics. An interrupted UI connection must not cancel a server-owned journal. Cancellation is a request that may be unsafe during commit; communicate when it can be honored.

## Safe relocation and seeding policy

- Root and destination authority comes from server-side *openat/no-follow*-class protections or equivalent verified handle-based traversal. String-normalizing a malicious path is insufficient to defeat symlink races.
- Check source identity and dimensions (volume, size, mtime and appropriate hashes), access permissions, concurrent writers and files referenced by other torrents. Detect foreign rTorrent/ruTorrent/scripts mutations and revalidate at commit.
- Same filesystem: atomic rename is a candidate **only** where target/filesystem/handles and engine rebind are proven safe. Existing open file handles and memory mapping can invalidate naive assumptions.
- Cross filesystem: stage copy/checkpoint, verify, coordinate I/O quiescence/reopen, prove correct engine mapping, then clean. If atomic handoff unavailable, defer until completion or explicitly refuse.
- Partial transfers: stage should not publish a misleading completed media file; completed chunks, sparse allocations, priority/skipped files and resume data need separate handling.
- Shared files and hardlinks: do not infer exclusive ownership from path. A hardlink is the same inode, not a detached copy. For library publication while the torrent is still writing, defer or produce a separate verified stable copy.
- Rename backing data only with an actual engine mapping and verifier; display title changes alone are metadata. Never make a torrent appear to seed when it cannot read its original pieces.
- Encryption/dedup filesystems, case-insensitive collisions, Unicode normalization, symlinks, ACL changes, hardlinks and quota limits get dedicated tests.

**Supported fallback:** when live relocation cannot preserve integrity or acceptable continuity, keep current data/transfer intact and provide a scheduled move-on-completion or verified copy action, explaining the limitation. The user may still change *desired destination* immediately, but it is visibly pending, not physically complete.

## Companion and independently authenticated storage

Companion is an optional constrained helper for advanced operations, **not a mandatory web gateway**. It must support a fixed allowlisted command schema, narrowly scoped per-root filesystem permissions, mutual or otherwise strong authenticated identity, operation journal, bounded concurrency, per-transfer fencing and redacted audit logs. No arbitrary shell, eval or user-controlled method names. Server may be hosted in Docker or managed seedbox, with path/container translation proven rather than assumed.

For hosts on which a companion cannot be deployed, investigate separately authenticated SFTP/SSH and provider filesystem APIs as alternative storage **capabilities**, but they cannot by themselves guarantee coordinated live rTorrent I/O. Any missing rebind/lock means a live move is disabled.

## Android local engine and lifecycle

Arvid libtorrent via [libtorrent4j](https://github.com/aldenml/libtorrent4j) `2.1.0-39` (arm64-v8a and x86_64 Android jars; Linux binary for JVM tests) backs the first `engine/LocalTorrentEngine` core: explicit existing save directory, stopped and unmanaged Add with duplicate-identity refusal, exact-infohash pause/resume with bounded readback (`VERIFIED`/`UNCERTAIN`/`NOT_FOUND`, because libtorrent applies them asynchronously) and session-only removal that keeps payload bytes. DHT, LSD, UPnP and NAT-PMP are off unless configured. The experimental libtorrent4j engine **is wired to an on-phone UI and a dataSync foreground service**; resumed torrent state, metadata-only magnets, opt-in DHT, per-file priorities, speed limits, real licensed web seeds and a verified independent finished-file copy to user-chosen SAF storage have merged. Torrent writes still use app-private storage: this is **not** general SAF-backed downloading, arbitrary backing-file movement, or physical Android background-lifecycle proof. Device APIs and SAF can grant document URIs rather than POSIX paths; storage engine integration needs an explicit file-I/O strategy. API 35+ dataSync foreground services have a total six-hour background cap; specialUse has declaration and review requirements. Background download/seeding promises require controlled physical Android 15/16/17 tests, including screen-off, Doze, networking/VPN changes, low storage, reboot and process kill. A remote engine remains active independent of the app.

## Security, testing, acceptance

Read SECURITY.md and QUALITY-LEDGER.md. Prove read-only real-server compatibility *before* introducing mutation methods. Then use disposable volumes and independent fault injection at every journal transition. No production seedbox operation may be used as the experiment that establishes whether an operation is safe.

## Default destination resolution and recent locations (owner clarification, 2026-10-27)

At Add draft creation, resolve a **default parent Place** for the selected engine without requiring selection. The optional picker always remains visible. For local Android torrent data, target a lazily created, idempotent user-visible **Downloads/Chunks Downloads** folder; secure practical direct-write and random-access libtorrent backing/storage mapping before replacing the current app-private implementation. Android 11+ disallows requesting the Download root with ACTION_OPEN_DOCUMENT_TREE, so do not treat SAF tree selection of that root as an acceptable implementation. Evaluate platform-allowed creation/access (including MediaStore's capabilities and limitations), engine compatibility, permissions, quota, disk pressure, process restart and seeding durability. If unusable, report a real supported fallback and do not label it Downloads/Chunks Downloads. Do not create the directory simply on app launch or on remote connection.

For each remote profile, resolve the server's **real configured default** (not a hard-coded Chunks folder or the last locally picked path), avoid sending a directory setter when using the server default unless needed and verified, and verify post-Add actual directory by RPC. Never create any Chunks-named directory on remote servers. For explicit custom paths, retain the existing parent-directory rules below. Recent locations are a bounded, ordered, locally stored list keyed to local engine identity or normalized server/account profile, recorded **only after verified successful use**. Do not persist auth secrets in recent keys/path labels or mix separate hosts; validate current permission, allowed root, existence and capability before reuse. A custom per-transfer destination does not change the persistent default. Tests must distinguish no-override RPC from explicit custom parent; include multi/single-file reporting and user-triggered creation capabilities.

## Add destination path semantics

For **new torrent Add**, a user who opts to change the destination chooses the **parent containing folder**; otherwise use the resolved existing engine default without a mandatory picker. rTorrent's `d.directory.set=<parent>` behaves consistently for single- and multi-file transfers; `d.directory_base.set=<parent>` can suppress the named subfolder of a multi-file torrent and therefore must NOT be used by the Add flow. The server readback must query `d.is_multi_file`, `d.name` and `d.directory`: a single-file torrent should report parent, while a multi-file torrent should report `parent/name`. This is not file movement; changing an existing transfer's directory requires separate verified filesystem operations. See [rTorrent directory behavior](https://rtorrent-docs.readthedocs.io/en/latest/cmd-ref.html).

## Distinguish session erase from file deletion

An allowlisted `d.erase` call removes the specific rTorrent download from its session and views. rTorrent documents that downloaded payload data is untouched, but configured events or tied-file deletion may have additional effects. This is NOT the same as removing files from a volume or deleting a named directory. Chunks shall always present these as separate actions, demand explicit identity and stale-state checks, verify session postconditions, and leave physical file deletion/relocation behind provider-scoped path ownership, journaling, and independent fault injection. No automatically retried erase after an uncertain network response. See the official rTorrent command reference: https://rtorrent-docs.readthedocs.io/en/latest/cmd-ref.html#d-erase.

## Quotas, cleanup impact graph and archive extraction (partially implemented)

Introduce per-StorageRoot accounting/quotas with separate engine backing, independent library outputs, archive extraction artifacts and staging. A file ownership/reference graph maps physical storage identity to all affected Transfer/file-index mappings; reclaim estimates cannot double-count hardlinks, shared files, sparse allocation or deduplicated extents. Quota enforcement belongs to the local engine write path and safe pause/resume logic, not merely a Compose slider. Remote quota is monitor-only unless a tested authenticated control exists; Android storage reports do not describe rTorrent host volume. Track snapshot freshness, unknown allocation and OS headroom.

A cleanup planner produces immutable dry-run impact and required approvals. Executor revalidates scope, identity, reference counts, engine writes and capability immediately before destruction; journal, observe and reconcile filesystem and torrent postconditions. Extraction is a separate bounded and transactional job: verified complete source archives in, user-approved destination and independent published output out, with streamed byte/file/depth/rate limits, safe handle-based containment, recovery staging and idempotent restart. Do not delete original archive on successful extraction. Remote extraction/deletion requires an independent scoped filesystem authority; rTorrent RPC alone does not confer one. Detailed model, slices and tests: [STORAGE-AND-EXTRACTION.md](STORAGE-AND-EXTRACTION.md).

## Device-local transfer alias boundary

Keep original rTorrent `d.name`, Chunks display alias, actual file mapping and optional independent library-copy filename separate. The alias key fingerprints endpoint, authenticated username and normalized infohash with SHA-256 in Android private, non-backed-up preferences; the fingerprint does not encrypt source metadata. Never pass aliases into RPC methods, infohash identity, classifier file operations or path authorization. Resolve display as `alias ?: original`, search either title, and sort using visible names without mutating the raw server snapshot. A local persistence failure must remain visibly unsaved. Future durable server profiles require scoped migration and deletion of orphan preferences. Merged native alias implementation [#74](https://github.com/gamesbyian/Chunks/pull/74) does not prove physical file rename, a populated real Seedhost workflow or remote replication.
