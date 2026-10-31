#!/usr/bin/env bash
# Offline negative controls. Synthetic adb is NOT real installed Android evidence.
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd -P)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin" "$tmp/app/build/outputs/apk/debug" "$tmp/app/build/outputs/apk/androidTest/debug"
touch "$tmp/app/build/outputs/apk/debug/app-debug.apk"
touch "$tmp/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
cat > "$tmp/bin/adb" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
mode="${MOCK_MODE:-SUCCESS}"
case "$*" in
  "shell getprop ro.build.version.sdk")
    [[ "$mode" != ADB_FAIL ]] || exit 27
    [[ "$mode" != BAD_API ]] && echo 35 || echo 29 ;;
  "install -r "*)
    [[ "$mode" != INSTALL_FAIL ]] || exit 28
    if [[ "$mode" == INSTALL_LIE ]]; then echo 'Failure [INSTALL_FAILED]'
    elif [[ "$mode" == STREAMED_INSTALL ]]; then
      echo 'Performing Streamed Install'
      echo Success
    else echo Success
    fi ;;
  "shell pm path io.github.gamesbyian.chunks.ownedlab")
    [[ "$mode" != WRONG_PACKAGE ]] || exit 29
    echo 'package:/data/app/io.github.gamesbyian.chunks.ownedlab/base.apk' ;;
  "shell am instrument -w -r "*)
    [[ "$*" == *"OwnedJniSessionSmokeInstrumentedTest"* ]] || exit 30
    [[ "$*" == *"io.github.gamesbyian.chunks.ownedlab.test/androidx.test.runner.AndroidJUnitRunner" ]] || exit 31
    [[ "$mode" != INSTRUMENT_EXIT_FAIL ]] || exit 32
    [[ "$mode" != WRONG_TEST ]] && echo 'INSTRUMENTATION_STATUS: test=pinnedOwnedDiskConstructorStartsOnlyInsideDisposablePrivateRoot' || echo 'INSTRUMENTATION_STATUS: test=wrongClass'
    [[ "$mode" != MISSING_NATIVE_REFUSAL ]] && echo 'INSTRUMENTATION_STATUS: test=ownedJniActuallyRefusesPreexistingForeignNativePayload'
    [[ "$mode" != MISSING_POSITIVE_PIECE ]] && echo 'INSTRUMENTATION_STATUS: test=ownedJniWritesAndRechecksActualPieceWithPinnedReceipts'
    if [[ "$mode" == SKIPPED ]]; then echo 'INSTRUMENTATION_STATUS_CODE: -3'
    else echo 'INSTRUMENTATION_STATUS_CODE: 0'; fi
    if [[ "$mode" == FAILED_TEST ]]; then echo 'FAILURES!!!'; fi
    if [[ "$mode" == BAD_COUNT ]]; then echo 'OK (2 tests)'
    elif [[ "$mode" == CRASH ]]; then echo 'INSTRUMENTATION_RESULT: shortMsg=Process crashed.'; echo 'INSTRUMENTATION_CODE: 0'
    else echo 'OK (3 tests)'; fi ;;
  "logcat -d -b crash -v time") echo 'MOCK_NATIVE_FATAL: expected crash diagnostic' ;;
  *) echo "Unexpected adb call: $*" >&2; exit 33 ;;
esac
MOCK
chmod +x "$tmp/bin/adb"
export PATH="$tmp/bin:$PATH"
(cd "$tmp" && MOCK_MODE=SUCCESS bash "$root/native_owned_jni_installed_smoke.sh" \
    >"$tmp/good.log")
grep -q '^CHUNKS_OWNED_ANDROID_INSTALLED=PASS api=35' "$tmp/good.log"
(cd "$tmp" && MOCK_MODE=STREAMED_INSTALL bash "$root/native_owned_jni_installed_smoke.sh" >"$tmp/streamed.log")
grep -q '^CHUNKS_OWNED_ANDROID_INSTALLED=PASS api=35' "$tmp/streamed.log"
for mode in ADB_FAIL BAD_API INSTALL_FAIL INSTALL_LIE WRONG_PACKAGE \
  INSTRUMENT_EXIT_FAIL WRONG_TEST MISSING_NATIVE_REFUSAL MISSING_POSITIVE_PIECE SKIPPED FAILED_TEST BAD_COUNT CRASH; do
  if (cd "$tmp" && MOCK_MODE="$mode" bash "$root/native_owned_jni_installed_smoke.sh" \
      >"$tmp/failure.log" 2>&1); then
    echo "CHUNKS_OWNED_JNI_MOCK=FAIL accepted $mode" >&2
    exit 1
  fi
done
(cd "$tmp" && MOCK_MODE=CRASH bash "$root/native_owned_jni_installed_smoke.sh" \
    >"$tmp/native-crash.log" 2>&1) && {
  echo 'CHUNKS_OWNED_JNI_MOCK=FAIL accepted native crash' >&2
  exit 1
}
grep -Fq 'MOCK_NATIVE_FATAL: expected crash diagnostic' "$tmp/native-crash.log"
grep -Fq 'CHUNKS_OWNED_ANDROID_CRASH_DIAGNOSTICS_BEGIN' "$tmp/native-crash.log"
grep -Fq 'CHUNKS_OWNED_ANDROID_CRASH_DIAGNOSTICS_END' "$tmp/native-crash.log"
echo 'CHUNKS_OWNED_JNI_MOCK=PASS two-valid-install-formats plus 13 negative receipt controls and crash diagnostics'
