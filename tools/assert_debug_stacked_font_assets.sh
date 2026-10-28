#!/usr/bin/env bash
# Fail before instrumentation if CI forgot the five experimental debug assets.
set -euo pipefail
apk="app/build/outputs/apk/debug/app-debug.apk"
test -s "$apk"
test ! -d app/src/main/assets/referenceFonts
for style in Regular Medium Semibold Bold ExtraBold; do
  asset="assets/referenceFonts/ChunksReferencePanel-${style}-Stacked.otf"
  unzip -Z1 "$apk" | grep -Fqx "$asset" || {
    echo "Missing debug-only font asset: $asset" >&2
    exit 1
  }
done
printf 'Verified all five Stacked CFFs in debug APK; no release asset staged\n'
