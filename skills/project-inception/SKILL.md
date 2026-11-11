---
name: project-inception
description: Research and specify substantive new software projects before major production development. Recover authority and existing work, investigate competing and adjacent products, original user feedback, reusable code/tools/resources, conceptual coverage, architecture and acceptance, then issue per-workstream readiness decisions. Skip routine fixes and bounded disposable experiments.
metadata:
  version: "0.1.0"
  origin: "Cross-project synthesis of Retrofit, conceptual modelling, mechanised expertise and research-before-build directives"
---

# Project Inception: investigate, specify, then commit

**Purpose:** equip an agent starting a consequential new software project to understand the problem, ecosystem, available work to borrow, intended product and credible implementation before committing to substantial architecture. This skill coordinates established capabilities; it does not duplicate their methods or require every skill in context.

## Trigger and exclusion

**Activate** for greenfield apps, major architectural replacement, unfamiliar cross-system features with many unresolved product assumptions, or an explicit research-first comprehensive design request. The presence of existing code does not establish that the product premise is understood.

**Skip** for known/reversible fixes, narrow already-specified features and a cheap disposable experiment that discriminates one important unknown. Do not turn routine work into a mandatory research committee. Respect the director's explicit scope.

## Seven linked investigations

The headings are coverage obligations for substantial inception, not seven independent reports. Reuse current evidence and record source versions. Investigate in parallel where possible, reconciling dependencies before costly commitments.

1. **Project truth:** apply [the source/authority and conflict-reconstruction protocol](references/project-truth-reconstruction.md) for multi-source handoffs; inspect current branch, PRs, project AGENTS.md, owner decisions, current code, tests, documented plans, past mistakes and ongoing agents. Classify established requirement, provisional idea, prohibited action, unknown and conflicting claim. Do not repeat decisions already made.
2. **Users and actual jobs:** apply [Evidence Acquisition E1–E8](../evidence-acquisition/SKILL.md) when original-language feedback or task sampling can change the design; identify actors, environments, workarounds, normal and failure journeys, accessibility, privacy and authorization. Search praise, criticism, nonusers and practitioner reports in original language and context where relevant. Separate observed prevalence from anecdotes and inference.
3. **Solution ecosystem:** execute the [source-class, independent route, behavior and disconfirmation protocol](../evidence-acquisition/references/retrieval-protocol.md) when landscape research matters; investigate direct competitors, adjacent applications, professional workflows, open-source precedents, established interface patterns and failed approaches. Compare controls, defaults, feedback, permissions, error/cancel/recovery and limitations, not feature names or screenshots alone. Seek counterexamples across industries and terminology.
4. **Reusable resources:** apply [Resource Discovery R1–R8](../engineering-resource-discovery/SKILL.md) for unfamiliar components and build-versus-reuse; inventory SDKs, platform capabilities, libraries, APIs, datasets, assets, existing related-project code, integration adapters, development infrastructure, devices, testing tools and services. Classify each as reuse, adapt, integrate, learn, build, reject or investigate. Check compatibility, trust, licensing, maintenance and costs before adopting code or services; never run unreviewed scripts.
5. **Conceptual coverage:** map actors, goals, objects, identities, transitions, dependencies, data provenance, permission, exceptions and integrations. Invoke [Concept Model Engineering](../concept-model-engineering/SKILL.md) when uncertain relationships may affect design. Reverse-walk user journeys; stress-test blind spots and source coverage before claiming novel gaps.
6. **Product and engineering specification:** compile source-backed outcomes via [Specification Engineering S1–S9](../product-specification-engineering/SKILL.md) before substantial work; identify user-visible states, feature boundaries, interaction options, defaults, recovery, data life-cycle, offline/privacy requirements, external handoffs, accessibility and test oracles. Compare architecture with [Engineering Judgment](../engineering-judgment/SKILL.md) when expensive to reverse. Offer informed optional choices with [Human-Agent Interaction Engineering](../human-agent-interaction-engineering/SKILL.md) when user preferences or authority matter.
7. **Readiness assessment:** evaluate each consequential user journey and workstream for resolved intent, credible implementation route, dependencies, authorization and observable acceptance. Name the minimum safe slices and the questions that could reverse them. Use the [readiness standard](references/readiness-standard.md) rather than declaring the whole project researched or blocked.

## Scope, alternatives and stopping

Research breadth must track novelty, possible rework cost, irreversible effects and uncertainty. Aim for comprehensive coverage of *material* choices, not impossible global exhaustiveness. Search further only when plausible new evidence could change the next authorized decision. Record missed source classes, language/version limits and contradictory claims. A source plateau is conditional on the sampling strategy.

Use bounded disposable technical spikes with an explicit hypothesis and retention/teardown rule when they answer hard questions cheaply. Independent ready workstreams may progress while peripheral choices remain open; do not quietly convert a spike into permanent architecture. Do not treat a polished report, screenshot or green compilation as evidence that production outcomes work.

For each material unresolved choice, identify alternatives, whose values are implicated, recommended direction, cheapest trustworthy discriminator and cost of being wrong. Human answers can be deferred or correct the premise; silence does not grant authorization. Engineering details within delegated authority do not require gratuitous director approval.

## Inspectable output

Prefer updating the existing project authority rather than creating competing documents. At project inception, discover where **current** plans, product specifications, agent onboarding, active work, decisions and evidence will live, using existing repository mechanisms before creating new ones. Apply [Operational Documentation Integrity D1–D4](../references/operational-documentation-integrity.md) to establish concise role ownership and maintenance triggers; do not copy the host AGENTS.md, mandate an ADR hierarchy or require every workstream to use all document types. Ensure the following can be recovered:
- **Baseline and evidence inventory:** current revision, decided/proposed/inaccessible sources, limits and affected users.
- **Ecosystem and reuse comparison:** existing products, user feedback, candidate components, constraints and dispositions.
- **Concept/journey coverage:** normal and abnormal journeys, relationships, evidence-backed omissions and disagreements.
- **Product/engineering contracts:** required and optional behaviors, UI states, architecture, integration/data boundaries, acceptance and verification economics.
- **Choices and readiness map:** owner decisions, deferred alternatives, workstreams ready for build versus research, decisive experiments and next slices.

A research hypothesis is not an owner decision; a specification is not an implementation; a prototype is not a shipped product.

**Completion receipt:** scope/revision; evidence and its limits; relevant prior art and reusable resources; conceptual/product/architecture coverage; consequential owner choices; per-workstream readiness; next implementation or experiment; unresolved blockers.

**Negative controls:** typo, settled Compose layout bug, narrow security correction, single API probe. **Positive controls:** new professional mobile workflow, cross-system app, risky architecture reset, unclear external imagery pipeline. The negative controls must never trigger a giant research phase.
