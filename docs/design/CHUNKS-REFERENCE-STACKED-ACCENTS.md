# Chunks Reference Panel: conservative stacked Latin accent study

**Status: experimental and nonshipping.** This is a separate five-weight CFF family, not an Android font replacement or a modification of the earlier Consolidated family. Track source-accurate design work under [#347](https://github.com/gamesbyian/Chunks/issues/347).

## Scope

The [Latin source ledger](../../tools/audit_reference_latin_coverage.py) identifies **108** mapped Latin letters per weight with stacked canonical combining diacritics still inherited from the original licensed face. This experiment targets only the subset that can be built safely from original OFL font contours without pretending attached/integrated marks are separate.

For each original character with an NFD decomposition of one ASCII base followed by at least two `Mn` combining marks:

- Exclude lowercase `i` and `j` until the dot/dotless treatment for multiple marks can be individually designed.
- Extract the corresponding original source CFF glyph, its original source base, and the new Chunks reference base.
- Accept only **at least as many independently detached above-base source mark contours as Unicode marks**. A contour that overlaps or touches the original stem is not blindly removed from it.
- Reuse the exact source mark paths without scaling; shift the group horizontally to the reference stem's centre and only raise it when needed to maintain **28 font units of base/mark clearance**. Preserve the source marks' relative placement.
- Refuse any output outside **−480 to +1040 font units**, insufficient mark contours or ambiguous glyph separation. Refused characters retain their previous source CFF outlines.
- Reconstruct the accepted compound from the actual custom reference base and original mark paths, assign the original reference base's advance, and recompute bounded contextual accent kerning against the **finished new charstrings**.

A JSON report distinguishes eligible, accepted and skipped source characters with precise refusal reasons, all input/output SHA hashes and per-glyph placement. No Unicode map is extended, and the existing native Devanagari and Latin shaping features remain intact.

## Commands and CI

```sh
python -m pip install -r tools/requirements-chunks-font.txt
python tools/build_reference_stacked_accents.py
python -m unittest discover -s tests -p test_reference_stacked_accents.py -v
python tools/render_reference_stacked_accents.py
```

Output: five `assets/fonts/experimental/stacked-studies/ChunksReferencePanel-{Style}-Stacked.otf` files, `reference-stacked-accent-metrics.json`, and a real-OTF image specimen `reference-stacked-five-weights.png`. All are **independent, named, reversible experiments** that retain the original SIL OFL license and existing stable variants.

CI in `.github/workflows/reference-panel-stacked-studies.yml` builds, tests, renders and publishes these outputs. The workflow commits reproducible generated artifacts only on a feature branch, never to `main` automatically.

## Remaining acceptance

Geometric detachment cannot establish optical style coherence, mark semantics, correct stacked accent ordering for every language, or Android accessibility. Any glyph skipped because of attached marks, too-tall composition or i/j ambiguity needs independent design work. Even accepted composites need visual checks at 14/24/48/82px and native Android normal/200% font scaling, wrapping, truncation and TalkBack. Do **not** install this font into the app or call issue #347 complete on the strength of a green source build alone.

## Complete per-glyph acceptance sheets and remaining coverage

The short 33px sentence specimen is only a smoke test. `tools/render_reference_stacked_grid.py` now renders **all 107 non-i/j stacked candidates** as side-by-side actual native CFF glyphs across Regular, Medium, Semibold, Bold and ExtraBold, repeated at **14/24/48/82px**. Each glyph/weight cell is explicitly marked **B** (rebuilt) or **F** (retained original/fallback). Rows are Unicode-labelled and aligned to common weight baselines, with 60 rows per page to prevent unwieldy artwork.

The same deterministic report compares each compiled Stacked face to the previous [Latin coverage ledger](../../tools/audit_reference_latin_coverage.py), verifying that only characters previously classified as unresolved stacked Latin marks were reconstructed. It calculates *remaining uncustomized Latin letters* per weight after this experimental augmentation, alongside the original counts. All five font SHA-256 hashes, original reconstruction report SHA, per-page PNG dimensions/hashes and explicit nonshipping status are preserved in `reference-stacked-grid-metrics.json`. Generate the report with:

```sh
python tools/render_reference_stacked_grid.py
python -m unittest discover -s tests -p test_reference_stacked_grid.py -v
```

The CI workflow publishes the eight PNG sheets and JSON report with the five OTFs. Classification as **B** means safely processed by the conservative source-geometry rules, not that the glyph has received visual or linguistic design approval. Android normal/200% sizing and TalkBack remain untested.

## Android native text rendering gate (test-only, not deployment)

`.github/workflows/experimental-font-android-render.yml` stages the five **existing experimental** Stacked OTFs into the **debug-only app APK's assets**, not `app/src/main/assets`, `app/src/main/res/font` or any release/shipping resource. Other instrumentation workflows without staged fixtures explicitly skip this one data-dependent test. It boots an API 35 x86_64 Pixel emulator and invokes `ExperimentalTypefaceNativeInstrumentedTest`. Real Android `TextView` and `Canvas` rasterization is exercised for **each of five CFF weights** at simulated 100% and 200% text size across a long multilingual torrent filename, difficult stacked marks, and transfer stats with accents, quotes and ellipses. The test verifies complete unellipsized string layout, nonempty native bitmap ink, accessibility labels and concrete screenshot files, expecting **30 PNG renders**. Dedicated CI requires all five debug APK fixtures (no silent skips) and publishes screenshots with Android **MediaStore** into `Pictures/ChunksFontAtlas` on the fresh test emulator, so Gradle's automatic app uninstall cannot erase the only exportable evidence. `tools/export_native_font_screenshots.sh` pulls the published files after instrumentation, validates every expected filename, all 30 PNG headers and SHA-256 receipts; workflow artifacts remain separate from release APKs.

**Limits:** these are authentic native Android TextView pixels and accessibility properties, not equivalent to the app's Compose typography integration, a running TalkBack screen reader, device-specific 200% nonlinear font scaling or owner approval of the source reference contours. This still needs manual visual inspection and the native app's full 200%/TalkBack screenshot matrix before any candidate can replace the approved font. The emulator's debug app carries test-only fonts temporarily, and the workflow verifies all five are actually packaged before running the test. No experimental fonts are stored in the repository's debug or release resources, or promoted to the app's production runtime.
