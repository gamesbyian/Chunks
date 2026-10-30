# Create & Share: torrent creation and publication contract

**Status:** Binding product design. Some basic creation/build/Android UI slices have separate partial test evidence in implementation notes below; **advanced tracker/browser/feed sharing and complete end-to-end acceptance remain planned and unverified**. Applies to local-only, remote-only, and combined Chunks configurations. See [PRODUCT](PRODUCT.md), [UNIFIED-ENVIRONMENTS](UNIFIED-ENVIRONMENTS.md), [SECURITY](SECURITY.md), and [SPEC-COVERAGE](SPEC-COVERAGE.md).

## Goal and modes
A user selects an Android file/folder and can publish a shareable torrent without knowing trackers, DHT or indexers. No account or external service is mandatory for the basic workflow. The same guided experience supports optional authenticated tracker and indexer connections, with progressive disclosure of expert controls.

- **Share by link:** create compliant .torrent metadata, a magnet URI where supported, and a seed job. Add vetted public trackers when applicable; use DHT/PEX as appropriate. Generation alone does not prove peer reachability or downloadability. No third-party index submission by default.
- **Public discovery:** separately opt in to submitting to supported public indexes, anonymously where explicitly offered, or using a connected identity. Public tracker announce, DHT discoverability and searchable indexing are separate states.
- **Connected services:** optional named publisher profiles for tracker/indexer/service accounts. A user may select one or multiple supported destinations and review each service's content/metadata and authentication requirements before committing.

## Default-share journey and later agency (owner UX, 2026-10-30)

