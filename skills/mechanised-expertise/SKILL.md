---
name: mechanised-expertise
description: Generate consequential questions, research plans, conceptual-gap audits, and testable engineering/UX decisions by executing bounded procedures. Use for ambiguous multi-system product decisions, unresearched user needs, concept-map discovery, or converting source advice into agent instructions. Skip for routine, reversible fixes and platform implementation with settled requirements.
metadata:
  version: "0.8.0"
  origin: "Original procedural synthesis of pinned mobile-development skills and project engineering incidents"
---

# Mechanised Expertise

**Purpose:** convert observed task facts into specific operations that produce useful questions, evidence, bounded choices, and verification. Do **not** ask an agent to imitate a human expert. The host project's user instructions, `AGENTS.md`, architecture, permissions, and real evidence always take precedence. A skill is neither authorization nor proof of a working feature.

## Start here: decide whether this skill is necessary

1. **Read the current task, relevant project decisions, and closest working implementation.** Write `actor | desired outcome | actual entrypoint | known constraint | observed uncertainty` (use `unknown` where genuinely unresolved). Do not invent product requirements.
2. **Routine and reversible with an established pattern?** Make the smallest conforming change and run its focused check. Stop; do not create an inquiry card, concept map, or research agenda.
3. **A consequential unknown could change implementation, user experience, authority, or verification?** Choose **one** row below. Open the **first linked reference** in that row; open subsequent links only if the first reveals an unresolved dependency. Do not pre-load other skills.
4. **A supposed risk is not reachable, or both answers imply the same action?** Reject that question. Return to the routine path or resolve the remaining real question. Never expand an investigation just because a keyword appears.

| Observed condition | First action | Open only when needed |
|---|---|---|
| User goal or UX requirements incomplete | Extract goal, current user journey, missing visible states, and conflicting user interpretations; research actual user accounts and established interfaces | [User needs and prior art](references/user-needs-and-prior-art.md), then [Evidence Acquisition](../evidence-acquisition/SKILL.md) only if source search itself is unresolved; then [nine inquiry operators](references/inquiry-generation-operators.md) |
| Multiple system, state, identity, permission, or lifecycle boundaries | Trace `actor → exact target → operation → suspension/transition → persisted state → observable outcome`; identify the first unresolved boundary | [Mechanical execution contract](references/mechanical-execution-contract.md), [conditional compositions](references/composition-operators.md) |
| Missing feature/relationship or apparently novel concept | Identify 3–6 competency questions, build only decision-relevant directed edges, reverse-trace the user's journey; audit retrieval, aliases, schema and known connections before calling a gap new | [Concept Model Engineering](../concept-model-engineering/SKILL.md), [mechanised-intuition method](references/mechanised-intuition-method.md) |
| Architectural/UX choice with material trade-offs | Separate hard constraints from preferences; compare the viable conventional option against one credible alternative; name a decision-reversing observation | [Engineering Judgment](../engineering-judgment/SKILL.md), [composition recipes](assets/compositions-v1.json) |
| Converting corpus claims into executable skills | Pin the source and scope; specify trigger, exclusion, named inputs, ordered transforms, opposing decisions, independent oracle, stop rule | [Corpus-to-procedure](references/corpus-to-procedure.md), [source synthesis](references/source-synthesis.md), [compact procedure index](references/procedure-index.md) |
| Need to establish a shipped behavior or separate plan from execution | Select test tier that reaches the claimed real boundary; log exact revision/environment, assertions executed, observed outcome, and remaining unknowns | [Evidence and corpus](references/evidence-and-corpus.md), [expertise evaluation](references/expertise-evaluation.md) |

After substantive prior-art/user-needs research, consider [an optional human-choice handoff](../human-agent-interaction-engineering/references/opportunity-handoffs.md) only when evidence establishes meaningful alternatives; deliver the research first. No survey is mandatory, and unanswered preferences are not blockers.

For native Android implementation with sufficiently understood goals, start directly with [Android App Development](../android-app-development/SKILL.md). Do not load all four skills. An advisory decision procedure may supplement that primary implementation skill only when it changes a concrete decision.

## Execute one inquiry transaction

For **each** activated uncertainty, record in the existing issue or work artifact:

`cue + source → question → answer A / action A; answer B / action B → cheapest trustworthy evidence → potential reversal → independent user-outcome check`.

1. **Ground** the cue in the current specification, source path, observed test, user report, or versioned primary document. Separate source text from editorial interpretation.
2. **Generate** a question with two plausible answers. **Discard** it if both lead to the same design, implementation, test, or authorization decision.
3. **Investigate** through the lowest-cost source that can answer reliably: existing code/test for implementation, primary docs for contracts, real runtime for behavior, contextual user testimony/observations for preferences. Ask the owner only for values or authorization that are not already recorded.
4. **Map only if needed:** If an unknown *relationship* among actors, goals, states, features, and outcomes could change the choice, invoke the concept-map skill; otherwise use a short path trace. A missing graph edge is not proof that a feature is absent.
5. **Choose** using verified hard constraints and the strongest relevant precedent, naming the observation that would reverse the plan. Mark any untested choice as `proposed`.
6. **Verify** the claimed effect at the required tier. Compilation, mocks, device UI, real remote provider, and user observations prove different things. Never promote a planned or skipped step to observed success.
7. **Stop** when additional plausible answers cannot change the decision or its acceptance check; document remaining uncertainty. Retain only a conditional lesson after actual observations.

## Optional executable tools, not mandatory rituals

- To inspect a single corpus-derived mechanism without loading the entire catalog: [procedure index](references/procedure-index.md), then `python3 skills/mechanised-expertise/scripts/procedure_lookup.py --id PROC-005` (from repository root; from this skill directory, use `python3 scripts/procedure_lookup.py --id PROC-005`). Do not choose a procedure solely from its name.
- To generate *unanswered* questions from inspected facts: [question compiler](scripts/compile_inquiry.py) and [synthetic remote example](examples/mechanical-remote-mutation.json). The [routine typo example](examples/mechanical-routine-typo.json) should produce no questions.
- To plan multiple procedures with explicit dependencies: [procedure-chain compiler](scripts/compile_procedure_chain.py) and [chain reference](references/procedure-chains.md). **All planned outputs remain unobserved** until independently executed.
- To validate a structured inquiry: [schema](schema/inquiry-card-v1.schema.json) and [inquiry checker](scripts/check_inquiry.py). No card required for minor changes.
- To inspect a worked UX research path: [transfer-list discovery case](examples/transfer-list-discovery.md). To review additional skill evidence and lineage: [pinned source table](assets/source-to-expertise-v09.csv) and [source synthesis extension](references/source-synthesis-extension.md).

**Minimal completion receipt:** `requested outcome | changed/observed code or user behavior | executed proof and environment | unresolved constraints | next authorized action`. For exploratory decisions, additionally attach the consequential question, evidence source, viable alternatives, and reversal condition.

**Limits:** Source recommendations, our proposed mechanised procedures, and measured effectiveness are different evidence classes. Source frequency does not prove correctness. Tools validate structure, not human preferences, external source truth, or production behavior.