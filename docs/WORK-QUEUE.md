# Chunks active engineering work queue

**Purpose:** one concise, executable set of current work priorities. **Scope:** app development, QA, release; font work is separately governed by [font lab operations](design/CHUNKS-FONT-LAB-OPERATIONS.md). **Status:** reconciled with live GitHub for #511 on 2026-10-29 UTC. **Refresh rule:** GitHub PR/issue/check state is authoritative at the instant of work. Never assume a date-stamped status below is still live.

[Agent instructions](../AGENTS.md) · [Documentation index](INDEX.md) · [Product contract](PRODUCT.md) · [Coverage gaps](SPEC-COVERAGE.md) · [Evidence](QUALITY-LEDGER.md) · [Documentation authoring standard](DOCUMENTATION-STANDARD.md)

## Newly specified advanced-operations backlog (lower than current P0/P1)

The 58 previously under-specified ecosystem items are assigned to canonical contracts: [intake/queue/policies](INTAKE-QUEUE-AND-POLICIES.md) (21), [files/protocol/diagnostics](FILES-PROTOCOL-AND-DIAGNOSTICS.md) (27) and [remote/integrations/preferences](REMOTE-INTEGRATIONS-AND-PREFERENCES.md) (10). **Specification does not imply shipped feature parity.** The following independently executable slices do not replace higher-priority real-phone/Seedhost/release acceptance and do not duplicate the separate Smart Seeding Rules lane.

1. **Pure policy/intake model:** bounded URL metainfo and optional source normalization, queue priorities and stalls, timezone/DST calendars, quota counters, VPN-required fail-closed states. Test secrets, conflicting policy, counter resets and uncertainty before native integration.
2. **Read-only diagnostics/inspection:** piece, peer, tracker/NAT and sampled traffic with source/timestamp; checksum/sidecar inspection and archive/path/link preflights. Test untrusted data, filesystem permissions, idempotency and unsupported servers without arbitrary remote write probes.
3. **Portable settings and expert UX:** versioned redacted server/profile export, import preview and identity mapping, responsive expert columns, adaptive remote read-only polling and help/report redact-by-default. Prove no secret disclosure or remote auto-mutation after restore, then validate 320×700, 412×892, 200% font and TalkBack.
4. **Opt-in external adapters:** feed/indexer sources, verified media completion handoff, independent cloud backup and advanced peer/protocol settings only once source/service-specific safety and capability evidence exist. No **broad or first-class privileged** seedbox admin panel is authorized; limited secondary Server tools are separately approved under D-SEEDBOX-TOOLS-20261029.

**Owner scope is now decided:** integrated discovery with a master disable switch, and secondary limited server tools, are binding under D-DISCOVERY-20261029 and D-SEEDBOX-TOOLS-20261029. The implementation slices and acceptance are below; unrestricted privileged host management remains out of scope. Record completed code slices in SPEC-COVERAGE/QUALITY-LEDGER, not another running plan.

## Approved Discovery and secondary Server tools: implementation slices

