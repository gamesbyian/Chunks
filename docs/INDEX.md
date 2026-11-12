# Chunks documentation index

**Visual research:** [Torrent-client iconography atlas](research/torrent-iconography/README.md) compares six clients' source-backed symbols with the existing Chunks SVG drafts. Its [HTML gallery](research/torrent-iconography/gallery.html) and exported choices are non-normative decision aids, not an implementation queue.

**Current integration:** Determine live main and open PRs from GitHub, not a dated index entry. #1662 and #1663 supplied verified runtime advances; acceptance details, previous red CI and source SHA receipts live in [QUALITY-LEDGER](QUALITY-LEDGER.md). Active work and remaining experience acceptance are governed by [WORK-QUEUE](WORK-QUEUE.md) and [SPEC-COVERAGE](SPEC-COVERAGE.md).

**New binding design and recovery contracts:** [Eight solid-colour Options cards](design/OPTIONS-SOLID-COLOUR-INDEX.md) and [Options information architecture](OPTIONS-INFORMATION-ARCHITECTURE.md); [existing and foreign partial-data reuse](FOREIGN-PARTIAL-TORRENT-RECOVERY.md), [local collision policy](LOCAL-TORRENT-COLLISION-POLICY.md), and [existing-transfer file repair](TRANSFER-FILE-RECOVERY.md). These are planned product requirements, not implementation evidence.

**Current completion:** [ordered cohorts, dependencies and batched validation](COMPLETION-ROADMAP.md) → [one ranked work queue](WORK-QUEUE.md) → [next dispatch handoff](NEXT-PUSH-HANDOFF-2026-11-08.md) → [acceptance](SPEC-COVERAGE.md). [12 November source audit](archive/COMPLETION-AUDIT-2026-11-12.md) is historical.


**Start work in this order:** [AGENTS](../AGENTS.md) → [WORK-QUEUE](WORK-QUEUE.md) → [NEXT-PUSH-HANDOFF](NEXT-PUSH-HANDOFF-2026-11-08.md) → [dispatch coordination](AGENT-LANES.md) → [SPEC-COVERAGE](SPEC-COVERAGE.md). Consult [QUALITY-LEDGER](QUALITY-LEDGER.md) for evidence, **never as a second task queue**.

**Branding:** [Android and full product naming contract](BRANDING-AND-APP-IDENTITY.md) covers the concise Android label, full store title, in-app wordmark and QA preview names.

**Product instructions:** [PRODUCT](PRODUCT.md) · [UX](UX.md) · [ARCHITECTURE](ARCHITECTURE.md) · [SECURITY](SECURITY.md) · [DECISIONS](DECISIONS.md). The [owner-approved unified UX/media requirements](NEXT-PUSH-UX-RECONCILIATION-2026-11-09.md) specify the single-row footer, All/device/server list parity, Add-only metadata choice and automatically applied artwork, while [OPTIONS](OPTIONS-INFORMATION-ARCHITECTURE.md), [TRANSFER-ARTWORK](TRANSFER-ARTWORK.md) and [SMART-ROUTING](SMART-ROUTING-AND-SOURCE-LIFECYCLE.md) govern the domain specifics.

**Current integration audit:** [18-PR reconciliation and remaining 17 dependencies](archive/OPEN-PR-RECONCILIATION-2026-11-11.md) records source-head test holds and the safely merged #1636 focus fix. The [active queue](WORK-QUEUE.md) remains the only executable backlog. **Second pass (merged #1640, assembled #1644, child-conflict map, local test setup):** [second-pass audit](archive/OPEN-PR-RECONCILIATION-2026-11-11-PASS2.md).

**Feature delivery:** [FEATURE-EXPERIENCE-CONTRACT](FEATURE-EXPERIENCE-CONTRACT.md) integrates screen placement, interaction states, visual evidence and separate experience acceptance with engineering implementation. It does not create a new backlog or require a full redesign for internal-only changes.

**Test standard:** [TEST-ARCHITECTURE](TEST-ARCHITECTURE.md) defines assertion categories, stable semantic ids, screenshot evidence versus baselines, UX-change reconciliation and failure classification; its [audit record](archive/TEST-ARCHITECTURE-AUDIT-2026-11-11.md) lists the obsolete assertions found and gaps.

