# Transfer sharing and metadata export

## Deferred Chunks-to-Chunks artwork and metadata accompaniment (owner direction, 2026-11-04)

**Dependent feature; no implementation authorization until all relevant artwork/metadata decisions are resolved and recorded.** A future Chunks-specific portable sharing bundle may accompany an ordinary magnet or validated .torrent with the sender's deliberately selected artwork, locally supplied poster/thumbnail bytes, selected alternates, custom display metadata and stable identity/provenance. The ordinary magnet and .torrent must remain independently usable in non-Chunks clients; no modifications to torrent infohash or payload. A receiver may import presentation choices without replacing an existing explicit local override or starting/restarting the torrent. Metadata-only artwork transfer between users who already hold the torrent is a desired optional outcome.

**Strict prerequisite and sequencing:** Resolve and document the canonical artwork/metadata system's matching/identity and scope (torrent vs media-work vs environment), selection precedence and override semantics, persistence and recovery, choice of image formats/sizes and multi-image ordering, imported art vs provider references, provider cache behavior, offline fallback, import conflicts, attribution/provenance representation, privacy and bundled transport. Have all relevant owner decisions and implementation acceptance for that system settled **first**. Only then specify final bundle schema/version, UX defaults, receiver merge behavior, capability negotiation, backward compatibility and realistic test matrix. Do not let share-specific work choose these policies prematurely or block core torrent sharing.

**Owner intent:** Tiny poster/cover/thumbnail images selected for viewing inside Chunks should be capable of travelling as actual image bytes in the bundle. Do not introduce a bespoke image-license checking workflow, mandatory attribution prompts, or blanket refusal of third-party artwork as product behavior. Existing provider API/auth/usage constraints still govern Chunks' own fetching; no additional in-app licensing bureaucracy is desired for person-to-person artwork transfer. Avoid sharing local paths, seedbox/profile identifiers, user accounts, tracker secrets or unrelated private metadata. Bound bundle/image sizes; validate imported content; limit URI permissions; make import explicit. Final UI, default inclusion and conflict policy remain **undecided pending upstream choices**, not an invitation to silently settle them.

**Priority:** downstream of [TRANSFER-ARTWORK](TRANSFER-ARTWORK.md), [SHARED-MEDIA-IDENTITY-AND-SIMILAR-TRANSFERS](SHARED-MEDIA-IDENTITY-AND-SIMILAR-TRANSFERS.md), and ordinary magnet/.torrent export above. Planned only, not a present P0 or independent release gate.


Status: planned, not implemented. Owner requirement 2026-11-01.

## Product behavior
From the same Transfer Details > Share menu for local and rTorrent, provide Share magnet, Copy magnet, and Share .torrent when verified complete metadata is retrievable. The original magnet/.torrent need not exist. Source transfer is never modified, redownloaded or restarted. Export retains the same swarm identity, not any guarantee of identical live peer connections or successful downloads. This is native rTorrent integration, not ruTorrent remote control.

## Technical contract
- Generate a standards-compliant magnet from existing v1 SHA-1 and/or v2 SHA-256 infohash; preserve both for hybrid torrents. Include known tracker announce URLs, safe display name and supported web seeds when applicable, with correct URL encoding. Never invent trackers. Magnet tracker flattening may lose tier semantics.
- Local: prefer supported libtorrent APIs for magnet/metadata export. Remote: native rTorrent XML-RPC for hash, name, trackers; do not presume XML-RPC yields a complete .torrent. For remote .torrent export, use a separately verified and authorized session-metafile retrieval method where available, without ruTorrent dependency.
- .torrent export requires verified, complete original info dictionary and, where required, v2/hybrid piece layers. Preserve original info bytes and independently validate bencoding and exact v1/v2 infohash; do not regenerate a new torrent from payload bytes.
- Respect private flag, DHT/PEX restrictions and tracker admission policies. Tracker URLs and exported torrent files can contain account-specific passkeys. Before sensitive sharing, clearly warn and require explicit confirmation. Do not silently strip mandatory passkeys, add public trackers, or emit secrets to logs, CI or telemetry.
- Honest statuses: magnet available with known hash; file export waiting for metadata, inaccessible on this server, unsupported, offline or uncertain. Offer magnet fallback where safe. Read-only operations must not blindly replay mutations.

## UX contract
Use the native Android Sharesheet, temporary content URIs with scoped grants and safe filenames, with no implicit upload. Show source environment's name and palette color, including duplicate infohash across environments. Plain-language availability explanations, no unnecessary settings. Accessible at 320x700, 412x892, 200% text, TalkBack, focus navigation, 48dp targets and reduced motion. Test paused, stopped, downloading, seeding, metadata-only, disconnected and activity-recreation states.

## Acceptance contract
- Against actual local libtorrent and credentialed native rTorrent, test public seeded torrent and metadata-resolving magnet (v1, v2, hybrid where supported), multiple trackers, Unicode/encoding, web seeds and private fixtures.
- Export from transfers after removing original input. Independently import into another real client, verify exact hash(es), metadata/tracker preservation, actual peer discovery where available, and independently parse exported .torrent including piece layers. Do not equate a recognized hash with successful swarm discovery.
- Negative tests: no metadata, unavailable remote session files, 401, timeouts, private passkey masking/confirmation, offline, duplicate hashes. Distinguish real server/client observations, Android emulator and physical device; mocks only supplement evidence.
- Only mark implemented in SPEC-COVERAGE and QUALITY-LEDGER after actual evidence.

Priority: P1 after P0 Add/start, data safety and lifecycle reliability. Phase 1 magnet generation and disclosures; phase 2 validated .torrent export subject to remote capabilities.
