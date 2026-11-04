# One P0 daily-use campaign: transfer journeys across environments

**Status:** Execution contract; not a second feature specification, priority queue, evidence ledger, or acceptance gate. The authority for requirements remains [PRODUCT](PRODUCT.md), [UX](UX.md), [UNIFIED-ENVIRONMENTS](UNIFIED-ENVIRONMENTS.md), [DECISIONS](DECISIONS.md), and [WORK-QUEUE](WORK-QUEUE.md). The original A–E categories remain evidence labels, with A/B sharing implementation workstream **Transfer lifecycle**, C/D sharing **Environment continuity**, and E being **one** independent acceptance and exact-source APK publication.

## Ownership and boundaries

- **One integration owner** owns the canonical fixture inventory, shared journey matrix, cross-stream conflict resolution, common outcome/identity handling where genuinely duplicated, PR merges, release receipts and stopping decision.
- **Workstream 1: Transfer lifecycle (A+B)** owns Add/source/destination/file priority, Start/Stop/Resume, Remove keep-data/delete-data, explicit retry/recheck and fresh same-hash re-add. Implement local and rTorrent adaptations together where the user journey crosses both. Do not widen or weaken existing exact-file, generation, account or storage safeguards.
- **Workstream 2: Environment continuity (C+D)** owns durable local transfer state, saved-profile cold reconnect, inventory freshness/paging, exact environment scoping and Combined/phone UI continuity across app lifecycle, offline, auth and timeout events.
- Shared Compose, common models, environment identity and Gradle/workflow files have **single-writer integration ownership**. Adapters can be edited in separate worktrees. Do not create a general-purpose framework for a few duplicated outcomes.
- An independent acceptance reviewer examines cross-environment results and disconfirming evidence; no permanent third implementation lane. The same acceptance journey can close several A–D rows, but phone/each rTorrent profile and each consequence retain their own pass/fail/untested status.

## Reusable fixture cohort: establish once, preserve across journeys

Canonical inventory: [P0-DAILY-USE-FIXTURES.tsv](P0-DAILY-USE-FIXTURES.tsv). Each row must have a durable fixture ID, environment/profile key, exact infohash, manifest/source (credential-safe), expected destination, relative file paths, SHA-256 or piece expectation, safe mutation scope, delete consent and actual observed stage. **Blank/unverified facts are NOT accepted data.** Track live credentials and sensitive exact paths only in protected tooling; the public manifest may use stable aliases plus a sealed artifact reference, never credentials or private tracker tokens.

Prepare one reusable cohort on the phone and *all three* approved rTorrent instances (primary/second/third), including: single-file, different-priority multi-file, BEP9 metadata-resolving magnet, complete and partial pieces, same infohash on two different servers, and a separate disposable delete/re-add transfer. Distinguish fixture **identity** from a specific instance's **session generation**; after Remove and re-add, retain the fixture ID and record a new session generation. Include foreign-file canaries and independent byte/hash readback for destructive tests. Avoid shared mutable fixture collision across concurrent agents by per-fixture leasing in the same inventory. Keep unchanged fixtures for subsequent stages instead of deleting/recreating them after every test.

Owner authorizes population of second and third instances, but this **does not** bypass protected host workflow dispatch gates. Live mutations require the existing trusted-main/manual first-attempt fences and exact reviewed target identity. Use disposable host fixtures for automatic PR tests. Unknown/inaccessible source data, empty instances and unresolved permission remain marked unverified, never filled with guessed hashes.

## Canonical full-journey scenario and evidence matrix

Use [P0-DAILY-USE-EVIDENCE.tsv](P0-DAILY-USE-EVIDENCE.tsv) as the single machine-parseable index. Rows cite already accepted run/commit/APK or current exercise; no duplicate owner issues or redundant release gates. The canonical end-to-end sequence on one cohort:

