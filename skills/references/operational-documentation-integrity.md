# Operational Documentation Integrity: keep active instructions current

**Purpose:** maintain project plans, specifications, onboarding, work queues and handoffs as *usable current instructions*, while retaining consequential decisions and evidence without turning operational documents into a chronological diary. This is a narrowly activated maintenance subroutine, not another source of project authority or a universal preflight.

## Activation, exclusions and inputs

- **At inception:** when establishing a substantial project's documentation, identify existing authority and choose role owners/conventions using the least structure that can express the work.
- **During development:** activate when a consequential owner decision, accepted spec change, implementation/merge, disproven premise, completed/blocked task, or genuine handoff may have made an operational instruction wrong, obsolete, missing or confusing. A direct request for documentation cleanup also activates it.
- **At a meaningful milestone or explicit audit:** inspect the relevant connected documents, expanding only when an actual inconsistency points further. Do not require a full-repository review for routine work.
- **Skip:** a trivial code/style change with no material effect on operating instructions, an accurate document already fit for its task, a settled historical record that is merely being read, or a speculative risk unsupported by a changing decision. A justified **NO CHANGE** is a successful result.
- **Required inputs:** current task and permission boundaries; host AGENTS/onboarding; authoritative owner decisions and applicable source/branch/PR state; affected operational files; available decision/evidence records. Treat unavailable inputs as unavailable, not as agreement. No procedure here authorizes changing code, release policy, secrets or owner intent.

## D1. Discover the documentation roles, not filenames

Inspect the host's own conventions before creating folders or ledgers. Record only relevant files and their purpose, reader, source/priority, last inspectable revision and current status. A single file may hold several clearly separated roles; an issue tracker or PR may already retain history adequately.

| Role | Reader's primary question | Keep active | Place elsewhere unless essential to the next decision |
|---|---|---|---|
| Plan / roadmap | What must be achieved and by what sequence/dependencies? | Outcomes, phases, prerequisites, decision gates, next operations and exit criteria | Dated progress diaries and superseded implementation attempts |
| Specification | What behavior and constraints are required? | Actors, states, interfaces, exceptions, authority, acceptance and deliberate unknowns | Discovery chronology, prior rejected versions and raw test transcripts |
| Agent onboarding / AGENTS | How do I safely orient and work here? | Entry points, authority order, required procedures, boundaries and applicable references | Session summaries, PR-by-PR histories and duplicated full specifications |
| Work queue | What remains actionable? | Priority, owner/lane, status, dependency, exact next action and completion oracle | Completed-work narration and repeated CI timelines |
| Handoff | How can the next agent resume this actual work? | Current revision/context, live constraints, unreconciled uncertainty, actionable continuation and evidence links | Full session transcript and unneeded merged-PR chronology |
| Decision record | What was decided and why? | Decision status, authority, alternatives, rationale, affected contracts, reconsideration triggers | Becoming an unreviewed competing requirement source |
| Evidence / history | What actually happened and what establishes it? | Original observation, scope/revision, links, failures, outcomes, source limits | Being mistaken for automatic current instructions |

**Authority caution:** a document role does not by itself determine which claim wins. Resolve conflicting claims via existing owner/project authority and the [Project Truth Reconstruction protocol](../project-inception/references/project-truth-reconstruction.md). Actual code describes implementation reality; it does not silently override intended requirements. CI, PR descriptions and retrospective summaries do not become product authority by being newer.

## D2. Compute the smallest justified change set

1. Start from the **event or defect**: record the accepted decision/observed change, exact scope, source and revision; distinguish `OWNER DECIDED`, `SPECIFIED`, `IMPLEMENTED`, `OBSERVED`, `VERIFIED`, `RELEASED`, `PROPOSED` and `UNKNOWN`. Do not infer one status from another.
2. Trace **forward** from the event into operational documents that govern the changed behavior or task. Trace **backward** from a suspicious instruction to the original authority and evidence. Look for contradictory defaults, duplicate owners, stale next actions, closed blockers, unsupported acceptance claims and references to superseded work.
3. For each consequential candidate, record a compact delta: `document/role | existing claim | current authority/source | disposition | exact replacement/removal | historical/evidence destination if necessary`. Classify the disposition as `KEEP`, `REWRITE`, `MOVE/LINK`, `REMOVE REDUNDANCY`, `MARK CONFLICT/UNKNOWN` or `NO CHANGE`.
4. If authority cannot be resolved through inspectable sources, **do not invent a winner**: preserve the competing scoped claims, mark the operational decision unresolved, and name the cheapest next source or owner decision. If source access is unavailable, record the evidence gap and do not overwrite established instructions on a guess.
5. Scope outward only if a changed instruction has a real downstream consumer. Do not enumerate every historical note or expand to all repositories because documents share generic words.

