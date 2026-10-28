# Source-traced Chunks reference punctuation, 28 opt-in SVG masters

**Status: independently editable visual alternatives, not yet shipping CFF glyphs.** This pipeline extracts punctuation from the exact checked-in original Chunks typography reference rather than inventing or borrowing its shapes. It does not modify approved wordmarks, Android assets, OTFs, or the existing 62 source-traced letters and digits.

The visible punctuation reference row supplies **29 horizontal ink segments**. A deterministic map combines the two separate ink strokes of each curly double quotation mark, producing **27 individually distinct glyph drawings**, and provides a separately addressable straight ASCII double quote as a copy of the left curly quotation drawing for future editorial choice, **28 named SVG variants**. Each source run is used exactly once, no source pixels are silently reassigned, and the original image hash is preserved in the generated manifest. All characters retain `U+XXXX.svg` naming, bounding box, component count and original source-run IDs.

The extracted set covers `! ? . , : ; “ ” ( ) [ ] { } @ # $ % & * + - = < > / \` plus a straight `"` alternative. Characters **not illustrated**, such as a single apostrophe, en dash, ellipsis and Unicode mathematical variants, continue to rely on the licensed Baloo 2 foundation unless independently designed in a later font-editing pass.

The reference image is a small raster illustration, not a vector font master. Automated contour tracing retains its recognizable shapes while introducing pixel-to-contour simplifications; accepted typography still requires contour review, correct weight-dependent proportions, baseline/side-bearing calibration, optical kerning, and real Android 14–82sp visual acceptance before installing these shapes in a production OTF.

### Reproduce

```sh
python -m pip install -r tools/requirements-chunks-reference-alphabet.txt
python tools/trace_reference_punctuation.py
python -m unittest discover -s tests -p test_reference_punctuation.py -v
python tools/render_reference_punctuation.py
```

Outputs: `assets/fonts/source/reference-punctuation/U+XXXX.svg` for 28 characters, a SHA-256-provenance `manifest.json`, and a rendered atlas of the actual SVGs. CI runs all the tests, publishes the atlas and keeps source assets updated in the feature branch. Any future OTF compilation should be a new **experimental** variant that preserves the accepted Chunks family and allows the artist to choose among punctuation versions. See [issue #347](https://github.com/gamesbyian/Chunks/issues/347).
