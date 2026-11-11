# Historical validation-cost audit: what the checks actually measured

**Audit boundary:** 2026-11-02/03 GitHub PR records and selected exact workflow-job logs. This is a targeted retrospective of observed failures and dispatch rules, **not** a whole-organization accounting of runner bills or proof that all past retests were wasteful. The row-specific evidence is authoritative only for its recorded head/run. A plan to fix a test is not a completed fix. Read the [validation decision procedure](validation-economics.md) before making a dispatch change.

## Case V01: UR-Recomp research files caused non-exercising Windows builds

- **Sources:** [#2924](https://github.com/gamesbyian/UR-Recomp/pull/2924), [#2926](https://github.com/gamesbyian/UR-Recomp/pull/2926), [#2901](https://github.com/gamesbyian/UR-Recomp/pull/2901).
- **Observed:** #2924 identifies four HP seeded two-player research-only paths that triggered hosted PR Windows, hosted main Windows and owner HP main Windows package builds. These ordinary smoke paths did not enable `hp_seeded_2p`; they could not validate the new science scheduler/oracle. The PR reports often over **20 combined runner-minutes** across the three extraneous builds. #2926 removed **24 specific path-trigger entries** in three Windows workflows for Python-unit-test-only files, retaining regular unit tests and product-source invalidation. These are *documented PR-census facts*, not an independently computed organization-wide saving.
- **False premise:** expensive source-built Windows QA is automatically valuable on every research-code change. The key missing variable was *whether this invocation can reach the changed behavior*. An expensive smoke of an unaffected product path is weak evidence for a changed research case.
- **Better decision:** distinguish file-to-runtime consumers and relevant mode flags, using safe old/new rename-aware path selectors and executable positive/negative dispatch tests. Route research-only changes to their actual Python suite and the trusted genuine science scheduler, while preserving Windows smoke for native/source/toolchain/product changes.
- **Further cost:** #2901 measured ~109–116 seconds of unchanged SDL3 preparation on hosted PR runs, plus superseded runs consuming 165 and 307 seconds before cancellation. A **source/toolchain-hash-verified cache** reuses setup, without caching generated game/ROM artifacts or replacing actual Windows qualification. A warm-run speedup remains to be measured; a proposed cache is not measured savings.
- **When full Windows remains required:** native game, renderer, audio, save, toolchain, relevant workflow or other actual Windows consumer changes, or the research-mode real Windows acceptance itself.

## Case V02: Expository one real operation, repeated Compose/teardown red

- **Sources:** [draft #401](https://github.com/gamesbyian/Expository/pull/401); [API35 run #41390895543](https://github.com/gamesbyian/Expository/actions/runs/41390895543); [#41537952499](https://github.com/gamesbyian/Expository/actions/runs/41537952499); [#41541696518](https://github.com/gamesbyian/Expository/actions/runs/41541696518); [diagnostic #416](https://github.com/gamesbyian/Expository/pull/416); selector [#415](https://github.com/gamesbyian/Expository/pull/415) and [#417](https://github.com/gamesbyian/Expository/pull/417).
- **Observed timeline:** early attempts failed at different boundaries: malformed SDK system image or picker accessibility before any transfer; later real OS grants and Copy reached real dispatch receipts; still later true exact 137,119-byte target readback, unchanged source and visible success occurred **before** `visitAncestors called on an unattached node` at Compose rule/Activity teardown. These findings must not be combined into one supposed root cause. The inspected **Nov 2 04:53:17Z–05:01:32Z** job log (#41390895543) took ~8m15s and still ended with detached-semantics failure; the **22:58:41Z–23:06:57Z** job log (#41541696518) took ~8m16s and failed with the same exception (1/1 executed test failed). Logs of the latter independently establish failed Gradle `connectedDebugAndroidTest`, not a successful entire E2 pass. Exact byte evidence is from inspected artifact reported in #401, not directly from the two job-log snippets.
- **Wrong conclusion to avoid:** either “Copy is broken because whole E2 red” or “Copy succeeded, so ignore/redact the teardown failure.” A complete green test is essential for **end-to-end lifecycle acceptance**, but repeatedly repeating the costly picker/byte journey is a poor *diagnostic discriminator* after the failure is isolated past Copy.
- **More informative next experiment:** minimal installed Material3/Activity teardown without I/O, then actual app navigation without picker, then real picker grants without Copy, then cancel review without Copy. #416 reports **4/4** minimal Material3 negative controls passed and actual navigation-only **1/1** passed. These passing controls narrow the trigger but **do not reproduce or solve** the #401 bug. Allowlisted exact-class diagnostics are legitimate, and #401 remains draft/red until genuine installed lifecycle finishes cleanly.
- **Cost rule:** do not dispatch a complete two-tree test for each no-I/O diagnosis once a smaller test can distinguish the hypothesis; **do** rerun one full exact-head two-tree test after a candidate fix to close the unverified teardown gate.

## Case V03: Chunks genuine FGS proof followed by silent reboot hang

- **Sources:** [run #41547036035](https://github.com/gamesbyian/Chunks/actions/runs/41547036035), [repair #1123](https://github.com/gamesbyian/Chunks/pull/1123).
- **Observed log:** job ran **2026-11-02 23:39:45Z–2026-11-03 00:14:56Z** (~35m11s). Cheap mocked negative controls and Android app/test compilation passed. API35 emulator boot completed and the **real FGS dataSync timeout + cold-restore** phase emitted positive receipts by **23:46:03Z**. Reboot phase was armed at **23:46:18Z**; nothing confirming a completed reboot acceptance followed before timeout/cancellation. Job-step label `Shortened dataSync limit pauses honestly` covers a combined command chaining FGS timeout, process death, reboot, phone intake, and must not be reported as four passed journeys.
- **Unknown:** exact blocking command is unconfirmed; PR #1123 names an unbounded `adb wait-for-device` as a *plausible* culprit, not established fact.
- **Better design:** finite ADB reboot/wait/instrumentation budgets, before/after milestone logs, preserve kernel boot-ID/post-reboot bytes/seeding assertions; put hard bounds around each subjourney so later tests are not silently held hostage. Distinguish prior FGS-positive evidence from unfinished reboot proof. #1123 was merged; its speedup and restored pass require a subsequent exact-head run.
- **False premise:** one long combined integration shell script is equivalent to an efficient batch. Shared setup is useful **only when each phase has deadlines, independent evidence and recoverable failure boundaries**.

## Case V04: Chunks precompleted magnet is not fresh peer ingress

- **Sources:** [draft #1051](https://github.com/gamesbyian/Chunks/pull/1051), referenced installed Seedhost [run #41448483130](https://github.com/gamesbyian/Chunks/actions/runs/41448483130) and successful disposable E2 [#41519351633](https://github.com/gamesbyian/Chunks/actions/runs/41519351633).
- **Observed:** a real public magnet was already fully verified on the test destination; `verified=expected>0` with `received=0` after Add cannot demonstrate fresh ingress. Deleting it to force traffic was not authorized. #1051 proposed bounded alternative publisher fixtures with exact hashes and careful stop/cleanup/readback. The PR was closed **unmerged**; do not treat its proposed rotation as implemented.
- **False premise:** a large download, real peer-capable server or verified destination bytes automatically proves the specific fresh-transfer behavior under test. Fixture initial state and changed counters matter.
- **Better decision:** preflight novelty and strict fixture ownership, measure actual fresh peer bytes and expected hashes with independent server readback, and make precompleted/uncertain states explicit inconclusive outcomes rather than false positives. Keep the genuine installed journey where it is the only appropriate oracle.

## Case V05: Chunks 1,178 JVM tests green, one conflated UI timeout red

- **Sources:** [foundation run #41236526635](https://github.com/gamesbyian/Chunks/actions/runs/41236526635) and [merged test-only #860](https://github.com/gamesbyian/Chunks/pull/860).
- **Observed by PR record:** production compiled and 1,178 JVM tests executed before `CombinedSavedServersTest.kt:66` failed with `ComposeTimeoutException`. A single five-second condition bundled background `Dispatchers.IO` list callbacks and Compose statuses; a tight CI scheduler cannot tell which phase delayed completion.
- **Better discriminator:** separately await *exactly two* background list callbacks, then independently assert correct rows/status at the UI. #860 made precisely this test-only change and was merged. It preserved assertions against wrong scope, eager reads and stale data, not merely extended timeouts.
- **False premise:** a big passing test count validates the one failing UI assumption; or any red timeout proves a product functional regression. State how much was executed and which observed phase failed.

## Case V06: Human visual review and UI atlas value

- **Sources:** [Glimpse PR #38](https://github.com/gamesbyian/Glimpse/pull/38), [Chunks draft #992](https://github.com/gamesbyian/Chunks/pull/992).
- **Observed:** Glimpse's automated screenshot atlas was green even while a real 320dp/200% capture rendered collection names letter-by-letter. Chunks #992 proposed **two** actual installed viewports with 200% system text and 14 distinct PNG artifacts, but was closed **unmerged**. A generated PNG count alone cannot demonstrate that the UI is usable or that the human reviewed the relevant pixels.
- **Better decision:** batch compatible visual fixes before one installed multi-viewport atlas, automate objective constraints (size, reachability, focus, 48dp targets, missing controls) and reserve the director's tactile/visual inspection for a consolidated, meaningful APK. Inspect screenshots rather than treating file existence as subjective acceptance. Preserve per-viewport screenshots and error reports.

## The shared mistake: treating the cost of a test as evidence of its value

The value of a test depends on **claim relevance, ability to exercise the changed path, trustworthy oracle, fixture state, new information expected, diagnosis precision and risk reduction**, not its duration, breadth, or count. When selecting a check, answer:

1. *Which exact unproven claim will this test separate from its alternative?* If none, it is a candidate to skip unless required release/merge policy independently mandates it.
2. *Can this invocation physically reach the changed behavior or runtime mode?* If not, route differently.
3. *What was already observed and on which exact source/environment?* Preserve durable independent evidence, but do not inherit it across invalidating changes.
4. *Would a shorter deterministic test discriminate the current hypothesis?* Use it first, then return to the full journey for acceptance.
5. *What initial state could make this test falsely green?* Stale fixtures, already complete files, mocked identity, skipped suites, no-op mutation or duplicate runtime.
6. *Will a red result show which substep failed?* Deadline, last assertion, independent item receipts and original artifacts are part of the test design.
7. *What is the real human decision being requested?* Group changes for one informed owner review instead of treating the owner as an iterative CI worker.

## Audit limits and next measurement

No stable cross-repository run-duration/billing export was available in this audit. Do **not** claim a percentage of wasted total project testing time or extrapolate from the cases. PR text is secondary historical evidence; the two Expository job logs and the Chunks 35-minute log were inspected directly. The #416 4/4 controls and #2924 three-workflow / 20-minute claims come from current PR descriptions.

On the next suitable project batch, record check dispatches, status, exact SHA, first/last timestamp, completed test cases, actual changed-path reachability, repeated emulator/setup time, failure class, time to isolate, human interactions and escaped defects. Compare **median accepted user journeys per wall-hour and per human testing minute**, not only CI duration. Separate runner minutes from critical-path wall time; parallel CI may improve one while worsening the other. Change policy based on observed savings and failure cost.
