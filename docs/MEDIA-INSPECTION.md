# On-demand media inspection and previews

> **Authority:** owner-approved niche media intelligence (D-NICHE-MEDIA-20261029). This contract defines read-only media/container inspection and optional thumbnails for authorized local and remote files; [EXTERNAL-FILE-ACCESS](EXTERNAL-FILE-ACCESS.md) governs the underlying byte-reader and Android Open with, [TRANSFER-ARTWORK](TRANSFER-ARTWORK.md) governs voluntarily enriched external cover art, and [PLEX-FILENAME-REPAIR](PLEX-FILENAME-REPAIR.md) governs rename proposals. **Status:** planned, no implementation or decoder/remote acceptance implied.

## Purpose and scope

Help a user answer **What is this file actually?** and **Which release should I keep or share?** without launching a heavyweight player or blindly trusting a filename. Chunks SHOULD inspect technical media/container metadata for selected compatible files: container, duration, video codec/profile/level, pixel size, frame rate, HDR/colour metadata *when present*, audio track languages/codecs/channels, subtitle tracks and types, chapters and stream sizes/bitrates *when the parser actually knows*. It MAY extract one or a few representative video stills as a bounded preview, and MAY use validated facts to inform Plex-compatible naming proposals, duplicate review, cross-seed candidate triage and file sorting. None of these inference consumers can override user filenames or mutate payloads automatically.

**Out of scope:** full transcoding, live DVR, a new built-in media player, universal subtitle detection, decoding every format, unbounded thumbnail batch generation, perceptual similarity as evidence of identical bytes, external cloud recognition without separately granted consent, media piracy classification or always-on library analysis.

## Scope, identity and permissions

A media inspection is keyed by immutable **environment/profile + storage adapter/Place + exact file identity + observed generation/length + parser version + inspected-byte range**. A torrent file-tree path is not necessarily readable backing data. Local SAF/MediaStore files and rTorrent-backed remote files use separately negotiated byte-reader permissions; remote XML-RPC is not a file reader. A completed torrent is not sufficient proof that bytes are available or unmodified. An incomplete file may expose an explicit **partial/uncertain** result for verified readable ranges only; never infer missing tracks or the complete duration from incomplete data.

## User journey and screen hierarchy

**Entry:** `Files / Places → selected file → Properties → Media details`, or a modest `Inspect media` action in transfer file detail's overflow. The file's **Properties** view remains the central hub; no permanent codec badges, scanner cards or separate top-level media screen. An unobtrusive cached **Technical details available** row MAY show under file properties; first-run prompts are unnecessary.

1. Show filename, scoped Place, exact size/completion and permission state immediately. If requested, run a **bounded read-only inspection** with cancel, bytes used and any network transfer disclosed before requesting remote ranges.
2. Present a compact summary (Video, Audio, Subtitles, Container) and expandable per-track details. Source facts are marked **Detected**, **Estimated**, **Missing**, **Partial** or **Unsupported**, with parser name/version and observation timestamp under **How determined**.
3. Show a representative thumbnail only if the file can be read and decoded within a fixed device cost budget, or a user explicitly chooses **Generate preview**. For remote files show **Preview may read up to N MiB / use mobile data** based on a preflight estimate; offer **Wi-Fi only** or **Don't preview**. No blank placeholder pretending to be a real frame.
4. Optional convenience actions: **Copy technical details**, **Open with…** (delegates to the external-app contract), **Use facts in filename review** (opens conservative Plex repair preview) and **Compare releases** when a user has selected multiple files. Comparison is factual, showing known and unknown attributes, not inventing which copy is superior.
5. Empty/offline/permission-revoked/unsupported/corrupt/DRM-encrypted/incomplete/timeout/decoder-failure outcomes preserve existing file UI and offer **Retry**, **Open with** or **Technical details unavailable**. Closing inspection cancels optional work when feasible and never pauses a torrent.

**Niche UX:** media inspection is deliberate, second-level and on-demand. The regular Transfers list, Add review and Places navigation remain unchanged. On 320×700 and 412×892 layouts use compact collapsible track rows, 48dp touch targets, labelled metadata rather than colour-only indicators, TalkBack reading order, 200% font support and no horizontal overflow of track names or codec strings.

## Metadata and thumbnail semantics

