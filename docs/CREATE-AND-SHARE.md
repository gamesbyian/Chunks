# Create & Share: torrent creation and publication contract

**Status:** Binding product design, not implemented or verified. Applies to local-only, remote-only, and combined Chunks configurations. See [PRODUCT](PRODUCT.md), [UNIFIED-ENVIRONMENTS](UNIFIED-ENVIRONMENTS.md), [SECURITY](SECURITY.md), and [SPEC-COVERAGE](SPEC-COVERAGE.md).

## Goal and modes
A user selects an Android file/folder and can publish a shareable torrent without knowing trackers, DHT or indexers. No account or external service is mandatory for the basic workflow. The same guided experience supports optional authenticated tracker and indexer connections, with progressive disclosure of expert controls.

- **Share by link:** create compliant .torrent metadata, a magnet URI where supported, and a seed job. Add vetted public trackers when applicable; use DHT/PEX as appropriate. Generation alone does not prove peer reachability or downloadability. No third-party index submission by default.
- **Public discovery:** separately opt in to submitting to supported public indexes, anonymously where explicitly offered, or using a connected identity. Public tracker announce, DHT discoverability and searchable indexing are separate states.
- **Connected services:** optional named publisher profiles for tracker/indexer/service accounts. A user may select one or multiple supported destinations and review each service's content/metadata and authentication requirements before committing.

## User journey
Entry points: Create & Share action, Android file/folder Share intent, or selected Chunks-accessible file(s). Preview exact chosen files, total bytes, filenames, directory structure, read permissions and available storage. Warn about IP exposure, persistent seeding, metadata leakage, and inability to revoke already distributed information. Never send content or metadata externally until the user explicitly initiates a network action.

Wizard: (1) choose file(s), (2) choose link only vs discoverability, (3) choose where to seed (phone and/or configured capable remote), (4) optionally choose publisher profiles and per-site categories/templates, (5) review recipient-visible metadata and credential/privacy implications, (6) create, verify, share. Allow safe cancel/resume with no duplicate submission or orphan jobs. Offer expert overrides for piece sizes, metadata, tracker tiers, and version choice subject to compatibility. Provide outputs: Copy magnet, Android Share, Save .torrent, and View seeding job.

Remote-only mode must not require starting the local torrent engine. Torrent metadata may be generated on-device independently of a local seed engine; a remote server cannot seed phone-only bytes unless a distinct, explicit payload-upload/migration workflow succeeds. This is separate from job-only Copy/Move Transfer. Remote success requires verified server-side data and an accepted seed job.

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

**Implementation/evidence:** none established by this contract; update SPEC-COVERAGE, WORK-QUEUE and QUALITY-LEDGER as work lands.

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
