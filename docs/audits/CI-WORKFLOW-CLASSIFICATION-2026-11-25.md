# CI workflow classification — 2026-11-25

**Status:** Evidence for [WORK-QUEUE](../WORK-QUEUE.md) row A2611-03, not a policy. Source: `main` at `3c7e3b58` (102 workflow files) and the complete Actions run history of the repository (21,218 runs, oldest 2026-10-24, collected 2026-11-25 through the GitHub API; the 60-day window therefore covers every run). Static fields come from `tools/ci_workflow_inventory.py`. Classes: **KEEP-gate** (path-selected PR witness recently executed), **KEEP-manual / KEEP-manual-protected** (dispatch evidence; protected Seedhost needs trusted-main arming), **KEEP-font-lab / KEEP-design** (owner asset contracts), **KEEP-deferred** (visual atlas), **ARCHIVE-candidate** (move to `docs/archive/workflows/` with its last passing run id, only after the per-row check below); **ARCHIVED** rows have moved.

Counts: ARCHIVE-candidate 11, KEEP-deferred 1, KEEP-design 6, KEEP-font-lab 17, KEEP-gate 34, KEEP-manual 6, KEEP-manual-protected 27.

## Findings

1. **Probes rot silently when they run rarely.** On 2026-11-21 one PR edited every workflow file and so triggered each self-path filter. Six non-gate probes ran red (two later repaired: generated-report drift, not rot) (`svg-workbench-mobile`, `svg-outlined-review`, `svg-inventory-quality`, `native-owned-debug-overlay-smoke`, `native-owned-writer-experiment`, `public-magnet-metadata-probe`), each with its last success weeks earlier. The one diagnosed cause is tooling drift: `ubuntu-latest` moved to 26.04 and the pinned Playwright 1.56.1 cannot install. A rarely run probe is not evidence until rerun.
2. **External rate limits during fan-out are not product failures.** `local-engine-public-webseed` failed the same day on HTTP 429 from Wikimedia (run 44950386593) and keeps its product role; rerun it alone before citing it.
3. **One workflow never ran** (`seedhost-copy-to-local-readonly.yml`), and two pilots are superseded by integrated work (`native-add-shared-build-experiment`, `avd-snapshot-pilot`).
4. Runs from 33 workflow files no longer in the repository (~120 runs) show earlier retirements already happened; no action.

## Before archiving a candidate

Confirm no active document, `tests/*.py`, `tools/` or other workflow references it (or update those references in the same PR); record its last successful run id; never archive a workflow cited by a current acceptance receipt or required by `tools/acceptance_gates.json`. Native-owned research workflows follow the owner decision for `experiments/native-owned-writer` (A2611-04). Seedhost consolidation into a parameterized dispatch needs owner review of arming and scope.

## Table

Columns: runs (all-time = 60 d) · success/failure/cancelled/skipped · last run · last success.

