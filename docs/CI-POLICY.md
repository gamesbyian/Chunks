# Chunks CI policy: executable test selection and evidence

**Use this file as instructions, not a CI diary.** Historical measurements, PRs, cost analyses and failed attempts are preserved in [CI-POLICY-HISTORY-2026-11-09.md](CI-POLICY-HISTORY-2026-11-09.md). Run-level results belong in [QUALITY-LEDGER](QUALITY-LEDGER.md). Required receipt schema and classification live in [CI-EVIDENCE-AND-RUNTIME-CONTRACT](CI-EVIDENCE-AND-RUNTIME-CONTRACT.md).

## Batched exact-source validation (mandatory)

Map each test to authoritative requirement, test intent, changed boundary and independent positive/negative witness. Batch cheap docs/static/affected JVM and compile checks; run targeted API29/API35/R8 or authorized real-peer tests for impacted Add, JNI, auth, identity, privacy, navigation, storage and lifecycle. One reconciled assembled candidate receives full native verifier, Android foundation, R8 and real-peer assurance, re-running gates invalidated by later changes. Build once and reuse only identical verified APK bytes. No emulator for docs-only and no duplicated full cross-branch WIP fanout. Diagnose red cases as product defect, obsolete literal/screenshot expectation, fixture or infrastructure without removing enduring assertions; a rerun alone never confers acceptance. Measure time to actionable failure, queue/setup/compile/emulator and redundant runner minutes separately. [Completion order](COMPLETION-ROADMAP.md).

## Select the smallest sufficient truthful checks

1. Determine actual changed source files, runtime surface and dependency graph. Run offline format/docs/JVM tests first, then targeted instrumented Android/real disposable server and applicable R8 checks. Do not launch every emulator for a documentation-only change or add new workflows when an existing shard can carry the case.
2. Changes to shared Add, source/identity, activity navigation, persistence, remote RPC or operation gating require their relevant installed actual user journeys and negative controls, not mock-only approval. Android UI changes require narrow/large-font/IME/TalkBack semantic checks; media enrichment changes require real consent/network/provider plus no-request negative checks.
3. Use approved CI selectors and build-once artifacts where byte identity can be independently verified. Do not reuse a previous head's APK/test result for changed source; require source SHA, Git tree and actual tested APK digest to match the accepted candidate.
4. Preserve full native Add shard coverage and final verifier; never reduce test counts or skip a failing test to force green. Respect the case-count/receipt rules in [CI-EVIDENCE-AND-RUNTIME-CONTRACT](CI-EVIDENCE-AND-RUNTIME-CONTRACT.md).
5. Genuine BitTorrent acceptance requires actual verified pieces/peer evidence in disposable authorized servers and installed phone clients. Server RPC ACKs, mock peers, started flags and cached size are insufficient. Protected host tests require the existing explicit authorization and secrets policy.

## Installed APK provenance must survive failed journeys

For each disposable installed Android journey, establish the exact checked-out source SHA and Git tree, actual debug/preview application ID and version, built APK candidate bytes, pulled **installed** APK bytes, size and SHA-256. A byte-for-byte compare may prove equality but its silent successful exit is insufficient to **report a hash or byte count**. Add bounded source-bound safe provenance output to the actual installed gate and durable receipt, including runs where later network/Details/lifecycle steps fail. Never substitute a historic APK digest, a test APK digest, an artifact ZIP hash, an Android JUnit digest, or a later rebuilt APK for the installed candidate. Keep raw host/device paths, private endpoints, auth headers, passkeys and pulled binary out of public logs; delete the temporary pulled APK after comparison. Record a missing digest as an evidence gap, not success.

## Shared native-focus failure diagnosis

If the installed native Add shard reports `ClipboardUriIntakeInstrumentedTest.singleCopiedUriPrefillsButAmbiguousOrUnrelatedUrisNeverDo` at the **foreground window focus** precondition, preserve the executed failure. Inspect the actual focused window/activity and owning package, UI/system overlays, emulator API/startup and JUnit receipt before making a QA-only fixture change. Keep the explicit foreground requirement and all clipboard-source negative assertions; never skip the case, remove focus preconditions, or classify earlier red runs as green. Avoid separate speculative test patches in each production branch. A validated QA correction must run full native shards/verifier at the exact integrated source.

