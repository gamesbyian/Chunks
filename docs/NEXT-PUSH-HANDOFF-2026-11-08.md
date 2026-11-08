# Chunks next-push integration handoff — 2026-11-08

> **LATEST INTEGRATION CHECKPOINT · 2026-11-08:** The table below is the pre-integration snapshot. #1405 candidate `0c526a8b` was independently reviewed and squash-merged into main as `b69a6367bdb6d3cdf742548c17fcb5aaa4d9a701`. Four PRs remain open: #1407 `11456f81` stacked on obsolete #1405 parent (must restack/requalify); #1404 `cdaceaf8` blocked by actual native `42505110698` and real magnet `42505110567` failures; #1408 `7e034c65` has latest native `42509555928`, atlas `42509555910` and docs `42509555932` successes on its stacked head (not full real-magnet proof); #1411 draft `34c9e6f3` still has skipped native/R8/magnet workflows. Comments coordinating necessary agent fixes posted to #1407, #1404 and #1411. Agent 4 now owns integration/QA/docs; Agents 1–3 own navigation, shared/phone Add, and remote respectively. Do not treat earlier no-agent wording or five-open-PR table as live. No assembled main peer/piece receipt or APK SHA-256 after this merge. The next unblocked action is Agent 2's #1407 restack plus exact-head gates, while #1404 real-magnet failure diagnosis proceeds independently.


**Status:** owner reports no agents currently working. This is a readiness snapshot, not an agent lease or claim of installed-product acceptance. Recheck GitHub live main, heads, checks and the queue before making changes.

## Source of truth and merge topology

