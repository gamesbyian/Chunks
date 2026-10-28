# Transfer artwork and media identification contract

**Status:** Owner-approved product direction, **planned/unimplemented**. Source reference: owner's colorful four-phone Chunks Transfers/Workspace/Storage/Details mockup supplied 2026-10-27. Tracking: [#403](https://github.com/gamesbyian/Chunks/issues/403). This document is the focused design/acceptance contract; [PRODUCT](PRODUCT.md) controls product behavior, [UX](UX.md) presentation, [ARCHITECTURE](ARCHITECTURE.md) implementation boundaries, [SECURITY](SECURITY.md) privacy, [WORK-QUEUE](WORK-QUEUE.md) ordering.

## Outcome and scope

Use square cover/key art to decorate **both local and remote rTorrent transfer rows/cards**, transfer details and optional future grid layouts, retaining strong hierarchy for transfer name, state, progress, speeds, size, ETA and primary controls. Recognizable movies and TV series/seasons are first-class. Transfers that cannot be confidently classified (software, archives, music, miscellaneous files, unidentified media) use attractive bundled file-type/category icons. No film/TV assumption for unknown torrents. An artwork image is strictly decorative **presentation metadata**; choosing, fetching or clearing it never changes payload files, torrent metadata, hashes, trackers, rename aliases, locations or remote sessions. Do not silently download artwork merely because a torrent was added.

## Image sourcing and provider strategy

- Provider-independent source model; evaluate **Fanart.tv** TV Square / Movie Square and **TheTVDB** square Icons as primary native-square sources. Investigate their actual public API endpoints, auth/API-key supply, usage tiers, licensing, attribution/branding requirements, image rights, rate limits and allowed persistent caching **before** implementation or deployment. Availability is not guaranteed; do not assert free/unrestricted API use or package confidential shared credentials in an APK.
- Consider TMDB as metadata identity/search assistance and image fallback; default posters/backdrops are not square. Crop only with content-aware positioning, never distort aspect ratio or crop prominent titles/faces heedlessly. Store provider, original image URL/identifier, aspect ratio, license/attribution fields where required and retrieval time; avoid depending on undocumented raw CDN patterns.
- Order: explicit user-selected image > verified native-square preferred provider > alternate native square provider > safely composed square crop > bundled themed icon. Never display a weakly matched image merely to fill whitespace.
- User-imported local image and user-provided image URI may be supported independently of third-party providers. Avoid automatically copying user images into exported metadata snapshots or external services.

## Broad media taxonomy and resilient multi-source metadata/artwork resolution (owner expansion, 2026-10-27)

**Owner requirement:** The enrichment pipeline covers **movies, TV, music, audiobooks, books, magazines, comics/manga, games, software/apps, adult media and common miscellaneous torrent types**, and tries multiple category-appropriate sources when a confident lookup provides no usable metadata or artwork. It should produce a beautiful, consistent square tile even when no real cover exists. Metadata and artwork are independently sourced, scored and attributed; a provider can supply title/creator/year without supplying an image and a later provider can complete artwork.

### Candidate source registry (research required, not all approved/integrated)

| Content type | Metadata source candidates | Cover / artwork candidates | Notes |
| --- | --- | --- | --- |
| Film, TV, season, episode | TMDB, TheTVDB, TVmaze, IMDb identifiers where contractually supported | Fanart.tv Movie/TV Square, TheTVDB Icons, TMDB images | Prefer true square; season-specific only when exact season established |
| Music, albums, singles, artists | MusicBrainz, Discogs, supported streaming catalogs | Cover Art Archive (by MusicBrainz release or release-group ID), Discogs images, artist art | Album/release identity differs from artist; don't misapply artist portraits to albums |
| Books and audiobooks | Open Library, Google Books, ISBN/edition metadata | Open Library Covers, Google Books cover links, embedded/locally selected covers | ISBN/edition/author strong matching; audiobook edition distinct where possible |
| Magazines / periodicals | ISSN/title/issue metadata, Internet Archive and publisher catalogs as permitted | Edition/issue scan or publisher-issued cover where licensed | Precise issue/date/volume matching; avoid depicting wrong issue |
| Comics, graphic novels, manga | ComicVine, Grand Comics Database, Metron, MangaDex where covered | Exact issue/volume covers from authorized databases | Series vs volume vs issue IDs; variant cover choice user-editable |
| Games / gaming media | IGDB, RAWG, Giant Bomb or catalog data with suitable contracts | IGDB covers/artworks, SteamGridDB where allowed, storefront imagery | Platform, edition, release and remaster disambiguation |
| Applications / software / mobile APK | F-Droid metadata, official app/store metadata, GitHub releases/repos where appropriate | Official app icon/package artwork or project branding | Exact app ID/package/publisher/version preferred; avoid incorrect trademark imagery |
| Adult film / explicit media | Specialist adult metadata indexes with documented permission and API, if any suitable source exists | Age-appropriate opt-in provider artwork or generic adult-content tile | **Default no remote lookup**, never expose explicit imagery without separate opt-in; safe thumbnail mode, privacy lock, no previews in notifications |
| Documents, archives, datasets, ISOs, fonts, ebooks without matches, other | Local MIME/signatures, filename extension, validated file composition and optional publisher/Internet Archive metadata | Beautiful built-in SVG/vector category art and file-type motifs | Generic art clearly categorized; no bogus inferred book/movie/video cover |