| Workflow | Class | Runs | S/F/C/Sk | Last run | Last success | Rationale |
|---|---|---:|---|---|---|---|
| `android-check.yml` | KEEP-gate | 3519 | 1698/400/1336/83 | 2026-11-25 in_progress | 2026-11-25 | Path-selected PR witness; recently executed. |
| `android-doze-harness-qa.yml` | KEEP-gate | 7 | 7/0/0/0 | 2026-10-30 success | 2026-10-30 | Path-selected PR witness; recently executed. |
| `android-fgs-timeout-repro.yml` | KEEP-gate | 464 | 211/40/213/0 | 2026-11-24 success | 2026-11-24 | Path-selected PR witness; recently executed. |
| `android-interrupt-harness-qa.yml` | KEEP-gate | 9 | 6/0/3/0 | 2026-10-30 success | 2026-10-30 | Path-selected PR witness; recently executed. |
| `android-native-low-storage-repro.yml` | KEEP-gate | 30 | 14/2/14/0 | 2026-11-21 success | 2026-11-21 | Path-selected PR witness; recently executed. |
| `android-public-swarm.yml` | KEEP-manual | 7 | 6/1/0/0 | 2026-11-24 success | 2026-11-24 | Manual evidence/diagnostic. |
| `android-radio-harness-qa.yml` | KEEP-gate | 56 | 50/0/6/0 | 2026-11-24 success | 2026-11-24 | Path-selected PR witness; recently executed. |
| `android10-downloads-instrumentation.yml` | KEEP-gate | 1024 | 522/93/394/15 | 2026-11-25 success | 2026-11-25 | Path-selected PR witness; recently executed. |
| `anonymous-zip-stage-api29.yml` | KEEP-gate | 32 | 18/0/14/0 | 2026-11-21 success | 2026-11-21 | Path-selected PR witness; recently executed. |
| `avd-snapshot-pilot.yml` | ARCHIVE-candidate | 1 | 0/1/0/0 | 2026-11-03 failure | — | Persistent AVD restore route retired by #2096; 1 run. |
| `chunks-accent-context-audit.yml` | KEEP-font-lab | 32 | 19/12/1/0 | 2026-10-28 success | 2026-10-28 | Main-only push/path-filtered PR per font-lab contract; no routine dispatch. |
| `chunks-cross-family-font-audit.yml` | KEEP-font-lab | 159 | 132/27/0/0 | 2026-11-02 success | 2026-11-02 | Main-only push/path-filtered PR per font-lab contract; no routine dispatch. |
| `chunks-font-family.yml` | KEEP-font-lab | 216 | 180/36/0/0 | 2026-11-02 success | 2026-11-02 | Main-only push/path-filtered PR per font-lab contract; no routine dispatch. |
| `chunks-font-visual-stress.yml` | KEEP-font-lab | 162 | 136/26/0/0 | 2026-10-29 success | 2026-10-29 | Main-only push/path-filtered PR per font-lab contract; no routine dispatch. |
| `chunks-reference-alphabet.yml` | KEEP-font-lab | 85 | 73/12/0/0 | 2026-11-01 success | 2026-11-01 | Main-only push/path-filtered PR per font-lab contract; no routine dispatch. |
| `chunks-reference-panel-font.yml` | KEEP-font-lab | 176 | 108/33/35/0 | 2026-11-01 success | 2026-11-01 | Main-only push/path-filtered PR per font-lab contract; no routine dispatch. |
| `chunks-reference-punctuation.yml` | KEEP-font-lab | 21 | 18/2/1/0 | 2026-11-01 success | 2026-11-01 | Main-only push/path-filtered PR per font-lab contract; no routine dispatch. |
| `collect-public-magnets.yml` | KEEP-gate | 30 | 15/0/15/0 | 2026-11-21 success | 2026-11-21 | Path-selected PR witness; recently executed. |
| `compose-system-font-200pct.yml` | KEEP-font-lab | 57 | 20/17/20/0 | 2026-11-21 success | 2026-11-21 | Main-only push/path-filtered PR per font-lab contract; no routine dispatch. |
| `disposable-rtorrent-probe.yml` | KEEP-gate | 746 | 426/41/272/7 | 2026-11-24 success | 2026-11-24 | Path-selected PR witness; recently executed. |
| `documentation-validation.yml` | KEEP-gate | 1746 | 1513/53/179/0 | 2026-11-25 in_progress | 2026-11-25 | Path-selected PR witness; recently executed. |
| `experimental-font-android-render.yml` | KEEP-font-lab | 20 | 2/6/12/0 | 2026-11-21 success | 2026-11-21 | Main-only push/path-filtered PR per font-lab contract; no routine dispatch. |
| `installed-combined-two-servers.yml` | KEEP-gate | 1017 | 443/134/381/59 | 2026-11-25 success | 2026-11-25 | Path-selected PR witness; recently executed. |
| `installed-partial-resume-death.yml` | KEEP-gate | 40 | 24/6/7/3 | 2026-11-25 failure | 2026-11-25 | Path-selected PR witness; recently executed. |
| `installed-protocol-client.yml` | KEEP-gate | 258 | 94/62/73/29 | 2026-11-25 failure | 2026-11-25 | Path-selected PR witness; recently executed. |
| `installed-public-auto-media-one-shot.yml` | KEEP-manual | 10 | 4/4/0/2 | 2026-11-23 success | 2026-11-23 | Manual evidence/diagnostic. |
| `installed-remote-delete-data.yml` | KEEP-gate | 978 | 478/77/365/58 | 2026-11-25 success | 2026-11-25 | Path-selected PR witness; recently executed. |
| `installed-remote-public-media-one-shot.yml` | KEEP-manual | 5 | 2/3/0/0 | 2026-11-24 success | 2026-11-24 | Manual evidence/diagnostic. |
| `installed-rtorrent-magnets.yml` | KEEP-gate | 998 | 351/251/252/144 | 2026-11-25 success | 2026-11-25 | Path-selected PR witness; recently executed. |
| `installed-source-archive.yml` | KEEP-gate | 26 | 12/8/6/0 | 2026-11-21 success | 2026-11-21 | Path-selected PR witness; recently executed. |
| `local-engine-public-swarm.yml` | KEEP-gate | 37 | 23/2/12/0 | 2026-11-21 success | 2026-11-21 | Path-selected PR witness; recently executed. |
| `local-engine-public-webseed.yml` | KEEP-gate | 21 | 12/3/6/0 | 2026-11-21 failure | 2026-11-12 | Path-selected PR witness; recently executed. |
| `minified-smoke.yml` | KEEP-gate | 2766 | 1182/38/830/715 | 2026-11-25 in_progress | 2026-11-25 | Path-selected PR witness; recently executed. |
| `native-add-instrumentation.yml` | KEEP-gate | 3523 | 1206/362/1168/785 | 2026-11-25 in_progress | 2026-11-25 | Path-selected PR witness; recently executed. |
| `native-add-shared-build-experiment.yml` | **ARCHIVED** ([archive](../archive/workflows/README.md)) | 2 | 1/0/0/1 | 2026-11-03 success | 2026-11-03 | Superseded by build-once native Add sharing (#2094/#2095); 2 runs, none since 2026-11-03. |
| `native-delete-boundary-research.yml` | KEEP-gate | 65 | 36/5/22/2 | 2026-11-24 success | 2026-11-24 | Path-selected PR witness; recently executed. |
| `native-mediastore-owned-fd-probe.yml` | KEEP-gate | 9 | 4/2/3/0 | 2026-11-21 success | 2026-11-21 | Path-selected PR witness; recently executed. |
| `native-owned-android-link.yml` | KEEP-gate | 27 | 23/3/1/0 | 2026-11-21 success | 2026-11-21 | Path-selected PR witness; recently executed. |
| `native-owned-debug-overlay-smoke.yml` | ARCHIVE-candidate | 23 | 18/5/0/0 | 2026-11-21 failure | 2026-11-01 | Red since 2026-11-01 when triggered; research path (experiments/native-owned-writer) awaiting owner decision (A2611-04). Cause not diagnosed. |
| `native-owned-process-restart.yml` | KEEP-gate | 5 | 3/0/2/0 | 2026-11-21 success | 2026-11-21 | Path-selected PR witness; recently executed. |
| `native-owned-provider-fd.yml` | KEEP-gate | 7 | 3/0/4/0 | 2026-11-21 success | 2026-11-21 | Path-selected PR witness; recently executed. |
| `native-owned-writer-experiment.yml` | ARCHIVE-candidate | 138 | 89/48/1/0 | 2026-11-21 failure | 2026-11-01 | Red since 2026-11-01 (89/48 success/failure); research path awaiting owner decision (A2611-04). Cause not diagnosed. |
| `native-post-add-collision-witness.yml` | KEEP-gate | 21 | 13/1/7/0 | 2026-11-21 success | 2026-11-21 | Path-selected PR witness; recently executed. |
| `native-visual-atlas.yml` | KEEP-deferred | 1281 | 543/32/320/386 | 2026-11-25 skipped | 2026-11-23 | Owner-deferred visual atlas; manual dispatch only. |
| `one-time-public-source-acceptance.yml` | KEEP-gate | 9 | 3/3/3/0 | 2026-11-21 failure | 2026-11-21 | Path-selected PR witness; recently executed. |
| `p0-delete-witness.yml` | KEEP-gate | 19 | 18/0/1/0 | 2026-11-21 success | 2026-11-21 | Path-selected PR witness; recently executed. |
| `p0-remote-start-preview.yml` | KEEP-gate | 10 | 4/0/6/0 | 2026-11-21 success | 2026-11-21 | Path-selected PR witness; recently executed. |
| `pages-iconography-atlas.yml` | KEEP-manual | 2 | 2/0/0/0 | 2026-11-21 success | 2026-11-21 | Release/distribution or published page. |
| `portable-agent-skills.yml` | KEEP-gate | 28 | 26/2/0/0 | 2026-11-21 success | 2026-11-21 | Path-selected PR witness; recently executed. |
| `public-magnet-metadata-probe.yml` | ARCHIVE-candidate | 8 | 3/4/1/0 | 2026-11-21 failure | 2026-11-02 | Red when triggered; 2026-11-21 logs show both legacy/modern rTorrent jobs failing after real public-swarm metadata discovery (public network dependent; not diagnosed further). Keep as candidate; rerun alone before citing. |
| `public-source-fixtures.yml` | KEEP-gate | 36 | 34/1/1/0 | 2026-11-21 success | 2026-11-21 | Path-selected PR witness; recently executed. |
| `publish-debug-apk.yml` | KEEP-manual | 27 | 25/1/1/0 | 2026-11-16 success | 2026-11-16 | Release/distribution or published page. |
| `real-client-interop.yml` | KEEP-gate | 210 | 143/8/36/23 | 2026-11-25 success | 2026-11-25 | Path-selected PR witness; recently executed. |
| `reference-layered-latin-coverage.yml` | KEEP-font-lab | 12 | 11/0/1/0 | 2026-11-01 success | 2026-11-01 | Main-only push/path-filtered PR per font-lab contract; no routine dispatch. |
| `reference-mixed-mark-studies.yml` | KEEP-font-lab | 14 | 13/0/1/0 | 2026-11-01 success | 2026-11-01 | Main-only push/path-filtered PR per font-lab contract; no routine dispatch. |
| `reference-panel-accent-kerned.yml` | KEEP-font-lab | 37 | 23/13/1/0 | 2026-11-01 success | 2026-11-01 | Main-only push/path-filtered PR per font-lab contract; no routine dispatch. |
| `reference-panel-accent-studies.yml` | KEEP-font-lab | 61 | 42/19/0/0 | 2026-11-01 success | 2026-11-01 | Main-only push/path-filtered PR per font-lab contract; no routine dispatch. |
| `reference-panel-consolidated.yml` | KEEP-font-lab | 66 | 35/10/21/0 | 2026-11-01 success | 2026-11-01 | Main-only push/path-filtered PR per font-lab contract; no routine dispatch. |
| `reference-panel-dotless-kerned.yml` | KEEP-font-lab | 45 | 29/14/2/0 | 2026-11-01 success | 2026-11-01 | Main-only push/path-filtered PR per font-lab contract; no routine dispatch. |
| `reference-panel-stacked-studies.yml` | KEEP-font-lab | 43 | 29/0/14/0 | 2026-11-01 success | 2026-11-01 | Main-only push/path-filtered PR per font-lab contract; no routine dispatch. |
| `reference-panel-weight-studies.yml` | KEEP-font-lab | 112 | 57/24/31/0 | 2026-11-01 success | 2026-11-01 | Main-only push/path-filtered PR per font-lab contract; no routine dispatch. |
| `release-apk.yml` | KEEP-manual | 1 | 1/0/0/0 | 2026-11-16 success | 2026-11-16 | Release/distribution or published page. |
| `seedhost-active-start-once.yml` | KEEP-manual-protected | 5 | 3/2/0/0 | 2026-11-01 success | 2026-11-01 | Owner-authorized protected Seedhost evidence; trusted-main arming only. Consolidation needs owner review (A2611-03). |
| `seedhost-android-add-ui-once.yml` | KEEP-manual-protected | 4 | 1/3/0/0 | 2026-10-28 success | 2026-10-28 | Owner-authorized protected Seedhost evidence; trusted-main arming only. Consolidation needs owner review (A2611-03). |
| `seedhost-android-mutation-once.yml` | KEEP-manual-protected | 1 | 1/0/0/0 | 2026-10-27 success | 2026-10-27 | Owner-authorized protected Seedhost evidence; trusted-main arming only. Consolidation needs owner review (A2611-03). |
| `seedhost-android-readonly.yml` | KEEP-manual-protected | 16 | 8/7/1/0 | 2026-11-24 success | 2026-11-24 | Owner-authorized protected Seedhost evidence; trusted-main arming only. Consolidation needs owner review (A2611-03). |
| `seedhost-android-real-transfer-add-once.yml` | KEEP-manual-protected | 8 | 0/7/0/1 | 2026-11-02 skipped | — | Owner-authorized protected Seedhost evidence; trusted-main arming only. Consolidation needs owner review (A2611-03). |
| `seedhost-byte-window-readonly.yml` | KEEP-manual-protected | 6 | 6/0/0/0 | 2026-10-27 success | 2026-10-27 | Owner-authorized protected Seedhost evidence; trusted-main arming only. Consolidation needs owner review (A2611-03). |
| `seedhost-combined-three-readonly.yml` | KEEP-manual-protected | 7 | 4/3/0/0 | 2026-11-15 success | 2026-11-15 | Owner-authorized protected Seedhost evidence; trusted-main arming only. Consolidation needs owner review (A2611-03). |
| `seedhost-copy-to-local-readonly.yml` | ARCHIVE-candidate | 0 | 0/0/0/0 | —  | — | Never run (0 runs all-time). |
| `seedhost-cross-client-move.yml` | KEEP-manual-protected | 4 | 3/0/1/0 | 2026-11-18 success | 2026-11-18 | Owner-authorized protected Seedhost evidence; trusted-main arming only. Consolidation needs owner review (A2611-03). |
| `seedhost-deletion-acceptance.yml` | KEEP-manual-protected | 17 | 9/6/2/0 | 2026-11-21 cancelled | 2026-11-14 | Owner-authorized protected Seedhost evidence; trusted-main arming only. Consolidation needs owner review (A2611-03). |
| `seedhost-endpoint-compatibility.yml` | KEEP-manual-protected | 15 | 11/4/0/0 | 2026-11-25 success | 2026-11-25 | Owner-authorized protected Seedhost evidence; trusted-main arming only. Consolidation needs owner review (A2611-03). |
| `seedhost-exact-magnet-peer-once.yml` | KEEP-manual-protected | 16 | 10/2/2/2 | 2026-11-21 cancelled | 2026-11-02 | Owner-authorized protected Seedhost evidence; trusted-main arming only. Consolidation needs owner review (A2611-03). |
| `seedhost-exact-peer-bytes-once.yml` | KEEP-manual-protected | 1 | 1/0/0/0 | 2026-11-01 success | 2026-11-01 | Owner-authorized protected Seedhost evidence; trusted-main arming only. Consolidation needs owner review (A2611-03). |
| `seedhost-existing-stop-start-once.yml` | KEEP-manual-protected | 3 | 1/2/0/0 | 2026-11-15 success | 2026-11-15 | Owner-authorized protected Seedhost evidence; trusted-main arming only. Consolidation needs owner review (A2611-03). |
| `seedhost-instances-readonly.yml` | KEEP-manual-protected | 6 | 6/0/0/0 | 2026-11-23 success | 2026-11-23 | Owner-authorized protected Seedhost evidence; trusted-main arming only. Consolidation needs owner review (A2611-03). |
| `seedhost-inventory-media-readonly.yml` | KEEP-manual-protected | 2 | 1/1/0/0 | 2026-11-15 success | 2026-11-15 | Owner-authorized protected Seedhost evidence; trusted-main arming only. Consolidation needs owner review (A2611-03). |
| `seedhost-kotlin-adapter-smoke.yml` | KEEP-manual-protected | 12 | 10/2/0/0 | 2026-10-26 success | 2026-10-26 | Owner-authorized protected Seedhost evidence; trusted-main arming only. Consolidation needs owner review (A2611-03). |
| `seedhost-magnet-readonly-forensics.yml` | KEEP-manual-protected | 8 | 8/0/0/0 | 2026-11-21 success | 2026-11-21 | Owner-authorized protected Seedhost evidence; trusted-main arming only. Consolidation needs owner review (A2611-03). |
| `seedhost-magnet-rutorrent-form-once.yml` | KEEP-manual-protected | 7 | 4/2/1/0 | 2026-11-21 success | 2026-11-21 | Owner-authorized protected Seedhost evidence; trusted-main arming only. Consolidation needs owner review (A2611-03). |
| `seedhost-magnet-twoarg-control-once.yml` | KEEP-manual-protected | 5 | 3/1/1/0 | 2026-11-21 cancelled | 2026-11-21 | Owner-authorized protected Seedhost evidence; trusted-main arming only. Consolidation needs owner review (A2611-03). |
| `seedhost-named-stall-readonly.yml` | KEEP-manual-protected | 2 | 1/1/0/0 | 2026-10-27 success | 2026-10-27 | Owner-authorized protected Seedhost evidence; trusted-main arming only. Consolidation needs owner review (A2611-03). |
| `seedhost-other-protocols-interop.yml` | KEEP-manual-protected | 13 | 12/1/0/0 | 2026-11-19 success | 2026-11-19 | Owner-authorized protected Seedhost evidence; trusted-main arming only. Consolidation needs owner review (A2611-03). |
| `seedhost-other-protocols-readonly.yml` | KEEP-manual-protected | 9 | 4/5/0/0 | 2026-11-25 success | 2026-11-25 | Owner-authorized protected Seedhost evidence; trusted-main arming only. Consolidation needs owner review (A2611-03). |
| `seedhost-p0-cohort-once.yml` | KEEP-manual-protected | 15 | 13/2/0/0 | 2026-11-21 success | 2026-11-21 | Owner-authorized protected Seedhost evidence; trusted-main arming only. Consolidation needs owner review (A2611-03). |
| `seedhost-public-add-smoke.yml` | KEEP-manual-protected | 36 | 35/1/0/0 | 2026-11-23 success | 2026-11-23 | Owner-authorized protected Seedhost evidence; trusted-main arming only. Consolidation needs owner review (A2611-03). |
| `seedhost-readonly-probe.yml` | KEEP-manual-protected | 7 | 6/1/0/0 | 2026-11-19 success | 2026-11-19 | Owner-authorized protected Seedhost evidence; trusted-main arming only. Consolidation needs owner review (A2611-03). |
| `seedhost-reconcile-readonly-once.yml` | KEEP-manual-protected | 1 | 1/0/0/0 | 2026-10-27 success | 2026-10-27 | Owner-authorized protected Seedhost evidence; trusted-main arming only. Consolidation needs owner review (A2611-03). |
| `seedhost-remote-seeding-stop-once.yml` | KEEP-manual-protected | 1 | 1/0/0/0 | 2026-11-19 success | 2026-11-19 | Owner-authorized protected Seedhost evidence; trusted-main arming only. Consolidation needs owner review (A2611-03). |
| `storage-owned-mediastore-documents.yml` | KEEP-gate | 88 | 40/6/41/0 | 2026-11-25 in_progress | 2026-11-24 | Path-selected PR witness; recently executed. |
| `svg-alternates-qa-refresh.yml` | **ARCHIVED** ([archive](../archive/workflows/README.md)) | 1 | 1/0/0/0 | 2026-10-28 success | 2026-10-28 | One run 2026-10-28; push-triggered one-off; no reference. |
| `svg-complete-reference-assets.yml` | KEEP-design | 22 | 21/1/0/0 | 2026-11-21 success | 2026-11-21 | Design-asset validation on SVG path changes; review after atlas reactivation. |
| `svg-inventory-quality.yml` | KEEP-design | 8 | 7/1/0/0 | 2026-11-21 failure | 2026-10-29 | Repaired 2026-11-25: red only from hand-edited generated QUALITY-REVIEW.md ('Stale quality summary'); the generator now emits the owner notes and all steps pass. |
| `svg-library-check.yml` | KEEP-design | 49 | 42/7/0/0 | 2026-11-21 success | 2026-11-21 | Design-asset validation on SVG path changes; review after atlas reactivation. |
| `svg-mixer.yml` | KEEP-design | 17 | 15/2/0/0 | 2026-11-21 success | 2026-11-21 | Design-asset validation on SVG path changes; review after atlas reactivation. |
| `svg-outlined-alternates.yml` | **ARCHIVED** ([archive](../archive/workflows/README.md)) | 1 | 1/0/0/0 | 2026-10-28 success | 2026-10-28 | One run 2026-10-28; push-triggered one-off; no reference. |
| `svg-outlined-review.yml` | KEEP-design | 7 | 5/2/0/0 | 2026-11-21 failure | 2026-10-29 | Repaired 2026-11-25: red only from hand-edited generated QUALITY-REVIEW.md ('Stale quality summary'); the generator now emits the owner notes and all steps pass. |
| `svg-palette-contrast.yml` | KEEP-design | 6 | 4/1/1/0 | 2026-11-21 success | 2026-11-21 | Design-asset validation on SVG path changes; review after atlas reactivation. |
| `svg-render-atlas.yml` | KEEP-design | 42 | 32/3/7/0 | 2026-11-21 success | 2026-11-21 | Design-asset validation on SVG path changes; review after atlas reactivation. |
| `svg-small-size.yml` | KEEP-design | 28 | 26/1/1/0 | 2026-11-21 success | 2026-11-21 | Design-asset validation on SVG path changes; review after atlas reactivation. |
| `svg-workbench-mobile.yml` | **ARCHIVED** ([archive](../archive/workflows/README.md)) | 8 | 2/5/1/0 | 2026-11-21 failure | 2026-10-28 | Red since 2026-10-28; last failure: pinned Playwright 1.56.1 cannot install on ubuntu-latest 26.04 (run 44950386815). No active doc/test/tool reference. |
