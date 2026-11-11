# Recognize opportunities created by other skills

**Scope:** Optional, cross-capability handoff at the point where a substantive investigation, conceptual model, comparison or implementation plan has produced evidence the human could use. This is a *selection rule*, not a compulsory four-skill pipeline or a requirement to generate forms. Its effectiveness remains unmeasured.

## Trigger: a useful choice space emerges

When producing a substantive research/model/design deliverable, make one bounded judgment:

> Did this work establish meaningful, evidence-backed alternatives whose consequences depend on a human's values, priorities or product authority, or show new grounds to reconsider an existing direction?

If **yes**, determine the least intrusive way to make the opportunity available. The original deliverable is completed first. A user-requested survey is presented directly; an unrequested opportunity may be a short offer, a small optional section, or a prepared choice view where the host UI genuinely supports it. Don't hold implementation hostage to unrequested feedback if an existing direction permits work.

**Necessary evidence:** (1) at least one concrete choice or reconsideration with distinct consequences; (2) supporting source/version or explicit inference with known gaps; (3) a credible reason the human would care; (4) existing project authority checked. The opportunity need not be a *blocker* or a previously recorded issue. One highly consequential alternative can suffice; there is no artificial minimum of 25.

**Reject / do nothing:** Routine reversible fix; purely technical choice settled by current requirements; no defensible alternatives; speculation masquerading as user research; repeated offer already dismissed without new information; user explicitly declines interaction; high cost/distraction relative to benefit; no accessible sources and no reliable current claim; or user requests only a focused answer where a large form would derail it. Do not manufacture a choice merely to justify a handoff.

## Minimal handoff object (local, not a new canonical database)

Keep only fields needed for an actual receiving action:

```yaml
origin: "prior-art investigation | concept-model delta | design comparison | other"
source_scope: "repository revision, products/versions, user-report collection, coverage limits"
choice_id: "stable within the project or report"
question: "plain-language decision"
state: "undecided | existing direction | provisional | superseded"
alternatives:
  - label: "established implementation or explicitly proposed alternative"
    evidence: "source locator and evidence kind"
    tradeoff: "meaningful consequence"
constituencies: "whose preferences/complaints matter in what context, and what is actually observed"
recommendation: "optional, rationale + reversal conditions"
dependencies: "other features, constraints and acceptance criteria"
proposed_interaction: "direct question | optional batch | comparison | reconsideration | no UI"
authority: "preference only | owner decision needed | explicit action approval needed"
```

This is a *transport shape*, not a requirement to write YAML, create an issue, or add fields to every concept map. Retain source IDs and decision IDs when transforming between representations. Missing evidence remains marked missing; a new human preference is evidence about the director's preference, not empirical evidence about all users.

## Composition decision

1. **Identify origin and deliver its primary output.** A concept-modeling task may deliver a map; research may deliver a report; an engineering task may deliver a change. Never replace it with a survey just because options became visible.
2. **Extract actual alternatives.** Map feature/control-pattern/user journey to supported options and circumstances. Separate *exists in competitor*, *reported user reaction*, *agent inference*, *existing project choice* and *new proposal*. Audit duplicates and disagreements before labeling a dimension open.
3. **Classify human leverage.** Does a choice alter user experience, accepted goals, risk tolerance or cost? Was it already decided? If so, only offer **reconsideration** when there is a credible reason; preserve the prior choice. If none of these conditions apply, leave it with normal agent execution.
4. **Choose presentation level.** An explicit user request for choices warrants the interface; a discovered but unsolicited opportunity typically warrants a concise invitation with the number and themes of evidence-backed dimensions, never a compulsory wall of controls. Provide the full form if requested or if clearly useful and not disruptive.
5. **Preserve optionality and momentum.** Options include skip, comment, alternate proposal, defer, and challenge-the-premise where supported. Unanswered items are unanswered. Do not turn provisional survey results into implementation authority or automatically stop authorized work.
6. **Loop back when useful.** A response may select a pattern, introduce a novel alternative, challenge a source interpretation or change the conceptual model's categories. Update existing project authorities only when authorized, and keep decision strength and provenance. Further investigation should target a consequential unknown, not generate more surveys indefinitely.

## Example: research on control patterns

An agent researches how several file-management apps present sorting, conflict resolution, compact/detailed file rows and multi-environment browsing. It also inspects original user praise/complaints. The concept model shows a distinction among **control appearance**, **control behavior**, **user situation**, **implementation cost**, **existing owner choice** and **recovery/safety requirement**.

A useful output is still the requested research and the model. The additional opportunity is an **optional feature-choice survey**, not a release gate:

| ID | Choice surfaced from model | What the agent should disclose |
|---|---|---|
| UX-01 | Compact vs detailed vs adjustable rows | Existing decision if any; who works with many items vs occasional use; evidence quality |
| UX-02 | Global sort vs saved sort per view | Distinct persistence and discoverability consequences; a conditional agent recommendation |
| UX-03 | Ask, rename or skip destination conflicts | Relevant safety invariants independent of preference; relationship to Move/Copy semantics |
| UX-04 | Unified vs environment-specific browser | Which user groups operate multiple environments; overlap with current project plans |

For each, show grounded implementation examples, source links where available, competing observations, recommendation and a free-form correction field. If the owner has already settled UX-01 and UX-04, label them **established / optionally revisit**, without recasting them as unanswered. A 25-feature inventory can be grouped into sections, and a response to only two items is still useful. If some supposed options come only from agent speculation, label them separately.

If the director later says “Any decisions for me?”, reopen the **same** grounded choice landscape with fresh status, including needed/upcoming/optional/reconsideration, rather than dumping every old feature or repeating settled questions.

## Counterfactual controls / independent evaluation design

These examples define expectations; they are not trial results.

| Input | Correct next action | Incorrect response |
|---|---|---|
| Rich, grounded prior art; owner asks only for report | Complete report, optionally offer useful survey | Replace research with compulsory 25-question survey |
| Rich, grounded model; user requests choice form | Present sourced cards with partial submit and existing choices | Ask whether a form is wanted or invent options |
| Map contains many features but no significant human trade-offs | Deliver map without gratuitous interaction | Generate form just because a map exists |
| Prior choice settled; credible contradictory new evidence | Offer optional **reconsideration** with prior choice preserved | Quietly reopen it as an undecided requirement |
| Prior choice settled; no new evidence | Continue agreed direction | Keep asking the owner the same question |
| Reply changes feature categories or supplies counterexample | Trace response to source/model and qualify revision | Treat response as a radio-button selection only |
| One or two survey items answered | Interpret only answered IDs as preferences unless explicit authority | Treat all blanks as consent, rejection or delay |
| Missing/weak evidence about who prefers an approach | State coverage limit, refrain from constituency claims | Manufacture quantified consensus |
| Owner wants no form, and work can continue | Respect refusal; continue within existing permissions | Repeatedly interrupt to offer the same interaction |

**Success is not simply survey creation.** Test both missed warranted opportunities and spurious interruptions; check correctness of provenance, choice status, options, recommendation strength, response semantics, owner attention cost and downstream permission boundaries. This reference does not authorize agent trials, new scheduling, or new CI gates.
