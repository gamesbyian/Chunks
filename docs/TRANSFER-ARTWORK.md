# Transfer artwork and media identification contract

**Status:** Owner-approved product direction, **planned/unimplemented**. Source reference: owner's colorful four-phone Chunks Transfers/Workspace/Storage/Details mockup supplied 2026-10-27. Tracking: [#403](https://github.com/gamesbyian/Chunks/issues/403). This document is the focused design/acceptance contract; [PRODUCT](PRODUCT.md) controls product behavior, [UX](UX.md) presentation, [ARCHITECTURE](ARCHITECTURE.md) implementation boundaries, [SECURITY](SECURITY.md) privacy, [WORK-QUEUE](WORK-QUEUE.md) ordering.

## Outcome and scope

Use square cover/key art to decorate **both local and remote rTorrent transfer rows/cards**, transfer details and optional future grid layouts, retaining strong hierarchy for transfer name, state, progress, speeds, size, ETA and primary controls. Recognizable movies and TV series/seasons are first-class. Transfers that cannot be confidently classified (software, archives, music, miscellaneous files, unidentified media) use attractive bundled file-type/category icons. No film/TV assumption for unknown torrents. An artwork image is strictly decorative **presentation metadata**; choosing, fetching or clearing it never changes payload files, torrent metadata, hashes, trackers, rename aliases, locations or remote sessions. Do not silently download artwork merely because a torrent was added.

## Image sourcing and provider strategy

- Provider-independent source model; evaluate **Fanart.tv** TV Square / Movie Square and **TheTVDB** square Icons as primary native-square sources. Investigate their actual public API endpoints, auth/API-key supply, usage tiers, licensing, attribution/branding requirements, image rights, rate limits and allowed persistent caching **before** implementation or deployment. Availability is not guaranteed; do not assert free/unrestricted API use or package confidential shared credentials in an APK.
- Consider TMDB as metadata identity/search assistance and image fallback; default posters/backdrops are not square. Crop only with content-aware positioning, never distort aspect ratio or crop prominent titles/faces heedlessly. Store provider, original image URL/identifier, aspect ratio, license/attribution fields where required and retrieval time; avoid depending on undocumented raw CDN patterns.
- Order: explicit user-selected image > verified native-square preferred provider > alternate native square provider > safely composed square crop > bundled themed icon. Never display a weakly matched image merely to fill whitespace.
- User-imported local image and user-provided image URI may be supported independently of third-party providers. Avoid automatically copying user images into exported metadata snapshots or external services.

## Identity and matching (read-only)

1. Deterministically parse filename/display title *locally*, stripping conventional release quality, codec, container, language and group tokens while preserving original untouched source. Identify movie title+year and series title+season/episode (including multi-episode and complete season packs). Conservative parsing of Unicode, punctuation, numerals and franchise disambiguation; abstain when the available evidence is weak.
2. A locally derived proposal is not verified identity. External search, when enabled, returns candidates with content kind, media identity, title, year and season; reject incompatible type/year or ambiguous close-scoring results. Show confirm/correct UI when necessary; ambiguous entries retain generic icon by default.
3. Persist explicit user confirmation or override scoped to the torrent's canonical local/remote transfer identity, with separate reusable media identity where justified. Handle duplicate media on multiple rTorrent profiles, torrent removal/re-add, mismatched infohash, recycled server IDs and display-alias changes safely. Never write a correction through rTorrent RPC.
4. Cache key distinguishes provider/source, content identity, artwork type/language, revision and size. Locally imported files are protected from expiry. Removing an assignment cannot delete unrelated art reused by other transfers.

## Privacy and consent

