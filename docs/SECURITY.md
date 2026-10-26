# Chunks security, privacy and operation authority

Updated after CRITICAL-REVIEW-2026-10-24.md. Public source repository, untrusted torrent metadata, powerful rTorrent RPC and destructive filesystem operations create a combined trust problem. The primary objective is protecting users' private server credentials and valuable already-seeding data.

## Trust boundaries and threats

1. Android UI/import boundary: an untrusted magnet link, torrent metainfo, file name or tracker URL can carry malicious paths, giant strings, Unicode, private tracker passkeys or URLs. Never use torrent-provided paths for raw file writes without engine + filesystem containment checks.
2. Chunks ↔ rTorrent RPC: the endpoint may permit execution as the torrent user's OS account. Do not expose generic RPC method invocation through UI, intents, external deep links, logs or plugins. Authenticate and minimize methods.
3. Chunks ↔ companion or alternative remote filesystem provider: default deny outside configured roots. Authorize each operation by server-side identity, permission and current target state, not by client-supplied classification suggestions.
4. User storage: active torrent backing bytes, resume/session state, library copies and metadata catalogs have distinct ownership and deletion rights.
5. Third-party metadata, crash reports and telemetry: potentially reveal private content, trackers, file paths and behaviors. Local-first, opt-in and with inspectable redacted output.

## Connection and credentials

- Never expose unauthenticated rTorrent SCGI to the public network. Prefer loopback/unix-socket SCGI behind a protected authenticated reverse proxy, private VPN or carefully scoped tunnel.
- Require certificate-validated HTTPS for non-loopback remote access. Do not offer “ignore certificate” as a persistent fix. Evaluate Basic/Digest/provider session schemes without credential leakage or cross-origin redirect following.
- Bound DNS resolution/response time, connection count, XML size and nesting, redirects, session retries, method concurrency and error display. Never print Authorization headers or full request/response bodies.
- Never publish server login URLs, credentials, auth headers, cookie/session tokens or tracker passkeys in public CI, PRs, issues, fixtures or logs. The owner explicitly designated the Chunks development Seedhost torrent inventory as nonconfidential: its **nonsecret** transfer names, hashes, file paths and states may be captured in reviewable test fixtures and native screenshot atlases. This authorization does not cover any unrelated server or embedded reusable tracker/authentication secrets.
- Prototype credentials are volatile. Production persistence needs reviewed Android Keystore-backed key handling, profile-level credential deletion, rotation/revocation, backup behavior and threat-model acceptance. A password typed in a UI is not proof it is safely stored.
- Avoid untrusted content injection from torrent titles, HTML-like file names and RPC fault strings. User-visible errors should sanitize remote-controlled text.

## Companion authorization and destructive file operations

- Separate the companion from rTorrent's unrestricted RPC privilege; no arbitrary shell command/execute endpoint. Fixed typed operations (inventory/prepare/move/copy/rename/verify/recover) with server-side target validation.
- Restrict operations to independently configured allowed roots. Use symlink-resistant directory handles and ownership/ACL checks, refuse traversal, reserved paths, special files, conflicting mount/container path translations, case-insensitive collisions and stale/incomplete inventories.
- Durable request IDs, restricted capabilities and fencing/locks are essential. A client disconnection is **not** a mutation failure. Resume only after reading the server's authoritative journal and reconciling a possibly committed operation.
- Never delete data while another torrent may reference the same underlying bytes, while copy verification is incomplete, or while rTorrent's mapped path is uncertain.
- An alternative SFTP/SSH transport might browse/copy files but does not automatically confer safe live torrent rebind capability; verify coordination or refuse.
- Automatic category suggestions do not create user authorization to alter files. Confirm destructive actions at exact target granularity, with batch dry-run and understandable recovery options.

## Data collection and consent

No default telemetry, network metadata enrichment, tracker discovery, content upload, or diagnostics upload. If logs are requested, provide preview and redaction before export; redact file paths, info hashes and hostnames by default. Allow user inspection and deletion of locally recorded rules/preferences.

## Acceptance

Security review must include RPC method allowlists, XXE/deep XML fuzz cases on Android, TLS/auth behavior, malicious path and symlink races, concurrent ruTorrent/external script modifications, large response exhaustion, cross-user root isolation, storage deletion semantics and failure recovery. Do not use production seedboxes for destructive fault injection. Report each tested attack surface against exact source SHA, server topology and platform version.

## Storage cleanup and archive threat boundary

Treat torrent file names and archive entries as untrusted. The cleanup wizard must be dry-run first; destructive changes require exact file ownership, scope, shared-reference/active-writer validation, separate confirmation of seeding loss, journal and observed postconditions. Never treat extraction success as permission to delete the original torrent-backed archive. An apparent duplicate can share an inode or torrent pieces and may release no physical bytes on removal.

