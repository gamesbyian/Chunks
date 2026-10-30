# External file access, remote opening and on-demand reading

**Status:** normative product/UX/technical feature contract; implementation and physical interoperability **not yet accepted**. **Authority:** [PRODUCT](PRODUCT.md), [UX](UX.md), [ARCHITECTURE](ARCHITECTURE.md), [SECURITY](SECURITY.md), and [UNIFIED-ENVIRONMENTS](UNIFIED-ENVIRONMENTS.md). Coverage and priority are recorded separately in [SPEC-COVERAGE](SPEC-COVERAGE.md) and [WORK-QUEUE](WORK-QUEUE.md).

## Read-only inspection consumer

The optional [MEDIA-INSPECTION](MEDIA-INSPECTION.md) workflow may request bounded file ranges and seek capability through the same permissioned byte-reader interface as Open with. It does not get broader filesystem, rTorrent RPC, transcoding or external cloud rights and must disclose remote data cost before reading. Container/codec facts and thumbnails are observed, privacy-scoped derived data, never proof of file integrity. This file retains exclusive ownership of byte-reader, URI and outbound app handoff safety; media parsing lives in its own feature contract.

## Intent and scope

Every readable file surfaced within a transfer's file tree, a local filesystem Place, or an independently accessible remote filesystem Place should be usable with other Android apps, without making a complete phone-side download the default prerequisite. Video-to-VLC is **one example**, not the definition of the feature. This includes documents, images, audio, archives, subtitles, ebooks, text, arbitrary binary files and future MIME handlers. Chunks delegates format-specific interpretation to the chosen app, subject to its own compatibility. Provide consistent **Open**, **Open with…**, **Share/Send**, **Save to device**, **Copy link** (only when an authorized, appropriate link exists), and **Properties** affordances wherever supported, with availability grounded in verified capabilities. An app may need local materialization or may reject a stream: do not promise universal compatibility.

A file's context is an exact **environment/profile + stable file identity + storage backing/Place + authorization + observed state**. Do not mistake a transfer registration or relative torrent pathname for permission to read bytes; a transfer job may refer to missing, moved, partially downloaded or inaccessible backing data. Do not conflate **Copy/Move Transfer** (job-only) with file reads, copying, export or materialization.

## Obvious next action without prior file-access configuration (owner UX, 2026-10-30)

