# Chunks font lab: isolated development and promotion contract

**Owner directive, 2026-10-28.** Typeface design is a contained, independently advancing effort. Routine font work must neither use a chain of feature branches/PRs nor flood the application's GitHub Actions queue.

## One working branch, zero routine PRs

- **Only active font development branch:** [`font/lab`](https://github.com/gamesbyian/Chunks/tree/font/lab). Work directly there; preserve history and make small source commits/checkpoints. Do not create a branch or PR for each glyph, weight, punctuation family, kerning experiment or atlas. Do not reuse abandoned historical `font/*` branches or push typography changes onto application feature branches.
- **No open PR on `font/lab` during iterative development.** GitHub font workflows are configured for main-only pushes and path-filtered PRs; an unreviewed lab branch with no PR should therefore produce **no automatic font CI** for routine commits. If workflow triggers change, inspect the live event configuration before assuming this remains true.
- Historical [PR #496](https://github.com/gamesbyian/Chunks/pull/496) was **closed unmerged**, not discarded. Its exact source and rebuilt binary head `752a9ee7c3da3716d6951f40419ae20f7dabf09b` was copied to `font/lab` and verified. That branch has **32 editable lighter-weight basic-glyph masters (8 glyphs × 4 weights)**, including experimental E/F. Main currently has the preceding **24 (6 × 4)**. These are engineering/art source counts, not approved designs.
- Keep application work on `main` and its independent app PRs. A dedicated app accessibility test (for example #488) can be integrated independently without pulling experimental OTFs into Android. No font-lab commit may modify production `app/src/main/res/font/**`, approved/heavy source art, launcher assets, Android UI or unrelated application code without a separately reviewed, explicit integration decision.
- Do not routinely merge/rebase changing application `main` into the lab. At each intended promotion, check divergence, carry forward only needed nonshipping font changes on the promotion branch and resolve conflicts there. No force-push/rewrite of the lab's history, no deletion of old agents' branches, and no cleanup that silently loses source masters.

## Local-first iteration, restrained CI

Do the work locally/in an agent's isolated checkout on `font/lab`, with one environment and reproducible commands. Commit editable SVGs, design notes, tests, and matching generated metrics/binaries at sensible batch checkpoints rather than triggering a remote job on every tiny edit.

Suggested first-time setup:
```sh
python -m pip install -r tools/requirements-reference-panel-weights.txt
```

During a batch, edit the SVG masters and run the targeted rebuild/test only when the work warrants it:
```sh
python tools/build_reference_panel_weight_studies.py --weights 400
python -m unittest discover -s tests -p test_reference_panel_weights.py -v
```
A partial build is diagnostic only. **Before freezing a batch**, perform the complete four-weight build and generate real specimens:
```sh
python tools/build_reference_panel_weight_studies.py
python -m unittest discover -s tests -p test_reference_panel_weights.py -v
python tools/render_reference_panel_weights.py
python tools/render_reference_glyph_grid.py
python tools/audit_font_families.py --output chunks-font-cross-family-audit.json
```
If an audit expects already-rebuilt downstream experimental faces, rebuild those locally too or record the dependency honestly; never claim a partial run proves full cross-family acceptance. Keep generated image output/artifact noise out of unrelated application commits.

**No routine workflow dispatches.** When local compute is insufficient, deliberately run **one narrow manual workflow** on the `font/lab` ref and retain its SHA/run receipt. Do not create scheduled Actions or fan out all font jobs. Workflows that previously auto-committed generated fonts from arbitrary feature-branch pushes must not be assumed to do so now: their current push filters are `main`-only. Local generation and committing matching provenance is the lab's responsibility.

## Single deliberate promotion

1. Finish a **meaningful batch** (rather than one or two glyphs) and checkpoint the lab's complete editable sources and reproducible CFFs. Maintain exact glyph counts, source and binary hashes, outstanding gaps, and per-size optical specimens. A passing geometry test is not owner art approval.
2. Freeze a candidate snapshot, create at most **one temporary integration branch** from that exact lab SHA, reconcile it with current `main`, then open **one** normal PR targeting `main`. The permanent `font/lab` branch has no open PR; development there should stop changing the frozen submission's source files while CI runs.
3. Allow all relevant exact-head PR checks only for this **consolidated promotion**, resolve genuine failures, and inspect changed paths so no accidental app code, approved originals or unrelated agents' changes are included. Do not suppress valid PR checks or waive device/visual/kerning approval to save minutes.
4. Merge only verified, reviewed nonshipping work; preserve shipping-font immutability. Resume subsequent design on the existing `font/lab` branch; do not spawn another persistent font lane. A separate explicit owner decision and actual Android Compose/200%/TalkBack acceptance are required before shipping a new Chunks font.

**Success criterion:** normal font editing queues **zero** automatic GitHub Actions runs and uses **one** stable source branch. The occasional batched promotion may intentionally run a comprehensive PR matrix. This is a CI- and branch-isolation policy, not a promise of a visually complete font.

See [font CI trigger policy](CHUNKS-FONT-CI-TRIGGER-POLICY.md), [issue #347](https://github.com/gamesbyian/Chunks/issues/347) and the [authoritative work queue](../WORK-QUEUE.md).
