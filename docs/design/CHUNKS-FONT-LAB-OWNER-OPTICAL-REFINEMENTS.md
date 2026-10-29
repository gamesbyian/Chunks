# Owner-directed optical refinement: `j`, `C`, `c`

**Source candidate only, isolated `font/lab`.** The owner approved the appearance of the fifth weight, ExtraBold 800, **without any changes** and requested two narrow corrections exclusively for Regular 400, Medium 500, Semibold 600 and Bold 700.

## Lowercase j dot alignment

The detached dot had been optically far left of the thick vertical shaft. Translate its original Bezier subpath **horizontally only** to align with the shaft's true vertical centre rather than the narrower header overhang.

| weight | SVG translation |
|---|---:|
| Regular 400 | +4.53 |
| Medium 500 | +4.71 |
| Semibold 600 | +4.88 |
| Bold 700 | +5.03 |

All dot points/control handles move identically. Its original size, shape, top/bottom coordinates, cap and descender are untouched. No new overhang past the 22-unit viewBox is introduced.

## C/c inward-facing bowl shape

The prior interior had three cubic segments with a noticeable kink/pinch around its middle. Replace them with **two continuous cubic Bézier sweeps** joined at a matching vertical tangent. At both terminal ends, use controls proportional to the existing quadratic tangent rays, so the inside arm flows cleanly into its rounded tips. The outside silhouette and both terminal lobes are preserved exactly.

Per-weight leftmost inner-curve points:
- Uppercase C: `6.72, 9.27, 11.91, 14.82`
- Lowercase c: `5.61, 7.48, 9.08, 11.21`

The profile is smooth, wide-open and more nearly elliptical. Preliminary source-curve samples show monotonic increase across the lighter weights, valid topology, unchanged exterior bounds and about **8.5% additional ink** relative to the previous pinched inner shape, still safely lighter than the immutable ExtraBold original. These visual candidates require a full CFF build and actual-size specimen review.

The change touches **only twelve** weight-specific source SVGs, plus a focused source test and these notes. The immutable `assets/fonts/source/reference-alphabet/U+*.svg`, the original ExtraBold reference, shipping Android resources, main and other agents' branches must not be edited. Execute `python -m unittest discover -s tests -p test_font_lab_owner_optical_refinements.py -v` locally, then run one freeze-triggered complete-family QA and optical comparison if the source test is green.

## Frozen technical acceptance of the requested changes

[Single controlled full-family acceptance #40820307607](https://github.com/gamesbyian/Chunks/actions/runs/40820307607) passed all stages. The exact source/CFF commit is [`44c419ac487c71a79bf70b95e560671a2e26d733`](https://github.com/gamesbyian/Chunks/commit/44c419ac487c71a79bf70b95e560671a2e26d733). Both targeted geometry tests and the 14/24px pixel counter audit passed, along with the 248-source master, 856-glyph cmap/shaping and 20-derivative-family gates. The prior compiled and current compiled ExtraBold 800 consolidated CFF have identical SHA-256 `55ba6f8a114a0da6223023bde4e0b6ad12d469616b2743ac471d8415b1d8813c`. This is verified technical completion of the owner's narrow request, not deployment or independent owner approval of the revised first four weight specimens.
