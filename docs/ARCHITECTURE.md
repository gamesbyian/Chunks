# Chunks technical architecture and safety contracts
Status: design authority for first vertical slice, not implementation acceptance.

## Modules and seams
Android Kotlin/Jetpack Compose shell → command/application coordinator → immutable transfer/place/collection models → local engine adapter (candidate Arvid libtorrent JNI) OR remote rTorrent adapter (XML-RPC HTTPS; modern JSON-RPC opt-in after probe). Classification consumes snapshots, produces recommendations; it never owns filesystem mutations. Optional Chunks Companion is a separate least-privilege service exposing restricted file operations with verified outcomes.

Start with one :app Gradle module and explicit package boundaries, not unearned microservices. Split modules when actual parallel implementation or build isolation justifies it. Room/SQLite is candidate for durable state, operation outbox, secrets references and history; never store plaintext passwords.

## Typed identity
Remote key = server UUID + canonical info hash (version-aware, avoid hash conflation). File key = torrent identity + engine-reported file index/path mapping. Physical location = Place ID + volume/path + server capability snapshot. Library copy has independent ownership and lifecycle. Classification and user labels are not file names.

## Command/confirmation states
New → Planned → Prepared → Executing → EngineAcknowledged → FilesystemVerified → Complete; errors may be Recoverable/Uncertain or Failed. Command IDs must be idempotent and persisted before external side effects. Timeouts imply **unknown**, never automatic "failed, resend" for a destructive action. Distinguish network acknowledgement from physical verification.

## Remote rTorrent
- v1 proof: read-only authenticated HTTPS XML-RPC commands system.client_version, system.listMethods, d.multicall2. Proxy SCGI on loopback/unix socket; never publish naked SCGI.
- Probe commands, input/output value shapes, response limits and version; offer bounded polling rather than overwhelming servers. Do not assume JSON-RPC exists on old releases.
- Mutations such as setting directory metadata are NOT filesystem relocation. Direct mode only exposes operations actually confirmed safe.
- A restricted companion may execute verified rename, copy, hardlink, folder creation and transaction recovery, scoped to configured allowed roots. Never accept arbitrary shell/command fields or client-supplied unvalidated paths.

## Move/rename design
1. Validate canonical roots, no path escapes/symlink swaps, collisions, free space, permissions, linked/shared inode ownership, partial data and torrent file mapping.
2. Commit intent in operation journal and take an exclusive per-transfer lock.
3. Same filesystem rename when safe; cross-volume staging copy/checkpoint/resume with independent bytes verification. Active transfer may need controlled I/O pause/handoff; remote connectivity itself need not drop.
4. Rebind engine and verify reported location plus actual bytes. For a separate library copy, preserve original seeding location.
5. Cleanup only after confirmed commit. If observer disconnects, server-side journal continues/reconciles. Never delete source while outcome is uncertain.

Hardlinks are unsafe to treat as detached snapshots while active torrent data remains writable. Reflinks/copies also require capability checks. Never silently rewrite media tags inside hashed seeding data.

## Android storage/lifecycle
Use SAF, document tree URI permissions and narrow scoped local files. Android 11+ excludes some tree roots; Android 15+ background rules constrain data sync. Actual background engine survival must be proven on devices; remote mode server transfer survives app death.

## Security
See SECURITY.md. No TLS bypass, no generic execute RPC surface, no public secrets, no untrusted path concatenation, no remote suggestions uploaded by default. Test with fixture server and throwaway data; never destructive tests on production seedboxes.