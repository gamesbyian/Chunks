# UX coherence and consolidation audit (2026-10-30)

**Type:** dated non-normative reconciliation record; this is **not** another design authority or executable queue. **Base:** `main` as inspected 2026-10-30, before UX/docs PR #783 and #795 merged. **Canonical result:** [UX](UX.md), with the [original pre-reconciliation text archived unchanged](archive/UX-2026-10-30-pre-reconciliation.md). **Validation status:** documentation edit only; GitHub Actions/head checks and merging remain to be reviewed. No app code, user test, physical handset or actual host behavior has been changed or tested by this work.

## Review scope and methodology

Reviewed the repository's live document tree, [AGENTS](../AGENTS.md), [documentation standard](DOCUMENTATION-STANDARD.md), [INDEX](INDEX.md), [PRODUCT](PRODUCT.md), [UX](UX.md), [UNIFIED-ENVIRONMENTS](UNIFIED-ENVIRONMENTS.md), [DECISIONS](DECISIONS.md), [WORK-QUEUE](WORK-QUEUE.md) and cross-linked specialist requirements: Add and Smart Connect; seeding/policy explanations; intake and optional Discovery; task outcomes; privacy; storage/cleanup; artwork; Create & Share; remote service tools; and visual-reference governance. Read open documentation changes in [#783](https://github.com/gamesbyian/Chunks/pull/783) and [#795](https://github.com/gamesbyian/Chunks/pull/795) before editing. Compared navigation, terminology, safe defaults, consent, mutation scope, evidence language, accessibility and downstream affordances. The review did **not** amount to visual inspection of the five PNG reference boards or a full source-code UI behavior audit; those are separate acceptance tasks.

## Material findings and dispositions

| Finding | Why it mattered | Reconciliation |
|---|---|---|
| **F1: Append-only UX order.** Deferred cloud delivery/TV were above the app's basic mental model; recent owner corrections were placed before information architecture. | An agent could build novelty before understanding the governing interaction model. | Shared philosophy/grammar and six task families now precede optional and historical sections; future features remain linked to their focused contracts. |
| **F2: Aspirational UI mixed with dated implementation receipts.** | A merged emulator slice could be misread as release-quality physical UX, or a partial implementation as a normative constraint. | Historical implementation-only sections are short compatibility anchors linking to the original archive; current proof belongs to COVERAGE/QUALITY. Mixed sections retain requirements but dated receipts are explicitly non-authoritative. |
| **F3: Local-first onboarding contradicted an old 'experimental app-private' first-run paragraph.** | It obscured the owner-selected phone-only default and public Downloads intention, while release-critical native safety was still unproven. | Local-only and remote-only are equal first-class user paths; the UI must disclose actual release capability and safe supported Place rather than forcing remote login or claiming unverified storage. See [UNIFIED-ENVIRONMENTS](UNIFIED-ENVIRONMENTS.md) and [STORAGE-AND-EXTRACTION](STORAGE-AND-EXTRACTION.md). |
| **F4: Current-environment 'switcher' wording risked hiding any-subset browsing.** | The main list can be scoped to arbitrary combinations, not just a selected single server. | Describe a persisted multi-select view/filter with visible per-row origins; do not infer Add destination, batch execution or rule applicability from the filter. [UNIFIED-ENVIRONMENTS](UNIFIED-ENVIRONMENTS.md) remains exact authority. |
| **F5: 'Simple' could be misimplemented as hiding useful choices.** | Hiding file/seeding/destination options creates surprises when the user overlooks them. | Incorporate #783's owner direction: compact visible effective summaries, no obligatory configuration for verified safe defaults, accessible Change/Why, and a later correction route for every skipped choice. |
| **F6: Human status vs technical status disconnected.** | Users cannot infer queued, paused, stalled, missing peers, metadata unavailable or ambiguous remote success from opaque flags. | Incorporate #795's result-first plain-language grammar, explicit known/unknown causes, freshness, safe actions, truthful empty/filter states and settings-by-symptom links; preserve detailed diagnostic counters. |
| **F7: Too many overlapping 'move', 'copy', 'rename', 'remove' labels.** | A job-only move might be mistaken for byte transfer; a display alias for filesystem rename; archive cleanup for safe seeding. | One explicit object/effect vocabulary with distinct job, backing, published copy, payload, extraction and deletion semantics; do not duplicate domain executors. |
| **F8: Contextual UX vs settings authority unclear.** | Multiple paths can diverge into separate saved/effective settings and scopes. | One underlying scoped setting/effective-value evaluator, multiple contextual entry points; every screen identifies app-wide/environment/transfer scope, effective value and consequences. |
| **F9: Expert capability and novice comprehension were being treated as competing aims.** | Dedicated 'beginner mode' or huge setup wizards would make the shared app less cooperative. | One adaptive UI with progressive technical details and explanation at point of need; acceptance covers both uncoached 40-second teach-back and full technical drill-down. |
| **F10: Visual references risked outranking capabilities.** | Reference mockups include tabs and controls the product has not approved or shipped. | Preserved rule: approved SVG is branding authority; reference sheets are inspiration, not permission to claim features; 48dp/200%/320×700 and safe states override decorative treatments. |