These names are **research candidates**, not a promise of API access, endorsement, availability or licensing. Record each provider's auth, terms, quotas, permitted caching/attribution, user-agent requirements, content moderation boundaries, API longevity and actual result quality before shipping. Open Library specifically warns against using its API as a high-traffic third-party backend; use low-rate human-initiated lookups, caching and approved use. Cover Art Archive has front-cover thumbnails and 404 for missing records. The old Google Custom Search JSON API is closed to new customers and should **not** be treated as a viable new GIS integration.

### Resolution pipeline: deterministic, bounded, explainable

1. **Local evidence first:** classify based on file composition and normalized names (when available), extensions, embedded local metadata, package manifests, ISBN/ISSN, ID3/FLAC/Vorbis tags, comic metadata (ComicInfo), existing cover files or embedded pictures, and available verified publisher IDs. Never read remote payload files just to classify when rTorrent lacks a safe authorized read-only capability. Recognize multi-format packs and leave ambiguous/general collections generic.
2. **Type-specific query plan:** build several sanitized variants (canonical title, title+year, creator+title, album+artist, series+issue/volume, game+platform, package ID), including Unicode and language normalization without erasing disambiguating words. Never include release-group junk, passkeys, tracker URLs, source path or raw torrent names.
3. **Providers in configurable priority:** try best-fit sources sequentially or in bounded parallel batches according to rate, availability and reliability. On 404, no artwork, low-confidence result, API error or unusable dimensions, attempt another permitted source. On 401/403, pause that provider and surface account/configuration action; on 429 honor Retry-After/backoff; do not pound endpoints. Cache negative results with short TTL and time-based recheck to avoid permanently blank cards.
4. **Merge structured evidence:** maintain normalized identity, category, title, original title, creators, year/date, series/season/episode/issue/edition, external IDs, synopsis if authorized, genres, publisher/developer, and each field's provider/provenance/confidence. A provider's record can enrich metadata even when its artwork fails. Respect source precedence and conflicting fields; never overwrite a manually confirmed field.
5. **Independent image search:** prioritize exact-edition native-square imagery. If only rectangular covers exist, use tasteful aspect-preserving layout (blurred/gradient side fills) rather than destructive crops where text would be lost. Set image score by subject match, correct edition, visual quality, license, actual pixel dimensions, aspect ratio, watermark, logo/text legibility and safety classification.
6. **GIS / general image-search last resort:** GIS here means **general image search**, not geographic information systems. Generate sanitized, targeted queries such as `"<title>" "<year>" official cover square`, `"<artist>" "<album>" album cover`, `"<series>" issue <number> cover`, `"<game>" "<platform>" cover art`. Add domain filters for official/publisher or trusted licensed repositories; request square/aspect filter, adequate pixel resolution and allowed content type where API supports it. A query result is **only a candidate**: verify domain and actual image bytes, content match and rights/attribution, reject stock/watermarks and unsafe redirects, and require user approval on weak evidence. Do not scrape ordinary search result HTML, hotlink arbitrary sites or assume search engine indexing grants re-use rights. Provider viability and search quotas must be researched before shipping.
7. **Guaranteed local visual fallback:** show polished bundled category vectors (film reel, TV, record/music, book, magazine, comic, controller, app, document, spreadsheet, archive, image, dataset, installer, font, disc, generic folder), with deterministic accent/color and optional clipped readable file-type label. Generic art must not misleadingly imitate a real media cover. Default art is available fully offline and accessible.
8. **Review and repair:** visual source badge/provenance in edit UI (not cluttering transfer cards); choose alternate match or artwork, manually search or import, pin user selection, clear/reset, retry one source or all, disable providers/category, and view lookup reason/failure without exposing secrets. A confirmed mapping may be reused for duplicates across phone and several servers while honoring per-transfer overrides and locked profile isolation.

