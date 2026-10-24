# Chunks live work queue

Snapshot 2026-10-24 after critical review; update from **actual main, issues, PRs and quality evidence**. THIS is the sole current priority owner. Plans, audits and issue discussion do not count as implemented functionality.

## Baseline confirmed

- [x] #11 bootstrap merged as main commit 6dba63fed72c92c7f97128ef3fa08d278a37b730.
- [x] #12 read-only synthetic rTorrent gateway fixture proof merged as main commit 5c9ee81d50d7309be065fa826b7fd643b59e9478. [Run 40057876761](https://github.com/gamesbyian/Chunks/actions/runs/40057876761) passed Android debug APK build and JVM unit tests against its PR SHA 5b5b75ed4abaa9f4e97ec4c7e4fa517130e8f681.
- [x] Product/architecture critique and recovery plan captured in CRITICAL-REVIEW-2026-10-24.md (documentation, not acceptance).
- [x] #14 merged at `a1573a4`: read-only native Transfers/Places/Organize/Server dashboard, remote file inspection, extended rate metrics with command-fault fallback, filtering/search/sort, non-mutating Add/Place preview. Android debug APK/JVM tests passed on exact PR SHA `7a7aae0` ([run 40060156362](https://github.com/gamesbyian/Chunks/actions/runs/40060156362)); no handset/live rTorrent proof.
- [x] #17 magnet Android Share/Open With intent intake and stricter draft syntax validation merged at `f0182b3`. [Run 40060539699](https://github.com/gamesbyian/Chunks/actions/runs/40060539699) passed `assembleDebug` and JVM tests at PR SHA `d586f712da63ba5c56a3d275ec68e29eb8834a7b`. These remain local drafts, not server Add.
- [ ] Real rTorrent server connectivity or version/proxy interoperability observed.
- [ ] Safe transfer Add, start/stop or other remote mutation observed.
- [ ] Remote physical move/rename proven on disposable data.
- [ ] Local BitTorrent engine implemented.
- [ ] Physical Android UX or continuous seeding proven.
- [ ] Release-quality APK signed and accepted.

## Now: one critical path

**R1. Read-only real-server compatibility (#1, #9, [compatibility proof PR #15](https://github.com/gamesbyian/Chunks/pull/15) while in review).** Prioritize this before expanding controls. Produce a sanitized server/proxy/transport/auth matrix, command availability, named views and complete-list semantics, response sizes and i8 support. Build negative and large-list fixtures; evaluate handling of timeouts/reconnections. Evidence: real read-only listing for at least two meaningfully different rTorrent environments and exact request/response-shape fixtures without private names. The existing JVM fixture tests are only the beginning.

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

Write and run **a reproducible sanitized real-server read-only compatibility test** for #1; do not mutate any server, or ship a full remote feature based only on synthetic XML-RPC fixtures. If real server access is unavailable, improve only deterministic protocol fixtures and record the external evidence gap. Reassess the implementation queue only after that discriminator.

## Closure rules

- CI source build != Android runtime != real rTorrent != observed actual storage != human UX.
- Every completed issue names exact source SHA, test/artifact, owner-observed outcome and remaining limits. No inventing a success badge from a plan.
- Destructive code requires separate approval/recovery evidence; no cleanup without verified source ownership.
- Keep this document current, not appended with old status essays. Historical details belong in evidence/PRs and the dated critical review.
