# Chunks: layered Latin glyph acceptance ledger

**Status: experimental/nonshipping, design provenance only.** Existing Consolidated, Stacked and Mixed Marks families remain separately named CFF font files. This ledger does **not** promote a new app font or certify accessibility.

## Problem

A character can be present in the 856-codepoint Unicode cmap while still using a licensed source outline, and a successful stacked-mark overlay may exist in one experimental family but not in another. The original [Latin source-art audit](../../tools/audit_reference_latin_coverage.py) reports the Consolidated candidate: 495 mapped Latin letters, with 185–186 lacking source-specific design across the weights. Subsequent isolated overlays change those counts without modifying the underlying baseline.

## Verified source model

`tools/audit_reference_layered_latin_coverage.py` reads the exact compiled five-weight OpenType/CFF and source SHA manifests of all three families, then reconciles every mapped Latin letter by weight. A glyph receives exactly one category: baseline reference/accents/dotless, original-source fallback, `stacked_above` or `mixed_above_below`. Additional categories preserve the original distinctions among simple canonical marks, stacked canonical marks, and other Latin/ligature glyphs.

Every mixed glyph must have been previously refused by the Stacked study and every stacked/mixed glyph must originate from the original audit's *unrebuilt stacked* bucket. A mismatch of any actual font hash, weight, 856-character cmap, duplicate source glyph, changed source scope, or unresolved count is a hard failure.

The expected provenance counts before final editorial review are:

| Weight | Initially unresolved | Stacked above | Mixed above/below | Still unresolved |
|---|---:|---:|---:|---:|
| Regular | 185 | 69 | 14 | 102 |
| Medium | 186 | 68 | 14 | 104 |
| Semibold | 186 | 67 | 14 | 105 |
| Bold | 186 | 56 | 14 | 116 |
| ExtraBold | 186 | 46 | 14 | 126 |

A reproducible JSON ledger lists all 495 mapped Latin letters with Unicode names, original decomposition, original source category, five-weight layered category, refused weights and exact hashes. CI publishes and retains the report. The report is a **design-work checklist**, not a claim that all rebuilt contours are faithful to the supplied reference. Manual drawings for Regular through Bold, source-specific unattached and integrated marks, optical tuning and real Android Compose/200%/TalkBack remain open in [#347](https://github.com/gamesbyian/Chunks/issues/347).

```sh
python tools/audit_reference_layered_latin_coverage.py --out reference-layered-latin-coverage.json
python -m unittest discover -s tests -p test_reference_layered_latin_coverage.py -v
```
