# Interaction design contract and pattern selection

**Scope:** An operational reference for Human-Agent Interaction Engineering. It covers human-initiated, agent-required and agent-offered exchanges. Use when designing the interface, not for routine engineering fixes. The host/product's actual UI capabilities determine presentation.

## The decision-or-interaction record

Use the smallest representation necessary; a Markdown table, issue references or typed local data are equally valid. Stable IDs make batched answers auditable even when display order changes.

| Field | Meaning and important distinction |
|---|---|
| `interaction_id` and `snapshot` | Which project and evidence revision/time the interface summarizes; label stale/inaccessible sources |
| `item_id`, `group`, `role` | Stable identity, related feature/dependency group, and classification as needed/upcoming/optional/reconsideration/informational |
| `question`, `state` | What choice is offered; undecided/provisional/decided/superseded/reopened, not conflated with work status |
| `source_refs`, `evidence_kind` | Actual reference and tier: implementation/source, observed user account, cited study, assumption, or speculative recommendation |
| `alternatives`, `tradeoffs` | Options grounded in real patterns or marked proposals, including consequences, reversibility and notable exclusions |
| `constituencies` | Which users care, in which conditions, and evidence quality; never invent quantified consensus |
| `recommendation` | Agent preference conditional on goals/constraints, with reason, confidence and reversal evidence; show existing owner's choice separately |
| `dependencies` | Other choices, workflows or acceptance criteria affected; distinguish proven coupling from hypothesis |
| `response`, `comment`, `response_kind` | Exact submitted value, optional novel alternative/correction, interpreted speech act and strength |
| `authority`, `effects`, `receipt` | Whether feedback is informative or authorizes specific action; what changed or remains unverified |

**Do not mandate a standalone decision database.** Derive a fresh view from the project sources whenever possible; persist only useful decisions and accepted updates in established authorities. If a separate index is justified, ensure stable IDs, links to canonical records, freshness and supersession rules.

## Interaction choices

| Purpose | Default interaction | Adapt when |
|---|---|---|
| One blocking unknown | Single question with consequences | User asks for broader survey, or dependency makes one question premature |
| Optional feature selection from research | Thematic cards with alternatives, evidence, recommendation, skip, comment | 20–30 items require grouping/search, staged display, partial submission |
| “Do I have decisions to make?” | Decision landscape: needed soon, upcoming, optional, reconsider | No project access: disclose coverage; no decisions: report cleanly |
| Compare designs | Visual alternatives with annotation / side-by-side trade-offs | Small-screen device: one-at-a-time carousel and comparison summary |
| Prioritize competing work | Rank, bucket, or allocate a constrained budget | Items are coupled: show prerequisites before allowing contradictory priorities |
| Collect physical/user observations | Specific task and reproducible observation fields | Failure is unsafe: stop and present safer diagnosis; never ask for sensitive data needlessly |
| Review many exceptions | Consolidated queue with cause groups and per-item comments | Independent obligations remain: preserve them behind the group |
| Approve consequential change | Explicit action list, exact objects, side effects and reversibility; positive confirmation | Authority unavailable: show proposed plan and request proper authorization |

A free-text response may be more valuable than predefined options when the option set is incomplete. Always allow “something else,” challenge of the premise, or leaving an item blank when the UI supports it. Do not infer reasons from unexplained radio selections.

## Ranking choices without overclaiming

Prioritize human-facing items using grounded factors: impending dependency or cost of delaying, influence on product experience, irreversibility, evidence of controversy, novelty to the owner, and whether an early choice prevents rework. This is *not* a numeric formula and doesn't authorize discarding requested comprehensive coverage. Show category and ranking basis. When the human says “give me everything,” provide complete discoverable coverage and useful grouping, not a privately filtered top five.

Treat evidence as multiple dimensions rather than one popularity score:

- **What exists:** documented implementation patterns and exact product/platform/version.
- **What people report:** direct user praise/complaints, who reported it, use context and sampling limits.
- **What agent infers:** interpretation, hypothesized tradeoff or recommendation, explicitly distinct.
- **What project already decided:** authoritative requirement, provisional direction, or superseded choice.
- **What remains uncertain:** negative evidence, missing sources, unresolved dependency, unanswered owner values.

For multilingual community research, preserve original-language source context and avoid counting reposts, translations or a loud minority as independent prevalence. Do not infer universal preferences from forum votes alone.

## Submission and reconciliation semantics

1. Bind each control to a stable `item_id`. If an item changes materially across versions, issue a revised ID or mark the old version superseded; never silently reuse an ID with a different question.
2. On **partial submission**, send only answered items plus optional comments; allow comment-only responses. Treat absent IDs as `no response`, not `defer` or `accepted default`. Make a deliberate “defer” different from leaving blank.
3. Preserve the raw answer alongside the agent's interpretation. A human may be supplying a new option, correcting evidence, expressing preference strength, or authorizing action; these are not interchangeable.
4. Before binding changes, check established authority, dependencies and surviving safety obligations. A general feedback survey isn't a release/merge/deletion authorization. If feedback contradicts an existing requirement, present the proposed supersession and ask for a scoped decision only if needed.
5. Reflect the final set of affected IDs, untouched decisions and proposed next actions. For implementation claims, require actual code/behavioral evidence rather than counting the choice as done.
6. Never claim a UI saved state across sessions unless the host actually supports persistence. If no functional form is available, use a numbered text response template or submit-via-chat mechanism, honestly.

## Accessibility and attention economics

Mobile readability, keyboard accessibility, large enough touch targets, screen-reader labelling, clear radio/checkbox semantics, visible selected state and contrast matter. Avoid long unbroken pages of controls. Use progressive disclosure to hide *supporting details*, not to conceal relevant decisions. Optional completion means there is no “100% complete” pressure; progress counters should count answered items without implying obligation. Keep the main research result readable without interacting with a form.

**Presentation cost rule:** Don't interrupt a user who asked for investigation with a compulsory form. Deliver requested findings; offer an optional, relevant design survey if the choice space is real. When the user *asks* for a survey or decision briefing, produce it directly if current evidence allows, rather than asking whether they want the very thing they requested.

## Negative control checklist

- Routine known fix: implement and test; no form.
- Research finds little reliable preference evidence: say so; present implementations without fabricated constituencies.
- Release queue has 14 duplicates for one cause: show one decision with all 14 obligations traceable.
- Director asks for all decisions: show meaningful optional/reconsideration choices, not only blockers.
- Director selects one alternative and leaves 24 blank: register one provisional preference and 24 unanswered, not 24 approvals.
- Director writes “that's the wrong premise”: reopen the underlying assumption instead of treating it as a rejection of one listed alternative.
- A destructive action needs authorization: do not silently infer it from a design preference.
- A widget cannot submit or loses context: use a truthful plain-text mechanism, not an inert button.

## Boundaries

Decision Synthesis and Closure handles evidence-complete blocker audits and downstream governance. This skill handles *which human-agent exchange should exist and how to design it*. Mechanised Expertise can supply discriminating question design. Concept Model Engineering can audit disputed relationships. None is a compulsory stage in another.
