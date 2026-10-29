#!/usr/bin/env bash
# Offline, single-lane Chunks typeface factory. No GitHub Actions, PR or push.
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
[[ $# -eq 0 ]] || { echo "Usage: bash tools/font_lab_build_all.sh" >&2; exit 2; }
export PYTHONHASHSEED=0 TZ=UTC

# Be explicit about source status before consuming expensive build time.
bash tools/font_lab_gate.sh source
python -m unittest discover -s tests -p 'test_font_lab_*.py' -v
python tools/font_lab_source_ledger.py

# The four lighter faces use hand-edited SVGs wherever supplied; missing
# reference glyphs retain the original safe polygon-derived provisional form.
python tools/build_reference_panel_weight_studies.py
python -m unittest discover -s tests -p test_reference_panel_weights.py -v
python tools/render_reference_panel_weights.py
python tools/render_reference_glyph_grid.py
python -m unittest discover -s tests -p test_reference_glyph_grid.py -v

# Build the accent families against these exact four regenerated CFF weights.
python tools/build_reference_accent_studies.py
python -m unittest discover -s tests -p test_reference_accent_studies.py -v
for weight in 400 500 600 700 800; do
  python tools/build_reference_accent_kerned.py --weights "$weight"
done
python -m unittest discover -s tests -p test_reference_accent_kerned.py -v
for weight in 400 500 600 700 800; do
  python tools/build_reference_dotless_accents.py --weights "$weight"
done
python -m unittest discover -s tests -p test_reference_dotless_accents.py -v

# More distant derivative families must use the just-built intermediates.
python tools/build_reference_punctuation_supplements.py
python tools/build_reference_punctuation_supplements.py --check
python tools/build_reference_panel_consolidated.py
python -m unittest discover -s tests -p test_reference_panel_consolidated.py -v
python tools/build_reference_stacked_accents.py
python -m unittest discover -s tests -p test_reference_stacked_accents.py -v
python tools/build_reference_mixed_marks.py
python -m unittest discover -s tests -p test_reference_mixed_marks.py -v
python tools/audit_reference_layered_latin_coverage.py --out assets/fonts/experimental/mixed-mark-studies/reference-layered-latin-coverage.json
python -m unittest discover -s tests -p test_reference_layered_latin_coverage.py -v

# Cross-family integrity (20 CFF binaries), independent license/shaping/cmap,
# source receipts, and visual specimens from actual font files.
python tools/audit_font_families.py --output chunks-font-cross-family-audit.json
python tools/render_reference_accent_grid.py
python tools/render_reference_stacked_grid.py
python tools/render_reference_mixed_marks.py
python tools/render_font_family_stress.py --out font-stress-artifacts
python -m unittest discover -s tests -p test_font_shaped_advance_audit.py -v

printf 'Font-lab rebuilt at source %s\n' "$(git rev-parse HEAD)"
printf 'Review the generated OTFs, metadata, fixtures and specimen PNGs before committing.\n'
printf 'No remote workflow has been dispatched and nothing has been pushed.\n'
