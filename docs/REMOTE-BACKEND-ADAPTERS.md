# Multi-backend remote adapters: canonical technical and acceptance contract

**Status:** owner-approved design, 2026-11-05; future execution only. **Authority:** [PRODUCT](PRODUCT.md), [ARCHITECTURE](ARCHITECTURE.md), [UNIFIED-ENVIRONMENTS](UNIFIED-ENVIRONMENTS.md), [CROSS-ENGINE-TRANSFER-JOBS](CROSS-ENGINE-TRANSFER-JOBS.md), [SMART-ROUTING-AND-SOURCE-LIFECYCLE](SMART-ROUTING-AND-SOURCE-LIFECYCLE.md), [SECURITY](SECURITY.md), and decision [D-MULTI-BACKEND-20261105](DECISIONS.md). Existing [#660](https://github.com/gamesbyian/Chunks/issues/660) is the single future-backend tracking umbrella. This contract supplies the backend-specific detail behind [ANDROID-TV-AND-REMOTE-BACKENDS](ANDROID-TV-AND-REMOTE-BACKENDS.md); TV presentation is a separate track.

## Purpose and binding sequencing

Provide one Android-native Chunks experience across the on-phone libtorrent client and any practical, maintainable remote BitTorrent clients. **Do not construct another set of Add screens, catalogs, rules engines, artwork stores, navigation systems or transfer-migration implementations for each protocol.** Isolate wire/auth/version differences in independently testable adapters behind the already evolving shared environment, observation and operation contracts.

**Scheduling is explicit and binding:** (1) finish and reconcile the ongoing universal app UX/architecture/automatic-artwork work; (2) satisfy remaining current local/rTorrent daily-use, lifecycle, integrity, protected-host and physical-device acceptance; (3) only then refactor or ship new remote adapters. These first two tracks may proceed concurrently under existing ownership; neither may be displaced by this roadmap. Prior-art investigation, contract specification, a bounded read-only code inventory and future test-fixture design can happen in idle capacity **without** modifying active rTorrent or UX source or consuming protected-host write CI. Do not create a parallel P0 queue, speculative platform or background daemon. A new adapter is never a current-release blocker.

The existing rTorrent implementation **may be restructured** to conform to the shared seam after the prerequisite gates, through staged behavior-preserving changes with exact-head real-rTorrent regression proof. No forced rewrite, big-bang migration, new service dependency or interruption of the existing user's profiles is authorized by this design.

## Scope and exclusions

**In scope:** selectable profile backend type; authenticated/safe connect and repair; normalized transfer listing including All/subsets; per-profile status and capabilities; Add magnet/.torrent; exact scoped Start/Stop; file inspection/priorities; optional ratio/speed/label controls where proven; session-only remove and separately confirmed remove-and-data; metadata/share where actually available; preserved Chunks-local aliases/artwork/rules; user-directed and smart-routed **job-only** Copy/Move. Real functionality is capability-gated by server version, permissions and reachability.

**Out of scope:** automatic payload/data copying in job Move or smart routing; requiring Flood, ruTorrent, a server-side Chunks component, or an account/cloud service; access to server file bytes inferred from RPC; alternate independent UI per backend; bypassing HTTPS/trust checks; fake support for unavailable client operations; replacing the local device's libtorrent4j engine; arbitrary RPC or shell consoles as the default integration. Distinct independent file-copy features remain distinct.

## Terminology, identities and persisted schema

- **EnvironmentId:** stable generated Chunks identity, independent of display name, URL, adapter, ordering or list scope. The environment owns color, privacy lock, saved connection, Places/defaults, settings and rule applicability.
- **BackendKind / adapter implementation version:** e.g. local-libtorrent, rtorrent, transmission, qbittorrent, deluge, utorrent, biglybt, synology-download-station. Backend kind is not inferred only from URL appearance; a deliberate selection or read-only handshake must confirm it.
- **RemoteProfile:** EnvironmentId, backend kind, exact HTTPS origin/path/port, authenticated instance identity evidence, credential reference in Android Keystore-backed storage, TLS/trust policy, configurable advanced endpoint and safe reconnect metadata. Never persist passwords/cookies/passkeys in ordinary snapshots, logs, metadata exports or Chunks-to-Chunks bundles. Server address or path changes require rediscovery and identity verification.
- **TorrentIdentity:** exact infohash-v1 and/or infohash-v2 when verifiable, private flag and metadata availability, plus backend-native job ID as an *adapter-local locator*. For hybrid torrents preserve both hashes; do not collapse v2 into a fabricated v1 hash. Missing metadata is provisional; do not treat a name or temporary server integer as a verified portable identity.
- **JobKey:** EnvironmentId plus exact verified torrent identity, with a scoped provisional registration handle only until identity verification. Same content on two environments is **two jobs**. Native numeric IDs, display aliases and current Combined selection cannot be cross-environment authority.
- **Snapshot:** scoped profile, generation/cursor, observed-at time, read coverage (full/paged/delta/partial), possible stale/offline state, normalized fields with explicit unknown vs absent semantics, and per-field provenance where relevant. Incomplete snapshots cannot assert complete totals or negative absence.
- **CapabilityReport:** backend version, API/protocol version, feature identity, supported/unsupported/unknown, reason, permission and verification state, timestamp/expiry. Capability reports are observations, not grants or proof a mutation succeeded.
- **OperationReceipt:** durable operation ID; exact JobKey/profile and user-authorized request; preconditions; requested -> acknowledged -> independently observed/verified or uncertain/failed stages; redacted transport evidence; retry and reconciliation classification. ACK/HTTP 200 is not success.

Persist stable profile IDs and Chunks-local presentation/rule associations independently of adapter libraries. All migrations are versioned and rollback-aware. Never change profile identity as a side effect of changing client type, URL, reconnecting or restoring app state without an explicit reviewed migration; do not silently repoint existing jobs.

## Adapter contract and architecture

Keep shared domain and query rules independent of HTTP, JSON-RPC, XML-RPC, cookies, SCGI and particular server filenames. A small Kotlin interface should conceptually expose:

1. **probe(profile):** bounded read-only authentication/version/instance and capability discovery; report specific setup failures. Auth and transport remain adapter-private.
2. **observe(profile, cursor?):** fetch full or incremental per-profile job snapshots with freshness/completeness and exact identity. Normalize statuses/units without inventing values.
3. **readJob(profile, exactIdentity):** obtain fresh details/file list/metadata and supported server-side settings. Never substitute a fuzzy filename match.
4. **prepareAdd(profile, source, Place, reviewed options):** validate source, destination, private metadata, duplicates and supported options; may return incomplete or blocked plan without mutating.
5. **execute(request):** typed operation for Add, Start, Stop, priority, label, verified setting, job-only Remove or separately confirmed Remove+Data. Backend alone is mutation authority for the selected exact profile.
6. **reobserveAndReconcile(receipt):** fresh exact-target observation and outcome classification, especially after timeout, lost session, process death or partial batch. Do not blindly replay uncertain mutators.
7. **metadataCapability(profile, job):** expose whether a verified original .torrent, magnet or sufficient infohash/trackers can be obtained for Chunks' independent sharing and job handoff.

This is a semantic contract, **not** a mandate to add seven methods or create parallel storage immediately. Reuse existing interfaces/coroutines/models where correct; a vertical adapter implementation should drive the smallest necessary extraction. Typed per-backend extension capabilities are preferable to bloating every shared transfer object with nullable flags. Normalized read models do not become command executors. A transport/session service can be shared across adapters only when behavior and security semantics truly match.

Keep the local device-local backend as another environment in the same observation/query/rules architecture while preserving its distinct native engine lifecycle and filesystem grants. The same shared Add/Details/Transfers/Options presentation must work in local-only, remote-only, Combined and no-profile states.

## Remote directory provider extension (planned, rTorrent first)

A profile may independently support `directory.list` and `directory.createChild`; absence must not break Add, defaults or ordinary remote torrent controls. Expose an exact-profile, typed `RemoteDirectoryProvider` returning server-verified canonical parent/root/child directory entries and permission/provenance, with bounded pages and response-generation fencing. Route ruTorrent `_getdir` only when that exact authenticated instance/tenant's plugin is proven accessible; it is separate from XML-RPC and generally **does not prove** folder creation. For create, require a separate narrowly authorized typed provider, server-side root/symlink containment, one-child validation, fresh read-only postcondition and uncertain-write reconciliation. `execute.*` and shell command execution are prohibited as automatic browse/mkdir fallbacks. Unknown/unsupported modes retain manual absolute path entry and the original per-profile default resolver. [Canonical feature contract](REMOTE-DIRECTORY-BROWSER.md), [design](design/REMOTE-DIRECTORY-BROWSER.md) and [#1680](https://github.com/gamesbyian/Chunks/issues/1680).

## Initial protocol and compatibility research matrix

These are **investigation targets, not claims of shipped support**. Version ranges must be pinned by tests before advertising compatibility.

| Adapter | Native surface / handshake | Known seams requiring explicit handling | First-pass scope |
| --- | --- | --- | --- |
| rTorrent (existing) | HTTPS XML-RPC/SCGI gateway, verified exact instance/route; version and method probing | ruTorrent gateways, provider paths, Basic/Digest/TLS, method variants, uncertain writes, Remove+Data provider plugin distinction | Preserve present implementation and receipts |
| Transmission | JSON RPC over HTTPS; session/version read, 409 X-Transmission-Session-Id renewal; optional Basic auth | Old bespoke kebab/camel RPC versus Transmission 4.1+ JSON-RPC 2.0 / snake_case; torrent numeric ID not portable identity; non-destructive remove flag false | First new adapter candidate |
| qBittorrent | WebUI API v2, cookie login, version endpoints and sync/maindata cursor | HTTP verb requirements, 403/ban/CSRF/reverse proxy, login body may fail with HTTP 200, full vs delta inventory, add/duplicate semantics, deleteFiles=false, category automatic-management side effects | First new adapter candidate |
| Deluge | Deluge Web JSON-RPC at configured /json and authorized connected daemon | Web session login, daemon selection, host_id scope, version/API method variability; native daemon transport is separately deferred | Third candidate |
| µTorrent / BitTorrent WebUI | Token/cookie HTTP WebUI protocol | Token and legacy API variants, authentication/TLS reachability, limited metainfo/verification APIs | Demand/maintenance gate |
| BiglyBT / Vuze | Explicit plugin/remote APIs or compatible selected RPC | No universal built-in API assumption; plugin install/availability, credentials and capability variance | Exploratory |
| Synology Download Station / others | Vendor-specific documented API | May be a download manager with non-equivalent BitTorrent/file semantics; permission/path and torrent identity proof | Exploratory |

**Transmission canonical spec:** https://github.com/transmission/transmission/blob/main/docs/rpc-spec.md . Transmission 4.1+ RPC v6 introduced JSON-RPC 2.0 and snake_case, with older RPC compatibility currently deprecated. The 409 session-ID negotiation is a protocol-defined pre-execution rejection: bounded retry with the supplied token is permissible only after validating that specific response and exact same origin/session. Do not generalize it to retrying uncertain Add/Remove timeouts.

**qBittorrent official API:** https://github.com/qbittorrent/qBittorrent/wiki/WebUI-API-%28qBittorrent-5.0%29 . API v2 cookie authentication, separate status/result validation, sync cursor and version-specific endpoints. Some category/auto-management operations can move **payload files**; never use them implicitly as Chunks-local tags/routing. Treat UI-origin/CSRF expectations and private reverse proxies explicitly.

**Deluge official Web API:** https://deluge.readthedocs.io/en/latest/reference/webapi.html . The Web API is JSON-RPC v1 at /json and can proxy methods to a selected daemon after web authorization. Flood's experimental Deluge implementation may use native daemon/Rencode instead; that is a **different transport**. Prefer the existing owner testable HTTPS web entry point first.

**Other surfaces:** test each against primary vendor protocols and active versions before writing production adapters. New supported backend names require measured test coverage and a security/maintenance owner, not just a row in this table.

## Shared user journey, defaults and UX

One **Connections** destination and one Add screen show available environments with their saved names, colors and honest connection state. Adding a profile chooses the backend type (or bounded read-only autodetect when unambiguous), service URL, username/auth method, optional advanced path and default Place. Never import another application's saved credentials or silently scan the LAN/hosts. On connect, show observed version/instance and capability restrictions. On disconnect, preserve last-known snapshots with age and reconnection action, without suggesting running torrents stopped.

All-environment Transfers uses the same search, sort, filters, compact rows, Details, disclosure, selection and action language. In a mixed-capability batch, preview supported/unsupported actions **per selected JobKey** and never silently redirect a command to another engine. Settings that apply to all are centrally owned by Chunks; backend-specific controls appear under the exact environment's advanced settings. Artwork/metadata enrichment is one independent, automatic, consented service over the shared observed inventory, with per-job corrections and exclusions.

**Add:** accept local .torrent, remote .torrent URL and magnet, preserving user-selected source-file handling (Keep/Archive/Delete) and per-transfer media-enrichment exclusion under the existing contract. Resolve a real backend-specific Place; show any unavailable priority/ratio/speed options and how they will be enforced (native, Chunks-connected only, pending, unsupported). The Combined view alone never implies Add target.

**Smart routing and job-only Move:** one classifier and rule evaluator chooses an explicit environment based on verified file metadata, not the adapter. For metadata-poor magnets, preserve the review and provisional status; select a permitted temporary environment only when configured, then offer or enact a pre-authorized *job-only* reroute after identification. Target Add requires exact registration/readback; separately approved retirement removes only the **source job**, preserving source payload bytes. Portable Chunks display alias, artwork/metadata/custom selections, enrichment exclusion and filename rename *intent/mapping* follow the job. Actual physical renames do not occur as part of Move. Offer an independent per-move choice to carry or omit speed, ratio and seeding rules, default omit unless the user saved another preference; show unsupported/unapplied values. A target may download the payload again. Never use a remote file move/rename/category-change API to accomplish this workflow.

**Sharing:** when source metainfo can be obtained safely (archived .torrent, source original, authorized server export), prefer verified exact metadata. Otherwise provide a suitable private-aware magnet where possible. Missing trackers/private passkeys, v2/hybrid identity or hash-only metadata must not be guessed. Chunks' optional portable artwork handoff stays gated by its own media contract.

**Accessibility:** identical action placement, labels and feedback regardless of backend; target labels independent of color, explicit unsupported-state explanation, usable TalkBack focus and 44dp touch targets on owner handset. Future TV UI is a separate presentation over this same tested core, never a reason to fork backend logic.

## Capability catalogue, defaults and safety constraints

Define versioned capability identifiers rather than assuming a flat supported=true:

- connectivity/read: connect, full-list, incremental-list, details, file-list, trackers, verified torrent identity, original-metainfo export, magnet/source retrieval;
- job actions: Add magnet, Add metainfo, Add URL where native (or Chunks-approved HTTPS fetch), Start/Stop, priority, sequential/queue ordering when supported, session-only Remove, **separate** Remove+Data;
- job policies: per-job speed limits, global speed limits, per-job ratio/seed duration, labels/tags, location selection; mark server-enforced vs Chunks-connected-only vs unsupported and their units;
- data paths: optional authorized remote file-byte stream, rename backing paths, external storage tools; **none implied merely by basic RPC**.

Each operation requires an exact profile and exact target identity, fresh enough preconditions, explicit user authorization for destructive effects, and an allowed capability. Typed Remove keep-data is never implemented by invoking the delete-data endpoint with a falsey/default guess; prove the backend's argument semantics. Server-side category/automanagement settings that relocate files must be treated as payload operations, separately reviewed or disabled.

Separate transport authentication, authorization, capability availability, user preference, operation acknowledgment and independently verified postcondition. Use the Chunks durable uncertain-outcome journal; reconnect triggers read-only reconciliation, not mutation replay. No automatic HTTPS-to-HTTP downgrade; no cross-origin redirects or credential forwarding. Do not log passwords, tokens, complete private magnet links, announce passkeys, raw torrent inventories or unredacted remote paths. Credentials must be scoped per exact profile, including seedbox numbered instances.

## Existing Chunks migration strategy, risk controls and rollout

0. **No-feature design/read-only inventory (now):** record actual source classes, domain DTOs, rTorrent coupling, credential/HTTP stack, existing fixtures and already-green UI flows. Identify shared seams, not a replacement subsystem. Avoid source edits that collide with current UX PRs.
1. **Conformance contract and fixture kit (after gates):** implement common *tests* and a compact capability/observation/receipt boundary first; baseline existing phone/rTorrent against them. Initial state-mapping should be read-only.
2. **Behavior-preserving rTorrent extraction:** migrate one operation at a time through the seam with identical exact-profile, auth, Stop/Start, Add, priority, session Remove and Combined behavior. Maintain a compatibility facade where necessary; do not re-key saved profiles or reset credentials. Capture old/new snapshot equivalence and real-host tests on the **exact** source head.
3. **First new backend end-to-end:** choose Transmission or qBittorrent by supported, owner-authorized live fixture readiness; default sequence Transmission then qBittorrent as #660, but permit inversion with documented evidence. Deliver connect -> full inventory -> Add -> Start/Stop -> priorities -> session-only Remove -> reconnect -> shared Combined/Details -> durable uncertain recovery, with actual peer/piece evidence. Do not claim partial screens as a shipped client.
4. **Second independent adapter:** use the other of Transmission/qBittorrent to prove no first-adapter assumptions leaked into core. Add Deluge Web next, only after equivalent gates.
5. **Scale by demand:** µTorrent, BiglyBT/Vuze, Synology and others are individually approved only with maintained specs, supportable API/auth, risk review, fixtures and tests. Do not build a general plugin runtime or in-app arbitrary scripts as a prerequisite.

Prefer direct Android HTTP clients and small protocol serializers/adapters over bundling Flood's Node server, a client daemon, or large all-in-one libraries. If an audited small permissively licensed library reduces work and size safely, record version, maintained status, vulnerabilities, exact provenance, notices and test delta before choosing it. Any shared adapter extraction must keep rTorrent release branches usable through phased work, not a single large rewrite.

## Acceptance matrix and CI budget

| Acceptance slice | Required evidence before claiming support |
| --- | --- |
| Connect/auth | Valid HTTPS endpoint; wrong password/403/401; expired cookie/session/CSRF; bad cert, redirect, hostname/path confusion; version mismatch; same-host multiple distinct instances |
| Inventory | Empty and 100+ torrents, incremental vs full resync, duplicate infohash across profiles, v1/v2/hybrid, partial/paged/stale/offline, restart; independent exact-hash readback |
| Add | Real .torrent/magnet and approved HTTPS source with actual peer/piece progress; no accidental auto-start; duplicate detection; target Place and private metainfo protected |
| Controls | Fresh targeted Start/Stop/priorities; actual file priority effect where possible; native/unsupported ratio/speed states; mixed-backend batch partial outcomes |
| Remove | Keep-data retains byte-for-byte payload and other jobs; Remove+Data is explicitly separate and requires scoped permission and independent file-absence/foreign-file protection proof |
| Recovery | Timeouts after potential commit, lost cookie/409, process death, reconnect, cancelled requests; read-only reconciliation with no duplicate Add/Remove or wrong-server fallback |
| UX | local-only, one remote, multiple remote, mixed local/remote, unsupported capability and account lock/repair; single UI controls and accessibility semantics |
| Routing/Move | Metadata-late magnets, user-defined type rules, exact target creation, job-only retirement, source bytes untouched, portable customization restoration, opt-in rules carried only where supported |
| Security | Secret redaction, no plain HTTP fallback, SSRF/origin restrictions, passkey safety, no remote byte access inference, protected fixtures non-destructive by default |

Testing strategy: pure schema/adapter conformance tests and adversarial recorded fixtures on every PR; reuse one common Android/UI acceptance suite with one parameterized adapter fixture instead of six duplicated suites; API-specific negative tests per backend; hermetic disposable actual servers in a manually batched integration matrix; protected owner Seedhost **read-only** by default, narrowly scoped writes only in guarded trusted-main manual workflows. Reuse [REAL-TRANSFER-ACCEPTANCE](REAL-TRANSFER-ACCEPTANCE.md), [CI-POLICY](CI-POLICY.md) and [QUALITY-LEDGER](QUALITY-LEDGER.md). No always-on per-adapter CI fan-out; execute impacted quick contract checks on PRs and collect expensive installed/live journeys at milestones or protocol-sensitive changes. Record exact head, server version, service route (redacted), fixture identity, actual bytes/pieces and failed/uncertain outcomes. No existing rTorrent evidence is invalidated by designing this abstraction.

The existing [Seedhost fixture declaration](ANDROID-TV-AND-REMOTE-BACKENDS.md#available-future-real-server-test-fixtures-owner-confirmation-2026-10-29) records three owner-configured future endpoints for Transmission, qBittorrent and Deluge, with username/password **secret names**, not exposed values. Service URLs may be UI routes, not API paths. Test actual reachable API and versions independently under secrets/permission gates; do not run destructive tests against pre-existing torrents.

## Prior art, provenance and dependency strategy

Study patterns and **write independent Kotlin implementations unless a code-level license audit approves copying**:

| Project | Reuse research | License/reuse consequence |
| --- | --- | --- |
| [Flood](https://github.com/jesec/flood) | per-client request manager/gateway/normalizer; rTorrent, Transmission, qBittorrent integration fixture design; documented Deluge experimental limitations | GPL-3.0 project; importing files into a combined distributable requires deliberate GPL compatibility/compliance, not merely a credit |
| [Transdroid](https://github.com/erickok/transdroid) | Android adapter interface, endpoint/session variants, auth, many-client UX and failure cases | GPL-3.0 project; study or explicitly audit any copied implementation |
| [Electorrent](https://github.com/tympanix/Electorrent) | multi-client command mapping and server compatibility alternatives | GPL-3.0 project; same audit boundary |
| Vendor APIs (Transmission, qBittorrent, Deluge and others) | source of truth for protocol/auth/method semantics; versioned compatibility tests | Specifications and interfaces can guide independent implementations; examine sample-code license before copying |
| Existing Chunks/libtorrent4j/rTorrent implementation | preserve already accepted core semantics and infrastructure | inspect dependency and asset licenses before distributing APK |

For each imported source/asset/dependency record upstream URL, commit/version, file path, copyright holders, SPDX/actual license, modifications, attribution/NOTICE/source-offer obligations, compatibility and SBOM entry. **Owner intent:** distribute Chunks freely with no proprietary claim to original Chunks work, while accurately crediting third-party contributors and following every applicable license. See [LICENSING-AND-ATTRIBUTION](LICENSING-AND-ATTRIBUTION.md) for the project-wide policy; user intention alone cannot waive another contributor's copyright, and credit alone does not satisfy GPL source/distribution conditions. License selection and notices for the eventual distributable must be completed with the audit, not guessed from public repository visibility.

## Implementation/evidence status and related artifacts

**Currently implemented:** local libtorrent4j and rTorrent slices, shared environment/Combined UI and other existing Chunks behavior **only to the extent separately accepted** in [SPEC-COVERAGE](SPEC-COVERAGE.md)/[QUALITY-LEDGER](QUALITY-LEDGER.md). **Not implemented:** new Transmission/qBittorrent/Deluge/µTorrent/BiglyBT/Synology adapters or universal conformance suite; this document is not acceptance evidence. The active execution ordering is [WORK-QUEUE](WORK-QUEUE.md); no new tasks should be dispatched from this roadmap ahead of rTorrent and current UX architecture. Tracking: [#660](https://github.com/gamesbyian/Chunks/issues/660). Product/UX: [UNIFIED-ENVIRONMENTS](UNIFIED-ENVIRONMENTS.md), [UX](UX.md). Source intake: [ADD-SOURCE-INTAKE](ADD-SOURCE-INTAKE.md). Rules: [SMART-SEEDING-RULES](SMART-SEEDING-RULES.md), [SMART-ROUTING-AND-SOURCE-LIFECYCLE](SMART-ROUTING-AND-SOURCE-LIFECYCLE.md). Job moves: [CROSS-ENGINE-TRANSFER-JOBS](CROSS-ENGINE-TRANSFER-JOBS.md).
