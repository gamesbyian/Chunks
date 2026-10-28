# Chunks font family, design-stage build

**Status:** First automated, installable OpenType family candidate, not an exact reconstruction of the raster reference's curves. Keep approved SVG wordmarks untouched. The six reference images are preserved under `docs/design/reference-images`.

## Five weights

The upstream variable vector master, `assets/fonts/source/Baloo2[wght].ttf`, supplies matching Latin/Latin Extended/Vietnamese glyph coverage and five real masters. Its license is SIL OFL 1.1, stored alongside it as `assets/fonts/source/OFL.txt`. The family is renamed *Chunks* to distinguish this modified derivative from the upstream font. The upstream copyright must remain in metadata. An independent manual reference-faithful redraw of its most distinctive letterforms remains a separate editorial task.

Output files are `assets/fonts/chunks/Chunks-{Regular,Medium,Semibold,Bold,ExtraBold}.otf`. They are actual OpenType/CFF cubic-outline binaries (not TrueType files with an OTF extension), individually weighted 400/500/600/700/800.

Rebuild without external downloads:

```bash
python -m pip install -r tools/requirements-chunks-font.txt
python tools/build_chunks_fonts.py --source 'assets/fonts/source/Baloo2[wght].ttf' --out assets/fonts/chunks
```

CI rebuilds, tests and publishes the five OTF files plus machine-readable metrics in an artifact. On the feature branch, CI commits generated fonts into the PR. All font source and license files must remain when distributing the OTFs.

## Automated spacing model

The builder takes each native outline at a real, instantiated weight. For every supported Latin/Latin Extended probe glyph (including common diacritics, numerals, punctuation and currencies), Pillow's HarfBuzz/libraqm shaping measures the current font pair positioning. A separate antialiased raster silhouette is sampled at consistent em size and baseline to measure inter-glyph ink clearance along rows where silhouettes overlap; it does not rasterize the finished font. The measured pair correction is bounded, reconciles native kerning, and is appended as an OpenType **GPOS PairPos** lookup to the existing `kern` feature without dropping other shaping features. No kerning adjustments are applied to pairs that lack sufficient overlapping ink for reliable measurement. The resulting OTF retains original GPOS, GDEF and GSUB features.

Font metrics use shared and consistent `hhea` and `OS/2` ascender/descender values, zero line gap and the `USE_TYPO_METRICS` bit to avoid unanticipated vertical drift between weights. Metrics preserve headroom for accented glyphs. Rendering still depends on Android font padding and Compose/TextView layout behavior, which require device verification.

The JSON output records weights, coverage, pair counts, recommended tracking in em, and recommended line-height multipliers. These tracking/leading presets are **UI/application settings**, because a font cannot provide one universally correct tracking value or leading for both a 48sp hero wordmark and 14sp torrent metadata. Initial targets: display **1.14**, headings **1.22**, body **1.36**, compact labels **1.42** line-height multipliers; tighter tracking at heavier display weights, near-neutral body tracking. Android integration should explicitly use matching weight `Font(R.font.chunks_..., FontWeight.*)`, apply `letterSpacing` in em and `lineHeight` in sp, and test minimum-size/200%-text/TalkBack legibility.

## Acceptance and remaining editorial work

- CI checks OpenType/CFF format, weight identity, fundamental accented Latin and punctuation coverage, native layout tables and deterministic output.
- Device screenshot comparison, aesthetic silhouette match to the reference image, actual typography at 200% Android font scaling, collision stress testing on long international filenames and cross-app font installer testing remain open.
- The headline wordmark SVG is authoritative for brand art until individually redrawn heavy-font glyphs pass a visual review; raster illustration is not an original font outline master.

## Verified build snapshot · 2026-10-27

GitHub Actions run [40605045342](https://github.com/gamesbyian/Chunks/actions/runs/40605045342) passed the actual five-font build and validation. The generated fonts have **1,601 glyphs and 856 mapped Unicode codepoints each**; all five files are true OpenType/CFF with GPOS/GDEF/GSUB tables, correct weight metadata, and preserved upstream Baloo 2 copyright. The optical probe examines **22,801 Latin and Latin-extended pairs per weight** (including punctuation, numbers and accent samples); other scripts retain source shaping/positioning without applying Latin optical corrections. Pair adjustments range from approximately 13,944 in ExtraBold to 18,951 in Regular.

A larger number of corrected pairs is not automatically better typography. Review the generated preview at actual UI sizes and use native Android testing to evaluate collisions, diacritics and letter density before deciding this candidate is final. The available display SVG logo remains the authority for brand-faithful wordmark silhouettes.