These are requirements under [DISCOVERY (intake contract)](INTAKE-QUEUE-AND-POLICIES.md#integrated-torrent-discovery-and-its-master-off-switch-owner-binding) and [SERVER TOOLS (remote contract)](REMOTE-INTEGRATIONS-AND-PREFERENCES.md#secondary-seedbox-tools-owner-binding), **not** evidence or a mandate to displace current P0/P1 work. No new app-level cloud infrastructure is authorized.

1. **Discovery state and off switch (independently executable, before network connector):** persistent default-off state, accessible Settings → Discovery master control, source-level consent, race-safe dispatch gate, cancellation of scheduled jobs and auto-intake on disable, preservation of existing transfers and manual Add. Gate at every **discovery-triggered** worker, network action and exact target Add submission, while preserving separately authorized direct automatic local-file intake and ordinary manual Add. Verify zero disabled egress, process death/restore/reenable, cancellation race and redaction.
2. **Integrated discovery UI and adapters:** native search screen and in-Add Find torrents entry, normalized public/private provider adapter results with origin, filters, dedupe, saved searches and RSS/Atom subscriptions. Use authenticated provider fixtures; independently opt-in auto-add and auto-start, source limits and safe stale-backlog handling. Verify existing Add scope, provider privacy and no mandatory account; test 320×700, 412×892, 200% type/TalkBack. Do not bypass CAPTCHA or deploy a central search backend.
3. **Read-only server tools:** Servers → named remote → Server tools, with provider capability model, separately authenticated nonprivileged service/health/quota endpoints, source/freshness labels, local-only absence and strict screen real-estate regression checks.
4. **Optional limited service controls:** enable per-profile only with separately verified provider permission, fixed allowlist, impact warning for live torrents, confirmation and postcondition/reconciliation. Test unsupported managed hosts, independent tenants, disconnect mid-restart and prohibitions on generic shell/reboot/firewall/user/package operations. Keep server administration out of default notification/background monitoring.

Do not let these slices create admin-focused primary navigation or silently reenable discovery through import/auto-start. The existing seeding rules, remote Smart Connect and unified local/remote acceptance remain separate prerequisites.

## Planned slice: Smart Seeding Rules

Implement [SMART-SEEDING-RULES](SMART-SEEDING-RULES.md) in independently testable stages once conflicting active PRs and ownership have been checked: pure policy model and deterministic tracker matching; stable rule identities, generated/editable names and explicit multi-environment selection; per-profile capability/readback adapters; Add-route preview and guarded start; opt-in unattended import hold/fallback; scoped batch application and accessibility/host acceptance. Tests must distinguish intended rule selection from runtime-enforced policy. This is planned work, not a new P0 or an implementation claim.

## Planned recovery slice: relocated or renamed transfer backing files

Develop [TRANSFER-FILE-RECOVERY](TRANSFER-FILE-RECOVERY.md) behind verified local/remote filesystem and engine capabilities: read-only diagnostics → bounded candidate discovery → content verification and unambiguous mapping → preview and user-approved non-destructive rebind/linking → hash recheck and confirmed resumption. Keep ahead of cosmetic organization work when independently executable; do not claim feature support or use unsafe rTorrent RPC shortcuts without actual capability tests.

## Planned organization slice: Plex filename repairs

Under existing organization priority and ownership, implement [PLEX-FILENAME-REPAIR](PLEX-FILENAME-REPAIR.md) in stages: deterministic conservative analyzer/minimal character-span diffs, batch review, alias-vs-physical-vs-published-copy distinction, safe exact-scope execution and recovery. Do not strip codec/release metadata or claim alias changes affect Plex; no backing-file mutation without engine mapping/integrity proof. Defer physical operations until corresponding storage adapter acceptance is available.

## Planned slice: outbound file Open with and remote on-demand file access

Implement incrementally under [EXTERNAL-FILE-ACCESS](EXTERNAL-FILE-ACCESS.md): (1) typed per-Place local/remote read capability and separation from rTorrent RPC, (2) safe Android content-provider Open/Open with on completed local files, (3) bounded remote reader with random byte ranges and secure temporary URI, (4) on-device recipient, seek/cache/expiry and approved server tests. Choose only when higher-priority unclaimed work or its prerequisites permit; do not treat documentation as verified implementation or bypass existing CI/agent-lane ownership.


## How agents select and complete work

1. Inspect current main, open PRs, claimed lanes, relevant issues and exact-head CI. Resolve obvious red integration issues before starting another overlapping change.
2. Choose the highest-priority unclaimed, independently testable slice below; bypass work blocked on a device, secret, vendor, permission or human signoff, and take the next viable task.
3. Read its canonical contract **before** changing code; preserve engine/profile identities, explicit target, privacy and no-data-loss invariants. An unknown capability fails closed.
4. Implement on a focused app branch, test locally first and use the [lean CI policy](CI-POLICY.md). Font changes remain exclusively on `font/lab`, with no per-glyph PRs or Actions fan-out.
5. Merge only if exact-head required tests and independent review/reconciliation are qualified. Record commit, environment, observable result and remaining tier gaps in [QUALITY-LEDGER](QUALITY-LEDGER.md), update [SPEC-COVERAGE](SPEC-COVERAGE.md) if scope changed, then update this queue. Do not turn successful tests into claims of handset or live-server acceptance.

## Create & Share development lane (active, lead app agent)

The required [Create & Share contract](CREATE-AND-SHARE.md) is **partly implemented**. Done: a pure-JVM metadata builder (#542) and the Create screen with file/folder input, review, Save/Share/Copy (#545). In review: the Share → Create entry, seed a verified copy on the phone (loopback peer download byte-exact) and sharing the .torrent file. Next: remote seed target with actual-byte verification; then consented public tracker/DHT health; then credential-vault-backed service adapters and controlled authenticated index publishing. Each slice must update [SPEC-COVERAGE](SPEC-COVERAGE.md) and [QUALITY-LEDGER](QUALITY-LEDGER.md) with actual evidence and pass existing safety gates. Do not displace active P0 recovery/reliability tasks or spawn broad CI for documentation. Registry/service integrations are bounded by provider APIs and approved test identities.

## Execution priorities

| Priority | Outcome / next executable slice | Authority and issue | Acceptance / stop rule |
|---|---|---|---|
| **P0** | Reliable local real-peer download/seed on a physical Android device, including foreground/background, network transitions, Doze/OEM and restart; investigate intermittent post-flush byte mismatches on real devices | [Local engine #5](https://github.com/gamesbyian/Chunks/issues/5), [integration QA #9](https://github.com/gamesbyian/Chunks/issues/9), [BUILD](BUILD.md) | Record real piece-hash, resume and lifecycle evidence; JVM/API35/loopback is not physical proof |
| **P0** | Full phone-to-development-Seedhost rTorrent Add/list/control/remove/reconnect journey with exact target and uncertain-write readback | [Remote core #4](https://github.com/gamesbyian/Chunks/issues/4), [SECURITY](SECURITY.md), [SEEDHOST](SEEDHOST-REMOTE-TESTING.md) | Exact-hash stopped Add, verified parent Place, controlled Start/Stop, session-only removal, postconditions; no blind retries |
| **P0** | End-to-end shared local/remote navigation and scope parity: local-only, remote-only, combined with at least two remote profiles | [UNIFIED-ENVIRONMENTS](UNIFIED-ENVIRONMENTS.md), [UX](UX.md) | Shared controls, visible environment origin, per-profile settings and list state, non-destructive enable/disable; 412×892, 320×700, 200% font, TalkBack and keyboard |
| **P1** | Smart Connect: endpoint capability ranking, actionable repair, advanced custom endpoint, real-host/proxy/provider compatibility | [Smart Connect #464](https://github.com/gamesbyian/Chunks/issues/464), [contract](SMART-REMOTE-CONNECTION.md) | Exact instance boundary, HTTPS, no credential leak or cross-tenant downgrade; real provider + installed Android evidence; unsupported honest |
| **P1** | Finish in Order: global disclosed presets, controlled restart/process-death recovery, capability-aware remote behavior and independent performance acceptance | [Finish in Order #415](https://github.com/gamesbyian/Chunks/issues/415), [PRODUCT](PRODUCT.md), [ARCHITECTURE](ARCHITECTURE.md) | Front-file hash-verified completion, pinned overrides, no dishonest ordering guarantee; distinguish already merged features from outstanding tests |
| **P1** | Public phone backing destinations/Places with reliable torrent random I/O, recoverable grants, SD/volume changes, safe in-progress file handoff | [Storage #5](https://github.com/gamesbyian/Chunks/issues/5), [operations #7](https://github.com/gamesbyian/Chunks/issues/7), [STORAGE](STORAGE-AND-EXTRACTION.md) | Verify seedability, provider/SAF limitations, collision, permission revoke, crash and file ownership; don't conflate library copy with backing move |
| **P1** | Storage budgets and archive safety: actual accounting, conservative write gating, cleanup dry run/recovery, real-provider ZIP extraction acceptance | [Storage #70](https://github.com/gamesbyian/Chunks/issues/70), [contract](STORAGE-AND-EXTRACTION.md) | No automatic deletion of seeding bytes; exact reclaim provenance and destructive confirmations |
| **P1** | Accessibility and release validation: representative real queues, visual atlas, 200%-font/TalkBack, physical handset, signing/SBOM/policy and distribution gates | [UX #8](https://github.com/gamesbyian/Chunks/issues/8), [release #10](https://github.com/gamesbyian/Chunks/issues/10), [BUILD](BUILD.md) | Signed release requires owner-controlled signing material and human physical/visual acceptance; exact SHA receipt required |
| **P1** | Visual pass (owner-authorised 2026-10-29): finish bringing remaining screens onto `ChunksTheme`, shared components and library icons; render-review each at 100% and 200% | [UX design system](UX.md#information-density-and-expressiveness), [UX #8](https://github.com/gamesbyian/Chunks/issues/8), [SVG library](design/svg/README.md) | Specs win over reference sheets; no mockup-only features; AA contrast, text beside every colour state; JVM renders are review aids, not device/TalkBack/200% acceptance or owner visual approval |
| **P1** | Release shrink/minification follow-up only after validating merged R8/qaMinified smoke #511 and current release configuration; assess ABI splits | [PR #511 (merged)](https://github.com/gamesbyian/Chunks/pull/511), [BUILD](BUILD.md), [CI](CI-POLICY.md) | Re-check current main and signed APK path; never infer release shrinking is enabled merely because qaMinified passed |
| **P2** | Multi-source media metadata/artwork, per-format picker, inherited overrides, opt-in external lookup and category-safe generic art | [Artwork #403](https://github.com/gamesbyian/Chunks/issues/403), [contract](TRANSFER-ARTWORK.md) | Source-provenance, permission, category coverage, adult opt-in and offline acceptance; no silent third-party lookup |
| **P2** | Collections, proposed naming, bulk review/undo and optional approved organization rules without unverified physical moves | [Organization #6](https://github.com/gamesbyian/Chunks/issues/6), [PRODUCT](PRODUCT.md) | Scaled lists, abstention on uncertain media, identity-safe undo, no automatic rename of backing data |
| **P2** | Advanced remote filesystem operations only after independent ownership/permission and crash recovery proof; continue narrow compatibility investigation | [Remote storage #2](https://github.com/gamesbyian/Chunks/issues/2), [#3](https://github.com/gamesbyian/Chunks/issues/3), [Alpine #151](https://github.com/gamesbyian/Chunks/issues/151) | Real storage capability required: `d.directory.set` is not file relocation; do not stall the app lane on one rTorrent variant |

## Cross-cutting release gates

- **Identity and state:** Every mutating action targets a precise environment/profile + verified infohash + fresh snapshot. Timeout = uncertain; read-only reconciliation before new write.
- **File safety:** Copy Transfer / Move Transfer are **job-only**; downloaded byte migration, independent library copy, extraction, rename and deletion have different labels, authority and acceptance. See [authoritative cross-engine contract](CROSS-ENGINE-TRANSFER-JOBS.md).
- **Opt-in features:** privacy lock, external enrichment, auto-extraction, physical file mutations and special ordering must follow separate stated consent and capability contracts.
- **Evidence tiers:** pure JVM/model, API emulator, real native engine, disposable rTorrent, approved development Seedhost, real phone/providers and human visual review are distinct, not interchangeable. [Evidence ledger](QUALITY-LEDGER.md).
- **Font isolation:** ongoing typeface development belongs only in `font/lab`, locally validated and promoted in a single reviewed batch; separate app accessibility work may proceed without modifying font sources.

## Current blockers, ownership, and history

**Human/physical gates:** actual supported handset with long-running swarm and lifecycle tests; real remote installed-app validation; physical storage providers; 200%-font and TalkBack; signing keys and authorization for distribution. **Do not idle on these:** continue independent unit/emulator, protocol, documentation and safety work.

**External or specialized lanes:** font #347 is owned by the independent font laboratory and must not be pulled into the app work pool. Existing open PRs are owned by their authors; inspect before branching. Do not create redundant fixes.

**Historical queue:** The complete pre-consolidation queue, including its detailed PR-by-PR implementation notes, has been preserved [verbatim in the dated archive](archive/WORK-QUEUE-2026-10-29-preconsolidation.md). It is evidence/context, **not** another live planning authority. Completed slice evidence belongs in [QUALITY-LEDGER](QUALITY-LEDGER.md). This file is the **only** live work queue.

## Required handoff

Report merged/open PRs; tested head and applicable run IDs; what actually happened at each evidence tier; failures, recovery and explicit unverified gates; changed canonical specs/issues/queue; and the **next executable unclaimed task**. No unsupported "release-ready" assertions.
