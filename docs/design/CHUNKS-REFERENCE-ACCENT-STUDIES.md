# Chunks Reference Panel: accent-fusion studies

**Status: non-shipping visual prototypes.** This experiment **does not alter the five accepted Chunks faces, installed Android OTF, original wordmark SVG, or the four lighter reference-weight studies.** Its five independently selectable `.otf` files live under `assets/fonts/experimental/accent-studies/`.

The source reference-alphabet artwork provides 62 distinctive Basic Latin letters and digits, but the remaining accented Latin glyphs in each font still use the upstream Baloo 2 outline. That creates an obvious discontinuity in UI strings such as *Résumé*, *Österreich* and *přehled*. This prototype conservatively recomposes eligible precomposed Latin glyphs using the **already rebuilt letter** plus the **original licensed mark outline**.

## Safety and geometry

- Only characters with exactly one canonical NFD Latin base and one Unicode `Mn` mark qualify. Other scripts, compatibility ligatures and connected marks are never coerced into an accent approximation.
- The mark must be a separately drawable contour **entirely above or below** its original base. Accents fused to stems or within counters are skipped, preserving their original glyphs.
- Lowercase **i** and **j** are excluded to avoid double-dot errors when recomposing above-mark accents. These can be revisited only with deliberate dotless custom masters.
- Detached source marks are horizontally aligned with the new custom base glyph, then moved vertically only as needed to maintain a 28-unit clearance. The original mark shapes are never scaled or redrawn.
- Candidates exceeding conservative vertical limits (−480 to +1040 units) are rejected. The original hhea/OS2 line metrics and OpenType GSUB/GDEF/GPOS tables remain intact. Unicode cmap and all 856 mapped codepoints remain identical.
- Each font receives a traceable JSON manifest (source/reference/output SHA-256, source-base glyph, direction, displacement, ink bounds, status). All five OTFs are reproducible without network access.
- Current rebuilds produce **243–244 accented glyphs per weight** (varying slightly with source/master contour separation). That number is *not* equivalent to a fully hand-designed Latin Extended family; source mark shapes still differ in texture and style from newly traced bases.
- Existing GPOS positioning and accent class kerning is retained. **A dedicated optical recalibration and 200% Android rendering audit remain required** before promoting this variant. The body font should remain native/system until that acceptance is complete.

## Rebuild

```sh
python -m pip install -r tools/requirements-reference-panel-weights.txt
python tools/build_reference_accent_studies.py
python -m unittest discover -s tests -p test_reference_accent_studies.py -v
python tools/render_reference_accent_studies.py
```

The workflow `.github/workflows/reference-panel-accent-studies.yml` generates each OTF, verifies deterministic builds, preserves all 62 custom Basic Latin glyphs and native Devanagari shaping, renders a real-font five-weight atlas, uploads the five variant OTFs, metrics, and source OFL license, and only commits the isolated `experimental/accent-studies/` subtree on its feature branch. The original four weight-study sources are untouched.

**Acceptance remaining:** independent accent artwork for integrated/attached accents; replacement i/j dot variants; mark balancing and legibility in 14sp filenames; exhaustive optical kerning and collision tests across multiple scripts and Android renderers; final human choice of which family or glyph variants to ship. Track under [#347](https://github.com/gamesbyian/Chunks/issues/347).