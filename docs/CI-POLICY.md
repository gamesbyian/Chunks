# Chunks CI policy: executable test selection and evidence

**Use this file as instructions, not a CI diary.** Historical measurements, PRs, cost analyses and failed attempts are preserved in [CI-POLICY-HISTORY-2026-11-09.md](CI-POLICY-HISTORY-2026-11-09.md). Run-level results belong in [QUALITY-LEDGER](QUALITY-LEDGER.md). Required receipt schema and classification live in [CI-EVIDENCE-AND-RUNTIME-CONTRACT](CI-EVIDENCE-AND-RUNTIME-CONTRACT.md).

## Select the smallest sufficient truthful checks

1. Determine actual changed source files, runtime surface and dependency graph. Run offline format/docs/JVM tests first, then targeted instrumented Android/real disposable server and applicable R8 checks. Do not launch every emulator for a documentation-only change or add new workflows when an existing shard can carry the case.
2. Changes to shared Add, source/identity, activity navigation, persistence, remote RPC or operation gating require their relevant installed actual user journeys and negative controls, not mock-only approval. Android UI changes require narrow/large-font/IME/TalkBack semantic checks; media enrichment changes require real consent/network/provider plus no-request negative checks.
3. Use approved CI selectors and build-once artifacts where byte identity can be independently verified. Do not reuse a previous head's APK/test result for changed source; require source SHA, Git tree and actual tested APK digest to match the accepted candidate.
4. Preserve full native Add shard coverage and final verifier; never reduce test counts or skip a failing test to force green. Respect the case-count/receipt rules in [CI-EVIDENCE-AND-RUNTIME-CONTRACT](CI-EVIDENCE-AND-RUNTIME-CONTRACT.md).
5. Genuine BitTorrent acceptance requires actual verified pieces/peer evidence in disposable authorized servers and installed phone clients. Server RPC ACKs, mock peers, started flags and cached size are insufficient. Protected host tests require the existing explicit authorization and secrets policy.

## Shared native-focus failure diagnosis

If the installed native Add shard reports `ClipboardUriIntakeInstrumentedTest.singleCopiedUriPrefillsButAmbiguousOrUnrelatedUrisNeverDo` at the **foreground window focus** precondition, preserve the executed failure. Inspect the actual focused window/activity and owning package, UI/system overlays, emulator API/startup and JUnit receipt before making a QA-only fixture change. Keep the explicit foreground requirement and all clipboard-source negative assertions; never skip the case, remove focus preconditions, or classify earlier red runs as green. Avoid separate speculative test patches in each production branch. A validated QA correction must run full native shards/verifier at the exact integrated source.

## Diagnose red CI before modifying a test

- Identify exact tested SHA, workflow/run/attempt, stage, expected and executed case identity, last safe checkpoint and environmental preconditions. Distinguish checkout/build, emulator boot, fixture setup, assertions, network/auth/provider refusal and CI infrastructure faults.
- Preserve the first genuine red and all retries in the ledger, even after a passing fix. Do not rerun indiscriminately: isolate failing proof, fix the cause and rerun the full required gate on the exact changed head.
- If the runner never reached a new assertion, say so explicitly; do not classify that assertion passed or failed. No all-skipped or missing receipts accepted.
- Never print private credentials, tracker tokens, user paths or raw server errors in CI logs, artifacts, PR descriptions or public issue text.

## Merge and release acceptance

The integrator must independently review PR diffs, source ancestry/overlap and required check execution. Green on an older parent does not qualify a rebased dependent. Use current GitHub state, not a dated handoff, before merging.

A source-matched installable debug APK may be identified as a tested pre-alpha only after confirming exact source and independently hashing the **actual installed and tested** APK bytes. Record receipt in [QUALITY-LEDGER](QUALITY-LEDGER.md). Physical Pixel, protected Seedhost, publisher-signing, OEM/Doze and human accessibility are separate evidence tiers and cannot be inferred from emulator tests.

## Time and cost discipline

Reuse genuine fixtures, cached Gradle and signed-off source artifacts only where compatible. Prefer narrow ordered feedback to parallel full-suite fan-out; measure actual runner minutes, attempts, source heads and failure cause before proposing costly changes. Keep always-emitted merge status and safety gates intact. Do not turn optional workflow skips into a passing acceptance claim.

## Mandatory next-push UI/media scenarios

Follow [next-push acceptance](NEXT-PUSH-UX-RECONCILIATION-2026-11-09.md): same compact list/full Details in All/device/server scopes, single-row four-root footer at 320/412dp and 200%/IME, two/three-across action groups, conditional Add-only art option (global enabled-by-default preference and separate provider consent), no active Details toggle, actual background art and persistence, and live phone/rTorrent source → Add → Transfer → Details → reconnect → Stop/Start → Remove with independently verified pieces. Do not claim a docs update satisfies these tests.
