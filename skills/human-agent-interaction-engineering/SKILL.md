---
name: human-agent-interaction-engineering
description: Design proportionate, evidence-backed human-agent interactions: optional feature-choice surveys after research, on-demand project decision briefings, structured reviews, preference elicitation, observation collection and explicit approval. Use when a user asks for forms or "any choices to make?", when work requires human input, or when research opens a meaningful optional choice space. Avoid needless questionnaires and confusing preferences with authorization.
metadata:
  version: "0.1.0"
  origin: "Original synthesis of owner-directed development, prior-art research and decision-review workflows"
---

# Human-Agent Interaction Engineering

**Purpose:** make the exchange of information, judgment, corrections or authority between human and agent as useful, low-burden and faithful as possible. An interaction is a task-specific interface, not necessarily a form. It can be initiated by either party. The host project's authority, actual evidence, user instructions, security constraints and available UI controls take precedence.

## Select the interaction from the actual trigger

| Trigger | Agent behavior | Avoid |
|---|---|---|
| **Needed:** progress depends on genuinely unavailable human information, preference or authorization | Ask the smallest sufficient consequential question; batch only independent choices that are easier considered together | Asking a user to debug inspectable code, treating silence as approval |
| **Requested:** human asks for a form, review, comparison or “are there some choices to make?” at any time | Inspect the current project/research state and return a prioritized, optional **decision landscape**, including choices not yet recorded as issues | Assuming “choices” means only current blockers or reproducing stale queues |
| **Offered:** agent's prior-art, user-feedback or design research reveals a meaningful landscape of alternatives | Deliver the research first; if the opportunity is substantial, offer an **optional** grounded survey with recommendations, affected constituencies and partial responses | Interrupting with 25 compulsory questions or imagining consensus from anecdotes |

**Recognize opportunistic composition at completion boundaries.** When substantive prior-art/user-feedback research, a concept map or a design comparison yields a meaningful evidence-backed set of alternatives, check once whether those alternatives give the human useful authority or preference leverage. This can justify a short optional invitation even if the original task did not mention forms. Keep the original requested deliverable intact; avoid offering a survey when the material adds no real choices or merely restates settled decisions. Use the [Opportunity Handoff procedure](references/opportunity-handoffs.md) for the bounded trigger and rejection test.

A user-requested choice briefing does **not** imply a release audit, nor require that anything be blocked. It can show approaching, optional and reconsiderable directions. An agent-offered choice survey does **not** convert research into settled product requirements. When no useful choices exist, say so and identify the coverage limit.

## Execution in seven bounded steps

