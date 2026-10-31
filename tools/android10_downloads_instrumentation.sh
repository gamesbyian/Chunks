#!/usr/bin/env bash
# Android 10 (API 29) shared Downloads: on a fresh install, drive the real
# system permission dialog from Chunks' own button, then run the Place and
# picker classes. Fail if any test was skipped by an assumption, so a green
# run cannot hide a test that never exercised Android 10.
set -euo pipefail
# Readbacks must have successful adb transport, not just plausible output.
api="$(adb shell getprop ro.build.version.sdk | tr -d '\r\n')" || {
  echo "CHUNKS_ANDROID10_DOWNLOADS: Android API readback failed" >&2
  exit 1
}
[[ "$api" == 29 ]] || {
  echo "CHUNKS_ANDROID10_DOWNLOADS: expected API 29, got $api" >&2
  exit 1
}

# A fresh install is necessary to actually exercise the first permission grant.
# adb uninstall can fail if the package was absent, but a failed uninstall of
# an installed package must never be mistaken for a clean device state.
adb uninstall io.github.gamesbyian.chunks >/dev/null 2>&1 || true
# pm path may report a nonzero status for a legitimately absent package.
# List packages instead, then require an exact package-name match, never a
# substring match against similarly named preview/other applications.
remaining="$(adb shell pm list packages io.github.gamesbyian.chunks)" || {
  echo "CHUNKS_ANDROID10_DOWNLOADS: cannot verify the previous app is gone" >&2
  exit 1
}
if printf '%s\n' "$remaining" | tr -d '\r' | grep -Fxq 'package:io.github.gamesbyian.chunks'; then
  echo "CHUNKS_ANDROID10_DOWNLOADS: previous app still installed" >&2
  exit 1
fi

install_checked() {
  local output
  output="$(adb install "$@" 2>&1)" || {
    echo "CHUNKS_ANDROID10_DOWNLOADS: adb install command failed" >&2
    exit 1
  }
  # adb variants may exit zero while the package manager prints Failure.
  if ! printf '%s\n' "$output" | tr -d '\r' | grep -qx 'Success' ||
     printf '%s\n' "$output" | grep -qiE '(^|[[:space:]])Failure'; then
    echo "CHUNKS_ANDROID10_DOWNLOADS: package manager did not confirm install" >&2
    exit 1
  fi
}
install_checked app/build/outputs/apk/debug/app-debug.apk
install_checked -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
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
 -e class io.github.gamesbyian.chunks.LocalPublicDownloadsPlaceInstrumentedTest,io.github.gamesbyian.chunks.LocalDownloadPlacePickerInstrumentedTest,io.github.gamesbyian.chunks.LocalPlaceWriteProbeRaceInstrumentedTest \
 io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner >"$log" 2>&1
status=$?
set -e
grep -E '^INSTRUMENTATION_STATUS: (class|test)=|^INSTRUMENTATION_STATUS_CODE:|^OK |^FAILURES|^Tests run' "$log" || true
fail_if_skipped_or_failed "$status" 'OK (18 tests)' "Android 10 shared Downloads instrumentation"
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