1. **L1 Add and destination**: choose the correct phone or named remote profile, .torrent or magnet; assert exact infohash, selected root, actual metadata, and correct created files/pieces.
2. **L2 Files and priorities**: choose the first file high and another low/skip, verify the actual engine priority and that verified pieces and observed file order reflect it. Do not infer priority from UI state alone.
3. **L3 Start/Stop**: start and observe actual progress, stop, verify stable resume/verified state; distinguish acknowledged request from applied result.
4. **C1 Interrupt and reconnect**: close/reboot/relaunch as appropriate; reconnect to saved server; check exact instance identity and fresh inventory without blind replay of a prior uncertain command.
5. **D1 Combined**: find the same transfer in the phone+three-server union, with correct named/coloured origin; verify selected row actions are scoped to its exact environment, even where hash matches another server.
6. **L4 Remove keep bytes**: confirm session absence from the exact engine and independent payload preservation, including when the transfer disappears from the selected list.
7. **L5 Re-add same hash**: establish a new independent session, without inheriting the old removal state or mistaking an old receipt for its outcome.
8. **L6 Delete bytes, recover**: on explicitly authorized disposable targets, review exact file mapping; remove with data, confirm matching native/real PHP result *and* physical absence and intact foreign canaries. Exercise failed/partial/uncertain outcomes with deliberate recheck and owner-reviewed retry, no automatic mutation replay.
9. **C2 Failure matrix**: offline, stale inventory, auth rejection, timeout and restart through at least one affected real adapter each; distinguish rejected-before-dispatch, pending, confirmed and uncertain. Restore UI state and target identity correctly.

Each scenario is indexed by **fixture/session generation + environment + exact source SHA + tested APK/build + result and independent receipt**. A pass for one profile is not proof for another. Record `not-run`, `blocked`, `failed`, `inconclusive` and `passed` distinctly, including timestamps and reproduction/fault trigger. The real-engine and installed-device tier must be explicit.

## Batching and selective qualification

Fast loop: build and narrow JVM/model test for the changed paths. One coordinated installed Android emulator and disposable real engine session per coherent cross-stream batch; reuse same APK and fixture inventory. Final campaign: apply unaffected previously accepted evidence, execute missing exact operations and negative cases, independently review canaries and outcomes, and inspect physical Pixel 6 Pro before any daily-use acceptance claim. Emulator boot/sdk download failure: rerun the **same emulator job**. Genuine adapter/UI assertion failure: fix and rerun only that test and dependent journey. Avoid restarting matrix on unrelated docs, P1 aesthetics or historical red runs. Old evidence remains usable only if source/behavior contract is unchanged.

Use narrow user-visible state categories: **confirmed** (with actual postcondition), **refused before dispatch**, **pending**, **uncertain**. The adapter retains native libtorrent event/readback or rTorrent XML-RPC/PHP receipt and the independent physical file witness. A remote queued PHP cleanup alone remains *pending bytes*.

## Stop rule

Daily-use P0 is satisfied only when independent evidence supports: *a user can add, manage, interrupt, recover, locate and remove transfers on the phone and each saved rTorrent server, including Combined browsing, without mixing environment identity, falsely reporting success or deleting unrelated files.* Existing A–E evidence cells cross-reference this one campaign; avoid promoting optional P1/P2 functionality or historical CI cleanliness into new P0 gates.

**Starting baseline (not complete acceptance):** paired Remove [#1231](https://github.com/gamesbyian/Chunks/pull/1231), UX [#1230](https://github.com/gamesbyian/Chunks/pull/1230), recovery [checkpoint](P0-RECOVERY-CHECKPOINT-2026-11-03.md). Existing published [APK source 91311baf](https://github.com/gamesbyian/Chunks/releases/tag/android-debug-91311baf70c7) is a candidate, not proof of full real-world daily use. Compact Add [#1233](https://github.com/gamesbyian/Chunks/pull/1233) stays separate until its failing installed native shard is explained; it is not permission to reopen accepted P0 work.
