# Single-process, offline font-family rebuild

The isolated font laboratory is now built from the editable branch `font/lab` using **one local command**, not a fanout of GitHub PRs or Actions:

```sh
python -m pip install -r tools/requirements-reference-panel-weights.txt
bash tools/font_lab_build_all.sh
```

This orchestrates: safe source and SVG tests; reference weight CFFs and 62-character review grids; accent reconstructions, accent pair kerning and dotless forms; punctuation supplements; consolidated CFFs; stacked mark and mixed mark variants; the layered Latin ledger; 20-face cross-family integrity; and final glyph/spacing render artifacts. It performs no network operations, no automatic pushes, no PR creation and no release font replacement. **The complete orchestrator PASSED end to end** in [one frozen full-family run #40812391453](https://github.com/gamesbyian/Chunks/actions/runs/40812391453), including 20 cross-family binaries. The final winding-corrected full-family run [#40812996755](https://github.com/gamesbyian/Chunks/actions/runs/40812996755) **PASSED** all stages, normalized 68 counter paths from 60 authored SVGs, verified the 20 experimental family/weight binaries and committed exact source/binary receipts at `08dfa8b17bb6acf3cd134204ce4ae69650b09f16`. See [current lab status](CHUNKS-FONT-LAB-STATUS.md).

`python tools/font_lab_source_ledger.py` is a read-only, JSON-formatted progress inventory. `--require-full` can serve as an **explicit design-completeness gate** when all 62 original glyphs finally have four editable source masters. It does not classify an affine-synthesized contour as independently hand-drawn or approved. Current source checkpoint has **62/62 glyphs with four editable masters (248/248)**, with the original heavy reference untouched; the 856-codepoint CFF mapping is not the same as 856 hand-approved reference glyphs.

The exact final candidate passed the [strict 14px/24px full-family freeze run #40817681146](https://github.com/gamesbyian/Chunks/actions/runs/40817681146), now including `python tools/font_lab_raster_counter_audit.py --strict --output font-lab-raster-counters.json` after the four lighter-weight OTFs are compiled. The audit measures **real font pixels**, failing if any expected counter of any of the four lighter faces has fewer than 2 enclosed pixels at either 14 or 24px. A zero-failure JSON report is attached to the run's artifact, and 48/82px specimens remain available for editorial review. This is a reproducible technical gate, not a substitute for visual signoff.

This factory intentionally targets **one local font agent**, and only a frozen, meaningfully reviewed batch should be promoted to a PR. Do not add `push` triggers for `font/lab` to run this factory automatically.

## OpenType hole winding

Edited SVG masters use even-odd fill while the CFF uses nonzero winding. A visually hollow SVG with identically wound outer and inner contours can become a filled counter in OpenType. Before the offline family build, normalize **only the editable weight overrides** with `python tools/font_lab_normalize_winding.py --write`, then check with `python tools/font_lab_normalize_winding.py` and source tests. The one-shot full-family freeze workflow runs both steps and commits the corrected lab SVGs and derived experimental CFFs together, without rewriting approved heavy source artwork. Native Bézier handles survive reversal unchanged.