Once Chunks has verified actual available files, surface **Open**, **Open with**, or **Show files** in context. If the bytes live on a server, distinguish **Open remotely** where genuinely supported from **Get files on this phone**, which requires an independently verified retrieval path and permission. Skipping an optional streaming-app choice must not make the completed download impossible to locate; offer the ordinary Android chooser when appropriate and remember only explicit app preferences. When an opening method is unsupported, unavailable, too large for the selected path, or would disclose private data, show a safe alternative without falsely claiming files are local. Make these actions available later from Files/detail, with truthful status after reconnect. [Default and continuity UX](UX.md#visible-options-automatic-defaults-and-nondecision-continuity-binding-owner-direction-2026-10-30).

## User experience

- The same contextual file actions and Android system resolver apply in transfer-file lists and local/remote filesystem browsing. Clearly display **Phone** or the exact **server profile**, read progress, completeness, accessibility and where relevant cached/offline state. Local-only, remote-only and combined configurations behave the same where capabilities permit.
- **Open** invokes an appropriate handler; **Open with…** invokes Android's applicable resolver/chooser for MIME, URI and intent. Honor the user's app choice and OS defaults, without hardcoded VLC dependencies. **Share/Send** is distinct: Android SEND/stream recipients may eagerly consume all bytes, so distinguish short-lived access versus explicit durable export. A failed recipient launch or rejected seek must not be reported as successful playback/open.
- Opening a remote 20 GB file must not silently download 20 GB, reserve 20 GB phone storage or send credentials to a third-party player. Offer progress and honest fallback: **Stream/open remotely**, **Download first**, **Choose different app**, **Configure file access**, or **Unavailable**, according to proven capability. State expected local caching/network consumption without inventing exact totals.
- Never auto-upload or publish a file merely because it was opened; avoid generating publicly shareable URLs as a side effect. A **Copy remote link** control is only enabled for verified user-authorized links and explains expiry/access scope. Transfer-file selection must retain original path and server even across navigation, profile switching or reconnection.
- Preview/thumbnails/search-indexing must not inadvertently download enormous remote assets or reveal private data. Distinguish an incomplete torrent file from an inaccessible file; opening partial content is opt-in and may fail or stall where missing verified byte ranges are required.
- Default no full download. Cache only demanded ranges with bounded user-configurable limits, clear occupancy visibility, predictable eviction, and no impact on original seeding bytes. Show stop/cancel and disable background retrieval when the reader closes; respect Android lifecycle restrictions and foreground-service requirements.

## Read capability model and preferred handoff

Treat remote-control and file-access configuration as separate, independently tested permissions. rTorrent XML-RPC typically provides metadata/control, **not arbitrary downloaded-file bytes**. Probe an authorized filesystem adapter using a harmless bounded read of a known approved file, validating identity, allowed root, size, authentication, and whether random offsets/range requests are supported. Never infer file access from successful RPC login or vendor name.

Candidate independent adapters include Android local ContentResolver/SAF, server SFTP/SSH, authorized HTTPS ranged download, WebDAV, SMB, FTP where safely configured and supported, provider filesystem APIs, and an optional verified companion. Their support depends on actual provider/instance policy, TLS/host-key, auth and server-side permissions. Lack of one adapter must not disable transfer management. Never require a companion for ordinary remote-control use.

Select among:
1. **Local content URI/file descriptor**: grant narrow temporary read access through a scoped Android `ContentProvider`/SAF, honoring Android storage permission boundaries.
2. **Direct external-app remote URI** only when the target app/protocol/auth mechanism can be safely used without leaking secrets, broadly exposing the seedbox, or exposing inaccessible private IPs; validate interop, target host and user consent. No token/password-in-URL by default.
3. **Chunks-managed on-demand bridge**: authenticated background reader delivers only requested byte ranges to the external consumer, via a temporary read-only `content://` URI and seekable proxy file descriptor where compatible. Android `StorageManager.openProxyFileDescriptor` is a candidate, **not a guarantee** of third-party application compatibility or low latency. Consumer may issue random seeks, duplicate descriptors, request metadata, or read from end-of-file first; implementation must support concurrency/cancellation/backpressure, safe bounds, cache reuse and correct EOF/errors.
4. **Explicit full/partial materialization fallback**: user approves space, location and download; preserve filename/MIME where possible and open only once materialized as required by the recipient. Some apps insist on a filesystem pathname, eagerly spool files, or do not support seekable content URIs. Do not pretend those apps support genuine streaming.

Use extension+magic/type evidence conservatively for MIME; untrusted names cannot select privileged actions. The Android chooser should be allowed to identify capable installed applications; never silently route private content into an unintended cloud or external service.

## Streaming and incomplete torrent semantics

Completed remote files may be randomly read independent of torrent scheduling when server byte-range access is proven. Streaming a large file consumes network bandwidth and optional cache proportional to data requested, not necessarily the total object size; container indexes, subtitle tracks, seeks and receiver prefetch may force reads far apart. Measure actual bytes. Seek can require multiple remote requests, and performance depends on path throughput, latency, rate limits, app behavior and server seek support.

Incomplete local torrent content requires verified-piece awareness and optionally piece/deadline prioritization that does not violate integrity or file-order policy. Never serve unverified/missing bytes as valid data. Incomplete remote rTorrent contents have no general guarantee of piece-availability mapping or scheduling by simple file-priority RPC; make support conditional or disallow opening until enough verified data is present. Explicitly distinguish *file size*, *completed bytes*, and *readable ranges*. Do not cause uncontrolled writes to remote torrent priorities from a read action.

## Security, ownership and lifecycle

- Bind every access token to one origin profile, file identity, allowed byte ranges/read-only operation, consuming UID/package where available, and short expiry. Grant temporary Android URI read permission only to intended recipients and revoke promptly; protect across process death, profile removal, privacy lock, logout and reconnect. Confirm exact tenant for numbered ruTorrent instances.
- Secrets stay in Chunks; never log passwords, SSH keys, signed URLs, private media paths or sensitive byte contents. Follow HTTPS cert and SSH host-key validation; disallow silent downgrade, open proxy, broad local network exposure or localhost HTTP servers accessible to other apps without authenticated capability-limited controls.
- Remote reads are non-mutating to source data, torrent registrations and remote files. File identity must be revalidated on reconnection and seek; stale size/path/inode or server replacement fails safely. Cache cannot become a second untracked library copy, a seeding-source deletion authorization, or an unlimited storage consumer.
- Don't grant external apps remote filesystem browse permissions or credentials just because a single file was opened. Respect private/locked environments and avoid leaks through notifications, intents, recents, chooser titles and previews.

## Capability/UI state model

`Unknown → Probing → ReadableSequential | ReadableRandomAccess | MetadataOnly | AuthenticationRequired | PermissionDenied | Offline | Unsupported`. Record last verification and applicable method separately by exact profile/Place; transient errors do not erase saved configuration. File-level states distinguish complete/incomplete, available/missing and content-changed. Present *what can be done now*, with a clear setup/retry path and no fake generic “Open with” action on metadata-only files.

## Acceptance and test matrix

- Local completed files via transfer-tree and Places: Android Open/Open with/Share, MIME resolver, one-time URI read grants, reader cancellation, file renamed or deleted after selection, Android 13+ document-provider variance.
- Remote complete 20 GB+ sparse/test artifact over SFTP or ranged HTTPS: open without full download, bounded cache and memory, random seek near EOF, repeated seeks, metadata probing, two concurrent readers, application quit, airplane-mode interruption, resume, server mutation, auth expiry, privacy lock.
- Test at least VLC for MKV (start/seek/audio/subtitle where available), one image viewer, PDF/document app, audio handler and archive handler. Include recipients that reject content URIs, require seek or eagerly consume full content; fall back explicitly. Never equate successful intent dispatch with successful open/playback.
- Compare successful remote XML-RPC but absent filesystem access, blocked SFTP, HTTP without Range, bad cert/host key, two identically named files across distinct ruTorrent instances, changed file during stream, and unsupported/partial content. Confirm errors are scoped and truthful.
- Use only approved lawful remote test artifacts and explicit development seedbox permissions. Capture network bytes transferred, peak cache/disk/RAM, open-to-first-read delay, seek correctness, cancellation and no credential leakage. Test API/emulator/JVM fixtures first, then real Android/VLC/Seedhost instance acceptance; log specific evidence in QUALITY-LEDGER. **These cases are requirements, not present-tense success claims.**
