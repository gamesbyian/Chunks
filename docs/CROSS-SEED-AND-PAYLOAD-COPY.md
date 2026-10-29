# Cross-seed intelligence and verified payload copies

> **Authority:** owner-approved niche workflows (D-NICHE-REUSE-20261029). Product outcome: reuse already owned bytes safely, not just clone a torrent job. This contract owns cross-seed discovery/verification and separately named cross-environment **payload** copy. [CROSS-ENGINE-TRANSFER-JOBS](CROSS-ENGINE-TRANSFER-JOBS.md) remains the exclusive authority for **Copy Transfer / Move Transfer**, which transport torrent registrations only, not payload bytes. [TRANSFER-FILE-RECOVERY](TRANSFER-FILE-RECOVERY.md) owns repairing an existing job after an external path change. **Status: planned, no implementation/host proof asserted.**

## Scope and exclusions

- **Cross-seed discovery:** with user consent, find additional compatible torrent metadata for files already available in a selected local/remote Place; compare candidates using metadata, filesystem and verification evidence; optionally add a second independently scoped torrent job reusing the same underlying content.
- **Verified payload copy:** explicitly copy actual bytes between selected Places on the phone and/or independently accessible remote hosts, preserving the original. Support a resumable, bounded transfer and verify the destination before presenting success. The target may later be used for a new seed job, but file-copy completion does not imply torrent-job registration.
- These are two distinct optional actions and never silently invoked by the ordinary Add/Copy Transfer/Move Transfer buttons. No automatic cross-tracker upload, no deduplication by title alone, no default cross-profile scanning and no Chunks-operated relay.
- **Excluded by default:** reflink/hardlink/symlink mutation without proof, shared-blob ownership transfer, automatic deletion of source files, converting a private tracker torrent to a public swarm, permanent headless cross-server mirroring and server privilege escalation. Read-only suggestions are allowed with opt-in; mutation always reviewed.
- Independent local-engine and rTorrent sessions share UX vocabulary, but only verified file adapters can inspect or copy backing bytes. RPC access is insufficient.

## Terminology and stable identities

A **torrent job** is `(environmentId, engineIdentity, infohashVersioned)`; **file identity** includes exact storage adapter, Place/root, path, size, observed generation and content fingerprint; a **candidate** is a proposed metainfo-to-file mapping, not evidence that an engine can safely seed it. **Cross-seed receipt** stores candidate source, matching evidence, approved mapping, target job scope and verification. **Payload-copy receipt** stores immutable source/destination endpoint identities, byte-range journal, source snapshot, cryptographic digests, progress, permission and user intent. A single filename or ordinary SHA of an individual file is insufficient to claim full torrent-piece compatibility; piece hashes, order, padding, file boundaries, per-file offsets, v1/v2/hybrid rules and skipped files matter.

## Relation to human Task outcomes

A resumed actual-byte copy MAY attach operation receipts to a user-selected goal under [TASK-OUTCOMES-AND-CONTINUITY](TASK-OUTCOMES-AND-CONTINUITY.md), but only verified destination bytes can satisfy a **Remote payload retrieved** milestone. Cross-seed **Candidate** is not a completed seeding outcome; independent hash/engine verification is needed. Neither a newly registered job nor a verified file copy implies that the user's media import, playback or recipient handoff succeeded. This feature does not create a second task-checkpoint or policy engine.

## Cross-seed discovery journey and UI states

**Entry:** `Transfers → selected job → More → Find compatible torrents`, or `Places → selected folder/files → More → Find cross-seeding opportunities`. A secondary optional **Cross-seeding** view is available within **Discover → Advanced** only while Discovery is enabled; it does not add a main-tab or permanent transfer-card button. A user may explicitly scan imported .torrent metadata without enabling network discovery.

1. Select exact environment, candidate Places and source providers. Preflight read permissions and review which file names, sizes, media identities or hashes will be disclosed. Search via [Discovery](INTAKE-QUEUE-AND-POLICIES.md) and connected indexer adapters **only if Discovery is enabled**. Offline comparison of user-provided metadata and authorized local files remains available when it is off. No credential sharing across indexers.
2. Show results grouped as **Verified compatible / Needs verification / Not compatible / Unsupported** with tracker source and privacy policy, bytes potentially reusable, missing/extra files, size and mapping differences. A title/size resemblance is always **possible match**, never `Verified`.
3. Preview exact filename mapping (including Unicode/case), paths, file offsets, piece layout, linked-file ownership and effective storage impacts. Prefer non-destructive existing-data recheck in a disposable/staged job when the backend supports it; before any rebind/link ensure both torrent jobs retain usable backing paths.
4. **Add another seeding job** uses the ordinary reviewed Add path with environment, Place, private-tracker identity, seeding group, start choice and capability readback. If no safe shared backing exists, offer an explicitly separate **Copy files here** proposal; never substitute a download without saying so.
5. Job remains **Draft → Candidate → Mapping reviewed → Checking → Verified shareable / Partial / Rejected / Uncertain**; only verified pieces count as existing. Show result and safe Undo for newly added registration (session-only) without deleting data.

A small **Find compatible torrents** row may appear under transfer detail's More menu. Background cross-seed suggestions are disabled by default and optional under `Settings → Discovery → Cross-seeding`; they obey the Discovery master switch. Never silently add jobs, register private passkeys on another environment, or change seeding policy.

## Copy actual files between Places

