#!/usr/bin/env bash
# Android 10 (API 29) shared Downloads capability: install, run the Place and
# picker classes, and fail if any test was skipped by an assumption, so a
# green run cannot hide a test that never exercised Android 10.
set -euo pipefail
test "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" = 29
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
log=$(mktemp)
trap 'rm -f "$log"' EXIT
set +e
adb shell am instrument -w -r \
 -e class io.github.gamesbyian.chunks.LocalPublicDownloadsPlaceInstrumentedTest,io.github.gamesbyian.chunks.LocalDownloadPlacePickerInstrumentedTest \
 io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner >"$log" 2>&1
status=$?
set -e
grep -E '^INSTRUMENTATION_STATUS: (class|test)=|^INSTRUMENTATION_STATUS_CODE:|^OK |^FAILURES|^Tests run' "$log" || true
if [ "$status" -ne 0 ] || ! grep -q '^OK (6 tests)' "$log" ||
   grep -qE '^INSTRUMENTATION_STATUS_CODE: -(1|2|3|4)' "$log"; then
 grep -A12 '^INSTRUMENTATION_STATUS: stack=' "$log" | head -60 || true
 echo "::error::Android 10 shared Downloads instrumentation failed, or a test was skipped"
 exit 1
fi
echo "CHUNKS_ANDROID10_DOWNLOADS: API 29 legacy storage + granted storage permissions; native engine wrote, resumed and seeded from Download/Chunks Downloads; picker requests permission only from its button"
