# Chunks: reference-letter silhouette alternatives

**Status: Non-shipping experimental vector glyph masters.** The owner-provided `03-typography-and-wordmark.png` is the design reference, not a source OTF. The currently installed five-weight family remains authoritative until these alternatives have passed optical, multilingual and handset comparisons. The six traced primary-wordmark glyphs in `assets/fonts/source/reference-traced-wordmark.svg` retain priority for logo fidelity.

## Extracted source and provenance

`tools/trace_reference_alphabet.py` turns the original reference illustration's white ExtraBold character grid into **62 separate editable SVG glyphs**: A–Z, a–z and 0–9. The script uses documented fixed row mappings, a brightness/chroma mask, connected silhouette contours with smoothed quadratic segments, and even-odd compound paths that preserve counters (including A, B, O, 0, 6, 8, 9). Each output file is named `U+NNNN.svg`; `manifest.json` records source SHA-256, exact image regions, contour counts and row labels. It does not OCR or invent identities from character images. It refuses incorrect row counts and a significantly different reference aspect ratio.

The reference may arrive in equivalent resolutions or compressions. For the recognized four-by-three design sheet, segmentation is normalized to a 1536×1152 reference coordinate system. A materially redesigned sheet requires manual mapping rather than guessing. The original PNG remains untouched and is never made a font source by embedding bitmap pixels into SVG.

To regenerate from the checked-in sheet:

```bash
python -m pip install -r tools/requirements-chunks-reference-alphabet.txt
python tools/trace_reference_alphabet.py \
  --source docs/design/reference-images/03-typography-and-wordmark.png \
  --out assets/fonts/source/reference-alphabet
python tools/render_reference_alphabet.py \
  --glyphs assets/fonts/source/reference-alphabet \
  --out chunks-reference-alphabet-atlas.png
```

The dedicated CI workflow checks all 62 glyphs and source provenance, publishes the exact vector output with a raster preview and keeps generated SVGs in sync on the feature branch. No Android resource, marketing logo or production OTF is changed by this work.

## Optical limitations and acceptance work

Original character glyphs are only about 24–40 source pixels wide, which limits contour accuracy. Rounded shoulders, counter sizes, stem terminals and weight interpolation **must** be evaluated at several rendered sizes. The reference character sheet is only a heavy-weight master; deriving Regular through Bold and assigning side bearings, em alignment, diacritics, kerning and extra punctuation requires separate measured design work. Uppercase C and lower-case h/u/n/k/s from the primary wordmark sheet are separate variants and should never be overwritten silently.

Full-family acceptance requires: native CFF build; monotonic ink area by weight; noncollapsed counters; all five weights share the same full Unicode coverage; optical gap and collision regressions on displayed Latin strings; native shaping preserved for non-Latin scripts; independent Android screenshot accessibility checks. Variant selection is deliberate, not automatic.