# Chunks next-push handoff: executable entry

This is a replaceable orientation card, not a status log or competing queue. [Historical handoff through 2026-11-25](NEXT-PUSH-HANDOFF-HISTORY-2026-11-25.md) preserves prior commands, decisions and evidence.

## 0. Recheck source and isolate current work

Read [AGENTS](../AGENTS.md), inspect live main/open PRs/checks, select the first unblocked [R0 or other ranked queue task](WORK-QUEUE.md), and inspect its relevant [technical capability](TECHNICAL-COMPLETENESS-LEDGER.md) and contract. Check actual overlap and serialize common owner files. Backend-first; atlas deferred; no owner PC.

## 1. Advance the current technical blockers

Follow the queue, not historical PR numbers. In particular, finish installed process-death/uncertainty evidence for #2026 before major UI-host ownership extraction. Trace existing retained Add authority before refactoring. Keep physical Pixel provider/task/TalkBack (#1786), OEM/VPN (#569), protected-host and signed release (#10) distinct. Use the [pass-2 audit](audits/ENGINEERING-AUDIT-2026-11-25.md) for rationale, not another queue.

## 2. Run real-engine and Android acceptance honestly

Use [CI policy](CI-POLICY.md) and [real transfer acceptance](REAL-TRANSFER-ACCEPTANCE.md). Exact-source installed debug routing now shares APK pairs across six compatible families; independent R8, foundation and protected gates remain. Use applicable offline preflights before Android. Disposable rig: `tools/interop/start_local_clients.sh build/interop && source tools/interop/local_env.sh build/interop`; use pinned Gradle 9.3.1 and actual daemon versions. Follow [Compose virtualization contract](TEST-ARCHITECTURE.md#compose-lazy-containers-virtualization-contract) and the existing LazyComposeActions; re-find controls and do not blindly repeat writes. [Release checklist](RELEASE-ACCEPTANCE.md) defines assembled acceptance.

**Local first:** an agent container can run the CI-equivalent JVM suite and the real-daemon interop selection before hosted CI ([BUILD local toolchain](BUILD.md#local-jvm-toolchain-for-agent-containers): JDK 17, UTF-8 locale, 429 retries). Without `/dev/kvm` the emulator tiers stay hosted. Credential storage changes trigger `credential-upgrade-witness.yml`; dispatch it with an older `base_ref` at release. Broad catches in suspend code must match `tools/coroutine_cancellation_reviews.json` (fix the code or add a reviewed SAFE entry). Known intermittent installed reds: #2104, #2108, #2109; rerun once for diagnosis, never cite a rerun pass as the fix. Before citing a rarely run probe, see the [workflow classification](audits/CI-WORKFLOW-CLASSIFICATION-2026-11-25.md); several are red from runner drift.

## 3. Stop and report

Owning PR/issue holds the exact source, actual tests, outcomes, failure/recovery evidence, remaining tiers and next action. Only update the queue if task disposition changes; change this card only when the orientation procedure changes. Use [evidence index](QUALITY-LEDGER.md), not repeated historical paragraphs.
