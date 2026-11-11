# Verification and efficient CI

## Evidence ladder

- **E0:** source review, design proposal, screenshots or pure deterministic model tests; no running Android proof.
- **E1:** JVM / isolated simulated endpoint tests; strong algorithmic evidence, not OS/provider interoperability.
- **E2:** installed *production-connected* Android app on known emulator/API, genuine OS intent/provider/service/Room and exact byte or state assertions. A test-only provider must be separately installed and absent from release APK.
- **E3:** genuine controlled/protected external protocol endpoint, with explicit host, account and operation authorization. Read-only access does not authorize remote destructive mutations.
- **E4:** actual handset, vendor/cloud provider, OS accessibility service, performance and owner visual/tactile acceptance, each explicitly tested.
- **E5:** stable signing/release artifact + upgrade preserving populated user data; separate signing identity from an isolated same-run temporary test key.

These categories are descriptive, not an automatic universal release checklist. State what tier is relevant to the feature. Count **executed** assertions, not merely job success. Check source SHA, fixture, artifact/runner and OS version.

## User-journey acceptance matrix

Every risky mutation must have (1) normal positive user journey through shipping path; (2) exact-target observable bytes/records after readback; (3) denied/stale/foreign target negative; (4) cancellation/interrupt/process death with source preservation and truthful status; (5) stale-session/permission/network change where applicable; (6) actual accessible UI reaching the action. For UI include 320dp-class and current device-size displays, 200% fonts, focus, keyboard, TalkBack, 48dp touch policy, reduced motion, small-screen controls, loading/error/empty and landscape/IME interactions.

A genuine UI test can fail *before reaching* the operation because of DocumentsUI visibility, clipped buttons, stale finders or emulator boot. Record the last reached assertion. Do not weaken test by pre-granting OS permissions, replacing picker with a fake callback or claiming byte acceptance from an activity launch.

## CI dispatch is executable product logic

- Classify changed files by old and new rename paths, shared dependencies and transitive risk. If the workflow's own GitHub paths filter suppresses dispatch, the downstream classifier cannot fix it. A small always-on selector can issue stable required-check results for docs-only while conservatively dispatching native checks on ambiguous/renamed/source changes. Test selector and trigger topology offline.
- Give each workflow a reason to exist. Avoid native/emulator builds for unrelated web/docs. Separate artifact sharing from sharing mere YAML instructions. Measure total runner cost and critical path before sharding/fan-out/parallelism.
- Preserve exact head, stable required check contexts, merge-queue behavior, clean ownership of protected credentials and no privileged execution of untrusted PR code. Unknown GitHub API pagination >3000, stale head, selector errors and mismatched counts must fail closed.
- Publish one compact agent-readable failure digest: first meaningful error, classification (product/harness/emulator/network/compile/unknown), file/test, exact SHA/API/variant, executed/skipped counts, reproduction command, full artifacts and redaction. Preserve primary failure even if teardown fails. Distinguish original fail from rerun green; do not silently retry/continue-on-error.
- If a signed or diagnostic APK is published, include immutable provenance, exact artifact SHA256, expected filename and signer certificate fingerprint. A standard debug compile is not a distributable release proof.

## Merge discipline

Review final diff against current main and accepted requirements, not a stale parent branch. Independent review for file loss, secret handling, native JNI and signer changes. After merge verify that GitHub issue closing keywords did not falsely complete open work. Report historical red and current green separately. Scope what each green test actually demonstrated.
