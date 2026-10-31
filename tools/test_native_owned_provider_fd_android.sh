#!/usr/bin/env bash
set -euo pipefail
expected=35
actual="$(adb shell getprop ro.build.version.sdk | tr -d '\r\n')"
[[ "$actual" == "$expected" ]] || { echo "Wrong Android API: $actual" >&2; exit 2; }
install_verified() {
  local result
  result="$(adb install -r "$1" 2>&1)" || return 1
  # Android adb commonly prints a progress line before its authoritative
  # exact Success line. Refuse any Failure marker or absent Success line.
  if ! grep -qx 'Success' <<<"$result" ||
     grep -qi 'Failure' <<<"$result"; then
    echo "Install failed: $result" >&2
    return 1
  fi
}
install_verified app/build/outputs/apk/debug/app-debug.apk
install_verified app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
log="$(mktemp)"
trap 'rm -f "$log"' EXIT
set +e
adb shell am instrument -w -r \
  -e class io.github.gamesbyian.chunks.engine.OwnedProviderFdAndroidProbe \
  io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner >"$log" 2>&1
status=$?
set -e
cat "$log"
[[ "$status" -eq 0 ]] || exit 1
grep -q '^INSTRUMENTATION_STATUS: test=ownedPendingDownloadsDescriptorCrossesJniAndSurvivesPublication' "$log"
grep -q '^INSTRUMENTATION_STATUS: test=nativeRefusesPipeWithoutBlockingOrWriting' "$log"
grep -qx 'OK (2 tests)' "$log"
grep -q '^INSTRUMENTATION_STATUS_CODE: 0' "$log"
if grep -qE "^FAILURES|^INSTRUMENTATION_STATUS_CODE: -(1|2|3|4)" "$log"; then exit 1; fi
echo "CHUNKS_NATIVE_MEDIASTORE_PFD_JNI=PASS api=35 tests=2 no-torrent-payload"
