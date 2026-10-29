# Font lab owner refinements: round two, `j/G/g/9`

**Scope:** the owner reported four more glyph defects, and again explicitly approved **the fifth ExtraBold 800 unchanged**. All edits are deliberately restricted to 16 native Bézier SVG masters (`j,G,g,9` in Regular 400, Medium 500, Semibold 600 and Bold 700) on isolated nonshipping `font/lab`. The original source masters, ExtraBold, `C/c` previous corrections, production Android font assets, `main`, and other development branches are untouched.

## Changes to glyph skeletons

- **`j`:** the old drawn stem began as a four-unit pointed horizontal overhang projecting left. This was an accidental wedge rather than a deliberate terminal. The body now starts and ends on its real left vertical shaft edge; the previously adjusted dot, hook, and descender remain identical.
- **`G`:** redraw the upper-right outer shoulder with a horizontal outgoing cubic tangent, removing the pinched edge. Raise the upper inside terminal and its two neighboring quadratic controls, opening the tight gap above the horizontal crossbar by approximately 2.3 source units while keeping the crossbar and lower terminal intact.
- **`g`:** lift the bowl's lower outside cubic controls by 2.35 and 2.12 units, respectively, to expose more white space where its curled tail approaches the bowl; keep the tail, enclosed main counter, and right body fixed.
- **`9`:** mirror **all native path points**, including the counter, around the 29-source-unit glyph advance box midpoint to correct a right-curling, reversed 9. This is baked into editable SVG path commands and **not** an SVG transform ignored by build scripts. All 400–700 outline areas and counter topology should remain intact.

The owner-preferred fifth reference weight is an explicit invariance contract. Do not apply these changes to the original ExtraBold. Do not mirror numeral 6.

## Quality gates

Focused source regression: `python -m unittest discover -s tests -p test_font_lab_owner_optical_refinements_round2.py -v`. The tests inspect native path commands, font outline topology and the ink gradient.

After a complete source batch, intentionally update **only the font lab freeze marker** once to run the nonroutine full-family acceptance. Demand 248/248 source masters, 856-codepoint CFF cmap and GSUB/GPOS/marks, all 20 derivative binaries, zero 14px/24px missing counters and unchanged byte-identical ExtraBold 800. Confirm visually on actual CFF-generated glyph specimens, not generated sample graphics alone. Remain nonshipping pending owner art approval of the revised 400–700 and separate real Android font-scale/TalkBack verification.

## Final frozen technical validation (round two)

The first [round-two frozen run #40822338290](https://github.com/gamesbyian/Chunks/actions/runs/40822338290) rejected the candidate early because Regular/Medium `G` upper-arc bounds exceeded the reference envelope and a new test searched for a line instead of an existing quadratic endpoint. Corrected both in [`03bfe73c`](https://github.com/gamesbyian/Chunks/commit/03bfe73c20cd31ced8faec8e13384e54c13f331d); no rules were relaxed. The subsequent [one final complete-family acceptance #40822542503](https://github.com/gamesbyian/Chunks/actions/runs/40822542503) **PASSED all steps** and saved the full 20-file derivative CFF family to isolated experimental build [`0aaacec0f84c794684447a8a9029210b6435296f`](https://github.com/gamesbyian/Chunks/commit/0aaacec0f84c794684447a8a9029210b6435296f). Native SVG optical/source tests, 248 masters, exact CFF bytes, 856 cmap/GSUB/GPOS, 14/24px raster counter visibility, marks/accents, shaping, kerning, Hindi preservation and all 20 derivative binaries passed. The original approved ExtraBold 800 is **byte-identical** to the prior production-free laboratory build (consolidated OTF SHA-256 `55ba6f8a114a0da6223023bde4e0b6ad12d469616b2743ac471d8415b1d8813c`). No actual handset testing or owner art verdict of revised first four weights has yet occurred.
