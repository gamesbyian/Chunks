# Engineering Judgment (portable agent skill)

This is the **general mechanized-expertise layer** in the private general-purpose skill library. It teaches agents how to recognize hidden engineering complexity and reproduce useful decision habits without asserting they have first-hand intuition or professional experience.

- [Start with SKILL.md](SKILL.md).
- [Recognize complexity signatures](references/signatures.md), then select only a relevant [conditional pattern](assets/patterns.json).
- [Elicit tacit cues from source-backed incidents](references/expertise-extraction.md) and [select an appropriately sized decision method](references/decision-protocol.md).
- Use [project cases](references/project-cases.md) as evidence, not permanent universal requirements.
- Test whether the skill transfers with [held-out probes](references/evaluation.md).

It complements [Android App Development](../android-app-development/SKILL.md) and [Concept Model Engineering](../concept-model-engineering/SKILL.md). In the owning project, AGENTS.md and product contract retain authority. No app repository is modified by this skill.

This package links to research and sources; it does not vendor outside agent skills. No separate publication license has been selected. It includes a dependency-free offline validator and transfer-probe tool, not an automatic cloud agent or developer workflow.

Run from this directory:

```
python3 scripts/check_skill.py
python3 -m unittest discover -s tests -v
python3 scripts/evaluate_transfer.py --emit-prompts
```

## Executable pilot, v0.2

The [judgment lab](lab/README.md) adds five self-contained behavioral tasks, including a low-risk label-change negative control. Run `python3 scripts/run_lab.py --selfcheck` to verify that every baseline fails an executed behavioral test and its reference passes the same tests. Export a candidate package with `--export`; it contains no oracle or reference solution. The scorer executes Python, so trial candidates must run in a separate disposable, unprivileged sandbox without secrets or network. Benchmark correctness is not the same as actual Android shipping acceptance.

## Matched-trial reporting

The [matched-trial protocol](lab/trials.md) and [comparison utility](scripts/compare_trials.py) can analyze externally collected control-versus-skill receipts. Comparisons require identical task, model/scaffold/tool access, time budget, test oracle and completed independent review. Possible contamination, skipped tests and unresolved reviews are excluded. The script does **not** run agents or claim any causal effect; real trial receipts have not yet been collected.