## Preservation and migration checklist

- [x] The previous `docs/UX.md` is copied **verbatim** to `docs/archive/UX-2026-10-30-pre-reconciliation.md` before restructuring, to make every sentence and evidence receipt recoverable.
- [x] All **42 original level-two headings** are retained in the live UX document at level three under organized task families or in the historical-compatibility area, preserving their names/anchors.
- [x] Focused domain contracts remain authoritative; the new front matter links and summarizes them without attempting to replace their full security, storage, remote-host, rule and state machines.
- [x] Job-only Copy/Move Transfer, non-clobber, per-profile scope, explicit privacy/consent, honest uncertain writes, file/Place ownership, no remote Chunks folder and deferred physical acceptance retain priority.
- [x] The two 2026-10-30 documentation branches' concepts are represented in the shared grammar, with compatibility headings for their prospective inbound UX anchors.
- [ ] Integrate **all non-UX hunks** from #783 and #795 and reconcile overlapping UX text by meaning, rather than merging their appended `UX.md` sections on top of this restructure.
- [ ] Verify the proposed PR's **exact head** through the path-scoped documentation link checker and tests, then independently review the diff and its compatibility anchors before merge.
- [ ] Reconcile the corresponding brief PRODUCT, DECISIONS, UNIFIED-ENVIRONMENTS, WORK-QUEUE and SPEC-COVERAGE cross-references once the two overlapping PRs have landed. Avoid a second UX queue or duplicated feature contracts.
- [ ] Later migrate any remaining dated receipt fragments from feature-level contracts into QUALITY/COVERAGE, without silently altering requirements. This is documentation hygiene, not P0 application readiness work.

## Conflict-handling protocol for current PRs

**#783 is the owner-guided visible options/automatic defaults/skip-safe continuity baseline**; **#795 is the complementary multilingual-research-inspired findability and explanatory-state layer**. Both presently target main and edit UX. The order of safe integration is: review/merge their qualified *substantive* product/domain/research changes, keep the current UX organization as the final presentation, and manually compare every removed or overwritten paragraph against the preserved original, #783 UX patch and #795 UX patch. Merge conflicts must be resolved semantically, not by selecting whichever giant document revision applies cleanly. The exact-head checker and independent review must run after the last merge/rebase.

**No one has permission to mark #8, #567, #779 or #568 implemented, verified or safe because of this contract rewrite.** G0 native foreign-file overwrite and real-host/UI lifecycle acceptance remain higher-priority executable work. The [work queue](WORK-QUEUE.md) is still the only execution order.

## Acceptance matrix for the resulting philosophy

| Journey | If the user does nothing optional | Findable later adjustment | Failure/unknown state |
|---|---|---|---|
| **First run** | On-phone use without mandatory seedbox, or remote-only without local service | Add/change named environments | Unknown backing capability, auth failure, disabled/offline vs deleted |
| **Magnet / .torrent / Share Add** | Verified default Place and explicitly authorized sharing/start behavior | Transfer details → Files, Sharing, Order, Save to, Start | Metadata unavailable, no sources, expired grant, unsafe destination, uncertain server Add |
| **Transfers and search** | Unified list of chosen environments with understandable statuses | Show all, search transfers/files, explain state, change sort/group | Stale/partial/locked results never misreported as global empty/live |
| **Rules / queue / limits** | Observed applicable and enforceable defaults, not guessed policy | Why this rule? → same scoped editor, preview effects | Unsupported enforcement, policy conflict, pending readback |
| **Cross-engine and storage actions** | No unrequested job/file mutation or deletion | Explicit job-only Copy/Move vs actual-byte and library operations | Collision, pending/uncertain mutation, missing ownership/proof, recovery |
| **Completed file / artwork / extraction** | Real file remains findable and seedable, generic artwork works, no deletion | Open / Get on phone; change art; manual extract / separate cleanup | No remote byte access, missing SAF grant, incomplete archive, unverified delivery |

Acceptance targets: local, remote, 2+ profiles, 320×700 and 412×892, 200% text, TalkBack, keyboard/reduced motion, process death and stale/uncertain readback, plus uncoached comprehension. **None of these are newly claimed verified** by the documentation consolidation.

## Next action

Independent review of the documentation PR alongside #783/#795; reconcile competing hunks and CI at the exact post-rebase head. For application development, follow the existing [G0→G5 queue](WORK-QUEUE.md); this audit does not reassign P0 work.
