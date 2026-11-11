# Agent Skills capability architecture

This directory is a library of **complementary capabilities**, not a pyramid with one master methodology. Select the *single primary skill* whose deliverable matches the task; add another only for a concrete, unresolved dependency. No skill can grant permission to modify a project.

## Complementary capabilities and task-specific orchestration

| Capability | Primary output | Example | Avoid |
|---|---|---|---|
| Domain reference | Applicable platform/API contract, version, prerequisite and exception | Identify Room migration contract or Android background restriction | Treating borrowed guidance as executable proof |
| Operational execution | Changed implementation, research extract, user interaction, or test artifact | Fix a file grant bug and run the installed acceptance journey | Replacing work with a plan |
| Evidence acquisition and ecosystem investigation | Inspected source/claim ledger, independent search routes, competitor and user observations, bounded search coverage | Research how competing inspection apps handle annotation and undo | Treating snippets/reposts as verified behavior or population preference |
| Engineering resource discovery | Qualified SDK/library/API/code/dataset candidates and build-versus-reuse disposition | Evaluate annotation library, AYA adapter or segmentation SDK before building | Automatically installing a top search result or inventing license compatibility |
| Product specification engineering | Traceable user outcomes, journey/state matrix, architecture/data contracts and workstream readiness | Compile a multi-feature retrofit-photo journey into states and acceptance | Pretending a feature list is a coherent implementable product |
| Mechanised inquiry and decisions | Source-linked choice-changing questions, alternatives, discriminators, stop rules | Identify which unanswered retry condition changes a design | Asking an agent to imitate intuition or investigate everything |
| Conceptual modelling | Typed concepts and relationships, coverage results, qualified unknowns | Build a map of users, intents, states, media identities and recovery | Equating missing edges with real-world absence |
| Human-agent interaction engineering | Appropriately scoped survey, decision briefing, observational checklist, review or approval interface and interpretable responses | Owner asks “any choices?”, or prior-art research reveals optional, evidence-backed feature alternatives | Mandatory forms, invented consensus, silently treating preferences as permission |
| Verification and evidence management | Independent observation tied to exact claim, environment, and revision | Show a persisted file survived restart, or mark untested | Promoting simulated, planned or verbal claims into observed success |

For a substantial greenfield product or costly architectural reset, start with [Project Inception](project-inception/SKILL.md) to coordinate reconstruction, [evidence acquisition](evidence-acquisition/SKILL.md), [resource discovery](engineering-resource-discovery/SKILL.md), conceptual coverage, [specification engineering](product-specification-engineering/SKILL.md) and per-workstream readiness. It is an orchestration trigger, not a universal prerequisite. See the [readiness standard](project-inception/references/readiness-standard.md) and [Retrofit example](project-inception/examples/retrofit-kickoff.md).

The same project can need several capabilities, but **none is a universal prerequisite**. A tiny visual correction may need only operational execution and a focused check. An ontology review may start directly with Concept Model Engineering. A complex UX problem may need user research, modelling, choice generation, implementation and runtime testing. The observed task determines the sequence.

Human-agent interaction is a capability in its own right: a form or decision landscape can be the requested deliverable even when no engineering decision is blocked. Use [Human-Agent Interaction Engineering](human-agent-interaction-engineering/SKILL.md) as the entry point for surveys, on-demand choice discovery and structured reviews. Use [Decision Synthesis and Closure](decision-synthesis-closure/SKILL.md) for comprehensive blocker audits and downstream reconciliation. Their boundaries overlap, but neither is a compulsory prerequisite of the other.

When a requested deliverable is research itself, select [Evidence Acquisition](evidence-acquisition/SKILL.md) directly. For a specific unfamiliar component selection, select [Engineering Resource Discovery](engineering-resource-discovery/SKILL.md). When an evidence-backed specification must be constructed from already recovered goals, use [Product Specification Engineering](product-specification-engineering/SKILL.md). These are optional independent capabilities, not a mandatory three-skill preflight.

## Operational documentation integrity

When a material project decision, implementation result or milestone changes what an agent must do next, maintain operational plans, specs, onboarding, work queues and handoffs using the bounded [Operational Documentation Integrity D1–D4 procedure](references/operational-documentation-integrity.md). Discover host-project document roles and authoritative sources rather than assuming particular filenames. Replace stale directions instead of appending progress chronicles; preserve consequential historical evidence in existing records. A routine change may correctly require **NO CHANGE**. This maintenance subroutine is not a universal preliminary skill or a reason to audit the entire repository.

## Cross-project validation economics