## Diagnose red CI before modifying a test

- Identify exact tested SHA, workflow/run/attempt, stage, expected and executed case identity, last safe checkpoint and environmental preconditions. Distinguish checkout/build, emulator boot, fixture setup, assertions, network/auth/provider refusal and CI infrastructure faults.
- Preserve the first genuine red and all retries in the ledger, even after a passing fix. Do not rerun indiscriminately: isolate failing proof, fix the cause and rerun the full required gate on the exact changed head.
- If the runner never reached a new assertion, say so explicitly; do not classify that assertion passed or failed. No all-skipped or missing receipts accepted.
- Never print private credentials, tracker tokens, user paths or raw server errors in CI logs, artifacts, PR descriptions or public issue text.

## Test design and exact-commit acceptance

Write and change tests by [TEST-ARCHITECTURE](TEST-ARCHITECTURE.md): stable semantic ids for enduring capabilities, outcomes over presentation, screenshots as evidence unless an approved baseline exists, and explicit reconciliation (never deletion or weakening) when a UX change is deliberate. Classify every red or missing result as product failure, test-contract drift, environment/dependency or **not executed**.

**Draft PRs skip the native, R8 and installed-magnet jobs; skipped is not passed.** Mark a PR ready so every gate runs on the current head, then run `python3 tools/pr_acceptance_report.py --sha <exact head> --profile integration` (`integration-real-peer` when Add, source intake or remote Add changed). It exits non-zero unless every gate in [`tools/acceptance_gates.json`](../tools/acceptance_gates.json) executed and passed on that exact commit; waivers need `--waive GATE=REASON`. Evidence from another head, a parent PR or a different workflow mode does not qualify this commit. Adding or removing a native screenshot producer requires the matching entry in [`tools/native_atlas_manifest.json`](../tools/native_atlas_manifest.json).

## Merge and release acceptance

The integrator must independently review PR diffs, source ancestry/overlap and required check execution. Green on an older parent does not qualify a rebased dependent. Use current GitHub state, not a dated handoff, before merging.

A source-matched installable debug APK may be identified as a tested pre-alpha only after confirming exact source and independently hashing the **actual installed and tested** APK bytes. Record receipt in [QUALITY-LEDGER](QUALITY-LEDGER.md). Physical Pixel, protected Seedhost, publisher-signing, OEM/Doze and human accessibility are separate evidence tiers and cannot be inferred from emulator tests.

## Time and cost discipline

Reuse genuine fixtures, cached Gradle and signed-off source artifacts only where compatible. Prefer narrow ordered feedback to parallel full-suite fan-out; measure actual runner minutes, attempts, source heads and failure cause before proposing costly changes. Keep always-emitted merge status and safety gates intact. Do not turn optional workflow skips into a passing acceptance claim.

For **Chunks R8 minified smoke**, keep the exact-source qaMinified app and instrumentation APK build, executed installed API35 case, strict source/run/JUnit receipt and mapping output. Keep the Gradle daemon warm between build and connected test as implemented in merged #1510; inspect the emitted build and installed-test seconds. Target normal job execution under 10 minutes, investigate sustained executions over 15 minutes, and treat sustained executions over 20 minutes as CI performance regressions. Differentiate GitHub runner queueing from execution and compare like-for-like exact-head runs; do not change acceptance assertions or skip emulator proofs to meet a duration target.

## Mandatory next-push UI/media scenarios

Follow [next-push acceptance](NEXT-PUSH-UX-RECONCILIATION-2026-11-09.md): same compact list/full Details in All/device/server scopes, single-row four-root footer at 320/412dp and 200%/IME, two/three-across action groups, conditional Add-only art option (global enabled-by-default preference and separate provider consent), no active Details toggle, actual background art and persistence, and live phone/rTorrent source → Add → Transfer → Details → reconnect → Stop/Start → Remove with independently verified pieces. Do not claim a docs update satisfies these tests.
