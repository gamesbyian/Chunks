# Existing-service integrations: indexers and verified storage synchronization

> **Authority:** owner-approved niche integrations (D-NICHE-SERVICES-20261029). This contract owns connecting Chunks to existing self-managed indexer services (Prowlarr first) and separately authorized remote-storage copy/synchronization providers. [INTAKE-QUEUE-AND-POLICIES](INTAKE-QUEUE-AND-POLICIES.md) exclusively owns Discover, its master off switch and normalized search intake; [REMOTE-INTEGRATIONS-AND-PREFERENCES](REMOTE-INTEGRATIONS-AND-PREFERENCES.md) owns general credential/profile portability and media/cloud handoff boundaries; [CROSS-SEED-AND-PAYLOAD-COPY](CROSS-SEED-AND-PAYLOAD-COPY.md) owns manual actual-byte copy and immutable verification. **Status:** planned design and test requirements, no universal provider interoperability claimed.

## Product scope and exclusions

A user running an existing Prowlarr-compatible service SHOULD be able to connect it **once**, inspect a safe preview of its indexer capabilities and use it as an optional source for Chunks Discover without re-entering every indexer credential. Other documented indexer aggregators or Torznab-style endpoints MAY be offered through distinct adapters, each verified separately. Chunks does not assume every installed Prowlarr indexer allows generic torrent search, sharing its credentials or automatic publication.

A user with authorized storage adapters MAY create **verified independent copies** or narrowly scoped **synchronize this selected folder** jobs between phone/seedbox/other explicitly connected Places; no permanent developer-run relay, mandatory cloud account, unrestricted host administration or global rclone deployment. Remote synchronization is an advanced convenience for data the user controls, not the default definition of Copy/Move Transfer.

**Exclusions:** becoming Prowlarr itself, modifying all Prowlarr applications/indexers, importing Usenet/NZB downloads into Chunks' torrent engine, installing server applications, administering host OS/users/firewall/containers, silent two-way mirroring, in-place deletion to force mirror parity, sweeping all remote folders or assuming that read access to an rTorrent API implies file, SSH or Prowlarr access.

## Identity and service capability model

A **ServiceConnection** contains immutable Chunks ID, adapter type/version, authenticated endpoint origin and tenant identity, scope-granted credential reference, read-only discovered capabilities, expiration/freshness and per-profile network policy. **IndexerSource** is the service's native stable indexer identifier, advertised category/search capability, provider type, availability and last verified sync; Chunks-generated provider labels cannot identify it. **StorageEndpoint** is an independent exact environment + filesystem adapter + authorized root + generation. **SyncPlan** has immutable plan ID, source and target Place, direction, inclusion/exclusion filters, collision policy, schedule/executor, verification policy, byte/quota budget, and user consent version.

## Prowlarr/indexer connection UX

**Entry:** `Settings → Discovery → Sources → Connect existing service`, with Prowlarr as a labelled supported adapter once proven. It MAY appear in Discover's source picker under **Connect sources**; do not add an always-present card to Transfers or main navigation. When the global Discovery switch is off, existing connected services remain configured but Chunks makes no indexer lookup/synchronization calls.

1. **Connect:** choose supported service type, exact HTTPS URL/LAN endpoint and API key or documented authentication; discover protocol/version and whether the token is read-only enough for advertised actions. Explain that the key belongs to the user's service; Chunks will not upload it to its own server. Private/LAN access gets separate per-endpoint approval and TLS disclosure; no global insecure network toggle.
2. **Review sources:** fetch a preview of indexers exposed by the service, categories, torrent vs Usenet protocol, enabled/disabled, availability, privacy/credential requirements and permitted search capabilities. Chunks selects **torrent-capable** sources explicitly. Unsupported/disabled/ambiguous sources remain visible as unsupported, not automatically activated. Provider search results retain exact service/indexer provenance.
3. **Choose synchronization mode:** **Use existing sources (read-only, default)**; user can manually refresh, turn individual indexer sources on/off in Chunks or disconnect. **Keep sources refreshed while Discovery is on** is a separate opt-in with a bounded cadence; neither option changes settings in Prowlarr. Native service indexer changes are reflected on refresh, with additions default inactive until reviewed if they broaden network disclosure, categories or privacy. No cyclic bidirectional auto-configuration.
4. **Search:** Chunks delegates to the service's documented API, normalizes supported torrent results, preserves service source/freshness and uses the common reviewed Add draft. Expired credentials or service outage show `Reconnect`, `Last verified`, `Retry`; do not fall back to another unapproved scraper, forward indexer passkeys to an external domain or claim every category is searchable.
5. **Disconnect/revoke:** stop service queries immediately, remove credentials on request, preserve existing torrent jobs, let user optionally delete Chunks-local source list/cache; never delete/change Prowlarr's indexers or app config.

**Authority and Discovery:** Source registry updates, scheduled sync, cross-seed provider searches and result refresh check the app-wide Discovery master gate **at dispatch**. Discovery off does not prevent opening a direct user-provided .torrent/magnet or ordinary local file watcher import. A Create & Share publisher profile is separately permissioned and cannot reuse a read-only search key to publish.

## Storage copy and optional synchronization UX

**Entry:** `Places → selected folder → More → Copy / Sync`, with **Copy once** first (opens [CROSS-SEED-AND-PAYLOAD-COPY](CROSS-SEED-AND-PAYLOAD-COPY.md)) and a secondary **Set up synchronization…** option. Also `Settings → Connections → Storage adapters` to manage credentials and authorized roots. No permanent global Sync tab, no server-management tile on Transfers and no default scan of remote homes.

