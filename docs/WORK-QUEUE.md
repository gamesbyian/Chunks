# Chunks live work queue

Snapshot 2026-10-24 after critical review; update from **actual main, issues, PRs and quality evidence**. THIS is the sole current priority owner. Plans, audits and issue discussion do not count as implemented functionality.

## Baseline confirmed

- [x] #11 bootstrap merged as main commit 6dba63fed72c92c7f97128ef3fa08d278a37b730.
- [x] #12 read-only synthetic rTorrent gateway fixture proof merged as main commit 5c9ee81d50d7309be065fa826b7fd643b59e9478. [Run 40057876761](https://github.com/gamesbyian/Chunks/actions/runs/40057876761) passed Android debug APK build and JVM unit tests against its PR SHA 5b5b75ed4abaa9f4e97ec4c7e4fa517130e8f681.
- [x] Product/architecture critique and recovery plan captured in CRITICAL-REVIEW-2026-10-24.md (documentation, not acceptance).
- [x] #14 merged at `a1573a4`: read-only native Transfers/Places/Organize/Server dashboard, remote file inspection, extended rate metrics with command-fault fallback, filtering/search/sort, non-mutating Add/Place preview. Android debug APK/JVM tests passed on exact PR SHA `7a7aae0` ([run 40060156362](https://github.com/gamesbyian/Chunks/actions/runs/40060156362)); no handset/live rTorrent proof.
- [x] #17 magnet Android Share/Open With intent intake and stricter draft syntax validation merged at `f0182b3`. [Run 40060539699](https://github.com/gamesbyian/Chunks/actions/runs/40060539699) passed `assembleDebug` and JVM tests at PR SHA `d586f712da63ba5c56a3d275ec68e29eb8834a7b`. These remain local drafts, not server Add.
- [x] #15 ephemeral real rTorrent 0.9.8-1ubuntu0.1 through localhost nginx/SCGI read-only adapter proof passed ([run 40060755245](https://github.com/gamesbyian/Chunks/actions/runs/40060755245), tested SHA `cd337cc8560c8c1d90c581be3de6f61176106e1c`); merged as `fe3b463`; workflow now manual-only.
- [x] #20 merged at `b2aa6cf`: hosted ruTorrent web-URL-to-RPC candidate derivation and manual-only protected Seedhost read-only smoke workflow. [CI run 40062658654](https://github.com/gamesbyian/Chunks/actions/runs/40062658654) passed Gradle debug assembly/JVM tests at PR SHA `b7fc42a8b6d8307ee00309fddb256dbbca9a91c4`. **Not** a live hosted-server connection.
- [x] Authenticated real Seedhost HTTPS XML-RPC version probe passed ([run 40063547095](https://github.com/gamesbyian/Chunks/actions/runs/40063547095), SHA `2f2660780363faf91a8d04ff8415264f0176c229`). No private values logged.
- [ ] Protected app-side authentication, full torrent listing/views, transport compatibility, modern build coverage and reconnection verified.
- [ ] Safe transfer Add, start/stop or other remote mutation observed.
- [ ] Remote physical move/rename proven on disposable data.
- [ ] Local BitTorrent engine implemented.
- [ ] Physical Android UX or continuous seeding proven.
- [ ] Release-quality APK signed and accepted.

- [x] Owner designated this Seedhost torrent population as development-only, nonconfidential and explicitly approved **destructive tests of torrents**. Authorization is scoped; robust operation-level safeguards and recovery evidence still required. See AGENTS.md and SEEDHOST-REMOTE-TESTING.md.

- [x] #27 merged as `ef03e09`: typed, exact-hash `d.start`/`d.stop` with `d.state` read-back and synthetic fixture tests. [Run 40064096463](https://github.com/gamesbyian/Chunks/actions/runs/40064096463) passed JVM tests and debug APK build at PR SHA `124cee10a2468a88eb0cb576bb308ae944baf97c`. No live server mutation or UI command exposure yet.

## Now: one critical path

**R1. Read-only real-server compatibility (#1, #9, [compatibility proof PR #15](https://github.com/gamesbyian/Chunks/pull/15) while in review).** Prioritize this before expanding controls. **The disposable legacy server gate is met** (see RTORRENT-COMPATIBILITY-MATRIX.md). Next produce a sanitized protected seedbox/modern build matrix, auth and proxy support, method/encoding behavior, named views, list completeness, response limits and i8 support. Build negative and large-list fixtures; evaluate handling of timeouts/reconnections. Evidence: real read-only listing for at least two meaningfully different rTorrent environments and exact request/response-shape fixtures without private names. The existing JVM fixture tests are only the beginning.

**R2. Companion-free remote daily-use beta (#4, #8).** With the connection proven, define typed safe command catalog and verification outcomes; Add with Place selection, selection/priorities, ordinary transfer controls and filtering/diagnostics. Acceptance: one end-to-end user journey on actual seedbox from Android share or paste of a magnet through confirmation of the intended server directory, with no upload of passkeys or torrent inventory. No generic user-controlled RPC methods.

**R3. File-operation safety proof (#2, #3, #7, #9).** Separate research/implementation of companion or secure alternative FS provider. First build exact-pathed disposable test volumes and a journal; test cross-volume move, partial and multi-file torrents, concurrent writer, symlink/case/space collisions, crash at each state. No real remote move action before independent acceptance. UI can display unsupported/deferred behavior without pretending the move is done.

**R4. Organization engine (#6, #8).** The #14 UI currently displays only heuristic suggestions; no rules, executable plans, approvals or actual file sorting are implemented. In parallel with non-destructive R2 only after shared model stabilizes. Category/Place/rule/permission separate, classifier adversarial fixtures and dry-run batch. Use accepted owner choices to learn; no unreviewed automation of physical paths.

**R5. Local BitTorrent engine (#5).** Engine selection, SAF integration and background seeding proof on Android devices; maintain typed semantics and honest foreground limitations. This is not a blocker for the first remote beta.

**R6. Release, polish and hardening (#8, #9, #10).** All primary journeys, large text/TalkBack, cutouts/layout, reduced motion, verified signing, dependency/license/security review. Nightly/huge CI factories are opt-in only when a measured risk justifies them.

## Work allocation (unassigned, not active agents)

- Lane A: #1/#4 remote adapter, schema, real read-only matrix.
- Lane B: #2/#3/#7/#9 filesystem/journal design and disposable independent QA.
- Lane C: #6/#8 classifier, Places/Collections and Android UX proof.
- Lane D: #5/#10 shared Gradle, app integration, evidence and release.
Coordinate through AGENT-LANES.md. No issue implies an agent is automatically working.

## Next executable action after this review

With the **trusted manual-only Seedhost version probe now passing**, continue **read-only app-side auth, sanitized capability/list completeness checks and modern rTorrent compatibility validation** for #1 using the newly proven disposable-server method; do not mutate any server, or ship a full remote feature based only on synthetic XML-RPC fixtures. If real server access is unavailable, improve only deterministic protocol fixtures and record the external evidence gap. Reassess the implementation queue only after that discriminator.

## Closure rules

- CI source build != Android runtime != real rTorrent != observed actual storage != human UX.
- Every completed issue names exact source SHA, test/artifact, owner-observed outcome and remaining limits. No inventing a success badge from a plan.
- Destructive code requires separate approval/recovery evidence; no cleanup without verified source ownership.
- Keep this document current, not appended with old status essays. Historical details belong in evidence/PRs and the dated critical review.

## Public Add fixture factory

Implemented and merged: [PR #29](https://github.com/gamesbyian/Chunks/pull/29) and [PR #30](https://github.com/gamesbyian/Chunks/pull/30) provide bounded licensed Archive `.torrent`/derived magnets, Commons metadata and opt-in small-file-generated v1 torrents. Offline Python suites passed at [runs 40064941665](https://github.com/gamesbyian/Chunks/actions/runs/40064941665) and [40065113278](https://github.com/gamesbyian/Chunks/actions/runs/40065113278). Live provider retrieval and peer/seeding viability remain unchecked.

## Stopped destination-first remote Add API

PR #31 [merged](https://github.com/gamesbyian/Chunks/pull/31) at `de99a6d`, [run 40065534962](https://github.com/gamesbyian/Chunks/actions/runs/40065534962) passed Android APK assembly and JVM fixtures at PR SHA `68c8406ca43f6a7b5a1f6504127cfe4b9fbf8eff`. It supplies a narrow XML-RPC Add path for a validated v1 magnet or exact-hash Archive .torrent URL; it refuses duplicate known main-view hashes, loads stopped with an explicit rTorrent directory post-load command and reads back hash/directory/state. Do **not** claim live server compatibility, real payload download or accessible UI until independently verified.

## Manual public source Add acceptance

[PR #32 merged](https://github.com/gamesbyian/Chunks/pull/32) at `22580cb`, [run 40065967589](https://github.com/gamesbyian/Chunks/actions/runs/40065967589) passed the offline Python tests at PR SHA `2378f2701d637d64e60802d83ced80f4793485c5`. The protected [public Add smoke](../.github/workflows/seedhost-public-add-smoke.yml) supports public Archive/Commons fixtures, default no-server preview and separately confirmed one-torrent stopped `load.raw_verbose` on the authorized Seedhost instance. Synthetic tests are not live server proof. Next verify the collector using a real source, dispatch explicit manual Add to the chosen server directory, reconcile the result against the Kotlin Add API, then deliver a genuinely accessible Add interface.

## Real-data visual QA preparation

Opt-in dev Seedhost `inspect_inventory=true` will capture nonsecret torrent metadata and build a compact deterministic `qa-cases.json`/`qa-cases.md` selecting long-name, RTL/Unicode, completed, incomplete, active, stopped and destination edge cases. Run source offline regression tests on PRs; first actual snapshot artifact and true multi-viewport Compose screenshot atlas remain acceptance gates. This selection does not affect transfers.

## 2026-10-24 queue handoff: new source/QA evidence

- [x] Public licensed Archive/Commons fixture collector, deterministic small Commons torrent/magnet creation, malicious-input guards and manual collector: merged #29/#30; offline Python runs 40064941665 / 40065113278 **passed**.
- [x] First Kotlin stopped destination-first RPC Add core merged #31; Android compile/JVM run 40065534962 **passed**. No app Add submission UI or live RPC Add proof.
- [x] Confirmed single stopped Add manual-only Seedhost smoke merged #32; offline run 40065967589 **passed**. No real mutation performed by this check.
- [x] Verified public candidate source URLs/licenses and HTML-safe attribution capture merged #34; offline run 40066367045 **passed**. Source API eligibility may vary.
- [x] Deterministic real-data snapshot QA selection integrated into opt-in manual Seedhost inventory workflow merged #35; offline run 40066630401 **passed**. No live source snapshot or actual screenshots yet.
- [ ] **Next proof:** manually run [licensed public fixture collection](https://github.com/gamesbyian/Chunks/actions/workflows/public-source-fixtures.yml) using a curated candidate from [PUBLIC-TORRENT-FIXTURES.md](PUBLIC-TORRENT-FIXTURES.md) and confirm manifest, hash and artifact.
- [ ] **Next proof:** manually run [Seedhost read-only inventory capture](https://github.com/gamesbyian/Chunks/actions/workflows/seedhost-readonly-probe.yml) with `inspect_inventory=true` and verify fixture plus QA selector output.
- [ ] **Next proof:** manually preview then hash-confirm one stopped public fixture Add via [protected Add acceptance](https://github.com/gamesbyian/Chunks/actions/workflows/seedhost-public-add-smoke.yml); verify actual server hash/path/state without retries.
- [ ] Only after proven compatibility: wire an accessible confirmed Add action and run real Compose screenshot/hierarchy atlas at meaningful viewports, incorporating selected real snapshot cases.

## Independent actual-source acceptance (2026-10-24)

- [x] **Live Archive and Commons source fixtures were collected and independently verified** in [PR #37](https://github.com/gamesbyian/Chunks/pull/37) / [CI 40071224039](https://github.com/gamesbyian/Chunks/actions/runs/40071224039), source SHA `5925bfb3b1d484c31f3d45018afc07fbfe823871`; merged at `cd71f95`. The real Archive item `BigBuckBunny_124` passed metadata, license, torrent and magnet checks; Commons `File:Example.ogg` passed actual byte count/SHA-256 and generated torrent+magnet parity. Short-lived artifact bundles: `chunks-archive-real-source`, `chunks-commons-real-source`. This supersedes the earlier “never collected a real fixture” caveat.
- [x] A live Commons API discrepancy was found and repaired: source URLs carried recognized `utm_*` query parameters. The collector now removes only these nonessential tracking keys and rejects unknown/token-bearing queries; [offline regression and live acceptance both passed](https://github.com/gamesbyian/Chunks/actions/runs/40071224039).
- [ ] Use a collected exact hash and explicit destination to perform the separate protected, manual-only **live Seedhost Add acceptance**. No real app Add or remote move proven by source collection.
- [ ] Acquire a first opt-in real Seedhost torrent inventory to drive actual Compose/Android viewport screenshot atlas and accessibility states. QA case selection code is not an image atlas.
- [ ] Connect actual Android runtime to protected Seedhost using its tested authentication challenge, confirm list accuracy and failure/reconnection states. PR #38 is only a source candidate until CI passes and handset/network tested.

## Native Android torrent file import preview candidate

PR #40 adds an Android Storage Access Framework `OpenDocument` picker, bounded 2 MiB v1 `.torrent` parser, on-device original `info` dictionary SHA-1, name/file-count/size preview and explicit refusal of traversal/invalid encoding/v2. This is a **local-only Add preview**, not upload or remote Add; selected bytes are not persisted or sent to Seedhost. App/UI CI and a physical Android document picker journey remain required gates.

## Protected Kotlin adapter gate candidate

PR #42 adds a trusted-main, manual-only Seedhost compatibility workflow invoking the **actual Kotlin RTorrentRemote adapter** against the authorized development server with repository credentials. It checks live authenticated version and main-view torrent decoding without state changes. PR CI will **skip** the live check if no secret environment is supplied, so only a separate manual run with explicit evidence can mark actual Seedhost Kotlin compatibility passed. This is an essential predecessor to declaring handset Add/lifecycle support.

## Confirmed Compose Add handoff candidate

PR #44 connects the existing v1 SAF .torrent parser and destination-first stopped-load core to an accessible two-stage Add screen. Submission stays disabled until the user explicitly reviews exact hash and destination. A failed or ambiguous submission cannot be immediately repeated from the same screen; user must inspect remote state. Merge only after Android/JVM green. Real device flow and actual Seedhost Add remain separate acceptance gates, not implied by a build.

## Real disposable rTorrent write acceptance candidate

PR #46 extends the existing localhost-only SCGI/nginx legacy rig to execute a real **stopped raw .torrent Add**, enforce duplicate-hash refusal, then perform verified start/stop against disposable rTorrent 0.9.8 data only. It verifies exact hash and directory, with asynchronous state polling but never repeats the Add after an uncertain outcome. The PR workflow obtains **no secrets** and never accesses the owner's Seedhost. This is a *proposal until the real rTorrent job passes*; actual Seedhost and Android/device operations remain separate.

## Destination-parent integrity correction

PR #47 corrects user-selected Add destination semantics from `d.directory_base.set` to `d.directory.set`, with separate expected physical folder roots for single-/multi-file torrents, both Kotlin and protected Python readback verification, and mismatch tests. Do not treat a multi-file torrent flattened into the selected parent as successful. **Pending CI and real-engine/Seedhost evidence**. This prevents early implementation success from accidentally violating the Place contract.
