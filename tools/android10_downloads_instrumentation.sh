#!/usr/bin/env bash
# Android 10 (API 29) shared Downloads: on a fresh install, drive the real
# system permission dialog from Chunks' own button, then run the Place and
# picker classes. Fail if any test was skipped by an assumption, so a green
# run cannot hide a test that never exercised Android 10.
set -euo pipefail
test "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" = 29
# A fresh install so the first pass sees no storage grant.
adb uninstall io.github.gamesbyian.chunks >/dev/null 2>&1 || true
adb install app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
log=$(mktemp)
trap 'rm -f "$log"' EXIT
fail_if_skipped_or_failed() { # $1 status, $2 expected "OK (N tests)", $3 label
 if [ "$1" -ne 0 ] || ! grep -q "^$2" "$log" ||
    grep -qE '^INSTRUMENTATION_STATUS_CODE: -(1|2|3|4)' "$log"; then
  grep -A12 '^INSTRUMENTATION_STATUS: stack=' "$log" | head -60 || true
  echo "::error::$3 failed, or a test was skipped"
  exit 1
 fi
}
# 1. The real Android 10 permission dialog, reached from Chunks' own button.
set +e
adb shell am instrument -w -r \
 -e class io.github.gamesbyian.chunks.Android10StoragePermissionDialogInstrumentedTest \
 io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner >"$log" 2>&1
status=$?
set -e
grep -E '^INSTRUMENTATION_STATUS: test=|^INSTRUMENTATION_STATUS_CODE:|^OK |^FAILURES' "$log" || true
fail_if_skipped_or_failed "$status" 'OK (1 test)' "Android 10 permission dialog journey"
# 2. Capability proof with the grant now in place.
set +e
adb shell am instrument -w -r \
 -e class io.github.gamesbyian.chunks.LocalPublicDownloadsPlaceInstrumentedTest,io.github.gamesbyian.chunks.LocalDownloadPlacePickerInstrumentedTest \
 io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner >"$log" 2>&1
status=$?
set -e
grep -E '^INSTRUMENTATION_STATUS: (class|test)=|^INSTRUMENTATION_STATUS_CODE:|^OK |^FAILURES|^Tests run' "$log" || true
fail_if_skipped_or_failed "$status" 'OK (6 tests)' "Android 10 shared Downloads instrumentation"
# 3. The foreground download service and its notification on API 29
#    (an API 31-only builder call once crashed it here).
set +e
adb shell am instrument -w -r \
 -e class io.github.gamesbyian.chunks.LocalEngineServiceInstrumentedTest \
 io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner >"$log" 2>&1
status=$?
set -e
grep -E '^INSTRUMENTATION_STATUS: test=|^INSTRUMENTATION_STATUS_CODE:|^OK |^FAILURES' "$log" || true
fail_if_skipped_or_failed "$status" 'OK (3 tests)' "Android 10 foreground download service"
echo "CHUNKS_ANDROID10_DOWNLOADS: API 29 user tapped Allow storage access then Android's own Allow, and new Adds switched to Downloads/Chunks Downloads; legacy storage + grant let the native engine write, resume and seed there; the foreground service ran, showed its notification and Stop all paused transfers"