1. **Define:** select exact named source/target environments and individually authorized roots. The default mode is **One-way copy of new/changed files** into a separate target folder. Bidirectional conflict resolution is not a default; enabling it would require a separately approved correctness design. A source torrent's backing data is never automatically moved or deleted.
2. **Preview:** file count, estimated total/copy bytes, target quota and storage budget, metered/battery/network cost, route (server-direct verified vs phone relay), schedule availability, effects on torrent jobs, excluded/sensitive files, symlink and case-folding collisions and change policy. Existing destination files default **Skip identical after verified comparison / Ask about conflicts**, never overwrite silently.
3. **Authorize:** separately connect exact file transport service, e.g. supported SFTP/FTPS/WebDAV/rclone-compatible endpoint *only when a tested adapter exists*. Avoid claiming rTorrent RPC, a seedbox hostname or a provider brand is enough. An optional server-side executor requires user-owned documented host permissions and opt-in; a phone-managed sync has no guarantee of 24/7 execution under Android lifecycle limits.
4. **Run:** journal per-file intent, snapshot, byte-range progress, received digest and verified postcondition. Persist checkpoint for cancellation/resume where backend safely supports it. Handle source changes, target changes, missing quota, permission revocation, process death and offline service with **Held/Needs review**, rather than creating a second unverified copy.
5. **Review results:** show **Files copied / Verified / Skipped / Conflicts / Unable to verify**, timestamp, route and scope. Allow future **Run now**, **Pause schedule**, **Edit rules**, **Disconnect endpoint** and **Clear history**. A sync plan never silently registers a torrent on the target, enforces seed ratio there or deletes backup source files. A later torrent Add is separately reviewed.

**Schedule:** Off by default, optionally manual or approved bounded periodic cadence. If Android cannot reliably execute the plan offline, label it **Runs while Chunks is available**, not a persistent daemon. Automatic remote-side scheduling is only possible if a separately verified and explicitly enabled user-owned executor exists. Never use an owned cloud service or background upload as an invisible fallback.

## Protocol, security and failure invariants

- Isolate Prowlarr indexer keys, torrent source credentials, media integration tokens, remote engine RPC login and file-adapter credentials. Store encrypted; exported settings exclude secrets or require separate independently approved recovery flow. Never assume a remote server uses the phone's VPN. No arbitrary XML/JSON endpoint methods, proxy-to-local SSRF, unsafe redirect credential forwarding or writable Prowlarr admin operations by default.
- Adapter negotiation records supported protocol/API version, auth role, endpoint path, TLS identity and last verified capabilities. Do not advertise browser WSS seed compatibility or host-side sync purely from an indexer API. Treat indexer result metadata and storage file paths as hostile inputs.
- Remote storage roots cannot be escaped by symlinks, relative paths, Unicode case tricks, bind mounts outside authority, or archive entries. Default to no follow symlink unless independently approved. Preserve shared/hardlink backings and torrent seeding dependencies; no silent quota cleanup or deletion. Full hash/piece verification as required by [CROSS-SEED-AND-PAYLOAD-COPY](CROSS-SEED-AND-PAYLOAD-COPY.md), never assume equal size means identical.
- A `SyncPlan` is durable Chunks configuration, not proof that work happened; a per-file transfer receipt distinguishes submitted, acknowledged, bytes persisted and independently verified. Safe retry/idempotence requires stable source/target snapshot. Ambiguous remote errors need readback first.
- A service integration never bypasses Chunks' global Discovery off state or automated-intake separate opt-ins. Search and copied content identities are local/redactable by default; no third-party telemetry without consent.

## Acceptance matrix

| Area | Required test evidence |
|---|---|
| Prowlarr | Read-only indexer listing/search fixtures, categories torrent vs Usenet, API version changes, expired token, auth/TLS failure and no mutating calls |
| Discovery isolation | Global off during pending sync/search, process restart, provider reconnect, lost permission and saved-source restore yields no discovery egress |
| Source correctness | Same release through two sources, stable native IDs, duplicate results, partial metadata, indexer health and private tracker passkey isolation |
| Storage adapters | RPC-only remote cannot read/write bytes; exact root/tenant, symlink escape, quota, path conflicts, mobile data and Android SAF loss |
| Verified sync | Same-size-different-content, changing source during transfer, interruption/resume, revoked credential and partial staging never claim success without exact verification |
| Scheduled limitations | Android Doze/process death, unplugged phone, remote-only configuration and user-owned persistent executor produce truthful capabilities |
| UX | Secondary sources/settings and Places actions, no new main tab, clear copy vs sync and job vs bytes labels, 320×700/412×892, TalkBack at 200% text |

## Implementation/evidence status and related artifacts

Specification-only. Record adapter capabilities, exact code SHAs, fixture protocols, device/host proof and failures in [SPEC-COVERAGE](SPEC-COVERAGE.md) and [QUALITY-LEDGER](QUALITY-LEDGER.md).

[PRODUCT](PRODUCT.md) · [UX](UX.md) · [ARCHITECTURE](ARCHITECTURE.md) · [SECURITY](SECURITY.md) · [INTAKE-QUEUE-AND-POLICIES](INTAKE-QUEUE-AND-POLICIES.md) · [REMOTE-INTEGRATIONS-AND-PREFERENCES](REMOTE-INTEGRATIONS-AND-PREFERENCES.md) · [CROSS-SEED-AND-PAYLOAD-COPY](CROSS-SEED-AND-PAYLOAD-COPY.md) · [CREATE-AND-SHARE](CREATE-AND-SHARE.md) · [WORK-QUEUE](WORK-QUEUE.md)