**Entry:** `Places → select files/folder → Copy files to…` or transfer detail's `More → Copy payload files…`. Keep **Copy Transfer** elsewhere with explicit **job only; downloads independently** explanation. In the copy wizard: **Source → Target environment/Place → Permissions/capacity → Copy strategy → Review → Execute → Verify**. Explain that Chunks may copy bytes through the phone if no server-to-server path exists; show network/metered/battery impact and that Android background limits can interrupt it. Destination path is independently selected and may differ from the torrent's published path.

- Strategies: verified file-adapter direct transfer where authorized, or encrypted bounded phone-mediated streaming. Do **not** infer SSH, SFTP, FTPS, rsync, rclone or server-side move from rTorrent RPC; adapters must declare read/write/random access/resume/checksum support independently.
- Use staging/temp names or generation-safe destinations, enforce quota, preserve original file metadata where supported, detect case/Unicode collisions and symlink traversal, and display before/after namespace. A **copy** never removes source payload/job or modifies remote server configuration. Existing target bytes require compare/skip/explicitly replace decisions; replace is a separate destructive flow with backup/rollback precautions, never a default.
- Resume requires persisted exact source generation/length/mtime (not trusted alone for verification), target staging identity, completed byte ranges, digest and checkpoint. A changed source, revoked permission or stale destination invalidates unsafe resume and triggers user review. Use independently computed hashes of finished output or equivalent verified piece validation, with readback, before marking success.
- Progress differentiates **Copying / Interrupted, can resume / Verifying / Verified / Failed / Uncertain** and bytes actually persisted. A surviving partial file stays under clearly named staging with Cleanup/Resume options; no orphan file deletion outside the confirmed target. User can choose whether to later **Add a torrent job using these bytes**, with a new separate Add review/recheck; never auto-add merely because copy completed.
- Cross-host operations do not implicitly become persistent synchronizers or daemon jobs. A synchronization schedule requires the separate opt-in [external services and storage](EXISTING-SERVICE-INTEGRATIONS.md) contract, a reliable supported execution host, and independently scoped permissions.

## Settings and defaults

All cross-seed scans/auto-suggestions **off** by default; users manually initiate scans. Cross-seed provider targets and local-file permission lists are separately scoped. Cross-seed results are private Chunks-local observations with retention controls. Copy is always manual and foreground/user-initiated by default. Never transfer via mobile data, private trackers or external relay without exact user-reviewed network/cost/privacy state. An unavailable storage adapter yields a truthful explanation; do not promise cross-seed compatibility or background resumability simply because an rTorrent transfer exists.

## Capabilities, security, failure and invariants

- A match may share the same content but have a different infohash, path, container structure or piece geometry. Verify independently before seeding; never treat file-level similarity, MediaInfo metadata or a rename suggestion as proof. For v1/v2/hybrid validate the engine's exact hash semantics, padding and multi-file piece boundary behaviour. A failed recheck never deletes or corrupts original seeding files.
- When hardlinks/reflinks can be used, require filesystem and adapter proof, prevent writes to shared backing, track multi-job owners, explain storage accounting, and block cleanup that would break another active job. Prefer separate copies where link semantics are unknown.
- Offline, missing bytes, partial download, untrusted magnet without metadata, inaccessible source, duplicate destination, tracker denial, stale auth, rate limit, disconnected remote, network loss and low storage each have distinct actionable states.
- Record idempotent mutation journal per environment and exact job/path; remote Add unknown outcomes must reconcile exact hash before retry. No automatic cross-seed reannouncement or cross-tracker disclosure.
- Private-torrent restrictions take precedence over cross-seed suggestions. No passkey-bearing URL, source folder name, computed hash or content title is disclosed to an indexer or third-party integration until the user explicitly authorizes that provider and data.

## Acceptance matrix

| Case | Deterministic test and independent evidence |
|---|---|
| Match quality | Identical, renamed, reordered, padded, partly selected, superficially similar, altered-byte, v1/v2/hybrid and cross-file-piece fixtures never yield false **Verified** |
| Source isolation | Existing seeding source remains readable and shareable through failed mapping, failed recheck, restart, remove-new-job and rollback |
| Remote capability | RPC-only profile shows no fictitious filesystem operations; file-adapter authorized vs denied/expired/tenant-isolated paths |
| Private tracker | No unintended source, infohash, passkey, credentials or data export; separate scoped Add approval |
| Payload copy | 0-byte, huge, sparse, Unicode/case, locked, symlink, collision, quota, interrupted and mutated-source fixtures validate safe resume and final hash; original bytes untouched |
| UX | Optional More-menu entry, truthful Copy Transfer vs Copy payload labels, progress/undo, explicit target and seeding group, 320×700 and 412×892 at 200% text and TalkBack |
| Lifecycle | Phone Doze/process death, remote disconnect, duplicate intent and retry produce no silent overwrite, phantom success or double Add |

**Evidence status:** specification only. Promote slices in [SPEC-COVERAGE](SPEC-COVERAGE.md) after JVM/Android/approved-host proof in [QUALITY-LEDGER](QUALITY-LEDGER.md).

## Related artifacts

[PRODUCT](PRODUCT.md) · [UX](UX.md) · [ARCHITECTURE](ARCHITECTURE.md) · [SECURITY](SECURITY.md) · [EXISTING-SERVICE-INTEGRATIONS](EXISTING-SERVICE-INTEGRATIONS.md) · [CROSS-ENGINE-TRANSFER-JOBS](CROSS-ENGINE-TRANSFER-JOBS.md) · [TRANSFER-FILE-RECOVERY](TRANSFER-FILE-RECOVERY.md) · [SMART-SEEDING-RULES](SMART-SEEDING-RULES.md) · [WORK-QUEUE](WORK-QUEUE.md)
