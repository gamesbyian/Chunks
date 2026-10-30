# Documentation architecture and agent authoring standard

**Status:** normative repository maintenance policy. **Applies to:** human maintainers and autonomous agents. **Purpose:** make product intent, implementation evidence, and next actions independently discoverable without competing sources of truth.

## Start here: authority by question

| Question | Canonical source | What does not override it |
|---|---|---|
| What should Chunks do? | [PRODUCT](PRODUCT.md) and narrowly scoped contracts linked from it | A PR description, queue note, screenshot or implementation shortcut |
| What does the user see/do? | [UX](UX.md) plus linked feature UX contracts | A mockup or unit-test fixture |
| How is it built safely? | [ARCHITECTURE](ARCHITECTURE.md), [SECURITY](SECURITY.md), feature technical contracts | Unreviewed code assumptions |
| Why was a choice made? | [DECISIONS](DECISIONS.md) (decision identifier + status) | Chronological chat transcripts |
| What is implemented and proven? | [QUALITY-LEDGER](QUALITY-LEDGER.md) and [SPEC-COVERAGE](SPEC-COVERAGE.md), checked against live code and CI | An aspirational requirement or prior milestone claim |
| What should an agent work on now? | [WORK-QUEUE](WORK-QUEUE.md), reconciled with live issues/PRs | Historic TODOs or document ordering |
| How must an agent operate? | [AGENTS](../AGENTS.md), [CI-POLICY](CI-POLICY.md) and specialized agent contracts | Informal handoff instructions |
| Where are documents? | [Documentation index](INDEX.md) | A guessed filename or broad repository search |

**Conflict precedence:** explicit latest owner correction and safety constraints → canonical narrowly scoped feature contract → governing PRODUCT/UX/ARCHITECTURE/SECURITY contract → DECISIONS entry → implementation plans. If two normative documents disagree, do not pick whichever is newest by timestamp; reconcile the discrepancy explicitly, preserve history in the decision ledger and flag unresolved product ambiguity. Code and tests describe observed behavior, not permission to override the product contract.

## Document types and their boundaries

1. **Product contract:** durable user-observable promises, defaults, invariants and exclusions. No PR-by-PR progress logs.
2. **Feature contract:** bounded behavior, non-goals, exact identities/scopes, user journey, failure semantics, capability differences and acceptance criteria. The canonical feature owner is linked from PRODUCT/UX; cross-references summarize rather than restate.
3. **UX guide:** information architecture, state transitions, copy/labels, accessibility, screen sizes and empty/loading/offline/error flows. Separate design aspiration from physically accepted UI.
4. **Architecture/security:** data models, commands, trust boundaries, authorization, recovery, portability and test seams. Distinguish capability existence, permission and verification.
5. **Decision ledger:** stable ID, classification (owner/binding, provisional, open, superseded), rationale, alternatives, implications and link to authoritative contract. Append a new decision for a material reversal rather than silently rewriting history.
6. **Coverage/evidence:** requirement ID, planned/partial/implemented status, tested SHA and test tier, gaps and linked issue. “Implemented” must identify the precise slice, not imply whole-feature completion.
7. **Work queue:** one dependency-first executable list with prerequisite, owner/lane, validation gate, blocker, issue/PR and order. P0 reliability/identity/QA gates outrank discretionary feature expansion; chronological date or feature inventory sequence never sets priority. Preserve substantial time-sensitive snapshots in dated archives and point to them; do not place chronological milestones or PR-by-PR narratives in the active queue.
8. **Reference/operations/handoff:** factual usage instructions and exact reproduction commands; do not introduce new product requirements incidentally.

## Required feature-contract template

Use these headings when creating or substantially rewriting a feature contract; explicitly mark irrelevant sections rather than leaving ambiguous gaps:

- **Purpose and authority:** link to governing product rule and decision ID
- **Scope / exclusions:** included and excluded behaviors, local and remote support
- **Terminology and identities:** stable keys; transfer job versus payload/file, profile and Place
- **User journey and UI states:** entry, normal path, cancellation, offline/error/uncertain, exit
- **Settings and defaults:** global/environment/transfer scope, persistence and migration
- **Capabilities and security:** permissions, feature detection, privacy, prohibited fallback
- **Failure, recovery and invariants:** idempotence, readback, stale state and destructive boundaries
- **Acceptance matrix:** deterministic testable assertions, environment variants and evidence tier
- **Implementation/evidence status:** separated from requirements; link to coverage/quality ledger
- **Related artifacts:** precise links to architecture, UX, tests, queue, issues and PRs

