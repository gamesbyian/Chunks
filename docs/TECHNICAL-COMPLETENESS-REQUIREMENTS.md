# Technical completeness ledger — detailed product requirements annex

Owner-authorized 2026-11-27 after the product specification review. This is the detailed requirement inventory of [TECHNICAL-COMPLETENESS-LEDGER](TECHNICAL-COMPLETENESS-LEDGER.md), not a second backlog. [WORK-QUEUE](WORK-QUEUE.md) alone sets order; [#2176](https://github.com/gamesbyian/Chunks/issues/2176) tracks closure. Baseline review: production tree at `bf5c4990cab5a4b73677f1bd9af88f09c4565be2`, reconciliation base `3b3e9251c28b825e1b2be801450a5a006e9affc3`. Tree and targeted source review, not a full caller or runtime audit. No new test evidence claimed.

**Initial dispositions:** P = existing slice or foundation, remaining scope or evidence not established; U = no end-to-end implementation identified in initial inventory (not proven absent); V = targeted verification needed before deciding. For each row, before implementation inspect live production callers, exact engine/OS support, tests and owning issue/PR; refine to absent, partial, integrated/acceptance-pending, accepted, proven capability-limited or explicitly owner-deferred. P/U/V do not authorize rebuilding working code or unsupported writes. Add exact source path/entrypoint, evidence tier and remaining scope to the owning issue; update this inventory only on material disposition changes. Evidence resides in PR/issue; no progress diary. A cohort ID is a pointer to the ranked R5 sequence, not a fixed agent lane.

## Product and specialist outcomes

| Cohort | Outcome | Canonical contracts | Initial | Boundary to verify / deliver |
| --- | --- | --- | --- | --- |
| P1 | Safe physical relocation / backing rename / renamed library copies | [TRANSFER-FILE-RECOVERY.md](TRANSFER-FILE-RECOVERY.md); [USER-DIRECTED-FILE-OPERATIONS.md](USER-DIRECTED-FILE-OPERATIONS.md) | P | RelocationPlanner and bounded library publishing; prove before/during/after relocation, separate engine mapping/physical path/alias, exclusive destination, readback, crash recovery and source preservation. #2/#7/#809 |
| P1 | Find files to restore transfer | [TRANSFER-FILE-RECOVERY.md](TRANSFER-FILE-RECOVERY.md) | P | ForeignPartialRecoveryPanel is narrower; find externally moved/renamed bytes, verify torrent hashes, exact relink and resume without undoing user filenames. #7/#5 |
| P1 | External file access, payload copy and cross-client path mapping | [EXTERNAL-FILE-ACCESS.md](EXTERNAL-FILE-ACCESS.md); [CROSS-SEED-AND-PAYLOAD-COPY.md](CROSS-SEED-AND-PAYLOAD-COPY.md) | P | Separate job-only handoff and verified server-to-local peer copy from general byte reader/copy; Open/Open with/Share/Send/export and seekable remote access, explicit download, resume/checksum, no unapproved source deletion. #2/#7/#2178 |
| P1 | Storage budget and cleanup | [STORAGE-AND-EXTRACTION.md](STORAGE-AND-EXTRACTION.md) | P | LocalStoragePanel explicitly advisory; volume/Place budgets, real safe pause/hold preserving seeding, low space, physical reclaim proof, cleanup review and separate seeding-impact consent. #70 |
| P2 | Archive extraction and cleanup | [STORAGE-AND-EXTRACTION.md](STORAGE-AND-EXTRACTION.md); [USER-DIRECTED-FILE-OPERATIONS.md](USER-DIRECTED-FILE-OPERATIONS.md) | P | Bounded owned MediaStore ZIP; explicit modern/legacy multipart RAR4/RAR5, 7z and encrypted format qualification; adjacent auto-extract and early archive-set extraction Options toggles both OFF; whole torrent complete by default, early only independently verified set; expansion bounds, destination conflict/recovery, provenance and separate archive deletion approval. #70/#809 |
| P2 | Fix for Plex / advanced Smart and Bulk Rename | [PLEX-FILENAME-REPAIR.md](PLEX-FILENAME-REPAIR.md) | P | Alias review is not physical filename repair; preserve release/codec/edition tokens, preview exact minimal changes, retain seedable originals where necessary, batch and undo. #7 |
| P3 | Collections, classification, search, naming and relationships | [SHARED-MEDIA-IDENTITY-AND-SIMILAR-TRANSFERS.md](SHARED-MEDIA-IDENTITY-AND-SIMILAR-TRANSFERS.md) | P | Manual collections and SimilarTransfersHost exist; cross-media graph, member evidence, hierarchy, batch suggestions, independent file-wide search, confidence and no silent mutation. #6/#403 |
| P3 | Artwork breadth and role-specific overrides | [TRANSFER-ARTWORK.md](TRANSFER-ARTWORK.md) | P | Provider enrichment and overrides exist; verify category breadth, square/poster/banner independent roles, inheritance preview and future matches, optional GIS/adult consent, cache and manual overrides. #403/#1453; extra editorial catalogs remain deferred where already classified. |
| P4 | Finish in Order, Smart Seeding and effective policy explanations | [SMART-SEEDING-RULES.md](SMART-SEEDING-RULES.md); [EFFECTIVE-POLICY-EXPLANATIONS.md](EFFECTIVE-POLICY-EXPLANATIONS.md) | P | Five-client core exists; inspect remaining named group matching, Balanced/Finish First/Sequential Playback and pin/reorder/group dependencies, exact precedence, caps and supported outcomes; no invented rTorrent cap. #415/#2179 |
| P5 | Discovery and intake | [INTAKE-QUEUE-AND-POLICIES.md](INTAKE-QUEUE-AND-POLICIES.md); [ADD-SOURCE-INTAKE.md](ADD-SOURCE-INTAKE.md) | P | DiscoveryGate exists, not an entire discovery journey. Search attributed provider results, filters/saved search, RSS history, notifications, source auth, watch/QR, independent Add and Start consent, Off stops requests and auto intake. #2177 |
| P5 | Smart Routing and conditional intake | [SMART-ROUTING-AND-SOURCE-LIFECYCLE.md](SMART-ROUTING-AND-SOURCE-LIFECYCLE.md) | P | Existing suggestions and opt-in preselection never grant automatic Add; integrate only separately authorized unattended intake with exact Place/client/Start, no implicit job retirement. #6/#2177 |
| P6 | Create & Share and transfer sharing | [CREATE-AND-SHARE.md](CREATE-AND-SHARE.md); [TRANSFER-SHARING.md](TRANSFER-SHARING.md) | P | Local builder and batch magnet export exist; verify existing-job Share/Copy magnet and verified .torrent, exact identity/private disclosure; remote seed capability, separately authorized publish, optional tracker/browser/feed only if independently proven. #2178 |
| P6 | Cross-seed and storage synchronization | [CROSS-SEED-AND-PAYLOAD-COPY.md](CROSS-SEED-AND-PAYLOAD-COPY.md); [EXISTING-SERVICE-INTEGRATIONS.md](EXISTING-SERVICE-INTEGRATIONS.md) | U | Verified reuse candidates, no silent registration, bounded user-initiated payload copy and independently authorized synchronization. #2178 |
| P6 | Event automation and human tasks | [EVENT-AUTOMATION.md](EVENT-AUTOMATION.md); [TASK-OUTCOMES-AND-CONTINUITY.md](TASK-OUTCOMES-AND-CONTINUITY.md) | U | When/If/Then, saved filters, read-only reconciliation and user-marked outcomes; optional task-free ordinary path, no unknown-write replay, no deletion through task archive. #2178/#2179 |
| P6 | Existing service connections, downstream delivery and secondary tools | [EXISTING-SERVICE-INTEGRATIONS.md](EXISTING-SERVICE-INTEGRATIONS.md); [EXTERNAL-DESTINATION-DELIVERY.md](EXTERNAL-DESTINATION-DELIVERY.md); [REMOTE-INTEGRATIONS-AND-PREFERENCES.md](REMOTE-INTEGRATIONS-AND-PREFERENCES.md) | U | Prowlarr/Torznab, Plex/Sonarr/Radarr, authorized Drive/FTP-family delivery, separate credentials, resource/quota/service status and individually approved service control only. No generic admin. #2178 |
| P6 | Technical media inspection | [MEDIA-INSPECTION.md](MEDIA-INSPECTION.md) | P | Local MediaTrackInspector and MediaTechnicalDetails exist; remote byte access, track/subtitle coverage and optional constrained thumbnails. #403/#2178 |
| P7 | Snapshots, configuration and connection portability | [SMART-REMOTE-CONNECTION.md](SMART-REMOTE-CONNECTION.md); [REMOTE-INTEGRATIONS-AND-PREFERENCES.md](REMOTE-INTEGRATIONS-AND-PREFERENCES.md) | P | Local/rTorrent definition restore and priority/alias paths exist; audit full approved settings and profile round-trip, other typed client support per contract, revocation, no auto replay. #289/#464 |
| P7 | Cross-cutting user journeys | [PRODUCT.md](PRODUCT.md); [UX.md](UX.md); [FEATURE-EXPERIENCE-CONTRACT.md](FEATURE-EXPERIENCE-CONTRACT.md) | P | File-wide browse, consistent operation history/cancel/recovery/conflicts, batch scope, effective settings and defaults, completion actions, notification management, separate integration credentials and accessible discovery; reuse existing owners, no second coordinator. #8/#2176 |
| P7 | Core and release acceptance | [SPEC-COVERAGE.md](SPEC-COVERAGE.md); [RELEASE-ACCEPTANCE.md](RELEASE-ACCEPTANCE.md) | P | Preserve implemented five-client Add, privacy, priority, artwork, resume, source lifecycle and job-only Copy/Move; exact-source proof and physical Pixel/OEM/TalkBack/protected-host/signing separately. #1844/#1786/#569/#10 |

## All 58 adopted advanced requirements

These are the individually adopted Fxx.yy identifiers in the three canonical contracts, not the entire 240-item research catalogue. Original contracts define conditional support, exclusions and defaults. A shared implementation may satisfy multiple IDs only with individually traceable evidence.

### [INTAKE-QUEUE-AND-POLICIES.md](INTAKE-QUEUE-AND-POLICIES.md)

| Requirement | Initial disposition | Queue cohort | Next evidence / missing boundary |
| --- | --- | --- | --- |
| F01.03 — URL metainfo acquisition | P | P5 | HTTPS source exists; verify credential/extensionless HTTP(S) variants without downgrade or source leak. |
| F01.05 — alternative intake channels | P | P5 | Manual intake exists; QR, feed and watched folders each need actual routed entry, dedupe and restart/Off tests. |
| F01.18 — authenticated metadata retrieval | U | P5 | Separate source accounts; authorized retrieval, expired/revoked credentials and redirect isolation. |
| F02.05 — queue ordering | V | P4 | Real queue reordering and filtered bystander isolation. |
| F02.06 — concurrent slots | V | P4 | Native slot-limit readback; explicit effect on existing jobs. |
| F02.07 — stall handling | P | P4 | Configurable stall window with distinct offline, queued and no-peer states. |
| F02.08 — retries | P | P4 | Bounded retry/read-only check with no uncertain-write replay. |
| F02.11 — history | P | P4 | Persistent bounded observed/requested/inferred timeline, retention and privacy. |
| F12.11 — watchdog | U | P6 | Opt-in watchdog only after verified failures, cooldown and no unknown-write replay. |
| F05.02 — alternate-speed calendar | U | P4 | Time-zone/DST schedules, expiry of manual overrides, readback. |
| F05.05 — quota limits | U | P4 | Period accounting, reset and metered/provider distinction, actual enforcement if supported. |
| F05.07 — conditional scheduling and F15.09 ISP/battery/Wi-Fi scheduler | V | P4 | Local Wi-Fi/unmetered/charging/battery/time policy transitions and recovery. |
| F15.09 — conditional scheduling and F15.09 ISP/battery/Wi-Fi scheduler | V | P4 | ISP/battery/Wi-Fi scheduler; Android network/power and process transitions. |
| F05.08 — network interface and ports | V | P4 | Verified bind/listen port and VPN loss/rotation; no peer leak. |
| F05.09 — adaptive tuning | U | P4 | Bounded opt-in preset, measured impact, reversible settings. |
| F05.10 — bandwidth isolation | P | P4 | Exact profile isolation, caps and temporary override with bystander readback. |
| F05.11 — adaptive liveness | P | P4 | Counter-delta stall recovery, opt-in slot/requeue with cooldown. |
| F06.08 — search-source proxy/credentials | U | P5 | Scoped index/feed auth and proxy, revocation and zero cross-credential disclosure. |
| F06.14 — release/quality filters | U | P5 | Explainable quality/language/codec/date filters; positive/negative preview. |
| F07.08 — notes/metadata | U | P3 | Editable private notes and typed annotations, safe export. |
| F07.10 — favorites and recents | P | P3 | Exact-job/Place/view pins and recents, lock/removal and clearing tests. |

### [FILES-PROTOCOL-AND-DIAGNOSTICS.md](FILES-PROTOCOL-AND-DIAGNOSTICS.md)

| Requirement | Initial disposition | Queue cohort | Next evidence / missing boundary |
| --- | --- | --- | --- |
| F03.12 — nested/encrypted archives | P | P2 | Bounded ZIP exists; explicit modern/legacy multipart RAR4/RAR5 set detection/order, nested/encrypted/other supported formats, missing/corrupt/mixed volume refusal, bombs and collision tests. |
| F03.13 — independent checksum and metadata inspection | P | P1 | #2184 merged canonical Local Details inspection of completed wanted files of complete Local transfers (0–64 GiB, two pinned reads, SHA-256/size and 64 KiB inert text previews), hosted JVM/R8, native Add and storage API29/35 regression green. #2185 merged opt-in SHA-1/MD5/CRC-32, exact single GNU/SFV row comparison and weak-algorithm labels (native 439 executed, R8, storage API29/35 green). Continue broader file contexts, authenticated remote bytes, full manifest parsing and physical proof. |
| F03.14 — cross-file pieces and layout | P | P1 | Actual cross-file piece verification plus explanatory skipped-boundary UI. |
| F03.20 — shared backing/link detection | P | P1 | Local inode accounting exists; shared-link risk and remote evidence before writes. |
| F03.24 — remote path translation | U | P1 | Explicit per-profile path mapping with reversible verified roots and escape negatives. |
| F15.07 — piece-aligned creation | V | P6 | Standard-compliant optional alignment only with real interoperability witness. |
| F15.15 — data-preserving cross-seed reuse | U | P6 | Cross-seed candidates require actual hash/layout match and independent authorization. |
| F04.08 — transport and port capabilities | V | P4 | Observed/configured/supported protocol and port distinctions. |
| F04.09 — encryption and tracker trust | V | P4 | Native peer encryption options and independent tracker TLS trust. |
| F04.10 — peers, slots and connection limits | V | P4 | Engine connection/upload slots and safe range/readback. |
| F04.11 — peer management | U | P4 | Capability-gated exact-peer add/kick/ban, no broad accidental blocks. |
| F04.12 — peer filtering/proxy metadata | U | P4 | Opt-in verified blocklists/class filters, provenance and undo. |
| F04.14 — piece-map diagnostic | U | P4 | Bounded piece map with actual availability and verified/skipped legend; not progress chunks. |
| F04.15 — request algorithm | P | P4 | Finish in Order exists; actual algorithm modes only with piece-demand proof. |
| F04.21 — disk I/O controls | V | P4 | Writable disk/cache limits with reset and safe unsupported states. |
| F13.01 — separate proxy paths | U | P4 | Separate proxy routing classes with no silent direct fallback. |
| F13.02 — proxy families | V | P4 | Supported proxy families and route proof; no anonymity claim. |
| F13.03 — VPN binding/kill behavior | V | P4 | Physical VPN-required loss and rotation with verified binding/hold. |
| F13.09 — ban/block policy | U | P4 | Per-client ban/block expiry, readback and overrides. |
| F13.10 — isolation | P | P4 | Existing exact-profile boundaries; test all additional traffic/settings scopes. |
| F13.14 — audit and recovery | P | P4 | Operation receipts exist; bounded redacted audit/export/retention across new actions. |
| F08.07 — port/NAT diagnostics | P | P4 | Port/NAT diagnosis with locality and unknown state. |
| F08.10 — graphs | U | P4 | Timestamped traffic/speed charts, gaps and counter resets. |
| F08.11 — swarm health | P | P4 | Separate connected, tracker and DHT counts and verified-byte growth. |
| F08.12 — health checks | P | P4 | Cancellable staged slow-download diagnostics without unapproved requests. |
| F08.13 — metadata export and change record | P | P4 | Review redacted metainfo/audit export; no accidental private share. |
| F09.02 — seek-driven preview | U | P1 | Verified seek-driven local/remote preview, priority restore and no hidden full download. |

### [REMOTE-INTEGRATIONS-AND-PREFERENCES.md](REMOTE-INTEGRATIONS-AND-PREFERENCES.md)

| Requirement | Initial disposition | Queue cohort | Next evidence / missing boundary |
| --- | --- | --- | --- |
| F10.13 — portable remote profiles | P | P7 | Portable nonsecret profiles and independently approved encrypted recovery. |
| F10.14 — pairing and certificate trust | V | P7 | Secure pairing/cert trust per endpoint, no TLS bypass. |
| F10.16 — access through tunnels/VPN | V | P4 | Existing VPN/tunnel access and precise auth/DNS/TLS failure diagnostics. |
| F10.17 — remote role and privileged operations | P | P6 | Exact tenant roles and separately authorized service controls. |
| F12.08 — completion to media managers | U | P6 | Verified-complete handoff to configured media manager, idempotent and opt-in. |
| F12.12 — external cloud/sync/backup | U | P6 | Independent verified cloud copy, quota/collision/consent and source preserved. |
| F14.03 — expert presentation | P | P7 | Expert layouts and saved visible fields; accessible at large text. |
| F14.10 — refresh and background sync | P | P4 | Adaptive/manual/interval polling, stale/backoff and no mutation retry. |
| F14.11 — preferences import/export | P | P7 | Separate preferences/templates/secrets import/export with diff and versioning. |
| F14.12 — help, reports and updates | P | P7 | Offline help, version/changelog and reviewed diagnostic export. |

## Scope and closure

Preserve explicit future platform/optional expansion: Android TV (#663), other engines beyond five (#660), per-transfer widgets (#689), Companion (#3), separately deferred editorial catalogs (#403), font lab (#347), visual atlas, Expository integration and artwork-sharing bundle until their governing prerequisites/owner promotion. Do not use future expansion as a blanket reason to defer ordinary Android functionality. Resolve ambiguity with the owner; an unavailable safe capability requires actual proof and truthful fallback, not a silent drop. Generic SAF writers/Replace remain disabled until independently qualified; remote folder rename is unsupported under current typed File Manager contract.

Every required row must have a traceable disposition and proof, with functional and experience acceptance separate. Report daily-use preview separately from full approved Android release. Do not claim complete based on empty queue, a screenshot, a model, settings persistence or a green mock. Track newly discovered explicit or implied requirements here and rank their work through the existing queue. No blanket feature promotion outside owner-approved product contracts.
