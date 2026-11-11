# Mechanised intuition: conditional expert inquiry and model-directed decisions

**Original editorial methodology.** The [source synthesis](source-synthesis.md) identifies narrower practices in original, pinned skill documents. No author there is claimed to have developed this combined procedure, and no controlled improvements have yet been demonstrated.

## Recognize evidence-bearing cues, not isolated keywords

Reconstruct **actor, goal, existing journey, runtime environment, resource/identity, ownership, transitions, observed outcome and hard constraints**. Read existing owner decisions and the shipping code first. Pay attention to *boundary crossings*: UI to persistence; one account/server to another; online to uncertain write; foreground to process death; granted access to revoked access; layout to accessibility; feature to another feature; model assumption to observed behavior.

A boundary is a **hypothesis** of complexity, not a bug. Activate only if different plausible explanations could change implementation, UX or acceptance.

## Compile questions, then discard the useless ones

For each uncertain cue formulate:

`cue | concrete question | plausible opposite answers | decision/requirement changed by each answer | cheapest reliable answer source | falsifier | applicability scope`.

Prioritize blockers and decision-reversing uncertainties over interesting background. A question fails the admission test if its possible answers don't change a relevant choice. Source policy: owner/repo/actual runtime for product facts; primary technical docs for platform behavior; real user accounts and comparable products for experiential facts; owner questions for genuinely missing value judgments or permission. Never ask the owner to repeat inspectable facts. No invented numerical confidence.

**Example:** “Reconnection should be automatic” → does that mean when app opens, when network recovers, or after reboot? Is the last authenticated server profile retained? Which answers change user-visible state, permissions or retry policy? Inspect existing specification, login code and Android lifecycle before owner questions.

## Recover experts' discriminating cues

Convert a passage, field incident, mature implementation or documented bug into:

`symptom -> novice attraction -> expert cue -> causal mechanism -> scoped exception -> cheapest counterexample -> observed outcome (or untested)`.

A strong rule teaches **when to notice** that normal advice will fail. Inspect the exact entrypoint and compare existing working behavior. Repeated guesses trigger instrumentation or structural comparison, not increasingly elaborate fixes. Keep original source claims and our generalization as separate provenance layers.

## Qualify conceptual blank spaces

Use a graph when relationships across multiple users, states, actors, services, versions, operations or sources could change the decision. Otherwise use a short causal trace. Model user **goals/perceptions**, system **state/ownership** and evidence; meaningful directed edges include “operation changes state”, “authorization scopes identity”, and “error appears to user”.

Before declaring a blank surprising, audit:
1. **Reverse traversal:** for each user goal, can an actor perform a visible action, receive trustworthy feedback and recover? For each implementation capability, which user goal and failure state does it serve?
2. **Source/ontology quality:** is the fact absent because a document wasn't searched, a passage wasn't extracted, aliases weren't normalized, a needed edge type doesn't exist, or known concepts weren't linked?
3. **Independent challenge:** inspect a held-out source or an alternative model, search old/foreign-language prior art and a credible already-built counterexample.
4. **Bounded novelty:** classify as `unresearched | unmodelled | underlinked-known | contradictory | version/provider boundary | excluded | unknown | candidate-frontier`. Never turn an open-world missing edge into proof of nonexistence.

Treat gaps by **expected impact on the decision**, not by node degree, number of empty cells or graph prettiness. The map may be adequately complete for one decision while incomplete for the universe.

## Decide with alternatives and reversal triggers

Freeze nonnegotiable constraints first: actual owner intent, permissions, data safety, supported devices and frameworks, source-traceable facts. Prefer the smallest conventional viable solution *fitting the current app*. When stakes justify it, steelman one serious alternative, simulate normal flow and a material counterexample, record tradeoffs and the specific result that would reverse your choice. Do not let a weighted score override hard constraints.

For UX define what a person **sees, recognizes, can reach, understands and recovers from**. Read [user needs and prior art](user-needs-and-prior-art.md) for questions and evidence collection rather than guessing layout from taste.

## Independently verify and retain the revised lesson

Match the oracle to the claim: unit tests for pure business logic, platform API exercise for lifecycle/permissions, installed app journey for UI, actual provider for remote effects, and genuine user observations for experiential assertions. Include one positive and one decision-discriminating negative check where material. State executed tier, platform/build/revision, untested assumptions and whether a fix actually shipped.

Retain a **decision delta**: cue predicted problem or false alarm; question changed the choice or wasted work; precedent transferred or failed; blank was genuinely surprising or a source/model omission; conditional rule amended with evidence.

### Depth control

- Routine, reversible, conventional edit: inspect, change, test, stop.
- Coupled/user-facing feature: compact record of cue → 1–3 consequential questions → answer source → option/reversal → oracle, in the existing task.
- Irreversible/high-risk: stronger evidence, competing option, explicit ownership/state/failure analysis, independent review.
- Exploratory: bounded competency-question model, source audit and qualified frontier report.

This process is intended to shorten the path to a sound decision, not impose a universal interrogation or an ontology on every task.