**History and measurements:** [CI policy history](CI-POLICY-HISTORY-2026-11-09.md) · [artwork implementation history](MEDIA-ARTWORK-IMPLEMENTATION-HISTORY-2026-11-09.md) · [source-exact media artwork integration and real-provider qualification](archive/MEDIA-ARTWORK-QUALIFICATION-2026-11-11.md) · [build artifact history](BUILD-HISTORY-2026-11-09.md). Active [CI policy](CI-POLICY.md), [artwork implementation instructions](MEDIA-ARTWORK-IMPLEMENTATION.md) and [build guidance](BUILD.md) state procedures rather than past run reports.

**Historical reference only:** [previous Work Queue](WORK-QUEUE-HISTORY-2026-11-09.md), [previous Spec Coverage](SPEC-COVERAGE-HISTORY-2026-11-09.md), [previous Agent Lanes](AGENT-LANES-HISTORY-2026-11-09.md), [previous Next Push](NEXT-PUSH-HANDOFF-HISTORY-2026-11-08.md), [quality receipts](QUALITY-LEDGER.md). These contain dated heads and retrospectives, not current assignments. Keep implementation plans imperative and move chronological logs here or to a new dated history file.

## Agent entry points

- [Latest dated P0 integration checkpoint (2026-11-03)](P0-INTEGRATION-CHECKPOINT-2026-11-03.md): merged code/evidence, exact protected-rTorrent Add uncertainty, red-CI classification, and non-physical continuation. Not a second work queue; use live GitHub and the canonical daily-use manifest first.

- [Android TV and multi-backend remote roadmap](ANDROID-TV-AND-REMOTE-BACKENDS.md): binding future TV UX, protocol adapter generalization, BiglyBT reuse/license audit, stage gates; deferred until foundational acceptance.

