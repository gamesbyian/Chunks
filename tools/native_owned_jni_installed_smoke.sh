#!/usr/bin/env bash
# #568: opt-in disposable emulator ONLY. The patched lab APK must never be
# distributed, installed on a user's device or used on public Downloads.
set -euo pipefail
[[ "$(adb shell getprop ro.build.version.sdk | tr -d '\r\n')" == 35 ]] || {
  echo "CHUNKS_OWNED_ANDROID_INSTALLED=REFUSED expected API35"; exit 2;
}
install_verified() {
  local result
  result="$(adb install -r "$1" 2>&1)" || {
    echo "CHUNKS_OWNED_ANDROID_INSTALLED=REFUSED adb install failed"; return 1;
  }
  # adb may print "Performing Streamed Install" before its final success.
  # Accept only an EXACT Success line, with no Failure marker anywhere.
  if ! grep -qx 'Success' <<<"$result" ||
     grep -qi 'Failure' <<<"$result"; then
    echo "CHUNKS_OWNED_ANDROID_INSTALLED=REFUSED package manager: $result"
    return 1
  fi
}
install_verified app/build/outputs/apk/debug/app-debug.apk
install_verified app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
package="io.github.gamesbyian.chunks.ownedlab"
adb shell pm path "$package" | tr -d '\r' | grep -E '^package:' > /dev/null
log="$(mktemp)"
trap 'rm -f "$log"' EXIT
set +e
adb shell am instrument -w -r \
  -e class io.github.gamesbyian.chunks.engine.OwnedJniSessionSmokeInstrumentedTest \
  "$package.test/androidx.test.runner.AndroidJUnitRunner" >"$log" 2>&1
status=$?
set -e
cat "$log"
if [[ "$status" != 0 ]] ||
   ! grep -qx 'OK (4 tests)' "$log" ||
   ! grep -q '^INSTRUMENTATION_STATUS: test=pinnedOwnedDiskConstructorStartsOnlyInsideDisposablePrivateRoot' "$log" ||
   ! grep -q '^INSTRUMENTATION_STATUS: test=ownedJniActuallyRefusesPreexistingForeignNativePayload' "$log" ||
   ! grep -q '^INSTRUMENTATION_STATUS: test=ownedJniWritesAndRechecksActualPieceWithPinnedReceipts' "$log" ||
   ! grep -q '^INSTRUMENTATION_STATUS: test=installedOwnedJniReceivesActualPeerPieceWithoutTouchingForeignData' "$log" ||
   ! grep -q '^INSTRUMENTATION_STATUS_CODE: 0' "$log" ||
   grep -qE '^FAILURES|^INSTRUMENTATION_STATUS_CODE: -(1|2|3|4)' "$log"; then
  echo "CHUNKS_OWNED_ANDROID_INSTALLED=REFUSED instrumentation not fully verified"
  # A real native process crash may return INSTRUMENTATION_CODE: 0 while
  # skipping all JUnit results. Dump only bounded crash-buffer diagnostics
  # from the disposable emulator so the actual JNI signal/stack is visible.
  # Log retrieval can itself fail after a fatal emulator disconnect, but it
  # must never change this negative test receipt into PASS.
  echo "CHUNKS_OWNED_ANDROID_CRASH_DIAGNOSTICS_BEGIN"
  adb logcat -d -b crash -v time 2>&1 | tail -n 180 || true
  echo "CHUNKS_OWNED_ANDROID_CRASH_DIAGNOSTICS_END"
  exit 1
fi
echo "CHUNKS_OWNED_ANDROID_INSTALLED=PASS api=35 abi=x86_64 scope=app-private native-foreign-refusal-positive-piece-loopback-peer"
