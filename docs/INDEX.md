# Chunks documentation index

The [documentation architecture and authoring standard](DOCUMENTATION-STANDARD.md) defines the authority of each document and how agents maintain it. This index is navigation, **not another specification**.

## Agent entry points

- [AGENTS](../AGENTS.md): required operating instructions and safety rules.
- [WORK-QUEUE](WORK-QUEUE.md): only active priority/order authority; reconcile with live PRs/issues.
- [QUALITY-LEDGER](QUALITY-LEDGER.md): accepted test evidence and limitations.
- [SPEC-COVERAGE](SPEC-COVERAGE.md): required versus implemented capability and acceptance gaps.
- [CI-POLICY](CI-POLICY.md): runner costs, test gates and workflows.

## Normative foundations

- [PRODUCT](PRODUCT.md): feature scope and user promises.
- [UX](UX.md): navigation, interactions, states, accessibility.
- [ARCHITECTURE](ARCHITECTURE.md): domain model, engine boundaries and execution design.
- [SECURITY](SECURITY.md): threat model, auth, storage and destructive safety.
- [DECISIONS](DECISIONS.md): stable decision record and supersession.
- [UNIFIED-ENVIRONMENTS](UNIFIED-ENVIRONMENTS.md): shared phone and rTorrent experience and scoped settings.
- [CROSS-ENGINE-TRANSFER-JOBS](CROSS-ENGINE-TRANSFER-JOBS.md): job-only Copy/Move; no payload transfer.
- [SMART-REMOTE-CONNECTION](SMART-REMOTE-CONNECTION.md): discovery, security and multi-profile onboarding.
- [TRANSFER-ARTWORK](TRANSFER-ARTWORK.md): enrichment, provider provenance and user overrides.
- [TRANSFER-PRIVACY-LOCK](TRANSFER-PRIVACY-LOCK.md): per-environment access control.
- [STORAGE-AND-EXTRACTION](STORAGE-AND-EXTRACTION.md): storage, cleanup, extraction and provenance.
- [RTORRENT-COMPATIBILITY-MATRIX](RTORRENT-COMPATIBILITY-MATRIX.md): verified RPC compatibility.

## Implementation and operations

- [BUILD](BUILD.md): build and local verification.
- [SEEDHOST-REMOTE-TESTING](SEEDHOST-REMOTE-TESTING.md): approved test topology and safety.
- [PUBLIC-TORRENT-FIXTURES](PUBLIC-TORRENT-FIXTURES.md): lawful test fixtures.
- [Font lab operations](design/CHUNKS-FONT-LAB-OPERATIONS.md): separate font branch, low-CI iteration.
- [Design references](design/reference-images/README.md): visual inspiration, not implementation proof.
- [Historical critical review](CRITICAL-REVIEW-2026-10-24.md): dated findings; do not treat as latest release status.

## Where to put new information

A user-facing promise goes in PRODUCT or its linked feature contract; screen behavior in UX; implementation details in ARCHITECTURE; a binding choice in DECISIONS; proof in QUALITY-LEDGER; missing test coverage in SPEC-COVERAGE; prioritized work in WORK-QUEUE. Use concise cross-references rather than distributing whole requirements across all seven files.
