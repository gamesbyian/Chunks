# Small-pixel optical counter repair (A, 4)

The first frozen 248-master CFF family passed shape, shaping, winding and layout tests. The winding-corrected actual font raster at **14px** revealed tiny/vanishing enclosed apertures in glyphs `A` and `4`, especially at heavier weights. This is not a winding failure: they remain real CFF holes and are visible at 24px, but rasterization makes their areas too small for comfortable 14px recognition.

This focused lab-only source revision enlarges *only the two inner closed contours* of `A` and `4` in all four lighter weights. The original outer Bézier contours, native control points, ExtraBold 800, Android shipping font and other 60 basic glyphs are untouched. Enlargement factors based on actual temporary CFF raster prototypes:

| Glyph | Regular | Medium | Semibold | Bold |
|---|---:|---:|---:|---:|
| A | 1.50× | 1.65× | 1.75× | 1.90× |
| 4 | 1.70× | 1.70× | 1.40× | 1.30× |

The scaling is centered on the inner contour's SVG envelope, retaining its native Bézier shape and original winding. Local temporary actual-CFF tests showed A's 14px aperture increase from 1px to about 3–4px depending on weight; 4's to around 4–5px. This is **preliminary prototyping**, not final validation of the updated source files. The authoritative full CFF recompile, strict source topology/weight checks and visual atlas are still required.

The [real-CFF raster counter audit](../../tools/font_lab_raster_counter_audit.py) inspects four experimental OTF files, counts interior background pixel islands at 14/24/48/82px, and enforces at least two white pixels per expected counter at 24px. 14px remains diagnostic, to avoid pretending this chunky display-style font is a body-text face. No CI push fanout; run it locally or in a single explicit freeze.

This optical change is not owner art approval. Preserve the owner reference as the immutable benchmark and review each face's shape and legibility in actual app UI.

## Remaining tiny-size follow-up (candidate 4)

The [third full acceptance](https://github.com/gamesbyian/Chunks/actions/runs/40816749998) **passed**, showing zero missing counters at 24px for all 20 source counter glyphs across all four weights. Raster at 14px reported only Medium 4 and Bold g/p/8 as remaining warnings; Regular and Semibold had zero.

This follow-up changes four editable *inner paths only*: Medium 4's counter shifts 1 source pixel upward; Bold g/p/8 enlarge their closed counter Béziers by 15% around each counter. Temporary native CFF raster experiments found Medium 4 has a 4px white interior at 14px with the shift; Bold g and p restore at least a 2px hole and Bold 8 restores two 2px holes. This is optical preflight, NOT an approval of the whole typography system. A fourth complete-family freeze must revalidate strict 14px **and** 24px rendered pixel counters, source topology, kerning and all derived font families.

## Fifth gate: Bold 8 final position fix

The fourth frozen [strict small-pixel run #40817339389](https://github.com/gamesbyian/Chunks/actions/runs/40817339389) failed for **one** actual raster case: Bold `8` at 14px had two counters of 2px and 1px. Regular/Medium/Semibold all had zero counter warnings at both 14/24px; Bold g and p also passed after the previous source changes. Reconstructing the exact Bold 8 SVG via the CFF pen locally confirmed that shifting only its **upper inner contour by -0.4 source SVG pixels vertically** yields two genuine raster holes of at least 2px at 14px and leaves 24px counters readable. No outer silhouette, other weights or base glyphs change. This exact source edit requires one final full-family freeze and SHA receipt to count as accepted; do not waive strict raster checking.
