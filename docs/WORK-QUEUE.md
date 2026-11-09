# Chunks active work queue

**Purpose:** Do the next work. This file must contain only ranked tasks, blockers, dependencies, owners, required actions, exit criteria and scope restrictions. Put PR outcomes, historical SHAs, failure narratives and test logs in [QUALITY-LEDGER](QUALITY-LEDGER.md) and [archived work queue](WORK-QUEUE-HISTORY-2026-11-09.md). Do not prepend dated progress paragraphs. Recheck live state before acting.

## 0. Reconcile current code and active PRs before touching production

**Integrator (Agent 4):** Fetch live `main`, open PRs/heads/checks, issues and file overlaps. Record an ephemeral execution card, not an ongoing status essay. Inspect existing PRs #1442 (Options clipboard), #1443 (read-only remote contradiction Recheck), #1444 (typed 401 challenge), #1449 (malformed Share extras), #1450 (uppercase MAGNET VIEW); these numbers are **starting pointers**, not assertions that they remain open. Ask lane owners to repair observed failures and enable/execute required native and R8 checks on the new exact heads.

**Done when:** incompatible or obsolete stacks explicitly resolved; each merged PR has independent exact-head acceptance, no masked failed assertions and no cross-lane file conflict. Never merge because an earlier head was green. Preserve frozen Add source/environment/account/Place/Start and no blind retries.

## 1. Implement one canonical Transfers and full Details experience (P0, #1454)

**Agent 1, presentation/navigation:** Route All environments, Transfers on this device and any named server through one Combined-derived catalog, compact `CombinedTransferRow` and dedicated `UnifiedTransferDetailPanel`. Remove legacy inline `detailsOpen` card mechanics, retain complete advanced actions in full Details/menus. Environment selection must filter only, never grant command authority or silently switch accounts. Preserve search, list state, navigation, duplicate hashes, stale/locked status and saved-profile exact identity.

**Done when:** installed All/device/server journeys use indistinguishable row mechanics, always open full Details and return preserving scope/scroll; real local and rTorrent actions remain reachable with correct auth/readback gating.

## 2. Reclaim vertical space without sacrificing reachability (P0, #1454)

**Agent 1, shared presentation:** Replace the stacked branch in `AuthenticatedTransfersFooter.kt` with a **single-row** persistent footer: **Transfers | + Add | Connections | Options**. Always operable from nested screens and with keyboard visible. Use compact icons **and** text labels. Group ordinary action buttons two per row, or three when short; use a shared responsive composable. Keep >=48dp-class touch targets, TalkBack and focus, readable 320/412dp and 200% text; avoid accidental destructive adjacency and hidden functions.

**Done when:** actual installed screenshots and interaction tests prove one-row navigation at normal/large type and all representative action groups have appropriate density; measured visible transfer content area increases. Do not silently clip buttons or replace labels with inaccessible icons.

## 3. Correct automatic artwork preference placement and default (P0, #1453)

**Agent 2, shared Universal Add:** In **Options**, global collection/display preference is enabled by default. In **Add only**, show default-checked “Automatically identify and fetch artwork & metadata” when that global feature is enabled, otherwise hide it. Commit an explicit Add opt-out to the exact environment+hash using the existing coordinator and `prepareForAdd` boundary, with restart/Share/reconnect parity. Remove the toggle from active Details; leave manual `Change identification or artwork` and poster override there. Coordinate the visual seam with Agent 1 without concurrent shared-file edits.

**Done when:** checked/unchecked/hidden Add states and durable exact-job preference verified on phone and rTorrent; active Details has no checkbox, and opt-out never allows background provider requests. Global default-on is not implicit external-provider consent.

## 4. Make media recognition and artwork actually automatic (P0, #1453)

**Agent 3, media/remote integration, coordinated with Agent 1 and 2:** Trace consent, global automation preference, metered/power-saver, provider eligibility/config, lookup cache/cooldown, observer authorization, offscreen inventory scheduling and poster fetch. Run bounded automatic enrichment for eligible authorized inventory at cold launch, connection restoration and newly observed jobs, not only on opened Details or composed list cards. Apply confident provider-backed matches, persist art and exact-instance overrides; never assert that a filename guess is provider verification. Surface informative paused/ambiguous/offline state without requiring an Identify button for normal flows.

**Done when:** real consented lookup and artwork appear in All/device/server lists and full Details across restart/reconnect, while opt-outs, manual overrides, blocked networks and privacy-locked environments behave correctly. No private raw paths/passkeys or unsanctioned requests.

## 5. Qualify assembled daily-use APK, then integrate (P0)

**Agent 4, independent QA:** Run complete installed source → Add → Transfers → full Details → reconnect → Stop/Start → Remove with phone HTTPS and genuine disposable authenticated rTorrent. Include two-server Combined, duplicate-hash identity, exact Place/name, checked source SHA/tree, Android API29/35, narrow/large-font/IME accessibility and consented artwork. Verify actual independent peers/pieces and extracted tested APK bytes with SHA-256. Preserve red/skipped/aborted history and qualify latest merged source, not a previous preview.

**Done when:** current integrated APK has a traceable tested-byte receipt, all required source-exact gates pass, issues and [SPEC-COVERAGE](SPEC-COVERAGE.md) are updated. Physical owner handset, protected production server, OEM/Doze, publisher signing and full manual accessibility remain distinct explicit gates.

## Invariants and sequencing

One universal Add submission authority, no competing database/executor, exact account and hash scoping, frozen source/Place/Start, read-only uncertain-write reconciliation, no blind mutation retries, explicit destructive confirmation, job-only Move never copies/deletes payload bytes, privacy locks before enrichment. Use existing reusable CI and fixtures; no broad workflow fan-out to bypass red tests. Workstreams 1–4 may overlap **only in separately owned files**, with Agent 4 controlling integration and documentation. See [agent ownership](AGENT-LANES.md), [CI policy](CI-POLICY.md), [security](SECURITY.md) and [current handoff](NEXT-PUSH-HANDOFF-2026-11-08.md).

## Deferred until P0 qualified

Later remote backends, advanced Smart Routing, artwork sharing/export, broader media graph, specialized UI polish and publisher distribution must not displace current installed daily-use correctness. Re-rank only on an explicitly observed critical integrity/security defect.