- Use a typed parser adapter with formats/features/seek cost capability table. Android platform retrievers may provide a safe initial subset; container/track-level depth requires a separately vetted parser. Unsupported codecs, malformed containers and suspect frame indexes fail closed or return partial data with source attribution.
- Preserve **raw observed facts** separately from **normalized interpretations** (e.g. AAC vs AC-3 audio, language tags, HDR metadata). Unknown language is not English; absent subtitle stream is not evidence that no external subtitle exists. Frame rate and bitrate can be variable or inferred; label estimates. A filename, poster database or indexer title cannot substitute for actual content inspection.
- Thumbnails are ephemeral derived data with byte/frame/time provenance. Frame selection prefers nonblack, nonempty frames without unbounded seek/decoding, using an explicit time/byte budget and safe downsample. Thumbnails MUST NOT be silently published to indexers, clipboard, notifications, app analytics or third-party cloud.
- Cache media observations only with user consent and bounded size/age. Cache keys invalidate on file write/path remap or storage generation; deletion/lock/permission revocation hides private cached results. Option to **Clear previews and inspection history** removes derived data, not source media or torrents. Avoid scanning large remote libraries in the background by default.
- A thumbnail is a preview of a decoded stream, not proof of torrent integrity. A codec/container match never proves same data for cross-seeding; [CROSS-SEED-AND-PAYLOAD-COPY](CROSS-SEED-AND-PAYLOAD-COPY.md) requires independent hash/piece verification.

## Remote reading, security and resource limits

- Use only [EXTERNAL-FILE-ACCESS](EXTERNAL-FILE-ACCESS.md) read capabilities, validated range requests and explicit host/network auth. Prefer header/trailer/metadata atoms where format allows; bound requests, bytes, CPU, memory, time and thumbnail dimensions. Never download gigabytes or tunnel a seedbox's entire collection through the phone because a user opened Properties.
- No untrusted parser process gets general filesystem access; implement sandbox/isolation appropriate to Android, malformed media fixtures and crash/timeouts. Metadata strings are untrusted UI input, not paths, shell commands or URLs. Reject external URL resolution and secondary resource references inside media metadata unless separately reviewed.
- Do not expose file names, media content, remote credentials or private tracker identities to any online identification API by default. A future optional metadata enrichment service is a *different* consent gate from local media inspection and the global Discovery switch.
- A user may opt in to **Auto-inspect selected completed media** for named environments/Places in advanced Settings. Default off, battery/network/data budget visible, and never implicitly enabled by other product features, Plex rename or artwork.

## Acceptance matrix

| Slice | Required independent proof |
|---|---|
| Track facts | Deterministic video/audio/subtitle/chapter/HDR fixtures, mixed containers, absent/ambiguous labels and variable bitrate; never mislabel unknown as verified |
| Decoder safety | Truncated, malicious, DRM, corrupt, huge metadata, path traversal and timeout samples remain bounded; no secondary URL/file fetch |
| Remote cost | RPC-only unavailable, read-only adapter success, range-denied/seek-limited, mobile-metered preflight and resumed UI after disconnect |
| Privacy/cache | No external content disclosure; lock, permission revocation, file mutation and clear-derived-data invalidate cached thumbnails and metadata |
| Cross-feature | Plex repair receives sourced *suggestions* only; cross-seeding ignores MediaInfo resemblance as cryptographic proof; Open with remains delegated |
| UX/a11y | Optional Properties entry, track expander, progress/cancel/offline messaging, 320×700/412×892, 200% text, TalkBack and keyboard |

## Implementation/evidence status and related artifacts

This is specification work. No new Android or server acceptance is asserted. Record independently verified parsers/format matrices, exact code SHA and device/host tier in [SPEC-COVERAGE](SPEC-COVERAGE.md) and [QUALITY-LEDGER](QUALITY-LEDGER.md).

[PRODUCT](PRODUCT.md) · [UX](UX.md) · [ARCHITECTURE](ARCHITECTURE.md) · [SECURITY](SECURITY.md) · [EXTERNAL-FILE-ACCESS](EXTERNAL-FILE-ACCESS.md) · [PLEX-FILENAME-REPAIR](PLEX-FILENAME-REPAIR.md) · [TRANSFER-ARTWORK](TRANSFER-ARTWORK.md) · [WORK-QUEUE](WORK-QUEUE.md)
