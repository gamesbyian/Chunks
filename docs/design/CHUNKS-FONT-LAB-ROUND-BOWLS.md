> **Historical contour design checkpoint.** The original source counts, binary-refresh warnings and pending stages below describe this earlier batch. For the current 248-master build, canonical receipts and remaining approval gates, see [font-lab status](CHUNKS-FONT-LAB-STATUS.md). These are nonshipping editable proposals, not owner-approved final type design.

# Nonshipping lab: O/o/zero and C/c independent bowl-master designs

**2026-10-28. Source-only preflight, NOT integrated CFF, NOT editorial approval.**

On [font/lab](https://github.com/gamesbyian/Chunks/tree/font/lab), twenty new weight-specific SVGs make five common round/open forms independently editable. Native cubic paths replace the geometric-erosion fallback. The `O`, `o` and `0` use separate continuous rounder outer bowls and separately controllable inset counters at each of Regular 400, Medium 500, Semibold 600, Bold 700. The open `C` and `c` use joined crescent bowls, open right apertures, controlled inner sweeps and rounded ends (native cubic and quadratic Béziers). **These are designed candidates, not exact reproductions or owner-approved shapes.** The five original 800-weight source SVGs remain unchanged.

Local outline-sampling preflight recorded (ink area as a fraction of immutable original reference glyph):
| Letter | 400 | 500 | 600 | 700 |
|---|---:|---:|---:|---:|
| O | .72 | .79 | .86 | .92 |
| o | .74 | .81 | .89 | .94 |
| 0 | .72 | .81 | .88 | .94 |
| C | .54 | .60 | .64 | .70 |
| c | .52 | .58 | .63 | .67 |

All candidate areas are strictly increasing, inherit the expected one-bowl/one-counter or single-open-contour topology and remain within ±3 source pixels of reference extent. Source-only test: `python -m unittest discover -s tests -p test_font_lab_round_shapes.py -v`. **Further work:** see whether C/c bowls are visually too open relative to O/o/zero; inspect their terminals with 14/24/48/82px rendered output and revise. Do not conflate an area ratio with a kerning or aesthetic pass.

**Artifact note:** the last source-branch OTF/metrics ledger contains **32 source overrides** (8 glyphs × 4 weights). These twenty SVGs are a new lab art checkpoint, raising editable *source* candidates to **52 (13 glyphs × four weights)**, but the committed four OTF binaries still reflect the earlier source revision. Rebuild all four CFFs and the metrics *locally* before treating this as a validated 13-glyph font. No new PR and no Actions should run during drawing. The authoritative lab source-only and full gates are `bash tools/font_lab_gate.sh source` and `bash tools/font_lab_gate.sh full`. All new work remains nonshipping.

Related: [font lab operating contract](CHUNKS-FONT-LAB-OPERATIONS.md), [issue #347](https://github.com/gamesbyian/Chunks/issues/347).
