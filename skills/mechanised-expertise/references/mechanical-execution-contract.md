# Mechanical execution contract for mechanised expertise

**Status:** original editorial method; effectiveness has not been measured. This reference replaces vague imperatives with observable inputs, exact transformations, choice-changing branches, independent evidence sources and explicit stop rules.

## Execute one inquiry transaction

1. **Establish the user-observable outcome.** Read the task, current owner decisions, shipping entrypoint and its tests. Record actor, object/identity, operating environment, permission or state transition, and the desired visible postcondition. Link claims to exact files, specification sections, user reports or trace locators. Mark an unverified hypothesis as hypothetical.
2. **Match an operator by evidence, not task vocabulary.** A file named AGENTS.md does not imply storage risk; a real asynchronous operation that can change target identity does imply a boundary worth inspecting. Use the evidence conditions below and the nine existing operator records. If none matches, make the smallest local change and run its focused test.
3. **Apply the operator's rejection condition first.** Determine whether the task is already specified, reversible, or covered by the actual platform contract. A matching reject condition stops that operator. A task classified routine that has a real consequential boundary is *misclassified*: resolve the contradiction instead of skipping the evidence.
4. **Populate a parameterized question from the observed facts.** State two plausible answers. For each, name the different implementation, UX requirement, policy or verification action that would follow. If both answers imply the same choice, discard the question.
5. **Choose the cheapest trustworthy answer source.** Current code and product decisions answer implementation facts; platform and protocol documentation answer contracts; runtime instrumentation answers causal behavior; original user accounts and observation answer experienced friction; the product owner answers only genuinely unresolved preferences or authorization. Do not ask the owner to repeat inspectable facts.
6. **Decide whether a conceptual map pays for itself.** Use a short path trace for a single well-understood flow. Use Concept Model Engineering only if a missing link across actors, states, features, permissions, or sources could reverse the decision. Audit source omissions, aliases, missing edge types and known unlinked facts *before* claiming a frontier.
7. **Define a reversal observation and an independent oracle.** What observation would falsify the preferred plan? What actual user-visible, provider or platform behavior would establish success? A test plan is not a test result, and a mocked callback is not a provider-side effect.
8. **Stop or proceed.** Stop research once remaining answers cannot change the choice or acceptance test. Implement within user/repository authority, then retain which questions proved useful, which were false leads, and what the executed evidence established.

The procedure does not ask an agent to think like a person, use intuition, exercise taste, or find an unspecified "best" design. Every consequential judgment is expressed through a checkable condition.

## Nine concrete transformations

| Operator | Inspect and transform | Reject when |
|---|---|---|
| GOAL-INVERT | Extract a success postcondition; examine its actual prerequisites and failure transitions; negate the postcondition for each reachable, consequential obstacle; formulate the obstacle question. | The suspected obstacle is unreachable or already covered by an actual verified recovery path. |
| MENTAL-MODEL | Read control label, actual invoked action and displayed feedback; write at least two distinct **hypotheses** about user expectations; seek user observations or comparable installed controls to distinguish them. | Text correction or fixed tokens change no user interaction or meaning. |
| BOUNDARY-CROSS | Trace exact resource ID, owner, authorization and generation across the real call chain; mark suspensions, reconnects, restarts and revocations; compare identity and state on both sides. | Pure local synchronous read with no identity drift or side effect. |
| DESIGN-QOC | Write one important design question, at least two feasible options, hard constraints and discriminating criteria; remove violating options, then seek a separating observation. | Accepted local solution meets constraints and credible rivals offer no material improvement. |
| MAP-REVERSE | Build typed goal → action → authority → state → visible result → recovery edges; traverse forward from required goals and backward from implemented features; check unsupported links against original sources. | One directly observable journey answers the scoped question without disputed relationships. |
| SCOPE-CHALLENGE | Extract an upstream recommendation's framework, version, resource owner, actor and prerequisites; compare each to the host project as match, mismatch or unknown. | Exact preconditions have already been verified identical or the rule is irrelevant. |
| SOURCE-OPPOSITION | Write a falsifiable claim, identify which source class supports it, retrieve an independent class or contradictory working implementation; preserve version and sampled population. | Direct primary evidence already proves the *bounded* claim at the needed tier. |
| ORACLE-INVERT | Write the claim and the actual executed assertions; identify a possible test-pass/user-fail case; check whether that case is reachable. | The test already exercises the necessary real boundary and observes the exact postcondition. |
| COMPOSE-JOBS | Record two real subsystems, their matching typed entities, existing journeys and plausible shared user job; propose a permitted cross-feature operation and search for existing prior art or contrary user need. | No user outcome, matching entity or permitted authority exists. |

## Optional deterministic inquiry compiler

To make the question-selection steps inspectable, create a normalized JSON input containing: task goal and risk tier; evidence inventory (id, type, locator, scope); observations (operator/kind, cue and source IDs); question-template parameters; explicit reject check; **two answer → action branches**; next evidence source; whether a relationship model could change the choice; possible reversal; and independent oracle.

Run from repository root:

    python3 skills/mechanised-expertise/scripts/compile_inquiry.py skills/mechanised-expertise/examples/mechanical-remote-mutation.json

For the negative control:

    python3 skills/mechanised-expertise/scripts/compile_inquiry.py skills/mechanised-expertise/examples/mechanical-routine-typo.json

The first produces unanswered questions and sources to inspect. The second produces no question. The compiler rejects missing or duplicated source IDs, wrong operator/cue combinations, omitted question facts, unexamined rejection conditions, and contradictory routine classifications. It skips a question whose possible answers do not change the decision.

**Limits:** this program validates internal structure, not whether a GitHub locator was actually read, an observation is correct, two differently worded decisions are semantically distinct, users want a feature, or an app works. An agent must independently inspect source material and run the relevant real checks. It never recommends a final option or executes user mutations.

## Continuous instruction-quality audit

During revisions, inspect all imperative statements in operational skills. A directive such as "think like an expert", "use intuition", "choose what feels right", "apply common sense" or "consider everything" is inadequate. Replace it with an explicit input or observable cue, an operation, a decision condition, an output, an evidence source, and a stop rule. This applies equally to UX, requirements, code, conceptual mapping and testing skills. Explanatory scholarship may discuss expert cognition; *operative commands must be executable*.

See the original source-to-expertise table and nine inquiry operators for provenance. The compiled transformations are editorial procedures and must not be counted as new independent published recommendations or proven improvements.