1. **Recover purpose and scope.** Establish which project, version, actor, current task and desired influence the interaction concerns. Read existing owner decisions, specs, actual work state, relevant research and constraints; note snapshot/revision and inaccessible sources. Do not repeat already answered questions.
2. **Generate candidates and preserve provenance.** Use [H1–H6 choice extraction](references/choice-landscape-extraction.md) when comprehensive research-to-choice coverage matters. When options must be mined from a large or unfamiliar corpus, apply the [Evidence Acquisition claim and behavior matrix](../evidence-acquisition/SKILL.md) first. Discover genuine options from primary sources, existing implementations, user reports, plans and uncertainty. For each candidate record `stable ID | issue/feature | alternatives | source/revision | reported constituencies/context | current decision state | consequences/dependencies | recommendation basis`. Separate observed prevalence from anecdote or agent inference. Existing decisions may be shown as **reconsideration opportunities**, clearly labelled as already decided.
3. **Decide which candidates to expose.** Classify: `needed now`, `upcoming`, `optional exploration`, `reconsider with new evidence`, `agent-owned / informational`. A required decision needs an actual authority boundary. A human-requested survey may display agent-owned issues for visibility, but must not falsely demand human approval. Consolidate duplicates without erasing independent obligations. Offer several worthwhile choices only if the human's attention has a credible payoff.
4. **Choose a usable interaction.** One uncertainty: conversational question. Multiple independent choices: optional batch cards or comparison matrix. Dependent choices: progressive/staged questions. Visual judgments: annotated comparison. Priority: ranking or resource allocation. Field evidence: bounded observation checklist. High-impact actions: scoped approval review. Respect the current device, accessibility, number of questions and UI capabilities. A long survey may need categories, filtering, expansion and a partial-submit path. Never show inert controls.
5. **Make each item informative and answerable.** Present concise question; plain-language alternatives and their trade-offs; agent recommendation *with rationale and confidence*; key source evidence and who cares **in which context**; effect on related choices; current default/owner decision; free-form correction or alternative; explicit skip/defer/revisit. Do not bias the interface by disguising inferred preferences as facts or hiding credible alternatives. Keep stable IDs across rounds and revisions.
6. **Interpret response semantics.** Distinguish `preference`, `provisional direction`, `confirmed requirement`, `approval to act`, `rejection`, `premise challenge`, `new alternative`, `request for investigation`, `defer`, and `no response`. Unanswered means **unknown**, not “no”, “agree”, “defer”, or authorized default. A choice in a research survey is a preference unless the user explicitly assigns binding force. Questions about safety do not replace independent evidence. Reflect consequential ambiguities before acting.
7. **Reconcile and close proportionately.** Echo answered IDs, interpreted meanings, untouched items, changed/reaffirmed assumptions and dependencies. Write to project authorities **only with authorization**, separating `elicited`, `decided`, `documented`, `implemented`, `verified`. If the task was just to collect preferences, stop after a usable summary. If implementation was authorized, trace accepted changes into specs/work queues and acceptance checks. Do not construct a second source of truth unless the project requires a durable decision index.

## Choose one targeted reference, not the whole library

- **When finding all material choice dimensions from extensive research:** [Choice landscape extraction](references/choice-landscape-extraction.md), with a reverse-coverage test.
- **When a different skill's output may create a useful director choice:** [Evidence-triggered opportunity handoffs](references/opportunity-handoffs.md), including cases where no interaction should be offered.
- **What UI and evidence fields to provide:** [Interaction design contract and patterns](references/interaction-design-contract.md).
- **After feature prior-art/user-criticism research:** [Optional feature-choice survey example](examples/research-to-optional-survey.md).
- **When a human unexpectedly asks “Any choices I could make?”:** [On-demand decision discovery example](examples/on-demand-choices.md).
- **When the job is an exhaustive blocker audit and downstream decision reconciliation:** use [Decision Synthesis and Closure](../decision-synthesis-closure/SKILL.md) as the primary skill instead.
- **When a genuinely disputed feature/identity/dependency relationship needs reconstruction:** optionally use [Concept Model Engineering](../concept-model-engineering/SKILL.md); the model must not become an imposed requirement.
- **When human answers are needed to distinguish hypotheses:** optionally use [Mechanised Expertise](../mechanised-expertise/SKILL.md) for the single choice-changing inquiry.

## Stop conditions and quality controls

**Minimum output:** an intelligible interaction (or a justified no-interaction response) plus `scope/source freshness | question IDs | evidence/recommendation distinctions | response semantics | consequences and dependencies | outstanding uncertainty | permitted next action`. The full ledger may remain behind the interface; it must be recoverable where source access exists.

**Independent checks:** compare shown claims with source evidence and established owner decisions, then check that skipped questions stayed unanswered and that submission returns the correct IDs, selected alternatives and optional comments. Include negative controls: routine fix, user requesting only a report, zero decisions, stale project snapshot, unsupported UI control, numerous nearly identical issues, high-risk approval, ungrounded “users prefer” claim. Measure false/missed choices, preference laundering, incorrect authority assumptions, question burden and lost evidence; attractive forms alone prove nothing.

**Non-authority:** This procedure doesn't require any new schema, issue, form, external service or persistent state. It neither grants blanket permission for project edits nor implies that simulated examples have been user-tested. Historical agent-evaluation plans remain dormant until separately authorized.
