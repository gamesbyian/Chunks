---
name: mechanised-expertise
description: Turn development advice into conditional expert inquiry, grounded research, concept-map gap audits, defensible implementation/UX decisions, and independent verification. Use for ambiguous or consequential mobile/software feature design and when synthesizing agent-skill corpora. Scale effort to risk; avoid bureaucratic analysis for routine fixes.
metadata:
  version: "0.7.0"
  origin: "Mobile development skills research corpus, with complementary general-purpose engineering judgment and concept-model skills"
---

# Mechanised Expertise: reusable questions and decisions, not universal checklists

**Purpose.** Recover the observable consequences of expert experience by making cues, inquiries, investigations, models, choice criteria, exceptions and verification explicit. Do not claim actual human intuition. This advisory skill supplies a *method*, not project requirements, permission to act, or proof that a recommendation works. Current user instructions, host `AGENTS.md`, code and platform-specific primary sources prevail.

## Source-backed synthesis and mechanised-intuition routing

Use [22 pinned original skill passages and their scoped strengths](../references/source-synthesis.md) plus the [structured source-to-inquiry transformations](../assets/source-to-expertise-v09.csv) when composing advice from the mobile-skills corpus. Those authors' recommendations, **our proposed mechanisms**, and any observed benefit are three different evidence classes. Never treat popularity or repetitive copies as proof of general effectiveness.

For consequential decisions apply the [conditional mechanised-intuition method](../references/mechanised-intuition-method.md): **recognize boundary cue → generate choice-changing question → seek cheapest trustworthy answer → audit a concept map only if missing relations could matter → decide with a reversal condition → prove user outcome → retain conditional lessons**. For mobile design, use [user-needs and original-language prior-art research](../references/user-needs-and-prior-art.md), including context, workarounds, nonusers, state feedback, small-screen accessibility and diverse interaction styles. The [transfer-list case](../examples/transfer-list-discovery.md) is a teaching exercise, **not real product evidence**.

These references supplement the lightweight routine path below. They do not create new product requirements, authorize other projects, or require an exhaustive questionnaire before ordinary fixes.


## Use source-derived compositions when different specialties interact

For consequential issues spanning multiple specialties, consult [conditional composition operators](../references/composition-operators.md) and the [10 structured multi-source recipes](../assets/compositions-v1.json). Each recipe combines relevant source IDs from the existing pinned [source-to-expertise table](../assets/source-to-expertise-v09.csv), and records a **trigger, skip condition, decision-changing question, cheapest evidence path, map/no-map boundary, reversal condition and independent oracle**. These *editorial hypotheses* do not establish effectiveness or independent intellectual origins. Select only decision-relevant recipes; do not require the full recipe for routine fixes.

## Generate missing expert questions before choosing recipes

For ambiguous or unexplored work, read the [nine bounded inquiry-generation operators](../references/inquiry-generation-operators.md) and their [structured catalog](../assets/inquiry-operators-v1.json). They turn goals into *plausible obstacles*, contrast users' mental models, inspect state/identity boundary crossings, represent consequential design alternatives as Questions–Options–Criteria, reverse-audit conceptual maps, disqualify transferred rules, seek opposing evidence, invert test oracles and look for useful cross-system user journeys. Use only operators whose causal trigger is present **and whose plausible opposing answers would change a decision or acceptance test**. Each has an explicit reject condition and stopping rule. The [three teaching cases](../examples/operator-applications-v1.json) include one deliberately trivial negative control; none demonstrates production effectiveness. Offline structural checks: `python3 scripts/audit_inquiry_operators.py`.

## Execution rule: inputs and operations, not appeals to intuition

For a consequential unfamiliar feature, use the [mechanical execution contract](../references/mechanical-execution-contract.md) before generating a large requirements list. The contract specifies how to extract facts from the real project, match one of nine inquiry operators by an observable cue, check its rejection condition, instantiate a choice-changing question, choose the next evidence source, decide whether a concept map is worth building, and define a falsifying result and user-outcome oracle.

