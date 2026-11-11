# Matched trial measurement: honest evidence for or against this skill

This is a suggested protocol, not a completed study. The [comparison script](../scripts/compare_trials.py) **only reads a manually collected JSON receipt file**. It cannot start agents, collect private telemetry, run Android CI, detect undisclosed contamination or independently judge patch quality.

## Experimental design

- Choose newly authored or held-out tasks whose full resolutions and test oracles are not visible to the candidate agent. Do not call tasks from this same repo **blind** if the agent can search GitHub or receives their reference/oracle files. For the included lab, use `--export` to distribute only task.md and candidate.py.
- Randomize which matched agent run receives the skill, or alternate the arm order across cases. Use the **same model, thinking effort, computer/runtime, source snapshot, tool access, time budget and test oracle** in both arms. Pair by task identity and experimental replicate. Avoid sharing previous agent output across arms.
- In the treatment arm, explicitly supply and confirm **the exact skill version**. In the control arm, forbid access to skill instructions. Record whether the tool/connector environment could access benchmark solutions. Potential contamination invalidates a clean causal comparison.
- Inspect runnable deliverables through a separate independent grader and human reviewer blind to experimental arm, preferably reviewing an exact code diff and real acceptance. Review both false positive and false negative outcomes. The fixed reference patch is not the sole acceptable implementation.
- Define **unnecessary work** in advance (e.g., unrelated subsystem rewrite or research campaign for a trivial label), and review it independently. Record elapsed seconds, agent/model tokens if available, tool calls, CI execution time and code scope. Avoid making a one-score composite with ungrounded weights.
- Treat missing review, unknown executed tests, or leaked solutions as **ineligible**, not as wins. Keep failed attempts and costs visible.

## Receipt format

```json
{
  "schema": "engineering-judgment/trial-receipts-v1",
  "runs": [
    {
      "run_id": "trial-001-control",
      "pair_id": "trial-001",
      "task_id": "small-label-fix",
      "arm": "control",
      "model": "same-model",
      "scaffold": "same-scaffold",
      "tool_access": "isolated",
      "time_budget_seconds": 300,
      "task_digest": "shared-task-sha",
      "oracle_digest": "shared-test-sha",
      "skill_loaded": false,
      "exposure": "isolated",
      "executed_tests": 2,
      "tests_passed": true,
      "review": "pending",
      "elapsed_seconds": 0,
      "unnecessary_work": false
    }
  ]
}
```

This is a **shape illustration only**, not an observed run. Add the matched `skill` arm and independently completed review before comparison. Schema fields are validated by the script; the grader cannot verify that receipts are truthful. **Do not commit real receipts containing credentials, private files, user data or unredacted logs.**

Run: `python3 scripts/compare_trials.py /path/to/isolated-receipts.json`

## Read the results correctly

The output separates paired skill-only successes from control-only successes and includes both-success/neither-success counts, total runtime, and independently judged unnecessary-work incidents. It **does not print statistical significance or proclaim a winner**. With five toy tasks, repeated runs might identify a regression; they cannot establish broad effects on Android app development.

The most valuable prospective trial is likely to compare agents working on a real bounded user journey in Chunks, Glimpse or Expository, with distinct branches/identical base and independent exact-head connected acceptance. A successful lab pilot is only a prerequisite for considering that more expensive evaluation.
