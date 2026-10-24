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

## Display aliases, suggestions and privacy

Display names are user-authored local metadata and may reveal what a person downloads. Keep them separate from hash/path/remote identities and never transmit them via rTorrent RPC, trackers, crash reports, logs or external metadata services as a side effect of editing or accepting a suggestion. Draft #69 uses private, non-backed-up Android preferences keyed by SHA-256 of endpoint, username and infohash. This fingerprint is **not encryption**; do not include credentials or session tokens in the key input. A future durable account/profile model needs explicit migration and cleanup of orphaned alias preferences, including after profile deletion or torrent removal/re-add.

Treat original torrent names and display aliases as untrusted input: bound lengths, reject hidden control/bidirectional override characters, render inert text, and test homographs, malformed Unicode, spoofed file extensions, deceptive technical tokens and empty input. Aliases must never authorize a destructive action, change path mappings, override exact-hash identity or silently alter seeding state. Privacy protection for ordinary users remains the default despite the owner's **scoped** permission to publish nonsecret inventory from the designated development Seedhost.

Deterministic local recommendation does not contact third parties. Future media catalogs/guest identification may require sending title-like data to an external service; design explicit user consent, preview of disclosed fields, recipient and provenance, confidence/error handling, retention/deletion policies and a no-network fallback. Do not claim guest names as known solely from release filenames. Review alias cache/data clearance, offline save failures and leaks via screenshots/diagnostics.

## Data collection and consent

No default telemetry, network metadata enrichment, tracker discovery, content upload, or diagnostics upload. If logs are requested, provide preview and redaction before export; redact file paths, info hashes and hostnames by default. Allow user inspection and deletion of locally recorded rules/preferences.

## Acceptance

Security review must include RPC method allowlists, XXE/deep XML fuzz cases on Android, TLS/auth behavior, malicious path and symlink races, concurrent ruTorrent/external script modifications, large response exhaustion, cross-user root isolation, storage deletion semantics and failure recovery. Do not use production seedboxes for destructive fault injection. Report each tested attack surface against exact source SHA, server topology and platform version.
