# Chunks documentation index

- [External destination delivery](EXTERNAL-DESTINATION-DELIVERY.md): optional verified local-torrent delivery to Google Drive/SAF or independently authorized FTP-family storage, staged copy, recovery and no implicit cloud seeding (planned).


The [documentation architecture and authoring standard](DOCUMENTATION-STANDARD.md) defines the authority of each document and how agents maintain it. This index is navigation, **not another specification**.

## Agent entry points

- [Android TV and multi-backend remote roadmap](ANDROID-TV-AND-REMOTE-BACKENDS.md): binding future TV UX, protocol adapter generalization, BiglyBT reuse/license audit, stage gates; deferred until foundational acceptance.

- [AGENTS](../AGENTS.md): required operating instructions and safety rules.
- [WORK-QUEUE](WORK-QUEUE.md): concise active priority/order authority; reconcile with live PRs/issues.
- [Independent Android architecture audit, 2026-10-30](ANDROID-ARCHITECTURE-AUDIT-2026-10-30.md): AUD-01–15 source findings, evidence and gaps, work-package definitions and unresolved review areas. [#687](https://github.com/gamesbyian/Chunks/issues/687) tracks follow-through; this dated report is **not a second work queue**.
- [Dependency-reset queue snapshot](archive/WORK-QUEUE-2026-10-29-pre-dependency-reset.txt): verbatim prior active board; historical, never executable.
- [Older queue archive](archive/WORK-QUEUE-2026-10-29-preconsolidation.md): preserved earlier milestone narratives; not a live plan.
- [QUALITY-LEDGER](QUALITY-LEDGER.md): dated test evidence and limitations; not a current task queue.
- [SPEC-COVERAGE](SPEC-COVERAGE.md): required versus implemented capability and acceptance gaps.
- [CI-POLICY](CI-POLICY.md): runner costs, test gates and workflows.
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
- [BitTorrent user-aversion and friction atlas](BITTORRENT-USER-AVERSION-RESEARCH-2026-10-29.md): qualitative, multilingual reports of dislike, non-use, abandonment and workflow pain (research, not product requirements).
- [BitTorrent human-centered concept map](research/BITTORRENT-HUMAN-CONCEPT-MAP-2026-10-29.md) and [missing-feature synthesis](research/BITTORRENT-MISSING-FEATURES-SYNTHESIS-2026-10-29.md): non-normative feature-space analysis, 33 capability families and 30 workflow seams; [evidence audit](research/BITTORRENT-CONCEPT-MAP-EVIDENCE-AUDIT-2026-10-29.md) and [typed graph](research/BITTORRENT-CONCEPT-GRAPH-2026-10-29.json).
- [Agent lanes](AGENT-LANES.md): concurrency/ownership model.
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