### Adult material and parental privacy boundaries

Adult content is a valid classification, but automatically distributing torrent titles to adult indexes or rendering explicit thumbnails can cause significant privacy exposure. Require **separate affirmative adult metadata and explicit-art permissions** from general media enrichment, disabled by default. In safe mode use neutral non-explicit tiles even if other categories use real art. Never leak explicit titles/images in OS notifications, recent-app thumbnails, lockscreen, analytics, export, error logs, diagnostics or a locked rTorrent profile. User controls should support hide/blurring thumbnails per category and separate metadata/artwork toggles. Do not bypass an upstream provider's age/content restrictions.

### Resolver state, metrics and bounded work

Use stable states `LOCAL_METADATA`, `QUEUED`, `PROVIDER_SEARCH`, `PARTIAL_METADATA`, `ARTWORK_READY`, `NOT_FOUND`, `NEEDS_REVIEW`, `BLOCKED_CONSENT`, `PROVIDER_COOLDOWN` and `ERROR_RETRYABLE`, including provenance and last attempt. Provider attempts and user changes must be restart-safe and never block transfer controls. Bound requests per transfer/category and total daily/network budget; only visible entries get higher scheduling priority, with optional idle-time library fill when enabled. Retry after connectivity changes and provider cooldown, deduplicate per canonical media identity, and refresh changed identifiers. Expose local diagnostic counts (match rate by category, provider coverage, fallback success, wrong-match corrections, size/cache consumption) without telemetry or content titles.

### Additional acceptance gates

- Regression fixture matrix for every taxonomy group including season pack, multialbum discography, multi-book series, comic issue variant, magazine month/year, game remaster, duplicate app package, uncategorizable mixed torrent, unknown encrypted/archive data and adult-content safe mode.
- Fake-provider sequence A miss → B identity only → C art found; A 429 cooldown → B success; contradictory matches → needs review; image search rejected for licensing/low score → generic fallback; offline success with bundled generic art. Verify metadata fields merge independently and manually pinned artwork always wins.
- Query sanitizer red-team: magnets, URL-encoded passkeys, VPN/hostnames, token-looking strings, remote path, PII-style filenames, Unicode confusion; prove outbound requests contain only explicitly consented sanitized terms.
- Pixel/Android visual tests: consistent square tile treatment across all media types, native cover vs letterboxed cover vs category vector, safe/adult artwork hidden until enabled, mixed local and remote queues, 1,000+ item list, flaky providers and long refresh.
- Per-provider contract compliance in separate checklist. No automated general search without terms/rate/licensing review and explicit consent. Report true provider limitations instead of fabricating a successful match.


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

## Multi-format artwork gallery and scoped user overrides (owner requirement, 2026-10-27)

Artwork is a **set of independently configurable visual roles**, not a single square image: at minimum **square tile (1:1)**, **portrait poster/cover** (source-preserving, typically 2:3 or book/album-native aspect), and **wide banner/hero** (typically 16:9 or source-native wide). Additional variants such as backdrop, logo or file-type icon can be introduced without schema migration. Each view chooses its appropriate role (compact transfer card/grid, poster library, banner/detail header), with a per-role fallback chain and content-aware crop/letterbox when necessary. Never force one image/crop across every view or distort source aspect ratios. Each torrent can individually pin a different image for **each** role.

### Change artwork picker

- A prominent **Change artwork** entry in local and remote transfer details opens a full-screen, accessible multi-format editor. Tabs/segmented roles: **Square**, **Poster**, **Banner** (and later extra roles). Display a responsive, scrollable grid of thumbnails merged from *all enabled providers* and local/embedded findings, with explicit source/attribution, dimensions, crop preview and identity confidence. Keep provider order and diversity configurable; never hide viable choices because the currently selected source answered first.
- Provide **Search more**, **Change media match**, **Refresh providers**, **Upload/select image from device** (Android Photo Picker / SAF; persist permission or copy to app-private storage safely), and **Use image URL** (HTTPS retrieval with explicit request/preview) alongside **Remove / Reset to automatic**. Local source selection is supported even if remote artwork lookups are off. URL submission is not proof of license or safe content; validate type, bytes, pixels, redirects/host safety and user consent, and copy a bounded processed derivative to private storage for stable offline use. No unauthenticated remote server-file read is implied.
- Choosing a candidate opens a crop/fit preview specific to the role, preserving the original image where permitted. Users can pan/zoom/set focal point or choose fit/blur-fill rather than destructive crop; save non-destructive crop parameters per visual role and allow later revision. Show correct art separately from metadata fields.
- Save is explicit and immediate in local presentation metadata, with Undo and Reset. Artwork choices must never rename or move downloaded files, alter torrent metainfo, trigger rTorrent mutations, or overwrite an unrelated media item's art. Maintain source provenance and user-selected pinning across cache eviction, normal refresh and display-alias changes; protect locally imported source/derivatives from automatic cleanup until no assignments reference them.
- Show pending/error/duplicate/empty gallery states; users can pick a built-in category visual deliberately. No provider requirement or network dependency to use local files.

