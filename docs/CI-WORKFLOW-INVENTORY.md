# Live GitHub Actions workflow inventory

The inventory is generated from **every** `.github/workflows/*.yml` and `*.yaml` on the checked-out source revision. Rather than maintaining a stale manually edited table, each Documentation validation run publishes an artifact named **chunks-workflow-inventory**, containing structured JSON for all workflow definitions, and prints a compact index directly into the job summary.

Generate it offline with standard Python (no extra dependencies):

```bash
python3 tools/ci_workflow_inventory.py --output /tmp/chunks-workflow-inventory.json --summary
python3 -m unittest discover -s tests -p test_ci_workflow_inventory.py
```

The report lists declared triggers, job names, path-filter presence, concurrency and superseded-run cancellation, explicit timeouts, action-reference counts, mutable version tags and coarse Gradle/emulator/native/matrix indicators. Security hints are booleans only. It copies neither workflow bodies nor secrets. The tests enumerate every workflow in the current checkout and refuse missing event/job definitions.

**Scope and limits:** This is a definition-level audit index, not GitHub's actual event matcher, a proof that checks executed, an action pin verifier, or measured runner utilization. A missing concurrency group or mutable tag is a review candidate, not automatic evidence of a bug. Verify positive and negative dispatch against representative changed files, and never merge protected release or Seedhost workflows into untrusted PR execution. The [dated CI audit](CI-AUDIT-2026-10-31.md), [binding engineering standard](CI-ENGINEERING-STANDARD.md), [CI policy](CI-POLICY.md), and [quality ledger](QUALITY-LEDGER.md) govern evidence and safety decisions.

The 2026-10-31 snapshot contained 68 workflow YAML definitions. The inventory intentionally does not hard-code that number so legitimate future consolidation does not produce a false failure.