## D3. Reconcile active documents and preserve consequential history

- **Edit in place:** replace obsolete directions, consolidate duplicated requirements, update dependencies and ensure each active work item has an actionable next step or explicit blocking condition. Avoid adding a new status paragraph that contradicts the earlier one. Do not re-label a proposed feature as accepted or a passing build as installed acceptance.
- **Plans and specs:** express present goals and future work as explicit actions, states, boundaries, ownership and tests. Preserve an essential brief rationale when without it a competent agent might repeat a dangerous or expensive mistake; link detailed cases or transcripts instead of pasting them.
- **Onboarding:** point to authoritative project-local paths and a minimal orientation route; avoid copying requirements into AGENTS.md. Keep repository-local permissions, security rules and ownership in force.
- **Queues and handoffs:** leave current incomplete/blocked/deferred items discoverable and labeled; remove finished work from active queues only after checking that no unfinished obligation was accidentally bundled with it. In a handoff, identify a concrete revision and continuation action, not a sequence of what the previous agent tried.
- **Preserve material evidence:** retain decision reversals, unique failed experiments, tested revisions, important performance or safety measurements, and reasons behind consequential prohibitions in the project's existing issue/ADR/evidence/history mechanism. Before removing the only surviving substantive record, establish a durable destination or link. Unimportant duplicate status narration does not require archival.
- **No second authority:** a history or evidence record remains historical/evidentiary unless explicitly adopted through the project's decision process. Links to commits/PRs are sufficient when accessible, durable and specific enough; do not create a new archive per edit.
- **No silent destructive rewriting:** respect immutable audit records, protected files, local ownership, repository permissions and simultaneous agent work. When only suggestions are authorized, provide a proposed delta rather than writing.

## D4. Check operational usability and stop

Run a bounded review of *changed* documents and direct consumers. Ask: (a) Can an unfamiliar agent find the binding instructions and current next action? (b) Are scope, prerequisites, owners, unknowns and acceptance distinguishable? (c) Is a superseded approach still phrased as current work? (d) Can consequential historical claims be traced to evidence without reading a full diary? (e) Were unaffected requirements and safety gates preserved?

Check changed paths, links, IDs and document references with existing lightweight tools when available. Confirm the edited claim against owner authority and, when material, actual source or execution evidence. A static text/link check cannot prove product correctness or agent competence. **Do not run full product CI just because Markdown changed** unless the host expressly requires it. Avoid cosmetic-wide rewrites and repeated reviews when no further decision-relevant defect is identified.

**Output/receipt:** `trigger + scope/revision | updated roles and precise deltas (or NO CHANGE) | authority/evidence preserved | unresolved conflicts or inaccessible sources | changed-reference checks | next owner/action if any`. The receipt belongs in the existing PR/issue/handoff, not necessarily a new file.

**Independent oracle:** give a reviewer unfamiliar with the change the affected current documents and a concrete question: identify today's requirement, next permitted task, authority, relevant risk boundary and acceptance condition. Compare answers to original owner/implementation/evidence, not merely to other edited documents. A correct no-change verdict is equally valid.

**Stop rule:** finish when each material triggered obligation has either a reconciled current instruction, an explicitly unresolved authority question, or a justified no-change outcome; direct links and claims are checked; preserved history remains retrievable. Do not continue digging through archives without an affordable finding that could alter the operational answer.

**Evaluation cases:** [positive and negative scenarios](operational-documentation-cases.md). These are instructional probes, not evidence that agents consistently follow the procedure.