## Agent change protocol

1. Inspect live main, active PRs, relevant specs and current work queue before proposing edits. Follow AGENTS and preserve the font-lab exception.
2. Find a feature's **single canonical contract**. Modify that contract first; update high-level cross-references only where necessary. Do not paste a new owner directive atop every document or append duplicative mini-specs to every file.
3. Revise contradictory normative statements in place. If retaining an old decision matters, mark it superseded with the new decision ID; keep outdated execution notes in a dated history section or archive. Never leave two apparently current instructions.
4. In any new requirement, distinguish **MUST** (binding), **SHOULD** (preferred with documented exception), **MAY** (optional), and **PLANNED / PARTIAL / VERIFIED** (implementation status). Avoid words like “done,” “works,” or “safe” without bounded observed evidence.
5. Distinguish technical prerequisites, release acceptance and optional enhancement; a feature can be partially shipped yet non-gating for the next beta. Never reclassify a product promise as cancelled merely because it moved out of active development. Keep prose concise, hierarchical and skimmable. Use descriptive headings; avoid repeated dated forewords, stream-of-consciousness increments, unexplained acronyms, loose “etc.” lists and pseudo-precise percentages.
6. Use repository-relative links, stable section anchors/IDs and clear examples. Link the authoritative rule instead of copying paragraphs. Avoid external tracking/query parameters in GitHub links.
7. Every implementation claim requires a code SHA, test tier and evidence link; distinguish JVM, emulator, disposable server, development seedbox, physical Android and human visual acceptance.
8. End with an exact scope statement, changed canonical links, open acceptance gaps and next actionable task. Documentation-only work must not claim tested Android functionality.
9. Keep documentation-only work to one contained PR; avoid automatic font CI, duplicate branches and broad formatting-only rewrites. Verify changed links and check diffs for accidental removal of requirements before merge.

## Safe cleanup / migration strategy

The existing documents contain valuable but historically accumulated text. Refactor incrementally by domain, without silently discarding owner decisions or safety preconditions. First establish authority/navigation and tag current versus historical sections. Then migrate repeated paragraphs into the canonical feature contract and replace duplicates with short links; only then reorganize deep sections. Each migration PR must contain a **requirements-preserved checklist** listing moved/merged material and separately noting intentional behavioral changes. Treat stylistic cleanup as zero functional change until verified otherwise.

## Automated local validation

Before proposing a documentation merge, run:
```sh
python3 tools/check_docs.py
python3 -m unittest discover -s tests -p 'test_docs_check.py'
```
The checker validates repository-local Markdown targets without network access and reports unresolved heading fragments. Use `--strict-anchors` when cleaning existing anchor debt; do not weaken the link-target check to hide broken paths. Link checks are quick/local and should not spawn Android or font GitHub workflows.

## UX-specific coherence gate

When a change affects user-visible behavior, first apply the shared [UX philosophy, interaction grammar and scope model](UX.md) to the full journey: entry → meaningful safe default → optional choices → requested/observed result → follow-up actions → failure/restart → later correction. A new setting belongs to one canonical effective-value evaluator and scope, even if several screens link to it. A new feature must not introduce a competing navigation destination or new meaning for Transfer, Place, Collection, environment, job-only Copy/Move or payload copy without an explicit owner decision and amendment to the owning contract.

UX changes must consider both a casual user's **40-second teach-back** and an advanced user's route to precise technical controls. For each optional Add/configuration choice, record the skipped-choice outcome and later Change route. For each status, distinguish observations, hypotheses, unknowns and safe next action. For each action, define scope, consent, uncertain outcomes and non-clobber/destructive gates. Preserve an anchor or give an explicit migration mapping when relocating previously linked material. Evidence and implementation notes belong to QUALITY-LEDGER/SPEC-COVERAGE; do not append them as new normative UX sections.

**Migration discipline:** review existing source headings and inbound links, retain dated prior text in an archive when removing it from the active contract, list preserved requirements and intentional changes, reconcile overlapping feature PRs by meaning, and verify exact-head documentation validation. A navigation improvement does not confer application readiness.

## Quality gate

A documentation PR passes only when an unfamiliar agent can answer: **What is required? Why? Where is it implemented? What evidence supports it? What remains? Which file may I edit? How will I verify success?** If these require searching multiple contradictory paragraphs, the document is not finished.
