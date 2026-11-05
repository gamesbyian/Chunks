#!/usr/bin/env bash
# Reuse the assembled candidate's installed APKs/emulator; do not rebuild or reinstall.
set -euo pipefail
pkg=${1:?installed candidate package}
[[ "$pkg" =~ ^io\.github\.gamesbyian\.chunks\.magnet[0-9a-f]{12}$ ]]
test "${CHUNKS_REAL_MEDIA:-}" = 1
test "${GITHUB_EVENT_NAME:-}" = workflow_dispatch
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r\n')" = 1
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
cls=io.github.gamesbyian.chunks.InstalledRealMediaInstrumentedTest
out="$RUNNER_TEMP/chunks-real-media"
mkdir -p "$out"
adb logcat -c
collect_and_restore() {
  adb logcat -d -v raw -s ChunksRealMedia:I > "$out/WITNESS.txt" || true
  adb pull "/sdcard/Android/data/$pkg/files/real-media-evidence/." "$out/" >/dev/null 2>&1 || true
  adb shell svc wifi enable
  adb shell svc data enable
}
trap collect_and_restore EXIT
failed=0
run_case() {
  local method=$1 status=success
  if ! adb shell am instrument -w -r -e class "$cls#$method" -e chunks_real_media authorized \
      "$runner" > "$out/$method.raw" 2>&1; then status=failure; fi
  python3 tools/parse_instrumentation_stream.py --raw "$out/$method.raw" --junit "$out/$method.xml" || return 1
  python3 tools/ci_failure_digest.py --suite "installed-real-media-$method" --status "$status" \
    --require-executed --glob "$out/$method.xml" --output "$out/$method-digest.json" || return 1
}
for method in phaseA_tvmazeConsentSelectionAndArtwork phaseA_musicbrainzSelectionAndArtwork phaseA_openlibrarySelectionAndArtwork; do
  if ! run_case "$method"; then failed=1; fi
  adb shell am force-stop "$pkg"
done
adb shell svc wifi disable
adb shell svc data disable
for method in phaseB_tvmazeOfflineProcess phaseB_musicbrainzOfflineProcess phaseB_openlibraryOfflineProcess; do
  if ! run_case "$method"; then failed=1; fi
  adb shell am force-stop "$pkg"
done
adb logcat -d -v raw -s ChunksRealMedia:I > "$out/WITNESS.txt"
adb pull "/sdcard/Android/data/$pkg/files/real-media-evidence/." "$out/" >/dev/null
printf 'source_sha=%s\n' "$GITHUB_SHA" > "$out/SOURCE.txt"
for provider in tvmaze musicbrainz openlibrary; do
  grep -E "^CHUNKS_REAL_MEDIA_PROVIDER provider=$provider phase=online consent=true selected=true pixels=true navigated=true sanitized=true requests=[1-9][0-9]*$" "$out/WITNESS.txt" || failed=1
  grep -Fx "CHUNKS_REAL_MEDIA_PROVIDER provider=$provider phase=offline new_process=true persisted=true pixels=true requests=0" "$out/WITNESS.txt" || failed=1
done
exit "$failed"
