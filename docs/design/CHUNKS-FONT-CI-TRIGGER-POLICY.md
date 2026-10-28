# Font CI trigger isolation (2026-10-28)

Chunks' font-specific workflows are path-filtered on pull requests. Android app-only changes **must not launch** OpenType builds, review atlases, typeface audits, or expensive native font instrumentation. The font workflows explicitly accept manual `workflow_dispatch`.

## No duplicate broad font CI on branch pushes

The three broad OpenType CI workflows now run on a push **only to `main`**, while keeping their existing font-specific `pull_request.paths` rules:

- `chunks-font-family.yml`
- `chunks-cross-family-font-audit.yml`
- `chunks-font-visual-stress.yml`

This removes a second, redundant set of broad OpenType rebuilds for every push to a font-changing PR branch. PR checks are **not** weakened: if the PR changes a relevant font, font source, or font-specific tool, the matching build still runs.

More specialized font pipelines may continue to react to pushes on experimental font branches, because they intentionally generate and commit their derived OTF and report artifacts on source branches. Their `pull_request.paths` limits remain in place, so app-only changes do not launch them. Changing those pipelines to main-only would break their reproducible artifact commits without redesigning the generation workflow.

## What counts as an unrelated PR?

A PR title or branch name alone cannot identify its actual changes. At the time of this audit, app-feature PRs #482, #481, and #474 contained files under `assets/fonts/experimental/weight-studies/` and/or `assets/fonts/source/reference-alphabet/weight-overrides/` in their **actual diffs**. GitHub legitimately triggers the font-specific checks on those PRs; suppressing their checks purely because their title says `feat(transfer)` or `fix(service)` would silently admit font binaries and SVG changes without validation. The safe cleanup is to split or reconcile those font changes in their source branches by their owners, not to skip CI for real font modifications. No destructive edits or branch history rewrites have been made to other agents' PRs.

If a PR changes only `app/src/**`, `docs/WORK-QUEUE.md`, `docs/QUALITY-LEDGER.md` or unrelated service/transfer code, font-specific PR path filters will not match. A merge to main that changes font files still performs the broad post-merge verification once.

## CI acceptance rules

1. PRs genuinely touching a CFF, reference font master, typography source script or font workflow retain relevant checks.
2. A PR changing no font-related paths does **not** trigger any font workflow.
3. Broad build/audit/stress workflows no longer run on feature branch `push` events, avoiding redundant build fanout; they continue on matching PR diffs and `main` changes.
4. Specialized experiment generators keep their source-branch artifact behavior until redesigned separately, and use narrow path filters.
5. Never replace the approved Android font or waive visual/Compose/TalkBack acceptance to reduce CI cost.
