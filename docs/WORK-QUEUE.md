# Chunks active work queue

**One executable priority source.** The owner's [backend-first mandate](BACKEND-FIRST-COMPLETION.md) and [full Android completion scope](COMPLETION-ROADMAP.md) govern this queue. The 2026-11-19 [source/issue/branch/CI audit](archive/DAY-ONE-REPO-AUDIT-2026-11-19.md) is a **snapshot**, not an alternative backlog; the former queue and all its dated PR/status paragraphs are preserved in [historical queue](WORK-QUEUE-HISTORY-2026-11-19.md). This queue defines actions, dependencies and exit tests, not PR diaries. Evidence belongs in [QUALITY-LEDGER](QUALITY-LEDGER.md).

**Owner's dispatch order:** Protect correctness and confirmed regressions first; converge shared Add; finish unimplemented advanced Android functionality and repeatable real-engine proof; integrate the approved final UI designs; perform one assembled release candidate and distribution check. **Do not wait for a calendar date or for elective UI polish to begin unblocked backend work.** The R0–R6 names below remain aligned with [COMPLETION-ROADMAP](COMPLETION-ROADMAP.md), but R5 technical work runs *before* the final R4 release gate.

## R0. Current ownership, safety and arbitration

1. Re-fetch live main, open PR heads and checks on every dispatch. **Fresh handoff after #1890:** #1888 (app-owned protocol Add attempt), #1889 (shared admission/receipt and stopped-check ordering), #1890 (Compose lazy-container contract), #1892 (shared test helpers) and #1893 (CI handoff) are merged. No PR was left open at this checkpoint; this is a snapshot, not a standing agent lock. Choose one scoped, unclaimed task only after inspecting the current state.
2. Enforce exact approved source, client/account ID, hash, Place, Start, source-file consent and independent postconditions. Preserve one write, uncertain-outcome no-replay fences, authenticated profile isolation and explicit job-only Move and data-delete authorization. A red/old-head/skipped check is never a pass.
3. Before implementing anything, check [technical completeness](TECHNICAL-COMPLETENESS-LEDGER.md), actual source and linked acceptance. The three added remote protocols already have write-capable production adapters and real-daemon proofs. Do not reimplement foundations or the **fixed** `StoppedCheck.begin` ordering (#1889). Independently diagnose the still-open truncated-payload repair flake, owner Pixel intake #1786 and repeated Add artwork opt-out #1838; preserve their historical failures rather than declaring them solved or rerunning at random.
4. Keep historical agent branches until their unique commits, PR ancestry and evidence are assessed; no branch deletion as a proxy for qualification. See [Day 1 audit](archive/DAY-ONE-REPO-AUDIT-2026-11-19.md).

**Exit:** one conflict-free, scoped implementation candidate with exact-source checks and explicitly tracked red/untested gates. The historical Day 1 docs review (#1891) is already merged; do not reopen its branch or create another parallel queue.

## R1. Source-first Add and exact-client daily operation

1. **Finish one app-owned Universal Add lifecycle [#1844](https://github.com/gamesbyian/Chunks/issues/1844):** #1888/#1889 merged retained protocol attempts, shared `UniversalAddAdmission` and shared receipts; Local/rTorrent retain their existing protected writers. **Merged foundation (#1895, `b24980b`):** one app-owned executor/observation lifecycle (`UniversalAddLifecycle` + `UniversalAddAttemptController`) now runs every Local/rTorrent/protocol Add write, and protocol Adds have a read-only Check status. **Merged/queued since (2026-11-19):** Local's readback is the app-owned `LocalNativeAddObserver` follow-up (#1902); one shared source-first intake (`UniversalAddSourceSection`), shared Start choice, `RTorrentAddObserver`, protocol HTTPS/Paste/canonical magnet and original-`.torrent` policy (#1903). **Next coherent vertical:** move each body's Save to / options / submit orchestration behind one capability descriptor and one submit/receipt region (`UniversalAddClientCapabilities` + a shared admit-and-launch), then retire the per-screen Add branches in `MainActivity`. Remove screen-owned orchestration only after installed parity is proven. Preserve frozen exact source/client/hash/Place/Start, existing native BEP9 and original-source handling; do not invent another writer or introduce an unverified retry.
2. Preserve magnet, chosen .torrent, HTTPS review and Android Share/Open With source precedence; exact selected client, destination, Start and Add-time per-file Skip/Low/High; explicit original source Keep/Archive/Delete and consented art choice. Continue remote post-Add readback outside the Add screen without forcing a separate ceremony.
3. Qualify duplicate hash, other-profile match, wrong-folder/Start, stale/hidden inventory, delayed/lost ACK, process death, screen recreation, disabled file priority, no-write refusal and recovered pending/uncertain receipt. Use installed Local/rTorrent and three-protocol real-daemon journeys; owner Pixel 6 Pro external provider acceptance is independent.
4. Keep the canonical combined Transfers browser and full Details for every Local/remote scope, persistent four-root footer and truthful Connected/Offline status. Necessary UI repair may accompany implementation; optional geometric polish waits for coordinated R2 work.
5. Confirm real folder picker reads **the selected remote**, default path is not silently overridden, New folder is capability-verified and folder Rename remains unavailable without typed readback [#1680](https://github.com/gamesbyian/Chunks/issues/1680).

**Exit:** one submitted Add action and one safe, observed outcome contract across all five clients, with actual peer/bytes, source/readback negatives and installed user journey. Record UI/physical-tier gaps separately.

## R3. Local engine, Android lifecycle and data integrity (run beside R1)

1. **Stopped recheck ordering is implemented and deterministically guarded** by `StoppedCheck.begin`/`StoppedCheckTest` (#1889); do not reopen that fix without fresh counterevidence. The `startAfterPayloadTruncated…` `repaired-download-flush` timeout was diagnosed as libtorrent's 60 s `min_reconnect_time` peer backoff after a same-host pause. Its test engines now set a 1 s backoff (2026-11-19; the app keeps the default). Watch the CI pass rate, and diagnose any new stall from evidence, not blind `connect_peer` retries or longer waits. Never turn checking into a fabricated success.
2. Verify partial-piece reuse, foreign collision and wrong-target protection, restored resume, retained service/work, process death, API29/API35 provider grants and safe Downloads storage. Add genuine cancelled/failed/late response negatives.
3. Verify exact authenticated rTorrent identities and reconnection, direct RPC semantics, readback after Add/Stop/Start/Recheck and session vs data-delete separation. Preserve whole-data-root safeguards on capable hosts.
4. Keep ZIP extraction and independent library publishing disabled until capability-checked target, atomic write/rollback, safe naming and independent path proof meet [user-directed file operations](USER-DIRECTED-FILE-OPERATIONS.md) and [#809](https://github.com/gamesbyian/Chunks/issues/809).
5. Verify Android background/Doze/VPN/OEM and foreground service timeouts; record physical-device and variant limitations rather than promoting emulator success to release proof.

**Exit:** real, piece-hashed Local and remote transfers survive interrupted Add, recheck, restart and eventual Remove without wrong-target data modification.

## R5. Approved advanced Android functionality (run before final R4)

1. **Seeding and priorities:** Remote Smart Seeding is merged (#1898, `38291a4`): opt-in per server, while connected, on the shared at-most-once journal for rTorrent, Transmission, qBittorrent and Deluge. Next:
   - a protected live Seedhost Stop gate;
   - numbered-instance getter evidence;
   - Transmission ratio-Stop proof;
   - native per-client upload caps with readback: implemented for Transmission/qBittorrent/Deluge (2026-11-19, real-daemon proof); next is an installed switch journey and a live Seedhost cap check.

   Apply rules remotely only with typed supported capabilities and stop-at-most-once persistence. Finish in Order and Add-time levels already exist across clients; test per-client restart and unknown states instead of reimplementing basic selectors.
2. **Smart Routing:** build on saved ordered rules and opt-in source-time suggestions. Integrate checked metadata-late routing and multi-client choices without automatic unreviewed Add, payload movement or cross-client identity confusion. Test all five clients with disposable same-payload jobs where feasible.
3. **Snapshots:** extend locally bounded definition export/restore, read-only interrupted journal recovery, settings remap and safe provider batch handling; remote definition export/restore only where exact engine capability/bytes and consent permit.
4. **File/identity management:** keep job-only Copy/Move independently verified before source-job retirement, enforce exact Place and active-client fences; finish safe archive, renamed filenames and storage accounting without enabling unproved writers.
5. **Settings:** compose the registry as a single typed ownership/persistence source, respecting global/client/transfer scope, media consent, offline state, identity and privacy without rewriting functional app surface repeatedly.

**Exit:** each approved Android-core capability is implemented, safely refused on genuinely unsupported clients, or explicitly owner-deferred. Record executed end-to-end positives and negative controls in the ledger.

## R2. Media, settings and coordinated functional experience

1. Preserve already implemented automatic opt-in media lookup, genuine category-ratio posters and user-selected overrides. Test offline/unknown, first and second Add art choices, default-enabled preference vs separate provider consent, manual overrides and restart privacy.
2. Implement owner-approved coordinated [compact Transfers](design/TRANSFERS-COMPACT-LIST-FIRST.md), [unified Details](design/TRANSFER-DETAILS-UNIFIED-IDENTITY.md), [unified Connections roster](design/CONNECTIONS-UNIFIED-ROSTER.md), [single Add](design/UNIVERSAL-ADD-CLIENT-PARITY.md), and [Options](design/OPTIONS-SOLID-COLOUR-INDEX.md). Use [root geometry](design/ROOT-HEADERS-AND-CONTROL-GEOMETRY.md) and [category artwork shapes](design/TRANSFER-ARTWORK-ASPECT-RATIOS.md), superseding older square-video prescriptions.
3. Keep one root footer and correct active selection, clean first-install Welcome, exact-client included-count labels, accessible short names/colours, functional 48dp-class controls and meaningful outcomes. Test 320/412dp and large fonts, TalkBack, keyboard and IME.
4. Reconcile deliberately obsolete literal/screenshot expectations **before** test execution, preserving all enduring behavioral, data and safety assertions. Actual rendered native screenshots require conscious presentation review, not merely an atlas artifact.

**Exit:** source-exact installed functional and presentation acceptance of the assembled five-client app, plus separate owner Pixel visual/physical proof.

## R4. Assembled QA, release and distribution

1. After the unblocked technical work above is integrated, freeze one candidate SHA/tree. Run affected JVM/docs/Android foundation, full native Add shards/verifier, R8, real peer/daemon and installed user journeys at that exact source; retain failures, missing/skipped checks and counters.
2. Run physical Pixel external-file/Doze/large-text/TalkBack and final UI inspection with confirmed APK bytes/hash. Verify branch governance and authorized signing certificate/secret readiness; do not infer either from workflow YAML.
3. Complete the [release-candidate go/no-go checklist](RELEASE-ACCEPTANCE.md). A stop-ship failure holds the release; an intrinsically unsupported operation may show a safe capability refusal with explicit scope.
4. Publish owner-approved signed assets using manual trusted-main workflow only when exact SHA, signature, version, package and immutable artifact hashes are verified.

**Exit:** release receipt in [QUALITY-LEDGER](QUALITY-LEDGER.md), installed identity, exact technical limitations and separate physical acceptance.

## R6. Later expansions (never silently discard)

Other remote protocols beyond the current five-client Android support, Android TV, individual widgets, Companion server, optional media/editorial catalogs, variant-specific research and font laboratory remain documented. Promote any of these only through an explicit owner decision; they are **not** blockers for currently scoped Android release unless a demonstrated safety dependency requires it.

**Always binding:** Preserve no blind replay, independent positive provenance, exact source/client/hash/Place/Start, immutable authorized source handling, safe consent, no cross-account mutations, and no unsupported file/metadata claims. Dated success and failure evidence belongs in [QUALITY-LEDGER](QUALITY-LEDGER.md).