### Apply to related transfers: selectable inheritance scope

After selecting artwork, provide context-aware **Apply to** controls with selectable scope (default **This torrent only**), preview of affected existing transfers and counts, and independent toggles **per artwork role**. Example scopes:

| Content | Scope candidates |
| --- | --- |
| Television | This torrent; all episodes in this **season**; all seasons/episodes of this **show**; specific series/edition; optionally show-wide posters and season-specific banners independently |
| Music | This torrent/release; all tracks on the **album**; all releases by the **artist**; optionally a whole artist discography; distinguish album/artist/compilation and multi-artist releases |
| Books/audiobooks | This edition/file; all editions of the **book/work**; all books in a **series**; all books by the **author** (co-authored works require deliberate match) |
| Comics/manga | This issue/variant; all issues of a **volume/arc**; whole **series**; publisher only if explicitly requested and identifiable |
| Magazines | This issue; year/volume; all issues of the **magazine title** |
| Movies | This exact release; this **movie/work** across releases; optional franchise/collection only with explicit choice |
| Games | This edition/platform; all variants of a **game**; franchise only by explicit user scope |
| Apps/software | This version/package; all releases sharing verified **package/app identity**; publisher only when explicitly selected |
| Adult / miscellaneous | Same verified title/series/collection as supported; otherwise this torrent only, privacy gate unchanged |

Use a shared **artwork assignment rule** keyed by verified media identity and explicit scope (work/series/season/artist/author/etc.), plus optional transfer-level and role-level overrides. Rules affect **Chunks display only**, both local and remote if selected and authorized; do not change the underlying torrent content. Scope identification must be evidence-backed: no accidental match from similar free-text names, same artist surname, similar show title, wrong edition, unverified remote profile or raw infohash guesses. For ambiguous groupings require user confirmation and show exclusions. New matching transfers may inherit saved artwork according to active rules; allow choosing **existing only** versus **existing and future matches**.

Precedence is deterministic per artwork role: **explicit transfer+role pin > narrower confirmed group rule (season/album/issue/edition) > broader confirmed rule (show/artist/author/series) > automatic provider selection > built-in category art**. Within same specificity, newest explicit rule wins with transparent conflict preview and undo/history; never silently overwrite an individually pinned selection. Removing a scoped rule recomputes fallback for affected transfers without erasing more-specific pins. Support different scope choices for square/poster/banner of the same title.

Show affected-transfer preview, known matched count, excluded/uncertain count, current overridden count and whether new arrivals inherit. Apply/undo atomically to local assignment rules, not as many remote writes; large lists must remain responsive. Reconciliation must handle transfers disappearing, duplicates across profiles, category changes, account isolation, offline rehydration and rules that no longer resolve. Never leak private locked transfer names when previewing a broad rule.

### Format-aware image quality and acceptance

Gallery filters: source, role, orientation/aspect, resolution, language, safe-content filter and image type where providers allow. Candidate ranking should prioritize exact content identity/edition and intended role first, then resolution, crop quality, provider reliability and source permissions. Use size-aware thumbnails but retain originals only where permitted and necessary for editing. When only poster art exists, the square role can use user-adjustable crop/fit while the poster role keeps the original; banners can use a separately chosen backdrop.

Test fixtures: manually assign distinct square/poster/banner images to one transfer; attach one season rule and another show rule, preserving a pinned episode image; artist-wide artwork with an album-level override; author scope with co-authored ambiguous book; same film across separate release hashes; untrusted URL and picker cancellation; image crop re-edit; cache eviction; source removal; same-profile and cross-profile duplication; offline and privacy-locked behavior; 200% text and TalkBack gallery grid. Every role remains independently reversible, and no remote torrent mutation occurs.


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
