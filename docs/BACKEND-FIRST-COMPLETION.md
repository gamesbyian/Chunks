# Backend-first functional completion mandate

**2026-11-23:** Owner-authorized [forensic findings](archive/FORENSIC-ENGINEERING-AUDIT-2026-11-23.md) are part of technical completion; follow [R0](WORK-QUEUE.md#r0-current-ownership-safety-and-arbitration). Proven safety gaps outrank discretionary features; measure before optimizing, extract actual ownership, preserve existing proof tiers and continue independent unblocked work. No rewrite or atlas campaign.

**Owner decision: 2026-11-16. Governs discretionary task selection and release-phase sequencing.** Read alongside [WORK-QUEUE](WORK-QUEUE.md), [COMPLETION-ROADMAP](COMPLETION-ROADMAP.md), [SPEC-COVERAGE](SPEC-COVERAGE.md), and [AGENTS.md](../AGENTS.md). This document changes prioritization, not existing product requirements or past evidence. Later explicit owner requests supersede it for their specific scope.

## Objective

The owner reports that the latest installed APK is already useful for everyday viewing, sorting, adding, removing data, media artwork and renaming. **Move the centre of gravity from incremental UI/UX polish to comprehensive technical capability, architecture and real-world correctness.** The current UI may remain a development-grade functional interface. Build, integrate and prove the machinery before the coordinated final visual/organizational refinement.

Do **not** interpret this mandate as permission to omit accessibility, functional user journeys, Android integration, meaningful status/error reporting, or safety checks. A new capability still requires the smallest honest, testable UI entrypoint when appropriate. Resolve breakage that blocks daily use, testing, security, recovery or data safety immediately. Do not polish an unfinished surface repeatedly when functional capabilities are unimplemented.

## Scope boundary: first fully featured Android app

Treat the existing approved Android phone/tablet application with bundled Local and rTorrent as the immediate technical completion target. Establish an explicit capability inventory rather than assuming every open issue is missing functionality. Include approved advanced capabilities within that target when they apply to this app: advanced priorities and Finish in Order, per-client seeding/ratio limits, smart categorization/routing, destination and file management, safe source lifecycle, relocation where technically feasible, portable snapshots, settings/persistence, connection handling, metadata/artwork, Android intake, background operation and restore, deletion integrity. Classify each by evidence and dependencies; do not claim all are absent.

Separate optional expansions whose breadth could create a perpetually moving finish line: additional remote backends, Android TV, widgets, companion server, additional media catalogues, and independent font laboratory. **2026-11-17 owner exception:** promote Transmission, qBittorrent and Deluge remote protocols before automatic Smart Routing so its eventual execution can be tested across clients. Track their acceptance separately rather than silently folding every possible backend into the initial Android core completion denominator. Existing Local/rTorrent integrity and Android intake regressions remain high priority. Keep them visible with explicit deferred scope and reassess when core Android technical acceptance is reached or the owner promotes them. Do not silently delete or demote requirements solely to improve completion counts.

For unsupported remote operations (e.g. directory creation/rename, data deletion, relocation), represent honest typed capabilities, blocked/unsupported reasons and safe fallback, not a fiction that all engines can do everything. Technically complete can include a proven fail-closed refusal for intrinsically unavailable operations.

## Default dispatch order for broad/autonomous agents

0. **Safety / regressions / integration first:** protect exact client/hash/Place/Start, no unwanted destructive writes, no blind replay, credentials/privacy. Reconcile open PRs, genuine red CI and owner-reproduced Android failures, including Open With. Never merge unqualified overlap.
1. **Functional specification inventory:** compare current source, tests, actual evidence and approved contract. Identify unimplemented or partial capabilities, exact missing acceptance, dependencies, and scope (core Android vs expansion). Update a concise status ledger before inventing work.
2. **Build missing capabilities:** prioritize foundational/high-leverage functionality and clean shared Local/remote contracts, settings schema and persistence, event/command lifecycle, storage safety and integration. Choose a coherent end-to-end vertical slice with functional UI only as necessary.
3. **Prove integration and resilience continuously:** installed Android, actual libtorrent and authorized real rTorrent/Seedhost transfers, verified peers/pieces and target readback, interruption/reconnect/cancel/duplicate/permission negatives. Test the changed boundaries, then run consolidated acceptance on one exact head. Never postpone all correctness tests to the end.
4. **Only after technical target substantially closes:** execute the owner's coordinated, cross-screen UX reorganization and final visual/accessibility polish, then full assembled release acceptance, distribution and maintenance preparation.

The earlier R0–R6 labels remain valuable as *categories and historical sequencing*; they are **not** permission to prefer R1/R2 cosmetic microtasks over unimplemented R5-class functionality under this newer owner mandate. Existing urgent R1 Android intake and Add command correctness still rank ahead of cosmetic work. Maintain approved UX designs intact for the final campaign. Fix a UI defect now when it prevents using/verifying a feature, causes a misleading or dangerous operation, or is independently requested by the owner.

## Capability ledger and measurable definition of completion

Maintain one concise, current matrix (see [TECHNICAL-COMPLETENESS-LEDGER](TECHNICAL-COMPLETENESS-LEDGER.md)) with:
- Capability and linked source/spec/issue; release scope and dependencies.
- Implementation: absent / partial / implemented / deliberately unsupported.
- Integration: isolated / end-to-end / blocked, with exact Local or remote variants.
- Evidence: unit, installed Android, real transfer + independent postcondition, recovery/negative and safety checks, by exact SHA and dates; **do not convert green mocks into real-world verification**.
- Experience: functional access / accessible / visual polish pending, tracked separately.
- Next concrete missing proof, blocker and owner decision if needed.

Use the ledger to choose the highest-impact unblocked technical deficiency. Distinguish already-working owner observations from universal proof, and do not reopen a resolved symptom without fresh reproducer. Avoid generating repetitive status-only PRs or retesting irrelevant obsolete UX contracts.

**Completion gates:** all in-scope capabilities implemented or explicitly capability-limited; consistent architecture and settings/identity ownership; realistic operational, recovery and data-integrity acceptance; consolidated exact-head release validation. A screen being beautiful, or the number of merged PRs/issues closed, is never proof of backend completion.

## Reporting

Each autonomous session should report: technical capabilities advanced, exact test/evidence tier, remaining gaps, important blockers, and the next highest-leverage functional task. Clearly identify any UI work performed because it was necessary for functionality versus elective polish. Never declare background agents running unless they actually have active dispatch/schedules.