The optional [question compiler](../scripts/compile_inquiry.py) accepts a normalized evidence-linked case and emits **unanswered questions or explicit skip records**. See its [remote-operation fixture](../examples/mechanical-remote-mutation.json) and [routine typo negative control](../examples/mechanical-routine-typo.json). It checks structure but cannot establish that supplied observations are true; original source inspection and runtime verification remain mandatory. A routine risk label must never overrule a consequential cue in source evidence.

**Directive standard:** avoid operational commands such as “think like an expert”, “use your intuition”, “choose what feels right” or “consider everything”. Instead specify the input, source-backed trigger, transformation, opposing decision consequences, evidence request, output and stop condition. Never automatically elevate a generated question to a user requirement.

## Convert corpus advice into stepwise procedures

When developing or revising agent skills from the research corpus, use the [source-to-procedure compilation contract](../references/corpus-to-procedure.md) and inspect the [22 curated operational examples](../assets/source-mechanisms-v1.json). Each example has an original source ID, trigger, explicit disqualifier, named source facts, **ordered operations with declared input/output dependencies**, opposing decision actions, a discriminator, an independent oracle and a stop rule. Check the authored procedures using `python3 scripts/audit_source_mechanisms.py`.

These examples are proposed transformations, not ten new independent upstream recommendations or proof of improved agent performance. The unconverted source corpus remains a research queue rather than implicitly carrying a mechanised implementation.

## Compose operational procedures without fabricating results

When a feature crosses multiple consequential boundaries, consult the [mechanical procedure-chain contract](../references/procedure-chains.md) and the [symbolic chain compiler](../scripts/compile_procedure_chain.py). Select only source-supported, non-excluded procedures from the existing 22 [source-derived mechanisms](../assets/source-mechanisms-v1.json). Bind every input to a source fact or a previous named output and check downstream dependencies. A planned source reading, implementation step or oracle is **not executed evidence**. The compiler emits only a nonexecuted plan, preserving unknowns and pending upstream outputs; any source truth, semantic correctness, real behavior or authorized mutation still requires separate inspection and execution. A routine one-line correction should produce no chain.

## Additional specialist source-to-procedure coverage

Use [eight more source-pinned Android expert practice transformations](../references/source-synthesis-extension.md) when the task actually concerns installed database upgrades, background scheduling, worker progress, UI test seams, controlled UI components, error observability, changing permission grants, or untrusted Android intents. The added [source table](../assets/source-to-expertise-v11-additions.csv) and the expanded [30 operational recipes](../assets/source-mechanisms-v1.json) keep published advice separate from editorial execution methods. No new rule automatically applies just because the task mentions its topic: check the procedure's concrete trigger and exclusion, bind its inputs to inspected evidence, and require its independent oracle before claiming a real result.

## 0. Right-size the inquiry

- **Small, reversible, conventional change:** inspect the closest existing implementation, change only the necessary code and test it. Stop. Do not create a separate concept model.
- **Ambiguous/user-facing feature or coupled state:** make a compact inquiry and compare likely options. Ask only for facts that code, documentation, standards or safe tests cannot establish.
- **Irreversible, safety-critical, destructive, cross-account, asynchronous, migration or unfamiliar architecture:** complete the risk-bearing questions, expose an explicit model, independently verify assumptions and record reversibility conditions before the change.
- **Frontier research/knowledge integration:** model the domain with the sibling [Concept Model Engineering](../../concept-model-engineering/SKILL.md) skill. Establish coverage before declaring a blank a novel discovery.

## 1. Recognize what the request leaves unsaid

Translate the request into **actors, goals, environment, objects, identities, transitions, outcomes and constraints**. Identify the expert cues that make hidden requirements probable: interruption, identity drift, device settings, latency, multiple input paths, missing fallback, platform version, ambiguous user feedback, migration, remote side effects, and irreversible loss. Choose *only triggered* question families from [Inquiry and recognition patterns](../references/inquiry-patterns.md).

For each consequential unknown record **(question; why it matters; best answer source; what answer changes; confidence)**. Answer from real project data first. Distinguish product-owner preferences from observable facts; escalate only unresolved product authority. Don't ask users to restate an answer already present in project requirements.

