# Evaluation: teach transfer rather than memorization

A helpful expert-skill benchmark must distinguish **replay of a familiar story** from **correct decisions in different surface contexts**.

## Built-in instrument

[Transfer probes](../assets/transfer-probes.json) contain deliberately unfamiliar short scenarios. Each asks for one **best first action**, not a full design, and supplies wrong options that would either overgeneralize an earlier rule or approve unverified work. The [offline tool](../scripts/evaluate_transfer.py) emits answer-free prompts and grades an independently prepared response file; the key stays in a separate file, inaccessible to a genuinely isolated candidate.

Run `python3 scripts/evaluate_transfer.py --emit-prompts` to retrieve questions without labels or answers. Give the output to a **fresh agent without access to this repo**. It must respond with a JSON list of objects with `id`, `choice`, and a short `rationale`. Grade by `python3 scripts/evaluate_transfer.py --grade /path/to/replies.json`. The score checks the choice only, not the reasoning.

## How to establish real benefit

1. Prepare a set of genuine *held-out* PR decision points from a later time or different project, not examples used to write the skill. Keep their accepted resolutions private from both agents.
2. Compare the same task on matched model/tool conditions with versus without the skill. Measure solution correctness, API reuse, overbroad gates, recognized exception cases, actual integration/CI passes, time/compute and any new regressions. Report exposure and contamination honestly.
3. Include **near analogues**, **far analogues**, **negative controls** where no large process is warranted, **adversarial exceptions** where a familiar rule is wrong, and **evidence-class** questions where tests do not establish shipping behavior.
4. Judge actual patches by independent review and a real production-connected test, not only the multiple-choice artifact, a self-report or plausible prose.
5. Retain a lesson only if it changes a discriminating decision or reliably saves effort; reject decorative checklists and vague advice with no measurable decision consequence.

## Good and bad signals

- Good: the candidate asks whether an action is truly authorized before deciding to block it; distinguishes an actual URI from filename; finds a shipping call site missing the tested guard; pivots after a disproved premise; chooses a cheap informative positive test.
- Bad: adds a generic framework whenever a “safety” keyword appears; assumes that all failure modes are equally likely; treats ordinary review artifacts as evidence of live provider behavior; asks the director questions answerable from code.
- Diagnostic but insufficient: perfect scores on familiar multiple-choice cases, increasing graph sizes, more written steps or narrower CI with lost coverage.

A skeptical review should attempt to produce cases where the skill makes a decision *worse*. These false-positive activation cases are as important as missed genuine complexity.

## Executable code-transfer cases

The separate [executable lab](../lab/README.md) moves beyond option selection: its five synthetic Python tasks ask an agent to modify source code and produce a real postcondition. A strict internal two-sided test gate requires the untouched starting code to **fail** and the reference correction to **pass** with executed tests. This establishes harness discrimination for those miniature tasks, not general engineering skill.

The long-term evaluation must include new tasks and prospective real PRs, with review independent of skill authors and actual production-acceptance evidence. A candidate that passes an insufficient test still needs review for unintended side effects and unnecessary architecture. Record CI and model/runtime budgets. Keep training/explanation cases separate from held-out evaluation.

## Pairwise pilot recording

Use [the paired-trial protocol](../lab/trials.md) to preserve control and treatment conditions, actual executed tests, independent patch review, contamination, time and unnecessary engineering work. Run `python3 scripts/compare_trials.py receipts.json` only on externally collected records. The output is deliberately descriptive and does not assign statistical significance or imply measured performance improvements before real matched trials occur.
