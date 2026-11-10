# Chunks dispatch and concurrency coordination

**There are no permanent agent lanes, assigned identities, fixed roster, fixed number of agents or reserved specialties in Chunks.** The filename is retained temporarily for compatibility with existing references; this document governs **dispatch-based coordination**, not lane allocation. The owner may dispatch one agent, several simultaneously, or a broad agent responsible for work spanning UI, Add, rTorrent, media, CI, integration and documentation. The **current owner dispatch** defines the agent's task and breadth. The [ranked work queue](WORK-QUEUE.md) defines priorities when the dispatch leaves choices open. Historic Agent 1–5 allocations, prompts and handoffs confer no authority over a new dispatch.

## On every dispatch

1. Read the actual owner request, [AGENTS](../AGENTS.md), live main/PRs/checks, [WORK-QUEUE](WORK-QUEUE.md), applicable product/spec/quality docs and [current handoff](NEXT-PUSH-HANDOFF-2026-11-08.md). Choose the smallest coherent work that satisfies the dispatch; if instructed to work broadly, **cross technical domains freely** and follow dependencies through to integration.
2. Check for actively overlapping work in open PRs and concurrent dispatches. Coordinate only **real current** overlaps, especially when two agents would edit the same file, API or behavioral contract. Serialize conflicting edits or use independently composable branches; do not assume another agent exists or is responsible because of an old numbered label.
3. A single agent can implement, document, test, coordinate and propose changes across the entire project if dispatched accordingly. A dispatch specifically limited to one task remains limited by that instruction, not by a fictitious permanent specialty.
4. Preserve the one Universal Add submission authority, exact profile/environment/Place/Start, read-only uncertain-write recovery, informed consent, job-only Move semantics, and genuine destructive approval. No dispatch scope overrides product safety invariants.
5. Test the exact source and record evidence honestly. For independent qualification or merging, follow explicit current dispatch and repository review/CI policy. Never treat self-authored assertions, unexecuted checks, red/skipped tests or an older APK as independent proof. Where independent review is required, arrange another reviewer or record the gap transparently instead of assigning it to a hypothetical “Agent 5”.
6. At completion or handoff, leave a concise actionable state: actual changes/PR, tests and exact-head limits, blockers, next steps and whether work remains. Update central plans and queue only where the dispatch requires; history belongs in the QUALITY-LEDGER or archive, not active instructions.

## Domains are work categories, not agent identities

Navigation/Connections/Transfers/footer, Universal Add/source intake, authenticated remote/rTorrent, media/artwork, and qualification/CI/documentation are **technical areas** whose work can be performed by any properly dispatched agent. In a concurrent dispatch, separate or coordinate edits based on file/interface contention, not permanent title or agent number. Broadly dispatched agents may cover multiple or all areas. All current UI tasks #1512, #1514–#1517 remain discoverable through the queue, with their technical requirements defined in [UX](UX.md).

## Documentation rule

Do not introduce “Agent 1 owns X” or “Agent 5 must merge Y” into active specifications, queues or handoffs. Describe the **work**, dependencies, actual overlap, proof requirements and applicable current dispatch instead. Previous numbered agent allocations are historical only: [archive](AGENT-LANES-HISTORY-2026-11-09.md).
