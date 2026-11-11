# Recovering tacit expertise from incidents and practitioner knowledge

## Adapted cognitive task analysis

Applied Cognitive Task Analysis (Militello and Hutton, 1998) provides three complementary elicitation modes: **task diagram**, **knowledge audit**, and **task simulation**. Adapt them to permitted developer work without pretending the agent interviewed real experts:

1. **Task diagram:** outline the ordinary user/system journey and its boundaries. Identify where permission, identity, platform or asynchronous state changes.
2. **Knowledge audit:** for each pivotal decision, inspect the original request, accepted/failed implementation, platform contract and executed evidence. Extract five explicit records: triggering cue, attempted mechanism, causal reason it worked or failed, prerequisites/exceptions, and a discriminator that would distinguish the competing explanations. Mark missing records unknown instead of inventing practitioner intuition.
3. **Task simulation:** replay a *specific* evidence-backed incident (a merged correction, reproducible red test or current source trace), stop before the resolution, and ask how the candidate responds to altered circumstances. Compare to actual implementation and postcondition afterward.

Do not invent interviews or personal experience. User comments and source history are evidence of the described experience, with context and possible bias, not a representative survey.

## The high-value incident record

For each reusable lesson preserve:
- **Trigger:** what the request or symptom looked like before solution.
- **Novice attraction:** a plausible simple approach that failed or was insufficient.
- **Expert cue:** the hidden distinction that changed the decision.
- **Conventional mechanism:** platform/library/user pattern and its applicability.
- **Boundary:** when that mechanism becomes unsafe, inapplicable or less effective.
- **Discriminator:** actual observable test, counterexample, source contract or independent user evidence.
- **Outcome and evidence:** precise PR/issue/CI links, merged or merely proposed, current applicable versions.
- **Transfer cases:** a near analogy and a far analogy that require the same invariant but a different surface implementation.

Keep historical examples separate from normative project requirements; update lessons when a counterexample reverses the premise. “Someone already implemented this in a competitor” can resolve a supposedly novel feature gap without implying that your app ships it.

## Recognize before comparing

Gary Klein's Recognition-Primed Decision model describes *sizing up a situation*, recognizing a plausible option and mentally simulating it. Adapt this as:

- Retrieve a likely standard solution from the matched **signature**.
- Write an explicit **input → call path → state transition → side effect → observed result** trace, with the source for each known transition and an explicit unresolved marker for every unknown one.
- Reject it quickly when actual cues contradict the precedent, then compare alternatives deliberately.
- Never claim an agent's plausible first option is authoritative merely because it resembles an expert's first option.

Case-based reasoning (Aamodt and Plaza, 1994) offers the retrieve → reuse → revise → retain cycle. Retain reusable **conditional distinctions**, not just the final patch.

## Research implementation precedent proportionately

1. Verify actual authoritative Android/standard-library or vendor protocol API and version.
2. Search mature open-source implementations, established clients and release history, particularly when proposing bespoke low-level machinery.
3. Search user praise and criticism for *observed* workflow preferences and failure patterns; include native-language terms for international ecosystems.
4. Ask where the borrowed pattern fails in a different identity, provider, OS, scale or accessibility context.
5. Make an evidence-backed local decision and test it. Do not copy code without license review; do not infer reliability from marketing or one forum comment.

### Academic provenance

- Gary Klein, [Recognition-Primed Decision Model](https://www.gary-klein.com/rpd), and [Naturalistic Decision Making review](https://onlinelibrary.wiley.com/doi/10.1002/bdm.381): recognition, uncertainty and situational context.
- Militello and Hutton, [Applied Cognitive Task Analysis](https://pubmed.ncbi.nlm.nih.gov/9819578/): task diagram, knowledge audit and scenario simulation.
- Aamodt and Plaza, [Case-Based Reasoning: Foundational cycle](https://www.cs.auckland.ac.nz/research/groups/ai-cbr/classroom/cbr-review.html): retrieve/reuse/revise/retain.
- Gary Klein, [Performing a Project Premortem](https://hbr.org/2007/09/performing-a-project-premortem): generate failure mechanisms before implementation.
- UK Government Digital Service, [Architecture Decision Records](https://gds-way.digital.cabinet-office.gov.uk/standards/architecture-decisions.html): preserve context, choice, consequences and revisiting conditions.
- [Open Agent Skills specification](https://github.com/agentskills/agentskills/blob/main/docs/specification.mdx): progressive disclosure so comprehensive expertise does not become universal cognitive overhead.

These explain useful principles; they do not establish that this new skill improves software delivery until tested.
