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
