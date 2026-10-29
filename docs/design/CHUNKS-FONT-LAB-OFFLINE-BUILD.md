# Single-process, offline font-family rebuild

The isolated font laboratory is now built from the editable branch `font/lab` using **one local command**, not a fanout of GitHub PRs or Actions:

```sh
python -m pip install -r tools/requirements-reference-panel-weights.txt
bash tools/font_lab_build_all.sh
```

This orchestrates: safe source and SVG tests; reference weight CFFs and 62-character review grids; accent reconstructions, accent pair kerning and dotless forms; punctuation supplements; consolidated CFFs; stacked mark and mixed mark variants; the layered Latin ledger; 20-face cross-family integrity; and final glyph/spacing render artifacts. It performs no network operations, no automatic pushes, no PR creation and no release font replacement. **The complete orchestrator is not yet evidenced as passing from end to end.** Its stages match existing individually audited workflows; a full local run is required before promotion.

`python tools/font_lab_source_ledger.py` is a read-only, JSON-formatted progress inventory. `--require-full` can serve as an **explicit design-completeness gate** when all 62 original glyphs finally have four editable source masters. It does not classify an affine-synthesized contour as independently hand-drawn or approved. Current source checkpoint has 29/62 glyphs with four editable masters (116/248), while 33/62 still use existing provisional geometry and need separate design; the 856-codepoint CFF mapping is not the same as 856 hand-approved reference glyphs.

This factory intentionally targets **one local font agent**, and only a frozen, meaningfully reviewed batch should be promoted to a PR. Do not add `push` triggers for `font/lab` to run this factory automatically.

## OpenType hole winding

Edited SVG masters use even-odd fill while the CFF uses nonzero winding. A visually hollow SVG with identically wound outer and inner contours can become a filled counter in OpenType. Before the offline family build, normalize **only the editable weight overrides** with `python tools/font_lab_normalize_winding.py --write`, then check with `python tools/font_lab_normalize_winding.py` and source tests. The one-shot full-family freeze workflow runs both steps and commits the corrected lab SVGs and derived experimental CFFs together, without rewriting approved heavy source artwork. Native Bézier handles survive reversal unchanged.