Offer a plain, nonblocking summary of included files, proposed share output, save location and supported sharing route with adjacent **Change** affordances. When the user leaves optional choices alone, create only the approved reversible draft and perform actions already separately authorized; never infer publication to an indexer, wider peer discovery, public tracker hosting, recipient delivery or third-party account authorization. Display real reachability/availability evidence before suggesting that recipients can obtain bytes. Keep a created draft/output accessible for later changes and further sharing steps without forcing a new creation workflow; when a metadata change requires a new torrent identity, explain that explicitly. [Default and continuity UX](UX.md#visible-options-automatic-defaults-and-nondecision-continuity-binding-owner-direction-2026-10-30).

## User journey
Entry points: Create & Share action, Android file/folder Share intent, or selected Chunks-accessible file(s). Preview exact chosen files, total bytes, filenames, directory structure, read permissions and available storage. Warn about IP exposure, persistent seeding, metadata leakage, and inability to revoke already distributed information. Never send content or metadata externally until the user explicitly initiates a network action.

Wizard: (1) choose file(s), (2) choose link only vs discoverability, (3) choose where to seed (phone and/or configured capable remote), (4) optionally choose publisher profiles and per-site categories/templates, (5) review recipient-visible metadata and credential/privacy implications, (6) create, verify, share. Allow safe cancel/resume with no duplicate submission or orphan jobs. Offer expert overrides for piece sizes, metadata, tracker tiers, and version choice subject to compatibility. Provide outputs: Copy magnet, Android Share, Save .torrent, and View seeding job.

Remote-only mode must not require starting the local torrent engine. Torrent metadata may be generated on-device independently of a local seed engine; a remote server cannot seed phone-only bytes unless a distinct, explicit payload-upload/migration workflow succeeds. This is separate from job-only Copy/Move Transfer. Remote success requires verified server-side data and an accepted seed job.

## Optional advanced sharing: embedded tracker, browser peers and subscription feeds

**Owner decision D-NICHE-SHARE-20261029:** make account-free sharing easier without making browser/WebRTC transport, a local tracker or subscription publishing compulsory. These are optional **Create & Share → More sharing options** controls, not new primary navigation, background services, or another creation wizard. Existing basic Create & Share remains the default route. The owner-approved baseline and its incremental implementation notes below are preserved, not reclassified as unimplemented by this addition.

### Three different kinds of shareability

1. **Ordinary torrent share (default):** save .torrent and, when allowed, share magnet; attempt normal local/remote seeding using already supported TCP/µTP/DHT/trackers. This may work without user accounts but still depends on actual seeding, connectivity and private-torrent policy.
2. **Reachable tracker for a small private group (expert, optional):** Chunks MAY run a minimal **embedded tracker** on the phone while the app/network and OS permit, for explicitly authorized peers. This tracker helps peers *discover* each other but stores or transmits **no payload file bytes by itself**. It does not guarantee that peers behind NAT can connect. Show actual bind interface, reachable address, port, announce URL, expected uptime, exposure/privacy and access controls. Never silently bind a public listener, change router port forwarding, or expose private-tracker content. A local loopback or LAN-only listener is labelled **Local network only**; public availability requires separately validated reachability, not a successful socket bind.
3. **Browser-friendly share (expert, capability-gated):** a web page can exchange BitTorrent data with a compatible **WebRTC-capable seeder**, using compatible tracker signaling (often WebSocket/WSS and approved ICE/STUN/TURN arrangements). Ordinary rTorrent and many classic BitTorrent TCP/µTP-only clients cannot supply WebRTC peers by themselves. The UX MUST say **Browser download available** only after an independent compatible seeder and signaling path are confirmed; ordinary magnet creation, inclusion of a WSS tracker or tracker announce success is insufficient. Browser compatibility is an additional share route, not a default guarantee.

**Do not offer a misleading one-tap “open in any browser” claim.** Show a clear compatibility matrix: **Torrent app link available / Browser link supported on this setup / Needs compatible seeder / Needs reachable signaling / Unsupported / Unknown**. A user may use a trusted, explicitly chosen external browser-compatible receiver/site; Chunks does not promise to host a public website, TURN server, WSS tracker, dynamic DNS or proxy. If a user runs their own compatible bridge, treat it as a separately authenticated and independently validated connector, never an always-on service silently installed on a seedbox.

### Advanced Create & Share journey

**Default screen:** Choose file → review recipient-visible names/size and seed environment → Create → Save/Copy/Share; no advanced-step interruption. A compact **More sharing options** expander may contain **Try a browser-friendly link**, **Run a local tracker**, and **Publish a subscription feed**, with terse explanations and capability/permission state. Do not crowd Create's first screen, Transfers, or Add review.

- **Browser-friendly:** Show `Check compatibility` first. Inspect selected phone/remote seed adapter, public/private torrent rules, signaling transport and peer reachability in a controlled test, without uploading the chosen payload elsewhere. If compatible, provide **Copy browser link**, **Share browser link** and **Try with a test peer**; show `Test connection` receipt and who will serve bytes. The link or receiver page must be explicitly user-provided, trusted or already vetted; Chunks does not invent an always-accessible browser site. If unavailable, fall back to **Share magnet/.torrent for a torrent app**, or offer a non-BitTorrent ordinary Android Share method. Do not promise direct WebRTC in an engine until a native binding/bridge has passed compatibility proof.
- **Embedded tracker:** Off by default. Explain that operating a tracker is not hosting a download website. Per-share or per-profile opt-in; choose LAN-only by default, with separately reviewed public exposure, endpoint authorization, token/announce scope, and start/stop. Show **Running / LAN only / Reachable externally / Stopped / Android paused / Uncertain** based on actual observation, and whether seed jobs are still running independently. Stop tracker on revocation, app lock or shutdown; never represent Android background hosting as permanent. Do not run a local tracker for private torrents unless the particular private tracker policy explicitly permits that behavior.
- **Subscription publishing:** For users repeatedly sharing a collection, optionally generate a versioned, standards-compliant RSS/Atom feed or manifest of **explicitly selected public share entries**. Each item contains stable share ID, timestamp, title/description, metadata/magnet or .torrent URL only when safe and actually available, and an explicit provenance/visibility label. Prefer **Export feed file** / **Copy feed URL** only if a user-owned hosting location has been verified. Chunks MAY publish to an independently configured site through explicit permission, but does not run a permanent public feed server by default. No automatic tracking of newly added private/filesystem content, no passkey-bearing URLs, and no automatic catalog of all Chunks transfers.
- **Follow/share updates:** If a share's metadata or publisher destination changes, distinguish **Same torrent identity / New torrent identity / Feed item revised**. A feed update never edits the immutable torrent infohash or migrates an existing seed job silently. Recipients subscribing with compatible external tools are not guaranteed a persistent availability service; show **Last published**, **Currently seeding**, and **Publisher reachability** independently.

### Local, remote and private safety

An embedded tracker and browser-compatible seeder are independent capabilities from **rTorrent RPC**, file transport and indexer publication. In remote-only mode Chunks may build metadata and publish a reviewed feed, but it cannot create remote seeding bytes, start a remote WebRTC bridge, open ports or host a tracker on the remote merely because it can connect to XML-RPC. If a chosen remote engine lacks WebRTC, show **Browser share unavailable on this server** and keep ordinary torrent sharing fully usable.

For both standard and browser links, explain network exposure and the optional use of public signaling/TURN services; STUN/TURN and signaling providers may learn network metadata, and relayed traffic may have cost/terms. Require exact per-service opt-in and provider names, avoid passkey or private infohash leakage, never automatically expose a private torrent to public DHT, public tracker, browser signaling server or public feed. A share token or client-side page must not be presented as cryptographic access control unless independently enforced.

### Failure, recovery, settings and audit

Persist selected advanced option, service identity, original user consent, endpoint and verification receipt per share; **advanced features are off by default** and do not start on device reboot without independently authorized and OS-supported lifecycle. A failed tracker bind, inaccessible port, NAT barrier, WSS signaling failure, WebRTC incompatibility, lost permission, relay rate limit, Android process death or absent seed produces an accurate next action without deleting existing torrent metadata/job.

Retry browser tests and feed publication only with bounded backoff and idempotency; publication to third-party endpoints has separate review and readback. Deleting a feed file or stopping a local tracker cannot revoke data or announce information already distributed. An existing private tracker torrent never silently becomes public because an external browser option is chosen.

### Acceptance for advanced sharing

| Slice | Required evidence |
|---|---|
| Ordinary sharing unaffected | Basic account-free Create remains short and works with all advanced options off; no unexpected network listeners, WSS calls or feed publishing |
| Tracker | LAN-only bind, explicit network exposure, auth/scope, NAT non-reachability, stopped-on-revoke and process-death states; cannot claim public reachability from local bind |
| Browser compatibility | Positive verified WebRTC peer path and negative ordinary rTorrent/TCP-only, missing WSS, blocked signaling, missing seeder, privacy-restricted and offline fixtures; no false **browser-ready** badge |
| Subscription | Valid RSS/Atom/manifest, stable entries, update/invalidation, approved external hosting, no private passkeys/implicit transfer listing, lost-host credentials |
| Safety | No duplicate seed jobs or external publication on retry, precise identity and environment scopes, optional transport does not mutate existing torrent identity |
| UX | Collapsed secondary expander; full basic wizard at 320×700, 412×892 and 200% text; TalkBack, cancel, test result and provider disclosure all accessible |

**Technical note:** browser WebTorrent peers require WebRTC-compatible seeders and signaling, not just conventional TCP/µTP seeders; see [WebTorrent FAQ](https://webtorrent.io/faq). External protocol support must be proven for each Android/remote adapter rather than inferred from a library's name.

## Service registry and authenticated publication
Define a versioned capability registry of service adapters. Each adapter declares supported operations (announce, anonymous upload, authenticated upload, status check, delete/unpublish if actually supported), auth mechanism (OAuth/API token/passkey/session as expressly supported), required metadata, permitted torrent versions, private flag and source requirements, rate limits, submission idempotency, moderation statuses, and supported automation interfaces. Capability probes are advisory; unknown means unavailable until verified. Do not scrape around login, CAPTCHA, anti-bot protections, or site rules. No blanket promise that all known trackers/indexers accept automated publication.

Profiles store service URL, identity label, supported auth reference, health, capability/version, and optional category/template mapping. Secrets reside in Android Keystore-backed encrypted storage or equivalent approved credential system; never in analytics, issue reports, screenshots, exported .torrent files unless the tracker protocol specifically requires a passkey announce URL and the user explicitly chooses that sensitive export. Redact tokens and passkeys from magnet and clipboard sharing by default. Never send private tracker credentials to unrelated third parties. Explicitly confirm if any sensitive announce URL must be shared with a recipient.

## Torrent identity and privacy invariants
Public and private tracker variants may require **different torrent identities and separate seed jobs**; never mix private announce credentials or private-tracker metadata into a public variant. Respect private flag and disable incompatible peer discovery (including DHT/PEX/LSD as required by private tracker rules and engine behavior). Some private trackers generate a personalized .torrent themselves: use the server-issued torrent where required instead of assuming locally generated metadata is accepted. Users must review any service terms and destination-specific restrictions.

The seed environment and publisher destinations are separate choices. Identical payload bytes do not imply identical infohashes, and public/private variants must be tracked separately by (environment, torrent identity). Prevent unintentional redistribution across profiles. An index listing can persist after local unpublishing; distinguish removing local seed job, deleting metadata on device, and requesting site delisting.

## Reliability and state model
Represent creation, hashing, metadata built, seed job added, announced, remotely reachable (when independently validated), submission attempted, accepted, awaiting moderation, rejected, listed, stale/unknown, and error states separately. Store idempotency keys and operation journal; after network timeouts reconcile status before retrying any potentially non-idempotent submission. Verify postconditions using service-supported status endpoints; never equate HTTP 200 with publication or tracker announce with indexing. No automatic reposting to other services on failure.

Seed availability depends on Android background execution, power/network policies, inbound peer reachability, NAT, and other swarm peers. Provide actionable diagnostics and truthful caveats; no unconditional guarantee of working inbound connections or continuous availability.

## Acceptance gates
1. Offline account-free creation of single- and multi-file .torrent metadata, deterministic valid pieces, save/share outputs, cancellation and Android content-provider permission handling.
2. Local-only flow produces a real seedable job; independent client retrieves test data with hash verification under controlled network conditions.
3. Remote-only does not start the local torrent client, and refuses to claim seeding success when remote payload is absent.
4. Public tracker/DHT announce is not labelled index publication; submission is explicit opt-in.
5. Authenticated service adapter tests cover valid, expired and revoked tokens, private passkeys, API changes, category requirements, moderation, rate limits, duplicate retries, and TLS failures.
6. Secret redaction regression tests cover logs, crash reports, share sheets, clipboards, exports, URI histories, and diagnostic captures.
7. Public/private torrent variant isolation tests prove no private tracker leak through magnets, DHT/PEX/LSD, exported public files, or cross-environment job handoffs.
8. Android accessibility, landscape/portrait, TalkBack, offline interruption and 320×700/412×892 layouts; tests demonstrate explicit environment and publishing-destination scope.
9. Each backend/service is integration-tested against permitted fixtures or documented test accounts; no live publication to arbitrary real services during CI.

**Implementation/evidence:** the narrower base-flow implementation notes below retain their own recorded proof; those tests do **not** establish independent peer sharing, indexed publication, WebRTC browser connectivity, embedded tracker reachability or subscription-feed hosting. Update SPEC-COVERAGE, WORK-QUEUE and QUALITY-LEDGER as verified stages land.

## Implementation note: metadata builder (2026-10-29, slice 1 partial)

`create/TorrentMetainfoBuilder` builds BitTorrent v1 `.torrent` metadata from caller-supplied file streams, with no engine, network or Android dependency. It provides:

- automatic piece sizing (16 KiB–16 MiB, targeting about 1,000–2,000 pieces);
- optional tracker tiers, the private flag and a comment;
- deterministic output unless a creation date is given;
- progress reporting and cancellation;
- a magnet link for public torrents only. Private torrents get no magnet, so private trackers are never shared through one.

It refuses unsafe or empty names, `..`/backslash segments, duplicate or file-versus-folder path conflicts, empty payloads, tracker URLs with embedded credentials or non-tracker schemes, and invalid piece sizes. A file that delivers fewer or more bytes than declared fails the build instead of producing mismatched metadata.

Evidence (JVM, desktop libtorrent):
- the info hash equals libtorrent's own `TorrentBuilder` for the same multi-file payload, including an empty file and pieces that straddle file boundaries;
- `TorrentMetainfoReader` round-trips it;
- the real engine verifies the existing payload as 100% complete.

**Not yet:** SAF/file-picker input, UI, v2/hybrid metadata, seeding, independent-peer download, or any publication.

## Implementation note: first Create journey (2026-10-29)

A **Create** section (tab, plus a Connect-screen tile; it works with or without a server) lets the user:
- choose one file (`OpenDocument`) or a folder (`OpenDocumentTree`), listed read-only through Android document providers in a stable name-sorted order, with no storage permission;
- review the name, file count, size and the first paths, under a **What recipients will see** warning (names and sizes are visible, sharing is irreversible, seeding exposes the IP address);
- optionally add trackers or mark the torrent private;
- build it on the phone, with progress and Cancel.

The result offers **Save .torrent file** (system save dialog), **Share .torrent file** (through a read-only, non-exported provider that serves only created `.torrent` files from one cache folder, with access granted per share), **Share magnet link** and **Copy magnet link**. Private torrents get neither magnet action. A **Not seeding yet** banner states that nobody can download until something seeds.

Evidence (JVM/Robolectric):
- fake-provider listing tests (folder, single file, cancellation);
- Compose tests of review → build → result, the private variant, and a refused credentialed tracker;
- renders at 100% and 200%.

**Not yet:** remote seeding, publication, and handset/TalkBack acceptance.

## Implementation note: Android Share entry (2026-10-29)

Sharing one file from any app to the separately labelled **Create torrent** target (an `activity-alias` of the main activity) opens the Create review with that file listed. Nothing is hashed until the user taps **Create torrent**. Shares addressed to Chunks itself are never routed here, so a shared `.torrent` or magnet keeps its existing Add review. Text, several files, conflicting stream/clip URIs and non-`content://` URIs are explained, not read. Evidence: JVM intent-routing tests and a Compose test of a shared file opening the review. The real Android share sheet on a handset is not yet verified.

## Implementation note: seed a copy on the phone (2026-10-29, slice 2 partial)

The phone engine can only seed real files, and picked files are provider documents. So **Seed a copy on this phone** first shows a review: the copy size, the verified default Place and its free space, a statement that the originals are not moved or changed, and a red warning when peers cannot find the phone (no tracker with DHT off, or a private torrent with no tracker).

On confirmation `CreateSeedCopy`:
1. copies into a **new** folder named after the torrent, refusing if anything with that name exists and keeping 64 MiB free beyond the copy;
2. writes `.chunks-part` files, syncs them, then renames them;
3. rebuilds the metadata from the copied bytes and requires the **same info hash**, refusing files that changed after creation;
4. adds the torrent stopped, starts it, and reports **Seeding** only after the engine verifies every piece, then keeps the foreground service running.

Cancel or failure removes only the partial copy that attempt created. A mismatch or engine refusal keeps the copy and says where it is.

Evidence (JVM, desktop libtorrent): a verified seeded copy was downloaded **byte-exact by an independent loopback peer** (acceptance gate 2 at JVM/loopback tier). Also tested: no overwrite, files changed after creation refused, cancellation keeps unrelated files. A Compose test covers the review and warning.

**Not yet:** a handset or a real swarm (NAT, Doze), remote seed targets, publication.

