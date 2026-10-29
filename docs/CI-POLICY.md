# Lean CI and evidence economy

**Documentation-only changes:** validate repository-local targets offline with `python3 tools/check_docs.py` and its unit tests. Do not dispatch Android, native, host or font Actions merely to validate Markdown. A docs PR merged to main does not justify an unrelated font fan-out; retain path-specific workflow triggers and the [font-lab isolation contract](design/CHUNKS-FONT-LAB-OPERATIONS.md). See [documentation standard](DOCUMENTATION-STANDARD.md).

Updated after Chunks critical review. Borrow Glimpse's native build proof, ROYGBIV's cheap focused gates and UR-Recomp's exact source/evidence accounting, without transplanting their expensive workflows.

## Font-lab exception: one quiet branch (owner, 2026-10-28)

All routine font development goes to [`font/lab`](https://github.com/gamesbyian/Chunks/tree/font/lab), with **no open PR** and **local first** CFF/kerning/specimen validation. Current font push triggers are restricted to `main`; PR checks trigger when a font-diff PR is opened. A lab edit must not routinely dispatch Actions or open new PRs. Freeze meaningful batches, submit one temporary promotion PR, and perform the full relevant exact-head checks only then. Keep independent Android/UI checks for app work. See [font lab operating contract](design/CHUNKS-FONT-LAB-OPERATIONS.md) and [font trigger details](design/CHUNKS-FONT-CI-TRIGGER-POLICY.md).

## Automatic CI

One PR Android build/test job when app/Gradle/toolchain sources change. Compile debug APK and run JVM tests on pinned, documented toolchain. Upload short-lived unsigned debug artifacts. Do not run both main-push and PR versions for identical source changes. Docs-only plans need editorial review, not a full Android runner.

Do not use Secrets in fork/PR builds. Never connect the CI runner to a private rTorrent host merely to validate a public pull request. Keep live server tests manually authorized, with sanitized result summaries.

**API35 instrumentation trigger (2026-10-28):** `native-add-instrumentation.yml` now also runs for `app/src/main/**` changes. Two production-only PRs the same day (#460 added a read-only RPC call; #463 renamed the Connect field) left the instrumented suite red on main because it only triggered on `app/src/androidTest/**`. The ~8-minute emulator run on app PRs is cheaper than diagnosing red main afterwards. Docs, font and design changes still do not trigger it.

## Opt-in dataSync timeout repro (#569, 2026-10-29)

`android-fgs-timeout-repro.yml` runs one class, `LocalEngineServiceTimeoutInstrumentedTest`, through `tools/fgs_timeout_repro.sh` on a disposable API35 emulator.
- **Setup:** it applies Android's documented test controls (`am compat enable FGS_INTRODUCE_TIME_LIMITS`, `device_config … data_sync_fgs_timeout_duration 15000`) and restores both on exit.
- **Triggers:** manual dispatch, plus PRs that touch the service timeout files, the test, the script or the workflow. It is not an app-wide fan-out.
- **Skipping:** the ordinary native suite skips the class through an instrumentation-argument assumption. The script fails if the test was skipped or did not report one pass.
- **Scope of evidence:** a pass proves shortened-limit handling on that emulator only. It does not cover the six-hour budget, Doze, OEM killers or handsets.
- **Sudden process death:** the same job then runs `tools/process_death_repro.sh` (`LocalEngineProcessDeathInstrumentedTest`).
  - **Phase A** seeds under the service and SIGKILLs its own process.
  - **Phase B**, in a new process, must find no leftover ongoing notification, the transfer restored stopped, unchanged bytes and a 100% recheck.
  - **Failure conditions:** the script fails unless phase A armed and actually died, and phase B passed without being skipped.

## Manual real-peer lifecycle modes (#568/#569)

`android-public-swarm.yml` is manual-only, needs no secrets and uses the sanitized Debian netinst. It has these exclusive modes:
- **default:** download a verified budget;
- `interrupt`: SIGKILL mid-download, then check restore and continuation;
- `doze`: forced deep idle during a foreground download;
- `netflap`: Wi-Fi and data off for 30 s, then require recovery.

A peer shortfall is reported as INCONCLUSIVE, never as engine acceptance. Each run is emulator evidence only.

## Targeted escalation (manual first)

- XML-RPC parser and transport changes: deterministic fixture and fuzz/size/error cases, plus a bounded integration matrix when justified.
- Server state mutations and idempotence: synthetic test service and disposable rTorrent host; verify real acknowledgement and no duplicate actions on response loss.
- Filesystem changes: independent fault injection using disposable datasets, multiple volumes and process kills at each journal checkpoint. Fail closed if recovery cannot be proved.
- Android UI changes: occasional targeted emulator screenshot/hierarchy atlas and real handset walkthrough, not a permanent broad screenshot job.
- Local engine background: device tests across Android background/service types and network conditions; do not use one green assembly to infer reliability.

## Receipts and stopping rules

Each experiment records source SHA, build/test variant, environment, questions, inputs (redacted), what was actually measured, outcome and missing coverage. A run can be success, meaningful negative result, inconclusive or infrastructure failure. Stop when more experiments will not change an active design/release decision. One job summary plus a small reproducible result suffices; raw media/file inventories never enter public artifacts.

Normal branch protection should only require consistently present checks; never require an opt-in or path-skipped check. Release gates in QUALITY-LEDGER.md are independent of merge validation.

## Retained manual real-rTorrent experiment

`disposable-rtorrent-probe.yml` uses narrowly scoped pull-request paths and manual dispatch after the one-time successful real rTorrent 0.9.8 run [40060755245](https://github.com/gamesbyian/Chunks/actions/runs/40060755245). It installs rTorrent and nginx in an ephemeral runner and runs a strictly read-only Kotlin smoke test. The PR trigger now deliberately covers the gateway adapter, both real-engine acceptance tests and the workflow YAML itself (merged #153; both legacy and modern real-engine jobs passed at exact head `ecb029e7ecdd9eff7d5bc5c940da404303f1ae5c`, [run 40372755722](https://github.com/gamesbyian/Chunks/actions/runs/40372755722)). Do not broaden it to every app/Gradle edit without a specific compatibility reason and runner budget. The normal Android PR check remains the sole automatic job for app source changes.

## Modern compatibility gate exception (October 26, 2026)

PRs #150 and #153 use **path-scoped** pull-request triggers for `disposable-rtorrent-probe.yml` itself to obtain evidence on materially different Arch Linux rTorrent 0.16.x. Its legacy and modern tests pass on exact source `2b9d2c3e6291c95fa260bdaaf634527d14ba6186` ([40368534273](https://github.com/gamesbyian/Chunks/actions/runs/40368534273)). It is **not** a general automatic app-source fan-out; no secrets, user media or hosted server is involved. Alpine 0.15.2 remains excluded and tracked in #151 after repeated HTTP 502 SCGI failures.

## Smart Connect safety and provider acceptance (2026-10-28)

[SMART-REMOTE-CONNECTION](SMART-REMOTE-CONNECTION.md) and
[SPEC-COVERAGE](SPEC-COVERAGE.md) control read-only discovery tests.

- **Cheap deterministic PR gate:** URL/hostname normalization, provider
  candidate ordering, exact-numbered-tenant preservation, account versus
  HTTP-auth username distinction, bounded candidate count, malicious
  `userinfo`/query/fragment/path input, HTTP downgrade refusal,
  certificate/auth/timeout classification, fallback after missing RPC
  mount, and no retry after 401/403/certificate failure.
- **Transport fixture:** real Kotlin `RTorrentRemote` against disposable
  authenticated Basic/Digest/nginx SCGI/ruTorrent HTTP-RPC endpoints,
  including 200 HTML login page, 401/403/407, redirect, TLS wrong host,
  expired or self-signed certificate, unsupported introspection,
  incomplete view, duplicate hashes, oversized XML and unknown methods.
  Separate capability observations from mutating-operation guarantees.
- **Manual protected provider tests:** normal PRs, forks and unreviewed
  commits receive no live server secrets. `seedhost-endpoint-compatibility.yml`
  is manual-only, trusted-main-only and selects exactly one case-sensitive
  credential mapping at a time. Independently verify the three numbered
  HTTPS routes, then the direct-SCGI HTTPS *candidate* as a separate
  hypothesis. Record source SHA, workflow run, endpoint family (not
  password), method/list evidence, and inconclusive/negative results.
- **Actual Android acceptance:** API35 Compose login and at least one
  real physical handset with certificate validation, network changes,
  200% font, TalkBack, foreground/background reconnect, cancellation
  and per-profile privacy gating. No screenshot can include a password
  field, authorization challenge, passkey, session cookie or tracker
  token. Success of JVM planning alone cannot close this gate.
- **Budget:** path-scoped fixtures and one selected real provider profile
  per manual dispatch rather than broad hourly loops. No API mutation
  probes during discovery. Server write capability acceptance is a
  separately authorized, exact-target and readback-gated workflow.

An older tested commit passing CI does **not** validate a later merge or
doc/code head. PR #463's earlier Android/JVM [run 40760083435](https://github.com/gamesbyian/Chunks/actions/runs/40760083435)
and native visual [40760083286](https://github.com/gamesbyian/Chunks/actions/runs/40760083286)
were successful on head `8922760cf81a11fb299a96c0013ea833f2845896`.
This is source/emulator-tier evidence only, not a verified live provider
or physical handset connection; the rebased and supplemented head must
be checked independently.
