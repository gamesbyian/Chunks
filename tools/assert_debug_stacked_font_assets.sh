#!/usr/bin/env bash
# Fail before instrumentation if CI forgot the five experimental debug assets.
set -euo pipefail
apk="app/build/outputs/apk/debug/app-debug.apk"
test -s "$apk"
test ! -d app/src/main/assets/referenceFonts
for style in Regular Medium Semibold Bold ExtraBold; do
  asset="assets/referenceFonts/ChunksReferencePanel-${style}-Stacked.otf"
  # Do not use grep -q with pipefail: unzip can receive SIGPIPE even
  # when the archive entry exists, falsely reporting a missing asset.
  if ! unzip -Z1 "$apk" | grep -Fx "$asset" >/dev/null; then
    echo "Missing debug-only font asset: $asset" >&2
    echo "APK asset entries (for packaging diagnostics):" >&2
    unzip -Z1 "$apk" | grep -E '^assets/' >&2 || true
    exit 1
  fi
done
printf 'Verified all five Stacked CFFs in debug APK; no release asset staged\n'
