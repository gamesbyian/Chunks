# Real-transfer-first acceptance and upstream reuse

> **P0 execution:** [One full-journey daily-use campaign](P0-DAILY-USE-JOURNEYS.md) reuses a single [fixture cohort](P0-DAILY-USE-FIXTURES.tsv) and [scenario evidence index](P0-DAILY-USE-EVIDENCE.tsv) across local + 3 remote environments. This document continues to govern actual peer and installed-engine evidence, not a separate full-suite acceptance campaign.

## Current magnet qualification contract (2026-11-02)

Preserve the installed `.torrent` positive [41363266446](https://github.com/gamesbyian/Chunks/actions/runs/41363266446). The repaired real host/JVM BEP9 test [41380769579](https://github.com/gamesbyian/Chunks/actions/runs/41380769579) proves the development rTorrent can obtain peer metadata and verified pieces. It does not qualify an Android APK or establish public tracker availability.

The [installed rTorrent magnet gate](../.github/workflows/installed-rtorrent-magnets.yml) builds a separately installable Preview once and installs that exact binary on API35 and exercises actual MainActivity Connect, pasted Add and Transfers controls with its normal profile/command guards. It pulls the installed base APK back and compares the exact bytes. The SHA-specific application ID coexists with earlier Chunks/Preview installations. PRs use disposable real rTorrent only. Live writes require either an explicitly armed, first-attempt trusted-main manual dispatch or a reviewed, unique first-attempt trusted-main merge marker touching the gated workflow; both share the protected mutation concurrency group. As of 2026-11-02, [#1035](https://github.com/gamesbyian/Chunks/pull/1035) merged the narrow early-metadata test correction and initiated protected [live run 41424049262](https://github.com/gamesbyian/Chunks/actions/runs/41424049262). The previous original-tracker live run [41403955772](https://github.com/gamesbyian/Chunks/actions/runs/41403955772) registered the magnet but failed an invalid pending-metadata assumption; neither run proves live peer bytes unless its final source-specific receipt passes. Two independently published public identities (official Debian netinst and Blender Sintel, CC BY 3.0) are supplied by real libtorrent TCP peers. Only original prefix pieces, independently rechecked before Add, are seeded. rTorrent receives magnets only and must resolve original metadata, receive and verify pieces, Stop/Start through production controls, receive further verified pieces while incomplete, and session Remove with unchanged unrelated inventory. A separate peerless magnet must show waiting for metadata and no payload progress, then be cleaned up.

Structured source/hash/stage/numeric receipts and actual AndroidJUnitRunner-derived JUnit are retained even on failure. Independent peer counters must agree with the two exact identities. An absent report, failed negative, duplicate identity or missing cleanup fails qualification. The offered APK must have the identical digest and source SHA as the installed positive run; filenames contain `chunks`. Full native regression still retains all existing 334 identities, 306 executions, 28 intentional skips and 24 actual PNGs. The controlled mode proves protocol and lifecycle. The explicit public-discovery mode pastes the original published tracker magnet and forbids any controller peer injection; independent source-piece checks remain, but supplied-peer upload and connection counters must both stay zero. Both modes require metadata, real rTorrent received and completed-piece counters, UI lifecycle and cleanup. An already complete public transfer must Stop/Start correctly; an incomplete transfer must receive further verified bytes. Physical/OEM remains a separately stated limit. Do not ask the owner to install an APK to discover whether this gate works.


**Owner direction (2026-11-01):** Chunks' ordinary features must be judged primarily by behavior on **real BitTorrent engines, real .torrent files, actual magnets, real peers and real servers**, through the shipping app path whenever applicable. Mock, synthetic, replay and simulated tests remain useful **secondary tools** for rapid feedback, security/negative cases and deterministic failures. They cannot certify a working torrent feature.

**Scope/authority:** This operational acceptance standard supplements [AGENTS](../AGENTS.md), [engineering delivery](ENGINEERING-DELIVERY-PROTOCOL.md), [CI policy](CI-POLICY.md), [quality ledger](QUALITY-LEDGER.md) and the existing [protected-host scope](SEEDHOST-REMOTE-TESTING.md). [WORK-QUEUE](WORK-QUEUE.md) alone orders implementation work. Do not invent a second queue or retroactively invalidate genuine evidence.

## What counts as evidence

| Tier | Evidence | Allowed claim |
| --- | --- | --- |
| A: mocks, parser fixtures, test doubles, synthetic replies | DTO/protocol/edge/failure-handling behavior | Only the exercised narrow logic. **Not** "Add works" or "transfer works". |
| B: real engine with actual .torrent / magnet, actual peer or controlled local seeder | Real rTorrent/libtorrent protocol operations and data movement | Engine/adapter operation in the stated version/topology. A magnet derived from a known hash without available metadata peers does **not** prove magnet resolution. |
| C: actual installed Android app on emulator, connected to a real engine/server with actual transfers | Entire UI → selected account/environment → production adapter → observed per-job outcome | Installed emulator integration, at recorded OS/API and server. |
| D: owner-authorized development server, real public or controlled peer traffic | Correct host-specific auth, Add, state, pieces and lifecycle | Tested host/profile only; if dispatched solely from runner/Kotlin, **not** a physical phone claim. |
| E: actual physical handset against target engine/server with real transfers | Real-world installed-device usability, network, permissions, lifecycle | Device/OEM/OS tested only; physical acceptance cannot be inferred from A–D. |

These tiers can overlap, not necessarily occur strictly in order. For features requiring end-to-end operation, at least one positive real-system proof from **B and the relevant C/D/E** must exist before calling the feature user-accepted. B alone may qualify a low-level backend component, not a UI feature. CI green, a request ACK, correct request bytes, `d.is_active=1`, screenshots, synthetic magnet insertion, empty inventory, global transfer rates or announced peers alone do not prove payload download. Never let an irrelevant physical test block unrelated verified work.

## Core positive-path test contract

- **Remote .torrent Add:** take an owner-supplied or lawful published **actual .torrent**, preserve source infohash and metainfo provenance, send through Chunks production Add to a selected rTorrent profile/parent, verify exact-hash membership, native started/active semantics and selected directory, then observe **that same hash's received-byte growth plus piece-verified progress** with a conventional peer (public swarm or a controlled non-webseed BitTorrent seeder). Test Stop → Start → further normal operation, file priorities and session-only Remove while preserving payload and unrelated jobs.
- **Remote Save to directory browser (planned):** For a disposable verified server account, compare a real ruTorrent `_getdir` listing with the Chunks tree, and use a selected folder for one ordinary Add with independently observed exact `d.directory`/infohash. Confirm 401/403, unsupported and wrong-account paths cannot browse/create; no browser must be needed for a valid-default Add. If a typed authorized mkdir provider exists, create one non-destructive unique child under a disposable allowed root, independently list/stat it, test already-exists/timeout/traversal/symlink refusal, and do not repeat an uncertain write. The feature's [contract](REMOTE-DIRECTORY-BROWSER.md) prohibits shell-based fallback. Run tests only for affected implementation, not docs-only changes.
- **Remote magnet Add:** use a real `magnet:?` for which **metadata peers exist** (or a controlled actual peer supplying metadata), through the actual Chunks Add path. Verify the exact hash transitions from metadata pending to resolved torrent name/files/pieces, then actual peer bytes and lifecycle. An arbitrary fake infohash with an announce URL is a *negative/control fixture*, not magnet acceptance.
- **Local Android engine:** installed Share/.torrent/magnet → selected user-visible Downloads Place → real piece-hash-verified bytes and actual seeding to a second peer → Stop/Start → process death/relaunch and reboot as supported → Remove preserving payload. Separate API29, API35, SAF/SD and physical/OEM receipts. Never promise indefinite seeding under Android background restrictions.
- **Combined environments:** installed phone + at least two authenticated real server profiles; same-hash/different-profile identity, real listing after reconnect, exact-scoped Add/Stop/Start/Remove, destination/priority scope, persisted filters and truthfully surfaced pending/refused/uncertain outcomes. Verify mutation target against the actual engine, not just UI state.
- **Reference controls:** for each critical conventional rTorrent operation, perform an equivalent operation with **ruTorrent or Transdroid** on the same designated test host using a comparable known-good torrent. Compare the rTorrent start/load method, relevant `d.*` state, directory, metadata resolution, peer/piece behavior and post-Stop Start; allow intended UI differences, investigate functional differences. Capture sanitized evidence, not credentials or tracker passkeys.

A controlled locally seeded real swarm is preferable to a flaky public swarm for repeatable CI, but must involve actual metainfo, actual metadata exchange when testing magnets, actual rTorrent/libtorrent peers and piece verification. Also perform bounded public-seeder interoperability checks where the environment permits. If public peers vanish or tracker access fails, report **inconclusive environment**, not application success or failure without diagnostic evidence.

## Execution and CI priority

1. Identify the **normal user action** and the expected observable outcome **before** coding; inspect established engine/library semantics and reference clients first. Prefer libtorrent's ordinary local functionality and rTorrent's native documented RPC commands. A bespoke implementation needs a demonstrated unsupported requirement, smallest-alternative comparison and independent review. Avoid copying third-party code without license assessment.
2. Develop with fast unit/mocked/adversarial tests as needed, but use **real-engine tests early**, including an executable positive case, before broad implementation or claiming completion. Mock volume is never a substitute for this positive case. A mock may reproduce a real failure once the failing real trace is captured.
3. For protocol/lifecycle changes, the smallest relevant **disposable real-rTorrent or local peer test is required at the exact PR head** when infrastructure supports it. For features that change Android entry paths, add installed emulator proof. Do not run expensive redundant native builds on unrelated docs/UI changes.
4. Actual protected Seedhost writes are executed through already authorized, explicitly gated workflows with exact profile/hash and readback, **not automatically on unreviewed PR/push**. Owner-supplied test torrents and approved public fixtures are first choices for destructive testing; preserve the chosen host's scope/credentials, use exact-hash removal and do not blindly retry ambiguous writes. A successful disposable server run does not authorize a claim about the protected instance.
5. For handset-dependent acceptance, keep the physical gate open until actually performed. Agents must not wait idle for a handset: qualify the available real server/emulator path, fix other unclaimed foundational work and supply a focused owner test card.
6. Record source SHA, APK identity if applicable, server version/profile alias (nonsecret), fixture source and hash, torrent vs magnet, peer topology, entry path (adapter versus installed UI), per-hash received/verified pieces, state before/after, cleanup, run IDs and evidence tier. Show distinct **Passed / Failed / Inconclusive / Not run** for each promised journey. A skipped or unconfigured integration test is **Not run**.
7. When results contradict the shipping experience, prioritize real observations and investigate **why the former test passed**; add a regression that fails on the earlier code and passes on the correction, retaining realistic peer/metadata acceptance. Never weaken assertions to manufacture green.
8. Protect basic Add/Start/Stop/download/seed/list and storage journeys from unrelated feature expansion until accepted. New advanced features may proceed on separate lanes when they do not delay core completion.


## Android-to-real-Seedhost Add release gate (P0, pending, 2026-11-01)

[PR #1001](https://github.com/gamesbyian/Chunks/pull/1001) adds an **unaccepted until executed** trusted-main manual workflow
`.github/workflows/seedhost-android-real-transfer-add-once.yml`. It compiles a
single APK, installs that exact binary on API35 and runs the real production
`VerifiedAddScreen` with two different official Ubuntu BitTorrent sources
against the authenticated approved development Seedhost. One is submitted as
actual v1 `.torrent` metainfo; the other uses only a magnet with a real
tracker and must fetch metadata from peers. Both must show the independently
read exact hash, expected metainfo/name/size, correct observed Place, started
state, **positive received peer bytes and positive piece-verified bytes**.
Exact Stop and session-only Erase retain payload and verify unrelated inventory.

An active bit, fake tracker, UI confirmation or mock success cannot pass. The
workflow emits its APK + source-SHA evidence **only if both tests pass**, and
keeps failure/inconclusive results red. It is protected by an explicit manual `apply=true` on trusted `main`\n**or a single, exact-text owner-authorized trusted-main merge marker for PR #1001**.\nThat one-time push exception is limited to the new workflow file path and first\nattempt; it cannot run on pull requests or normal pushes. It has the
same host mutation concurrency group as existing Seedhost Android acceptance.
The tests use the **production Add Compose screen directly**, not full
MainActivity Connect navigation, which remains a separate acceptance tier.
Public peer availability is not guaranteed, so an unavailable swarm means
**not accepted**, not proof of a product defect. A controlled-seeder
installed-app case is a desirable further improvement.

Until an exact-SHA successful protected workflow receipt exists, **do not
label any APK as rTorrent Add-qualified**. The existing synthetic installed
MainActivity test and JVM real-byte test do not satisfy this release gate
separately. The owner's physical device is still final OEM acceptance;
it is not the primary engine-start discovery tool. No protected secret access from untrusted pull requests. The one-time\nmerge dispatch must be treated as real server mutation, with no automatic\nrerun after an ambiguous response.

## Current concrete baseline and open acceptance (2026-11-01)

- [#973](https://github.com/gamesbyian/Chunks/pull/973) corrected Chunks' regular remote load to rTorrent native start-on-load after a physical user reported Chunks-created .torrent/magnet jobs locked paused while ruTorrent and Transdroid Adds worked.
- [Owner Seedhost native-start](https://github.com/gamesbyian/Chunks/actions/runs/41349305222) tested real file+magnet job startup; [peer-byte run](https://github.com/gamesbyian/Chunks/actions/runs/41350485602) verified an actual **512 KiB** file-torrent transfer with independent runner seeder, exact-hash received growth and completed pieces. This is strong host/adapter proof, **not** installed physical Android nor a real-magnet metadata download.
- **Still open:** current APK → real host .torrent byte growth, fresh real-swarm magnet resolution and bytes, installed Stop/Start/priorities/Remove, other normal user paths and owner physical acceptance. Track existing [#4](https://github.com/gamesbyian/Chunks/issues/4) and [#9](https://github.com/gamesbyian/Chunks/issues/9), not a duplicate issue.
- See [public torrent fixtures](PUBLIC-TORRENT-FIXTURES.md) and [user-supplied test inputs](USER-SUPPLIED-TORRENT-TEST-INPUTS.md). Non-secret user-provided torrent files are reusable testing inputs; do not publish tracker passkeys from any metainfo/announce URL.

**Completion rule:** A feature's evidence should be expressed as an independently reproducible *working journey*, rather than the number of passing tests. If an acceptance tier is missing, say exactly which tier, and leave that acceptance open.

## Update: actual installed Android and isolated magnet metadata (2026-11-02)

The earlier 2026-11-01 open gates are superseded **only** where the new
positive or negative receipts below establish actual behavior. A green unit
test or a started bit never substitutes for a download.

- **Installed Android .torrent Add has positive end-to-end evidence.** Protected
  API35 installed/emulated production `VerifiedAddScreen` to development
  Seedhost run [41363266446](https://github.com/gamesbyian/Chunks/actions/runs/41363266446)
  passed the original genuine public Ubuntu .torrent Add, exact-hash real
  received and verified peer bytes, Stop -> Start, and scoped session cleanup.
  The overall workflow was **red** because the subsequent magnet failed; do
  not discard the earlier narrow file-positive receipt, imply whole APK
  qualification, or rerun that passing path without a specific regression.
- **Installed Android public magnets still fail.** That same run's magnet
  progressed through UI's exact-hash "verified Add+Start" but no real metadata
  or peer/piece receipt. The different Ubuntu 25.10 two-magnet run
  [41365427039](https://github.com/gamesbyian/Chunks/actions/runs/41365427039)
  also failed both. Per-magnet safe checkpoints have since been added in
  [#1007](https://github.com/gamesbyian/Chunks/pull/1007) after its full native
  Android instrumentation run
  [41367486529](https://github.com/gamesbyian/Chunks/actions/runs/41367486529)
  passed. Preserve those separate evidence categories.
- **Direct read-only rTorrent forensics proves unresolved metadata, rather than
  a stopped/inactive or missing Add.** The credentialed trusted-main
  [41368630531](https://github.com/gamesbyian/Chunks/actions/runs/41368630531)
  independently derives original public v1 metainfo hashes, names and sizes.
  All three previously added public magnet identities were still present
  in the server's 458-job session, started=1, active=1, open=1, with trackers
  but name/size disagreeing with original bencoded metadata, reported size
  under 1MiB, connected peers=0, received bytes=0 and verified bytes=0.
  Read-only inventory/getter failures and unsupported optional methods are
  never misreported as successful metadata resolution.
- **The extra directory command alone is not sufficient to explain failure.**
  A one-shot direct Transdroid-style two-argument `load.start("", magnet)`
  control using the previously seeded Ubuntu server fixture also registered
  its hash but never resolved metadata or got bytes, and was safely session
  removed: [41369021386](https://github.com/gamesbyian/Chunks/actions/runs/41369021386).
  Similarly the actual ruTorrent `addtorrent.php` form registered the same
  hash with an uncertain HTTP acknowledgement; the corrected no-retry
  180-second observation reported no metadata, peer or verified bytes, then
  exact cleanup: [41369847790](https://github.com/gamesbyian/Chunks/actions/runs/41369847790).
  These are **not** proof that all clients' magnet Adds are broken globally;
  the relevant public magnet and tracker/metadata peers may be unavailable
  to this particular seedbox. Never compare this to the .torrent receipt
  as though a .torrent upload required BEP9 peer metadata discovery.
- **Control public-swarm variability with an actual metadata-serving seeder.**
  [#1013](https://github.com/gamesbyian/Chunks/pull/1013) adds a separate
  unique real 512KiB V1 magnet-only host/JVM experiment using the previously
  accepted runner-side libtorrent seeder, approved real rTorrent TCP listener,
  independent BEP9 metainfo readback, actual peer bytes, piece verification
  and exact session-only cleanup. Its protected trusted-main live run is
  [41370450181](https://github.com/gamesbyian/Chunks/actions/runs/41370450181).
  Evaluate that **actual completed result**, never a queue/running indicator,
  before marking this host-side magnet acceptance positive. Even a genuine
  positive host/JVM result does not on its own qualify an installed Android
  magnet UI or a physical handset.

**Operational rule:** Read-only reconcile leftover exact test hashes before
any repeat Add. For any ambiguous ACK, prohibit replay and only perform
independent readback until exact identity/state allows a safe single session
cleanup. When a run is red, retain the sanitized last-phase receipt and never
publish the candidate APK as qualified.

## Planned evidence: transfer-sharing export and external file associations

Do not mark [transfer sharing](TRANSFER-SHARING.md) complete from a fabricated magnet or a parsable test fixture. Export from a real local or rTorrent transfer after discarding its original input, then import into an independent real client and assert exact v1/v2/hybrid infohash, preserved supported trackers and actual observed metadata/peer discovery where available. A `.torrent` export additionally requires independently validated original info bytes and v2 piece layers when required. Test private, offline and unavailable-metadata negative paths without leaking account secrets into evidence.

For [Expository association](EXPOSITORY-FILE-ASSOCIATIONS.md), require an actual per-profile rTorrent file inventory and installed permission-gated IPC/pairing with Expository, plus denial/revocation and stale/incomplete-result tests before claiming usable badges or file-operation warnings. Both are P1 and cannot replace ordinary live torrent acceptance.