- [AGENTS](../AGENTS.md): required operating instructions and safety rules.
- [WORK-QUEUE](WORK-QUEUE.md): concise active priority/order authority; reconcile with live PRs/issues.
- [Real-transfer-first acceptance](REAL-TRANSFER-ACCEPTANCE.md): owner-mandated real .torrent/magnet/server/peer evidence tiers, positive user journeys, reference-client comparisons and limited role of mocks; required before claiming a BitTorrent feature works.
- [Engineering delivery protocol](ENGINEERING-DELIVERY-PROTOCOL.md): practical lessons from merged/unmerged PRs: prove normal flows, contain only affected unsafe capabilities, avoid research-stack explosion, triage CI failures by actual phase and integrate exact source. Governs execution method; not a second work queue.\n- [Repository-wide documentation and tracker reconciliation, 2026-10-31](REPOSITORY-DOCS-AND-TRACKING-AUDIT-2026-10-31.md): 107 text-document inventory, all open issue/PR status reconciliation, exact-head native stack red evidence and remaining verification limits; dated audit, not a second work queue.
- [Requirements rationality audit, 2026-10-31](REQUIREMENTS-RATIONALITY-AUDIT-2026-10-31.md): review new release blockers against concrete harm, platform/library practice and independent evidence tiers.
- [User-directed file operations](USER-DIRECTED-FILE-OPERATIONS.md): canonical conflict/Replace/Keep both/Skip rules and precise Android provider API distinctions; #809 implementation remains gated.
- [Independent Android architecture audit, 2026-10-30](ANDROID-ARCHITECTURE-AUDIT-2026-10-30.md): AUD-01–15 source findings, evidence and gaps, work-package definitions and unresolved review areas. [#687](https://github.com/gamesbyian/Chunks/issues/687) tracks follow-through; this dated report is **not a second work queue**.
- [Dependency-reset queue snapshot](archive/WORK-QUEUE-2026-10-29-pre-dependency-reset.txt): verbatim prior active board; historical, never executable.
- [Older queue archive](archive/WORK-QUEUE-2026-10-29-preconsolidation.md): preserved earlier milestone narratives; not a live plan.
- [QUALITY-LEDGER](QUALITY-LEDGER.md): dated test evidence and limitations; not a current task queue.
- [SPEC-COVERAGE](SPEC-COVERAGE.md): required versus implemented capability and acceptance gaps.
- [CI-POLICY](CI-POLICY.md): currently applicable runner costs, test gates and workflows.
- [CI engineering standard](CI-ENGINEERING-STANDARD.md): binding audit, dispatch efficiency, risk-aware verification, parallelism and agent-readable diagnostics requirements.
- [CI audit and baseline, 2026-10-31](CI-AUDIT-2026-10-31.md): measured workflow inventory, historical failure controls, and next optimization candidates.
- [Live workflow inventory](CI-WORKFLOW-INVENTORY.md): full agent-readable workflow metadata artifact and reproducible offline analyzer.
- [Physical handset acceptance](PHYSICAL-HANDSET-ACCEPTANCE.md): receipt template, the opt-in scripts that run unchanged on a handset, and manual lifecycle/accessibility checks.

## Advanced operations (normative feature contracts)

- [Intake, queue and policies](INTAKE-QUEUE-AND-POLICIES.md): 21 catalogue gap items plus **owner-approved optional integrated Discovery**, explicit master off switch, and source/auto-add consent, alongside queue, schedules and traffic rules.
- [Files, protocol and diagnostics](FILES-PROTOCOL-AND-DIAGNOSTICS.md): 27 archive/link, piece/peer/network privacy, diagnostics and partial-preview requirements.
- [Remote integrations and settings portability](REMOTE-INTEGRATIONS-AND-PREFERENCES.md): 10 gap items plus **owner-approved secondary Server tools**, service-health/quota visibility and limited capability-gated service actions; opt-in adapters and preferences.
- [Smart Seeding Rules](SMART-SEEDING-RULES.md): owner-approved policy, generated editable rule names and explicit named-environment applicability.

- [Task outcomes and continuity](TASK-OUTCOMES-AND-CONTINUITY.md): MF02 end-to-end evidence receipts and MF04 non-destructive human task resumption (planned, binding).
- [Effective-policy explanations](EFFECTIVE-POLICY-EXPLANATIONS.md): MF31 rule winners, conflicts, precedence traces and safe simulations (planned, binding).

## Owner-approved niche workflow contracts

- [Create & Share advanced options](CREATE-AND-SHARE.md#optional-advanced-sharing-embedded-tracker-browser-peers-and-subscription-feeds): optional tracker, WebRTC/browser-peer compatibility and share subscription feeds, without changing the core wizard.
- [Cross-seeding and verified payload copy](CROSS-SEED-AND-PAYLOAD-COPY.md): compare existing bytes against new torrent metadata and copy actual payloads safely; distinct from job-only Copy/Move Transfer.
- [Media inspection](MEDIA-INSPECTION.md): on-demand container/codec/track properties and bounded local/remote preview thumbnails.
- [Event automation](EVENT-AUTOMATION.md): opt-in conditional rules, saved filters, redacted webhooks and Android handoffs without unrestricted scripts.
- [Existing-service integrations](EXISTING-SERVICE-INTEGRATIONS.md): Prowlarr-compatible torrent indexer connections and narrowly authorized remote storage sync; secondary flows.

## Normative foundations

- [PRODUCT](PRODUCT.md): feature scope and user promises.
- [UX](UX.md): navigation, interactions, states, accessibility.
- [ARCHITECTURE](ARCHITECTURE.md): domain model, engine boundaries and execution design.
- [SECURITY](SECURITY.md): threat model, auth, storage and destructive safety.
- [DECISIONS](DECISIONS.md): stable decision record and supersession.
- [EXTERNAL-FILE-ACCESS](EXTERNAL-FILE-ACCESS.md): Android Open with, remote byte-range reads, streaming bridges, file security and acceptance.
- [PLEX-FILENAME-REPAIR](PLEX-FILENAME-REPAIR.md): minimal-change Plex compatibility analysis tied to safe filename, alias and library-copy workflows.
- [TRANSFER-FILE-RECOVERY](TRANSFER-FILE-RECOVERY.md): find displaced or renamed existing data and safely restore transfer mappings.
- [SMART-SEEDING-RULES](SMART-SEEDING-RULES.md): ratio, duration and upload-rate groups, tracker matching, Add review and safe unattended assignment.
- [UNIFIED-ENVIRONMENTS](UNIFIED-ENVIRONMENTS.md): shared phone and rTorrent experience and scoped settings.
- [CREATE-AND-SHARE](CREATE-AND-SHARE.md): anonymous and authenticated torrent creation, seeding and publishing, service profiles, privacy, and acceptance.
- [CROSS-ENGINE-TRANSFER-JOBS](CROSS-ENGINE-TRANSFER-JOBS.md): job-only Copy/Move; no payload transfer.
- [SMART-REMOTE-CONNECTION](SMART-REMOTE-CONNECTION.md): discovery, security and multi-profile onboarding.
- [TRANSFER-ARTWORK](TRANSFER-ARTWORK.md): enrichment, provider provenance and user overrides.
- [TRANSFER-PRIVACY-LOCK](TRANSFER-PRIVACY-LOCK.md): per-environment access control.
- [STORAGE-AND-EXTRACTION](STORAGE-AND-EXTRACTION.md): storage, cleanup, extraction and provenance.
- [RTORRENT-COMPATIBILITY-MATRIX](RTORRENT-COMPATIBILITY-MATRIX.md): verified RPC compatibility.
- [TRANSFER-SNAPSHOTS](TRANSFER-SNAPSHOTS.md): portable transfer definition backup and restoration.
- [NATIVE-VISUAL-ATLAS](NATIVE-VISUAL-ATLAS.md): screenshot capture and independent review guidance.

## Implementation and operations

- [BUILD](BUILD.md): build and local verification.
- [SEEDHOST-REMOTE-TESTING](SEEDHOST-REMOTE-TESTING.md): approved test topology and safety.
- [PUBLIC-TORRENT-FIXTURES](PUBLIC-TORRENT-FIXTURES.md): lawful test fixtures.
- [User-supplied test inputs](USER-SUPPLIED-TORRENT-TEST-INPUTS.md): controlled owner-provided fixtures.
- [RESEARCH](RESEARCH.md) and [REUSE-AUDIT](REUSE-AUDIT.md): upstream references, prior art and reuse boundaries.
- [Open-source reuse audit (2026-10-29)](research/OPEN-SOURCE-REUSE-2026-10-29.md): code-level candidates and adaptation/licence/test decisions for foundational Android, rTorrent and multi-environment work; non-normative research.
- [Multilingual BitTorrent client and remote UX friction research, 2026-10-30](research/BITTORRENT-UX-FRICTION-MULTILINGUAL-2026-10-30.md): two-pass discovery matrix, firsthand complaints, unresolved source leads and prioritized non-normative recommendations.
- [BitTorrent user-aversion and friction atlas](BITTORRENT-USER-AVERSION-RESEARCH-2026-10-29.md): qualitative, multilingual reports of dislike, non-use, abandonment and workflow pain (research, not product requirements).
- [BitTorrent human-centered concept map](research/BITTORRENT-HUMAN-CONCEPT-MAP-2026-10-29.md) and [missing-feature synthesis](research/BITTORRENT-MISSING-FEATURES-SYNTHESIS-2026-10-29.md): non-normative feature-space analysis, 33 capability families and 30 workflow seams; [evidence audit](research/BITTORRENT-CONCEPT-MAP-EVIDENCE-AUDIT-2026-10-29.md) and [typed graph](research/BITTORRENT-CONCEPT-GRAPH-2026-10-29.json).
- [Dispatch coordination](AGENT-LANES.md): **no fixed lanes or agent identities**; coordinate actual overlapping files/interfaces only.
- [Dated handoffs](HANDOFF-2026-10-28.md), [Claude app handoff](CLAUDE-APP-HANDOFF-2026-10-28.md), [earlier handoff](HANDOFF-2026-10-27.md): historical session context, not current priorities.
- [Archive guide](archive/README.md): preserved pre-consolidation documents and status interpretation.
- [Font lab operations](design/CHUNKS-FONT-LAB-OPERATIONS.md): separate font branch, low-CI iteration.
- [Design references](design/reference-images/README.md): visual inspiration, not implementation proof.
- [Historical critical review](CRITICAL-REVIEW-2026-10-24.md): dated findings; do not treat as latest release status.

## Documentation verification

- [Offline link checker](../tools/check_docs.py) and [unit tests](../tests/test_docs_check.py): run locally before modifying or merging docs. Commands in the [standard](DOCUMENTATION-STANDARD.md#automated-local-validation).

## Where to put new information

A user-facing promise goes in PRODUCT or its linked feature contract; screen behavior in UX; implementation details in ARCHITECTURE; a binding choice in DECISIONS; proof in QUALITY-LEDGER; missing test coverage in SPEC-COVERAGE; prioritized work in WORK-QUEUE. Use concise cross-references rather than distributing whole requirements across all seven files.

- [Per-transfer home-screen widgets](PER-TRANSFER-HOME-WIDGETS.md): deferred P3, independent live indicator per transfer, launcher removal feasibility gate.

- [Cross-media real-record/API verification](research/MEDIA-API-RECORD-EVIDENCE-2026-11-02.md): dated public credits and provider-specific API schema/quality caveats, with explicit gaps before live JSON and installed integration acceptance.
