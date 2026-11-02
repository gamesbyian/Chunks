# Transfer sharing and metadata export

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