## 2. Research and assemble a bounded model

Seek the most discriminating source for each question: existing code and tests; user feedback and complaints; official platform and accessibility standards; mature competitors and libraries; practical case reports including original-language phrasing. Record version, independence/derivation, and what was actually inspected. A popular community rule is not a verified platform requirement.

Invoke [Concept Model Engineering](../../concept-model-engineering/SKILL.md) if the decision depends on relationships across features, many entities/states, incomplete source classes, suspected missing interactions, or apparently novel gaps. State competency questions, build directed propositions, probe a held-out source or alternate representation, then classify blanks as acquisition, alias, schema, missing edge, disagreement, boundary, deliberate omission or candidate frontier. Do not infer absence from an open-world map.

## 3. Make and challenge a decision

Freeze hard constraints and non-goals. Prefer a conventional plausible solution that matches the observed environment; compare alternatives when an exception matters. For each candidate **trace** the user input → actual call path → state transition → visible result, then trace only the material failure variants (denied permission, stale identity, offline/timeout, process death, retry, input scale, accessibility or migration). Identify the cue that would reverse the preferred option and the cheapest observation that separates competing hypotheses. Use [Engineering Judgment](../../engineering-judgment/SKILL.md) for higher-stakes trade-offs; never use a numerical weighted score to override a hard constraint.

Define each UX outcome as an observable user-perceived state and feasible interaction, not just a developer-facing implementation. Confirm what feedback, visibility, cancellation, accessibility, and recovery the user receives. Mark any source guidance that is framework-specific, opinionated or version-gated.

## 4. Prove the thing claimed

Create an *independent oracle* aligned to the user goal. A build proves compilation; a test double proves the stubbed contract; an RPC ACK proves only that the server responded; a device trace can prove a specific real flow on a specific device. Test positive and discriminating negative cases. Record platform/API/device/provider, exact code revision, what actually ran, and the unresolved limits. Never replace failed tests with a success story or repeat an uncertain side effect blindly.

Use the [Evidence and corpus protocol](../references/evidence-and-corpus.md). The machine-checkable [inquiry card schema](../schema/inquiry-card-v1.schema.json) and [validator](../scripts/check_inquiry.py) are optional for complicated cases; use prose in an existing issue/PR for small work. An [illustrative reconnection case](../examples/remote-reconnect.json) shows unresolved questions without pretending tests were run.

## 5. Retain the generalizable distinction

After observing the outcome, record whether the original cue predicted the risk, whether an exception appeared, what evidence changed the decision, what question was wasteful, and which conditional practice should be refined. Keep source statements and *our proposed heuristics* separately identified. Do not upgrade an inferred rule to established advice without testing and applicable evidence.

**Short receipt:** `Outcome | triggered cues | consequential questions and answer sources | coverage/blanks | options and exception | choice/reversal trigger | observed oracle | uncertainty/next action`.

## Evaluation and proportionality

An agent's ability to recite a checklist, select plausible option labels or cite an existing source file does not establish expert judgment. Evaluate the whole consequential chain: recognition cue -> question that can change a decision -> trustworthy answer source -> qualified conceptual gap where needed -> competing option and reversal condition -> observed independent user-facing oracle.

Read [Expertise Evaluation](../references/expertise-evaluation.md) when transforming a corpus recommendation, designing a skill experiment or auditing whether a proposed inquiry is worth its cost. Preserve original source claims and their exact provenance separately from editorial reasoning hypotheses. A frequent recommendation can still be unhelpful, derived or inapplicable.

Keep routine reversible tasks short. Penalize unnecessary owner questions, irrelevant concept mapping, excessive research, unsupported verification claims and new architecture introduced without a discriminating failure. Correctness, proportionality, source quality and actual postconditions should be assessed separately.

Eight synthetic mobile scenarios and fifteen offline evaluator tests are packaged in the separate v0.8 research archive; this documentation-only update **does not install the lab** into the repository or claim a measured skill benefit.