When the task is specifically to reduce test/CI cycles or the director's repeated manual acceptance, use the optional [Validation Economics procedure](android-app-development/references/validation-economics.md) and its [observed Chunks, Expository, UR-Recomp and Glimpse audit](android-app-development/references/validation-case-audit.md). Despite its Android-skill location, the procedure's dispatch-relevance, shared-setup and diagnostic-information rules apply across repositories. Do not activate it for every routine commit, downgrade project-specific release gates, or treat a full green run as evidence of a path it cannot exercise.

## Two-way application without hierarchy

- **Mechanised inquiry → conceptual modelling:** generate a bounded question; invoke modelling only when an uncertain *relation* could alter a real decision, research question, or output.
- **Conceptual modelling → mechanised inquiry:** use deterministic extraction, alias checks, reverse traversals, counterexample probes and explicit stop conditions *inside the modelling workflow itself*. Do not require a full second skill just to run these operations.
- **Both → other work:** user research, implementation, testing, design, debugging and delivery can each consume findings. A model is a possible product deliverable, not an obligatory intermediary.
- **Other work → both:** observations and test outcomes may update source claims, conditional question templates and model relations. Preserve direct evidence and editorial inference separately.
- **Research or model → human interface:** offer an optional choice survey when grounded prior art and user criticism expose consequential alternatives; preserve provenance, and permit partial answers without commitment.
- **Human interface → research or model:** a response may provide a new requirement, a provisional preference, a counterexample or a challenge to the entire premise; trace its effects without converting silence into agreement.

## Evidence-triggered opportunity handoffs

The handoff between capabilities is driven by a **newly established consequential choice**, not by completion of a mandatory pipeline. At the boundary of substantive research, modelling or design synthesis, the primary agent can check whether it has discovered evidence-backed alternatives whose consequences depend on owner values or established authority. If so, expose the choice space as an optional interaction; if not, simply deliver the original artifact. This check should be proportionate and should **not** load every skill, create a standing form obligation, or reopen settled decisions without new evidence.

See the [Opportunity Handoff procedure](human-agent-interaction-engineering/references/opportunity-handoffs.md) for triggers, rejection conditions, a minimal transport record, evidence distinctions and negative controls. The same evidence may flow back from human responses into a concept map, requirements or further targeted investigation. Existing project rules determine what may be written.

## Routing examples

0. New complex application with unresearched users, ecosystem and architecture: Project Inception first. Permit bounded proof spikes and isolated ready slices.

0a. Focused competitor and native-language user-criticism research: Evidence Acquisition, without pretending uninstalled marketing features were observed.
0b. Need to find an appropriate image processing library or integrate existing code: Engineering Resource Discovery, including license/compatibility gates and bounded proof spikes.
0c. Multi-feature product plan requiring explicit UI, ownership and acceptance contracts: Product Specification Engineering, with independent workstream readiness.

1. Known Compose layout bug with a reproducer: Android Development first; fix and test. No map or research committee.
2. Integrating two independent user workflows whose identities differ: primary engineering/product skill; invoke a bounded map only for relationships that could change implementation or UX.
3. Building an ontology from multilingual source material: Concept Model Engineering directly; apply its own mechanical source inventory, extraction, identity, relation, and gap-audit operations.
4. Compiling third-party recommendations into agent procedures: Mechanised Expertise; preserve pinned source scope, convert only inspectable conditions into operations, and separately record unsupported editorial extensions.
5. Reviewing a shipped result: task-specific verification; a passing mocked result never establishes remote/provider effects.
6. After comparative user-preference and implementation research: deliver the report, then optionally offer a grounded feature-choice survey rather than blocking on answers.
7. Owner unexpectedly asks “What decisions can I make?”: inspect current sources and provide needed, upcoming, optional and reconsideration opportunities; do not force a release audit.
8. Owner asks to make plans, specs, onboarding and queues primarily instructional: inspect role/authority, execute the bounded documentation integrity procedure, preserve consequential evidence elsewhere and stop when current next actions are unambiguous.

## Acceptance for changes to this library

For operational-quality review follow [the recursive competence-assumption audit](references/operational-completeness-audit.md), including negative controls and fallbacks when inputs cannot be obtained. Its [structural reviewer](scripts/audit_instruction_completeness.py) produces candidates, not certification of agent competence.

For every new procedure specify **activation evidence, rejection condition, required inputs, ordered actions, named outputs, decision branch (when applicable), independent oracle, and stop rule**. A standalone reference may describe facts without becoming a mandatory workflow. Check both positive and negative control tasks. Do not require all skills in a single agent's initial context. No method gains authority merely because its name appears in a parent skill.
