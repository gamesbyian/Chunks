# Operational completeness audit: identify missing expertise, not just forbidden phrases

**Purpose:** for every consequential agent instruction, determine whether a different but generally capable agent could find a reproducible method to execute it. This is an editorial review standard, not proof of better agent performance.

## Trace the instruction through its methods

1. **Locate exact directive:** path, line or stable text anchor, intended task, what already counts as authority and which real result would matter.
2. **Expose the expertise assumption:** what does "research", "inspect", "compare", "choose", "specify", "verify" or another verb require the agent to know? What missing facts, actions, standards or unfamiliar tool skills could lead to plausible but wrong work?
3. **Follow links recursively:** open the relevant reference, its next procedural handoff and required example. A link to another vague command has not resolved the assumption. Stop tracing only at specific operations, existing verified code/tools, or an explicit limitation/fallback.
4. **Require fields for the chain:** activation cue, exclusion, obtainable input/evidence, actual transformation, consequential branch, observable output, independent oracle, stop rule and what to do when a source, tool or expertise is unavailable. Check that the chain's outputs match the next skill's input, preserving source/version and human authority.
5. **Attack central transformations:** simulate missing evidence, source conflict, an irrelevant keyword, a cheaper alternative and an abnormal user journey. Could the agent choose search more, test, implement a known pattern, ask only necessary authority, or do nothing without guessing?
6. **Audit completeness in both directions:** starting from the user request, are required operations supported by instructions? Starting from a procedure, is there a real task cue rather than a detached instruction catalog?
7. **Classify:** COMPLETE only after an appropriate independent behavioral test; PARTIAL when a method exists but depends on unexpressed knowledge; UNRESOLVED when no usable method is supplied; REVIEW REQUIRED when source or behavior is inaccessible; NOT APPLICABLE for explanatory history. Static lint cannot decide semantic completeness.
8. **Repair at narrowest authoritative location:** add or improve one reusable specialist method, then link callers; avoid copying a giant subskill into every SKILL.md. Add task-level positive/negative controls and a reversal observation.

## Documentation-maintenance instructions are also executable claims

When reviewing advice such as “keep docs current”, “archive history”, “update handoffs” or “maintain the work queue”, trace it through the bounded [Operational Documentation Integrity D1–D4 procedure](operational-documentation-integrity.md). Confirm that an unfamiliar agent can discover document roles, establish current authority, compute a justified delta, preserve unique evidence, detect conflicts, and choose **NO CHANGE** when appropriate. Use the [decision probes](operational-documentation-cases.md) to test specific cases. Links and structural checks are only candidates for review, not proof of sound maintenance or improved agent decisions.

## Mechanical helper and exact limits

From project root:

    python3 skills/scripts/audit_instruction_completeness.py --skills-dir skills --scan --limit 100
    python3 skills/scripts/audit_instruction_completeness.py --skills-dir skills --check-contracts
    python3 -m unittest discover -s skills/tests -p test_instruction_completeness.py

The scanner emits a **review queue** based on ordinary imperative verbs, including instructions that have links. It can have false positives and miss instructions with unusual wording. It never reports the suite operationally complete.

The contract checker validates only the [anchored reviewed records](../assets/reviewed-instruction-contracts.json): exact instruction excerpt present, linked method exists, fields are nonempty and obvious placeholders absent. It does not establish that the input is obtainable, a source is true, a method is sufficient or an agent can follow it. Those require original-source inspection and task-based evaluation.

The three new research/specification skills include their own decision branches, examples and fallback rules. Their inclusion in the ledger is an audit *sample*, not a claim that all 149 original files or later additions have been fully evaluated.

## Behavioural evaluation, only after explicit authorization

Use independently selected tasks, matched agents/tool availability, counterfactual missing-information conditions, actual source/code inspections and independent outcome assessment. Include no-action/routine controls and cases requiring MORE research/testing. Measure omission and false-completion rates, irreversible safety decisions, invalid source claims, human involvement and time/cost. Follow the [dormant evaluation plan](../AGENT-DECISION-SKILLS-EVALUATION-PLAN.md); this document does not authorize running an agent campaign.

## Anti-patterns

- Rewording "think expertly" as "compare the best mature solutions" without showing how to find and challenge them.
- Filling a source field with an uninspected link.
- Treating twenty translated reposts as twenty independent observations.
- An auditor claiming success simply because a checklist or Markdown link exists.
- An otherwise useful skill whose final step sends an agent to another skill that again says only "make an appropriate choice".
- Requiring a full ontology or exhaustive market study before a reversible typo fix.
