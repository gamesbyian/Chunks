# Claude app-development handoff (2026-10-28, 14:45 MDT)

This is a **dated operational snapshot**, subordinate to live GitHub state and [AGENTS](../AGENTS.md), [WORK-QUEUE](WORK-QUEUE.md), [QUALITY-LEDGER](QUALITY-LEDGER.md), [CI-POLICY](CI-POLICY.md), and the product/security contracts. **Refresh before acting.** Do not create a second priority queue.

## Repository reconciliation

- Active app developer: Claude handoff recipient. A separate font worker owns OTF/CFF source, typography validation, glyph reviews and related font PRs. Avoid touching font or reference-art branches, files or issues unless directly requested.
- This session **squash-merged #418** (one-at-a-time stopped local snapshot restore), exact PR head `b4ed2d03f0ef36f616b13a747160d92670b1da58` with successful Android foundation, native instrumentation and disposable-rTorrent runs. Merge commit `ddcff89062f6bc9858bac23d47ac9f390ffeb346`. Do not claim physical-device, remote restore, or batch restore acceptance.
- Other initially open PRs: **#423** reviewed episode-first *one-time* native file-priority focus, **#463** bounded same-origin HTTPS Smart Connect discovery, **#468** cross-engine job-only Copy/Move owner correction, **#406** artwork source/fallback/gallery and format-specific override documentation, **#445** font Android rasterization, **#469** font provenance audit. At initial inspection #423 and #463 had exact-head green runs, but both became nonmergeable after #418 landed; #468 and #406 were already conflicting. Resolve conflicts carefully against latest main and owner authority. #445 was still running; #469 font checks passed, but font worker owns both.
- GitHub classic combined commit statuses were empty; **actual workflow runs were queried separately**. Do not interpret an empty combined-status array as passing CI. Recheck exact SHA and all required jobs after every rebase/merge. Never merge while jobs pending or skipped critical acceptance gates.

## Owner corrections requiring special care

- **Copy/Move Transfer** between a local phone client and rTorrent copies/moves the *torrent job/definition*, **not downloaded files**. Copy creates a separately operable job at destination, beginning from its own available data; Move adds independent destination verification and then explicit source-job-only removal, **retaining source payload**. Requires durable uncertainty handling, identity/scope/permission controls and no blind retry. #468 must be reconciled into operative specs before implementation. The separately existing explicitly named **payload Copy** from Seedhost to phone is a different capability/operation and has verified byte-transfer evidence; keep its labels and code distinct.
- Smart Connect #463 is a first safe slice; no general provider compatibility claim. Never autodowngrade HTTPS, guess another tenant or send credentials to unbounded hosts; direct Seedhost SCGI and ruTorrent paths require scoped proof. Follow #464 and [SMART-REMOTE-CONNECTION](SMART-REMOTE-CONNECTION.md).
- Finish in Order #415 is adaptive usable-file completion scheduling; #423's static one-time batch is groundwork, not full completion. Keep under explicit priorities, with correct piece availability and independent readback, never promise guaranteed ordering.
- Art/metadata #406 specifies square/poster/banner roles, provider fallback across media categories, opt-in safe search, gallery selection and user-upload/URL overrides with scoped inheritance. Do not present docs as shipped feature.
- Distinguish server→phone **payload copying**, proven on a real Seedhost against the server's bound address `5.79.112.219`, from phone↔server **job handoff** above. Peer wire and management HTTPS/XML-RPC ports are unrelated.

## Highest-value development approach

1. Refresh PRs and CI. Integrate qualified #423, #463 and corrected #468/#406 without discarding simultaneous main changes. Tackle conflicts on independent topic branches; when overlapping content exists, preserve both intentions and current authoritative safety rules.
2. Repair genuine red CI by reading job-level logs and reproducing first; do not remove assertions, skip tests or relax guards simply to go green. Run focused cheap tests and targeted API35/rTorrent gates per CI policy.
3. Keep parallel **non-overlapping feature slices** moving whenever tests/CI are pending: local transfer completion/priority flow; remote connection wizard; job-only handoff; remote snapshot restore; batch actions; archive provenance and extraction; transfer artwork/metadata; Android storage, performance, accessibility and lifecycle; product UI. Only one editor at a time for Gradle, engine interfaces, root navigation and canonical contracts.
4. Require observable functionality plus negative/stale/uncertain-path tests in each PR. Maintain fresh exact-head ledger receipts, update owning issue/work queue and acceptance map after merges, and accurately distinguish desktop/JVM, emulator, real Seedhost and physical handset evidence.
5. Prioritize release-blocking phone physical swarm and lifecycle, phone→Seedhost Add, high-volume inventory correctness, accessibility, safety and signed distribution. Never infer release readiness from green CI alone.

## Handoff finish contract

Report precise merged/open/conflicting PRs, latest main SHA, failing job IDs with root causes, test receipts and environments, concrete implemented features, outstanding device/human/remote gates, updated docs/issues and next runnable tasks. Don't idle on CI; pick another independent app slice. Don't touch font-worker files.
