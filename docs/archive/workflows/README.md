# Archived GitHub Actions workflows

Workflows moved here no longer run: GitHub executes only `.github/workflows/`. Each was an ARCHIVE-candidate in the [workflow classification](../../audits/CI-WORKFLOW-CLASSIFICATION-2026-11-25.md) and passed its reference check. To restore one, move it back, repair it against the current runner image, and requalify it; do not cite its old runs as current evidence.

| Workflow | Archived | Last successful run | Reason |
|---|---|---|---|
| `svg-workbench-mobile.yml` | 2026-11-25 | [40626031950](https://github.com/gamesbyian/Chunks/actions/runs/40626031950) (2026-10-28) | Red since: pinned Playwright 1.56.1 cannot install on the Ubuntu 26.04 runner image ([run 44950386815](https://github.com/gamesbyian/Chunks/actions/runs/44950386815)); no references. Restore with a supported Playwright if the SVG gallery workbench needs browser evidence again. |
| `native-add-shared-build-experiment.yml` | 2026-11-25 | [41588363042](https://github.com/gamesbyian/Chunks/actions/runs/41588363042) (2026-11-03) | Manual-only comparison superseded by the integrated build-once native Add sharing (#2094/#2095); its contract test now keeps only the production route's PR and manual triggers. |
| `svg-alternates-qa-refresh.yml` | 2026-11-25 | [40625885476](https://github.com/gamesbyian/Chunks/actions/runs/40625885476) (2026-10-28) | Push trigger only on the retired `feat/svg-font-independent-alternates-20261027` branch, with `contents: write` auto-commit; one run ever. The path-only alternates it produced remain checked by `svg-outlined-review.yml`. |
| `svg-outlined-alternates.yml` | 2026-11-25 | [40625797578](https://github.com/gamesbyian/Chunks/actions/runs/40625797578) (2026-10-28) | Same retired-branch push trigger and write permission; one run ever; superseded by `svg-outlined-review.yml` validation. |