- External lookup is **OFF by default**, separately opt-in, with a concise explanation that normalized media titles/years can be sent to named providers and that providers may see the device's network address. Offer enable/disable, provider choice when feasible, per-item manual lookup, purge downloaded artwork and lookup history, and no-lookup mode.
- Never send **infohash**, magnet URI, tracker URL/passkey, original raw torrent name, file paths, server address/host/profile, account credentials, filesystem layout, private collection name or full file inventories to artwork providers. Construct minimum title/year/TV season query only after local sanitization and reject strings retaining potentially sensitive path/URL/token fragments. Offer manual typed search for unknowns.
- No lookup while protected content is locked or during privacy-limited background work. Respect independently authenticated remote profiles, profile boundaries and privacy-screen redaction. Artwork cache must not leak locked title/media identity in previews, notifications, recents screenshots, crash reports or diagnostics. Avoid analytics/tracking by default.
- User-imported art and API responses are untrusted input: restrict schemes to HTTPS for remote, enforce redirect/host/size/type/pixel bounds, safe image decoding and reasonable timeouts; disallow SVG/XML active content as remote images. Provider API failures must not impede torrent command execution.

## UI design

- On transfer cards show square, softly rounded thumbnail at leading edge, with reasonable small-screen sizing (target 64–80dp in normal rows where room permits); scale and crop without image stretching. Thumbnail must not cause jitter when loaded. Default placeholders keep stable layout height.
- The mockup's colorful segmented progress bar, speed readouts, status chip, prominent one-tap pause/resume control and typography must remain clear; artwork may neither obscure them nor serve as the sole status indicator. On extra-narrow/200% text, allow responsive stacked layout or smaller/decorative image. Do not sacrifice the 48dp-class functional touch target for cosmetic thumbnails.
- Transfer details may use a larger version of the same artwork with an explicit **Change artwork / Remove artwork / Correct title match** affordance. Accessibility: descriptive title separate from decorative art, TalkBack actions and state descriptions, sufficient text contrast, focus order, keyboard access and reduced-motion placeholders. Include image-disable setting and data-saving preference; normal transfers remain fully usable without artwork.
- Do not make image lookup a blocking step of Add or list refresh. Large queues load lazily, prioritize viewport, cancel requests on item exit, deduplicate media, and prevent stale asynchronous responses from binding to recycled rows.

## Storage, caching and failure

- Bounded disk cache with app-private thumbnails (suggest 200–400px target initially, adjustable after profiling), memory limits, LRU eviction and deduplicated media keys; cap network concurrency, download size and decoded pixels. Persist only minimum metadata and ensure a clear-all control.
- Offline/airplane mode, expired provider token, revoked permissions, broken image URL, provider outage and malformed API data must fall back immediately without transfer-list errors. Cached art may remain visible offline per provider terms.
- Artwork must not enter actual torrent payload, download destinations, archive extraction results, automated storage cleanup tallies, or .chunksnap exports by default. A future explicit appearance-settings backup is separately spec'd and consented.

## Acceptance and test matrix

- Parser fixtures: distinct movies with/without year, remakes, numerically titled films, accents and non-Latin titles, S01E02/E03 multi-episode, season packs, episodic filenames, ambiguous title, unrelated ZIP/software, spoofed URL/secret text. Verify original data untouched and abstention/no wrong art.
- Provider contract tests use deterministic mock responses and simulate auth denial, 429/rate limiting, no square art, conflicting results, image decode failure, excessive image dimensions, redirects and offline availability. No live credentials in CI.
- Consent tests: zero external requests by default, disable/clear, locked remote profile, user manual override, server isolation, crash-report redaction and no raw file names/URLs/identifiers transmitted.
- UI: screenshot comparison against owner mockup; populated phone/remote lists, detailed transfer with >100 files, long names, 320px width, RTL, 200% font, dark/high-contrast, TalkBack, paused/seeding/error, placeholder/failed/late image states. Verify 48dp buttons and that one-tap transfer actions still work.
- Performance: 1,000+ rows with viewport-only fetch, bounded workers/memory and cache-size enforcement. No list/network lock contention or degradation of actual P0 torrent engine transfer.
- Evidence is graded separately: JVM unit, mocked HTTP, Android emulator visual/accessibility, live-provider manual/API compliance, and actual physical Pixel device; do not label release accepted on screenshots alone.

## Delivery sequence and priorities

**P2 visual enrichment, after P0 core real-download/seeding integrity and P1 safety/accessibility gates.** First provider/legal feasibility and offline parser/model; then opt-in adapter/cache + correction UX; then both list/detail UI and native acceptance. Do not fold the feature into unreviewed rename/move/auto-organize behavior. See #403.
