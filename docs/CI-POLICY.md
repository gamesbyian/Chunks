# Lean CI and evidence economy

Updated after Chunks critical review. Borrow Glimpse's native build proof, ROYGBIV's cheap focused gates and UR-Recomp's exact source/evidence accounting, without transplanting their expensive workflows.

## Automatic CI

One PR Android build/test job when app/Gradle/toolchain sources change. Compile debug APK and run JVM tests on pinned, documented toolchain. Upload short-lived unsigned debug artifacts. Do not run both main-push and PR versions for identical source changes. Docs-only plans need editorial review, not a full Android runner.

Do not use Secrets in fork/PR builds. Never connect the CI runner to a private rTorrent host merely to validate a public pull request. Keep live server tests manually authorized, with sanitized result summaries.

## Targeted escalation (manual first)

- XML-RPC parser and transport changes: deterministic fixture and fuzz/size/error cases, plus a bounded integration matrix when justified.
- Server state mutations and idempotence: synthetic test service and disposable rTorrent host; verify real acknowledgement and no duplicate actions on response loss.
- Filesystem changes: independent fault injection using disposable datasets, multiple volumes and process kills at each journal checkpoint. Fail closed if recovery cannot be proved.
- Android UI changes: occasional targeted emulator screenshot/hierarchy atlas and real handset walkthrough, not a permanent broad screenshot job.
- Chunks-only display aliases and smart naming: deterministic JVM corpus (release-group cleanup, episode/year, unknown suffixes, adversarial/bidi text, no invented metadata), plus one focused native UI/instrumentation journey (suggestion stays draft until saved, reset, persistence across restart and separate endpoint/account/hash identity). Inspect actual populated queue pixels, 200% text and TalkBack, and confirm editor code cannot issue RPC or change backing-file mapping. This gate does not require a destructive Seedhost test, and neither existing baseline-login screenshots nor a passing APK compile prove it. Avoid creating a permanent dedicated CI workflow solely for naming.
- Local engine background: device tests across Android background/service types and network conditions; do not use one green assembly to infer reliability.

## Receipts and stopping rules

Each experiment records source SHA, build/test variant, environment, questions, inputs (redacted), what was actually measured, outcome and missing coverage. A run can be success, meaningful negative result, inconclusive or infrastructure failure. Stop when more experiments will not change an active design/release decision. One job summary plus a small reproducible result suffices; raw media/file inventories never enter public artifacts.

Normal branch protection should only require consistently present checks; never require an opt-in or path-skipped check. Release gates in QUALITY-LEDGER.md are independent of merge validation.

## Retained manual real-rTorrent experiment

`disposable-rtorrent-probe.yml` is manual-only after the one-time successful real rTorrent 0.9.8 run [40060755245](https://github.com/gamesbyian/Chunks/actions/runs/40060755245). It installs rTorrent and nginx in an ephemeral runner and runs a strictly read-only Kotlin smoke test. Do not reintroduce PR/push fanout without showing a new durable compatibility gate and an appropriate runner budget. The normal Android PR check remains the sole automatic job for app source changes.
