#!/usr/bin/env bash
# Local-only Chunks font lab source preflight and optional full CFF build.
# This script never pushes, opens PRs, dispatches Actions or edits Android fonts.
set -euo pipefail

usage() { echo "Usage: bash tools/font_lab_gate.sh [source|full] (only on font/lab)" >&2; exit 2; }
mode="${1:-source}"
[[ $# -le 1 && ( "$mode" == source || "$mode" == full ) ]] || usage
root="$(git rev-parse --show-toplevel)"
cd "$root"
branch="$(git symbolic-ref --quiet --short HEAD || true)"
if [[ "$branch" != "font/lab" ]]; then
  echo "REFUSED: font work must stay on font/lab (current: ${branch:-detached HEAD})" >&2
  exit 2
fi
if git rev-parse --verify origin/main >/dev/null 2>&1; then
  main_ref=origin/main
elif git rev-parse --verify main >/dev/null 2>&1; then
  main_ref=main
else
  echo "REFUSED: no main reference available for protected-art scope audit" >&2
  exit 2
fi
base="$(git merge-base HEAD "$main_ref")"
protected=(
  app/src/main/res/font
  app/src/main/assets/licenses
  docs/design/approved
  docs/design/reference-images
  assets/fonts/chunks
  assets/fonts/experimental/ChunksReferencePanel-ExtraBold-Optical.otf
  ':(glob)assets/fonts/source/reference-alphabet/U+*.svg'
  ':(glob)assets/fonts/source/reference-punctuation/U+*.svg'
)
changed="$(git diff --name-only "$base" HEAD -- "${protected[@]}")"
working="$(git status --porcelain -- "${protected[@]}")"
if [[ -n "$changed" || -n "$working" ]]; then
  echo 'REFUSED: protected shipping/reference files changed in font lab:' >&2
  [[ -z "$changed" ]] || printf '%s\n' "$changed" >&2
  [[ -z "$working" ]] || printf '%s\n' "$working" >&2
  exit 2
fi

python - <<'PY'
from pathlib import Path
from xml.etree import ElementTree as ET
folder = Path('assets/fonts/source/reference-alphabet/weight-overrides')
files = []
for weight in ('400', '500', '600', '700'):
    for path in sorted((folder / weight).glob('U+*.svg')):
        ET.parse(path)
        files.append(path)
print(f'Local font lab: {len(files)} parseable editable weight-specific SVGs; protected original and shipping paths unchanged')
PY
if [[ "$mode" == source ]]; then
  echo 'Source preflight complete. No CI dispatched and no font binaries rebuilt.'
  exit 0
fi

python tools/build_reference_panel_weight_studies.py
python -m unittest discover -s tests -p test_reference_panel_weights.py -v
python tools/render_reference_panel_weights.py
python tools/render_reference_glyph_grid.py
python tools/audit_font_families.py --output chunks-font-cross-family-audit.json
printf '%s\n' 'Local full font build/metrics and specimens complete; no CI dispatched.'
