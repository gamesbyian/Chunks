---
name: engineering-judgment
description: Make experienced, defensible software and product engineering decisions when an apparently simple request hides architectural, lifecycle, identity, UX, security, data, performance or testing complexity. Recognize problem signatures; recover expert cues and conventional solutions; simulate consequences; find discriminating evidence; avoid overengineering. Use before consequential design/implementation choices and in failure postmortems, not as a mandatory gate for routine fixes.
metadata:
  version: "0.2.1"
  origin: "Chunks, Glimpse, Expository, AYA and UR-Recomp engineering cases"
---

# Engineering Judgment: explicit mechanisms for tacit expertise

**Purpose:** reproduce the useful effects of experienced professional judgment without claiming intuitive insight, human experience, or certainty from fluent explanations. This is an advisory **decision subroutine** inside an authorized workflow. Project AGENTS.md, user requirements, source evidence and established safety boundaries take precedence. Never treat research or a skill as authorization to mutate user data.

## Decide how deeply to investigate

- **Routine/reversible/known:** follow the local convention, make the smallest change and run focused checks. No new issue, research corpus or decision document.
- **Coupled/ambiguous/user-visible:** run the compact six-step method below, reading only a relevant pattern/reference; record a few sentences in the existing issue/PR.
- **Irreversible, cross-account, uncertain side effects, migration, security, unfamiliar platform or large architecture:** collect direct evidence, test a positive/negative control, explore alternatives and document the decision in the existing project authority. Seek a scoped director decision only for unresolved product authority, not for inspectable code behavior.
- **Fundamentally exploratory:** invoke [concept modeling](../concept-model-engineering/SKILL.md) to qualify missing knowledge; do not promote the resulting hypotheses directly to app requirements.

The **cost of investigation** should scale with irreversibility, uncertainty, external coupling and cost of a mistaken assumption, not with how long the agent can write.

## Six-step decision procedure

1. **Recognize:** Translate the user's request into an observable outcome, and identify its hidden **complexity signatures**. Name actors, objects, state boundaries and what a normal user would expect. Load the matching rows of [signature catalog](references/signatures.md).
2. **Extract discriminating conditions from evidence:** for unfamiliar source landscapes use [Evidence Acquisition](../evidence-acquisition/SKILL.md), and for unknown conventional implementations use [Resource Discovery](../engineering-resource-discovery/SKILL.md). Inspect accepted/failed incidents, the closest working implementation, platform contracts, user observations and actual source entrypoints. For each precedent, record **observed trigger → previously failed or successful mechanism → prerequisite → exception → cheapest observable discriminator**. Compare those five fields to the current task. If a prerequisite is unknown, inspect its source rather than guessing. Use [expertise extraction](references/expertise-extraction.md) and the [mechanical inquiry contract](../mechanised-expertise/references/mechanical-execution-contract.md).
3. **Name the forces:** Freeze authority, context and hard constraints; separate policy from implementation, observed facts from assumptions, and safety from ordinary authorized behavior. Identify the one or two unknowns that could reverse the decision.
4. **Trace candidate mechanisms:** Start with the smallest conventional plausible option. Write **user input → actual entrypoint → state/identity owner → side effect → user-visible result**. Add only reachable failure transitions (cancellation, timeout, restart, permissions or changed identity) that can alter the choice. Write the observation that would refute the preferred option and the independently checkable success result. Compare explicit alternatives when a precedent fails its prerequisites or the decision is expensive to reverse.
5. **Discriminate:** Choose the cheapest trustworthy observation or experiment that separates competing hypotheses. Test the actual production path and exact target, not a convenient proxy. Preserve genuine red evidence; distinguish simulated tests, real platform/provider tests and device/owner acceptance.
6. **Decide, integrate, learn:** Implement a bounded vertical slice; record the decision in the **existing** issue/PR/ADR, with source, alternatives, scope, consequences and reversal trigger. After actual results, update or supersede the *generalizable decision pattern*, never blanket-copy a context-specific rule.

## First answer to an ambiguous feature request

Return a **compact decision sketch**, not a design bureaucracy:

`Desired outcome → recognized problem families → hidden constraints → conventional solution to investigate → key disconfirming cue → smallest real proof → next action.`

If a clear standard approach satisfies the case, act. If a premise is disproven, revise the classification rather than building increasingly elaborate defenses for it. If a potentially harmful operation is already disabled, leave that narrow capability blocked while allowing unrelated safe journeys to progress.

A consequential UX/product comparison may establish alternatives that the director could reasonably choose among, even when no decision blocks engineering progress. At that output boundary, consider a bounded, optional [opportunity handoff](../human-agent-interaction-engineering/references/opportunity-handoffs.md). Do not repeat a decision already settled or convert an engineering default into an owner approval ritual.

## Related mechanisms

- [Mechanised expertise synthesis](../mechanised-expertise/references/mechanised-intuition-method.md) for conditionally generating decisive questions, selecting original-source/prior-art evidence, and combining specialist advice only when the chosen task warrants it. Do not impose a separate methodology on routine changes.
- [Complexity signatures](references/signatures.md) for what to investigate.
- [Expertise extraction and research](references/expertise-extraction.md) for turning incidents, source, practitioner experience and user feedback into reusable decision rules.
- [Decision protocol and anti-patterns](references/decision-protocol.md) for using conventional patterns, simulation, reversals, escalation and evidence.
- [Cross-project historical cases](references/project-cases.md) for Chunks, Glimpse, Expository, AYA and UR-Recomp.
- [Validation economics](../android-app-development/references/validation-economics.md) for **consequential testing strategy** decisions: whether a suite can exercise changed code, when to share expensive setup, when a tiny diagnostic is more informative than another full E2 run, and when early high-risk proof is mandatory. Grounded in the [observed cross-repository audit](../android-app-development/references/validation-case-audit.md). This is optional, not a universal gate.
- [Testing and transfer evaluation](references/evaluation.md) for unseen analogues and anti-memorization.
- [Structured decision patterns](assets/patterns.json) and an [offline probe set](assets/transfer-probes.json) support reproducible decision checks. The [executable judgment lab](lab/README.md) goes further: five runnable code tasks whose original behavior fails while a valid reference passes the same source-level tests. These checks **still cannot establish** a general productivity improvement without matched agents and independent product-level review.

**Success criterion:** an agent reaches a better observed decision *with less unnecessary work*, recognizes important exceptions and knows when familiar rules stop applying. Fluent rationale, a long checklist, or passing multiple-choice questions alone cannot establish expertise.
