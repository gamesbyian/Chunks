# Chunks Reference: mixed above-and-below Latin marks

**Status:** optional, nonshipping, independent type-design experiment. Does not change approved base Chunks OTFs, Android packaged font or the prior five Stacked CFF candidates.

The source [stacked accent evidence](../../assets/fonts/experimental/stacked-studies/reference-stacked-accent-metrics.json) rejects 38 Latin characters per weight as having insufficient distinct above-base marks. Some have a second Unicode combining mark **below** the stem, which an above-only algorithm cannot correctly handle. This experiment tries a smaller, controlled subset of those already rejected characters.

## Conservative acceptance rules

The builder reads actual OFL source CFFs, the unchanged five Stacked fonts and the original per-glyph refusal manifest. It never changes an existing successful Stacked glyph. It considers a previously skipped precomposed Latin letter only if Unicode NFD is an ASCII base plus at least two explicitly supported canonical combining marks including both above (class 230) and below (classes 202/220). Horns, unusual placement classes, i/j dot substitutions and unknown mark directions stay inherited without guessing.

To qualify, **at least one independently detached source contour per required mark direction** must lie wholly above/below its original base by the six-font-unit safety threshold. Source mark contours are not scaled or redrawn. They are shifted together horizontally by the source-to-reference base centre delta, and vertically just enough to clear the reference stem by 28 units on their respective sides. The entire result must remain between font-space y = −480 and +1040. Attached cedillas and integrated horns are deliberately not cut away.

The candidate uses the existing Stacked face as its input and receives new **Chunks Reference Mixed Marks** family and CFF/PostScript names to avoid system font collisions. Bounded context kerning is computed only for newly built glyphs; all prior CFF charstrings, glyph advances, Unicode mappings (856), OpenType GPOS/GSUB/GDEF, Hindi shaping and registered fonts remain preserved. Per-weight records identify each newly composed glyph, source NFD marks, detached contour counts, offsets, final bbox, refusal reasons and original/Stacked/output SHA-256.

```sh
python -m pip install -r tools/requirements-chunks-font.txt
python tools/build_reference_mixed_marks.py
python -m unittest discover -s tests -p test_reference_mixed_marks.py -v
python tools/render_reference_mixed_marks.py
```

The CI workflow produces five independent CFFs, a complete JSON manifest, four native-font comparison atlases (14/24/48/82px) and license evidence. Source-branch artifacts are reproducible; CI does not automatically rewrite `main`.

**Not final typography:** geometric contour detachment cannot prove language-specific placement, mark identity, style coherence, line height, good output across text shaping engines or actual Android Compose/TalkBack accessibility. Every reconstructed glyph still needs owner/editorial inspection and full-device acceptance before any font promotion. The canonical completion ledger remains [issue #347](https://github.com/gamesbyian/Chunks/issues/347).
