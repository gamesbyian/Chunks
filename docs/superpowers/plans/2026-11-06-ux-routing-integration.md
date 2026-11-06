# UX and routing integration implementation plan

Goal: preserve the active UX cluster, resolve reproducible qualification failures, and advance privacy-safe Add and deterministic routing in independently tested increments.
Architecture: retain existing local/rTorrent executors and job-only Move. Reuse the media controller and exact environment/hash keys. Routing evaluation remains pure until verified Add integration is qualified.
Tech stack: Kotlin, Compose, existing Android/JVM and installed workflows.
Spec: SMART-ROUTING-AND-SOURCE-LIFECYCLE.md; UNIVERSAL-NAVIGATION-AND-TRANSFER-DISCLOSURE-2026-11-05.md.

Execution card: outcome = coherent navigation/disclosure and consented enrichment; evidence = live PR #1325/26/28/29/30 and main failures; authority = owner autonomous campaign; intervention = preserve their unique patches on current main; proof = focused regressions then one assembled exact-head CI; exit = independently reviewed green integration or explicit red handoff.

- [x] Reconcile main and active PR diffs; integrate both stacks and automatic enrichment without losing tests. Conflicts in four document headers retain both normative additions.
- [ ] Reproduce stale first-run assertion and clipboard failure; inspect installed diagnostics, fix causes with targeted tests, preserve original red evidence.
- [ ] Qualify one candidate: all JVM tests, native Add shards, installed real rTorrent/Combined, API29 and R8 as selected by current workflows. No merge on red.
- [x] Implement durable per-transfer automatic exclusion at controller/dispatch and Details boundaries. Add admission remains a separate next slice. Test zero provider requests, restart, same hash in different environments, in-flight results and manual choice preservation.
- [x] Implement pure routing evaluation with ordered predicates, missing evidence, confidence, explicit user override/fallback and exact target availability; table-driven positive/negative tests. No torrent/payload mutations.
- [ ] Independent whole-branch review; update WORK-QUEUE, SPEC-COVERAGE and QUALITY-LEDGER with exact proof and remaining work; commit and create cohesive PRs.

Review focus: clipboard focus and inherited input; filtered versus genuine empty state; excluded jobs after restart/reconnect; concurrent automatic results overriding user edits; unknown/locked destinations. Android physical/human and no-payload staging remain separate unverified tiers.