Extraction must bound total actual output bytes, ratio, member count, depth, CPU/time and staging space, and prevent traversal, links, Unicode/case collisions, special file writes, malicious multipart archives and decompression bombs. Android SAF roots and remote storage credentials have distinct grants; no broad arbitrary remote deletion/extraction shell. Exact requirements and adversarial QA: [STORAGE-AND-EXTRACTION.md](STORAGE-AND-EXTRACTION.md).

## Display alias privacy boundary

Locally entered labels can reveal torrent contents. Keep them in app-private preferences, unbacked-up in the current prototype, and never transmit them via rTorrent RPC, tracker requests or external metadata enrichment. Hash-based preference keys are identifiers, not encryption; never include passwords, session cookies or tracker passkeys in the key input. Bound and validate aliases, reject hidden bidi/control characters and render them only as inert text. Suggestions derive exclusively from supplied filename facts; any future external title/guest lookup requires explicit, informed, revocable consent and source provenance. An alias must never authorize physical file operations or replace the exact hash/path mutation preflight.

## Literal-loopback-only Android HTTP test boundary

Normal remote endpoints still require authenticated certificate-validated HTTPS. Android Network Security Configuration permits cleartext **only** for literal localhost/127.0.0.1/::1 so the actual installed app can be tested against a local synthetic XML-RPC server. The Kotlin adapter independently rejects other HTTP hosts, unauthenticated arbitrary SCGI and redirects. The fixture must issue only version/list/files methods and no mutation calls. This acceptance surface is distinct from a live Seedhost or physical Android handset. The Android DOM parser may reject optional XInclude setters; parser fallback retains the mandatory pre-parse DOCTYPE/ENTITY rejection and external entity safeguards.

## XML-RPC structure rejection and platform proof (merged #105)

[PR #105](https://github.com/gamesbyian/Chunks/pull/105) merged `59f1f62123e9f9e448b34c3efeabe9d667f98f79` after exact-source Android/JVM [40163171672](https://github.com/gamesbyian/Chunks/actions/runs/40163171672) and native API35 [40163171740](https://github.com/gamesbyian/Chunks/actions/runs/40163171740) passed (21/21 instrumentation). The existing 4 MiB transport/decoder limit and mandatory DTD/entity ban remain. New fail-closed SAX structural preflight checks XML element nesting and total elements **before** constructing DOM. The recursive XML-RPC decoder also bounds per-array width, struct membership, aggregate decoded value count, scalar text and map-key lengths. Deterministic hostile fixtures exercise deep/small inputs, oversized arrays and fields and real Android parser compatibility with ordinary replies. These are response parsing controls, never permissions to issue arbitrary RPC or mutate torrents. Keep real protected Seedhost inventory, 12k-transfer latency/compatibility, credential lifecycle and non-legacy engine testing separately gated.

## Bounded Android ZIP document inspection and torrent metadata integrity (2026-10-25)

- [#110](https://github.com/gamesbyian/Chunks/pull/110): Android OpenDocument reader accepts only `content://` scheme, copies at most 256 MiB into a private temporary cache file, runs existing member/path and streamed 64 MiB CRC/ratio checks, reports bounded metadata and attempts temporary-copy deletion in all normal return/error paths. **It does not write ZIP output or delete the original.** This is not proof of handling provider hangs, process death, low-space staging races, failed temp cleanup or output-handle traversal safety. Those remain before extraction.
- [#111](https://github.com/gamesbyian/Chunks/pull/111): the transfer-list parser refuses non-hex or non-40/64-character hashes before exposing exact-hash control targets. Rejecting an entire ambiguous RPC snapshot is intentional; do not recover by guessing or silently deduplicating.
- [#112](https://github.com/gamesbyian/Chunks/pull/112): v1 torrent metainfo preview rejects piece-digest counts that contradict declared logical payload size and piece length before passing bytes to `load.raw_verbose`. This is input validation, not content verification of downloaded torrent payload, tracker trust, BitTorrent v2 support or a real server write proof.

## Merged Android engine and SAF threat boundary (2026-10-26)

The local engine's default save area remains app-private. A finished transfer may produce an **independent** SAF library copy while original bytes continue to seed; the operation cannot grant rights to delete or move torrent-backed originals. Manual ZIP extraction writes to a **new** SAF folder after bounded validation, with provider-returned name checks and journal/recovery. Existing API35 debug DocumentsProvider tests do not prove arbitrary SD/cloud, permission revocation, non-seekable slow providers, process kill or OS background limits. Before accepting those claims require real URI/permission identity, symlink/collision and actual-bytes safeguards, post-recovery readback and seeding verification. #154's transient read-failure liveness correction must not be represented as physical FGS lifetime proof. See [SPEC-COVERAGE](SPEC-COVERAGE.md) and issues #5/#70.