Inspected main: `e978d069e91ef51e5d51eba32e0a149db76fab0c` (#1409 evidence/documentation merge). Five open PRs at inspection:

| PR | Exact head | Dependency | Latest checked-head evidence | Disposition |
| --- | --- | --- | --- | --- |
| [#1405](https://github.com/gamesbyian/Chunks/pull/1405) shared Add acknowledgment revocation | `0c526a8b76b537d4ac16db02ceab04d210c21624` | main | foundation [42504883634](https://github.com/gamesbyian/Chunks/actions/runs/42504883634), native [42504883654](https://github.com/gamesbyian/Chunks/actions/runs/42504883654), R8 [42504883822](https://github.com/gamesbyian/Chunks/actions/runs/42504883822) SUCCESS | review exact diff, validate superseded positive UI fixture and gate scope, then merge if qualified |
| [#1407](https://github.com/gamesbyian/Chunks/pull/1407) duplicate-hash phone readback | `11456f819ca06e88fa4bf9d1a5c0969c94e58c96` | #1405 branch | foundation [42504851135](https://github.com/gamesbyian/Chunks/actions/runs/42504851135), native [42504851111](https://github.com/gamesbyian/Chunks/actions/runs/42504851111), R8 [42504851201](https://github.com/gamesbyian/Chunks/actions/runs/42504851201) SUCCESS on stacked head | restack on merged main, verify changed-head tests and review; do not equate stacked green with final main |
| [#1404](https://github.com/gamesbyian/Chunks/pull/1404) Transfers-first fixed footer/navigation | `cdaceaf8c77d4b01d46024fd54238c6d24702312` | main | foundation [42505110647](https://github.com/gamesbyian/Chunks/actions/runs/42505110647) and R8 [42505110596](https://github.com/gamesbyian/Chunks/actions/runs/42505110596) SUCCESS; native [42505110698](https://github.com/gamesbyian/Chunks/actions/runs/42505110698) and installed real magnet [42505110567](https://github.com/gamesbyian/Chunks/actions/runs/42505110567) FAILURE; API29, remote Remove/data, combined two-server, lifecycle and atlas SUCCESS | **blocked**, diagnose executed red tests on current head, retain assertions; no merge |
| [#1408](https://github.com/gamesbyian/Chunks/pull/1408) installed four-destination navigation acceptance | `7e034c656bfa6a9289a6c737292bf7518d4d4128` | #1404 branch | native [42509555928](https://github.com/gamesbyian/Chunks/actions/runs/42509555928), atlas [42509555910](https://github.com/gamesbyian/Chunks/actions/runs/42509555910), docs [42509555932](https://github.com/gamesbyian/Chunks/actions/runs/42509555932) SUCCESS | inspect test execution and branch ancestry; land into qualified navigation tree only |
| [#1411](https://github.com/gamesbyian/Chunks/pull/1411) strict rTorrent multi-file flag | `34c9e6f314f480fc9b777dce1dee8166f6a5c5f9` | main; DRAFT | foundation [42504852184](https://github.com/gamesbyian/Chunks/actions/runs/42504852184), disposable protocol [42504852202](https://github.com/gamesbyian/Chunks/actions/runs/42504852202), remote Remove/data [42504852220](https://github.com/gamesbyian/Chunks/actions/runs/42504852220) SUCCESS; native/R8/real magnet SKIPPED | hold draft until relevant exact-head gates actually execute; don't count skipped as green |

Workflow conclusions above describe observed job results, **not** an independent audit of logs, test-case execution or exact APK bytes. Current main has no newly established post-navigation, source-and-digest-pinned full assembled installed acceptance.

## Next push, in executable order

1. **Integrate narrow integrity PRs first:** review #1405 source and negative/positive tests, preserve attempted-write and read-only contrary-observation fences. If qualified, squash/merge; then rebase #1407 onto new main and rerun required exact-head scoped gates. Keep stale/partial catalog reads from revoking a proven observation.
2. **Repair the user-visible navigation gate:** #1404 must make Transfers the first view, keep a fixed operable four-destination footer on every screen including keyboard/nested routes, surface all options under Options and use “transfers on this device” terminology. Diagnose *both* current-head red native instrumentation and real magnet lifecycle before any promotion; verify unhidden initial transfer rows, connected servers, Add draft retention, Details and Remove. Fold #1408's independent installed navigation test into the final compatible tree and require it to execute.
3. **Resolve remote strict-readback draft:** #1411 validates only 0/1 multi-file RPC readback. Review semantic and auth failure handling; run the applicable missing native/R8/real installed gates or document justified selector scope. Keep draft/blocked status until qualification, preserving uncertainty without retry.
4. **Qualify assembled main after merges:** source SHA, checkout tree when relevant, debug APK SHA-256, tested APK byte identity, genuine phone and authenticated disposable rTorrent peer/piece evidence, Add→Transfers→Details→reconnect→Stop/Start→Remove, modern/legacy routes, two-server combined, API29/35, R8 and visual/accessibility regression gates. Record exact workflow links and failed attempts; publish a named Chunks debug APK receipt only if matched. Physical Pixel 6 Pro, protected provider/account, publisher signing, TalkBack/OEM/Doze are separate unqualified tiers.
5. **Then stage next product scope:** concentrate options and scoped preferences, review source/Place/Start and long-term rules UI, with Smart Routing remaining *proposal-only*. No autonomous Add/Move, unapproved deletion or payload transfer.

## Parallel roles when owner restarts agents

- **Agent 1:** shared coordinator and contrary-readback invariants; own scoped model and tests, no remote/phone engine refactors.
- **Agent 2:** phone Share/Open With, clipboard, URI/magnet intake and exact-session duplicate evidence; coordinate shared model with Agent 1.
- **Agent 3:** authenticated rTorrent Add/readback and numbered route isolation; no new transport or blind retry.
- **Agent 4:** independent installed test receipts, genuine peers/pieces, APK provenance, focused CI; no production code ownership.
- **Agent 5/integration lead:** exclusively update AGENTS, WORK-QUEUE, QUALITY-LEDGER, SPEC-COVERAGE, AGENT-LANES and handoffs, review/merge exact heads, reconcile issues and final assembled acceptance. The **owner says no lanes are currently staffed**. Treat these as future role boundaries, not active claims.

## Non-negotiable boundaries

Exact environment/account and infohash, frozen user-reviewed source/Place/Start, explicit destructive authorization, read-only uncertain-write reconciliation, no automatic replay, no private metainfo leakage, and **job-only Move without payload transfer or file deletion**. A skipped gate is not passing, an earlier head does not qualify a replacement head, and a debug emulator APK is neither physical-device nor publisher-signed acceptance.

## Document precedence and cleanup

[WORK-QUEUE](WORK-QUEUE.md) is the executable priority; [QUALITY-LEDGER](QUALITY-LEDGER.md) is evidence; [SPEC-COVERAGE](SPEC-COVERAGE.md) defines unproven obligations; [AGENT-LANES](AGENT-LANES.md) sets collision boundaries; [CI-POLICY](CI-POLICY.md) constrains workflow fan-out. Older dated “LIVE” paragraphs in these long-running files are historical once superseded by this handoff and a newer observed head; preserve historical failures and run IDs rather than deleting their receipts. Each incoming agent must refresh live state before taking a lease.

**Docs validation required for this handoff PR:** `python3 tools/check_docs.py` and `python3 -m unittest discover -s tests -p 'test_docs_check.py'`. Documentation-only PR should run docs validation, not broad emulator fan-out.
