# Chunks multi-family visual regression atlas

**Nonshipping evidence.** The independent 15-face acceptance audit checks binary and layout integrity; this visual test adds actual Pillow/HarfBuzz glyph rasterization at **14, 24, 48, and 82px** without shrinking text to fit. It compares all five weight rows across three font variants at each weight: production candidate, reference-panel contour study, and accent-fusion study.

The four fixed test strings exercise recognizable Chunks letterforms, accented Latin, transfer numerals/speeds, and Hindi shaping. Every font row is annotated with the family and weight. Overlong glyph ink triggers a visible red review-cell border, and the JSON records exact SHA-256, measured ink width/height, available cell width and overflow status, so typography differences cannot be hidden by ad hoc screenshot resizing. The artifacts are meant for human design review, and do not imply that rendering is beautiful or accessible. Headline and smaller Android UI typography require separate on-device testing.

Run locally:

```bash
python -m pip install -r tools/requirements-chunks-font.txt
python tools/render_font_family_stress.py --out font-stress-artifacts
```

CI uploads `chunks-font-variants-{14,24,48,82}px.png` and `chunks-font-visual-stress.json` on font/source changes. This generator does **not** modify font binaries or Android resources. Outstanding contour edits, alternate accented forms, smaller type acceptance, and owner selection remain [Issue #347](https://github.com/gamesbyian/Chunks/issues/347).
## Actual findings and relative width drift

The first CI artifact (run [40620541213](https://github.com/gamesbyian/Chunks/actions/runs/40620541213)) found **zero comparison-cell overflows at 14/24/48px** and **seven at 82px**, all in the accented Latin sample `Résumé ÖÄ Ç ñ`. The most noticeable change is Regular accent-fusion: **625px** ink width at 82px versus **518px** in the original Regular OTF, approximately **1.21×**. Medium through ExtraBold accent-fusion similarly measure 627–634px, versus 526–544px for the corresponding base fonts.

The generator now records the candidate-to-base `width_vs_base_ratio` for every sample, weight and size, and a `relative_width_review` collection for expansions of **15% or more**. This is a **review trigger**, not a categorical failure. A visual sample may be wider because its intentional letterform design is wider, and a fixed comparison cell is not an Android app layout constraint. The next acceptance pass should check filename ellipsizing, 200% text scaling and real component width before adjusting glyph advances or tracking. The accepted Android font is unchanged.
