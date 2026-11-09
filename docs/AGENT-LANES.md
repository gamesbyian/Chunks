# Chunks agent work allocation and file ownership

**Purpose:** assign independent work, seams and merge authority. History is in [AGENT-LANES-HISTORY-2026-11-09.md](AGENT-LANES-HISTORY-2026-11-09.md). No agent has a permanent lease: check live branches, files, PRs and current owner instructions before starting.

## Start every agent

1. Inspect live `main`, all open PRs/diffs/checks, [AGENTS](../AGENTS.md), [WORK-QUEUE](WORK-QUEUE.md), [SPEC-COVERAGE](SPEC-COVERAGE.md), [QUALITY-LEDGER](QUALITY-LEDGER.md) and [next-push handoff](NEXT-PUSH-HANDOFF-2026-11-08.md).
2. Take one actionable, non-overlapping slice; announce scope/ownership and coordinate a shared interface before editing another lane's production files.
3. Maintain exact source environment/account/hash/Place/Start, one Add authority, privacy and failure fences. Test new head; submit a small PR and let Agent 4 decide merge after independent qualification.
4. Record **what remains** in active instructions; append execution logs and receipts to the evidence ledger or archive.

## Agent 1: navigation and presentation

**Own:** MainActivity route/presentation, `CombinedTransfersScreen`, `AuthenticatedTransfersFooter`, shared action layouts, Options UI, unified list and full Details UI, focused Compose/JVM tests. Finish blocked #1442 first or coordinate supersession; #1454 is the next slice. Make All/device/server one view scoped by filter; footer always one row; action group 2–3 across; maintain accessibility and all actions. **Do not touch** shared Add coordinator/phone source parser, rTorrent transport, central docs or CI workflows.

## Agent 2: Universal Add and Android intake

**Own:** Universal Add coordinator/phone intake/session and per-job Add-time artwork preference seam, scoped tests. Clear #1449/#1450 source integrity or supersession before new Add artwork work. Ensure global feature-enabled condition and Add-only default-checked checkbox, exact-environment/hash opt-out persisted at admission; no toggle in active Details after the presentation owner removes it. **Do not touch** remote transport, navigation/Options implementation, integration docs or workflows. Agree UI seam with Agent 1.

## Agent 3: authenticated rTorrent and enrichment seam

**Own:** authenticated rTorrent adapters, precise remote readback, source identity and media enrichment scheduling/provider authorization in disjoint owned files. Diagnose #1443/#1444 failure gates; preserve actual server 401, exact-account/hash/Place and no replay. Audit offscreen all-environment automatic metadata lookup and poster persistence (#1453) without new transport/executor or unauthorized outbound requests. Coordinate shared presentation with Agent 1 and Add opt-out with Agent 2. **Do not touch** central docs/CI or phone input.

## Agent 4: independent qualification and integration

**Own:** focused QA and installed test artifacts, CI selectors/evidence, canonical docs, issue/PR status reconciliation, independent merges and source-tree/APK-byte provenance. Reject red/skipped/stale heads, test actual peers/pieces and phone HTTPS, merge qualified compatible slices only. Publish an APK only with matching tested bytes and accurate limits. **Do not implement** the three lanes' production UI/coordinator/engine in parallel.

## Cross-lane protocol

Shared files are **single writer**. If a change spans seams, write/agree an interface contract and land in dependency order. No self-merge, no blanket agent file locks, no newly invented submission authority, no blind recovery or private metainfo leakage. Prioritize current queue, and keep evidence narrations out of these instructions.
