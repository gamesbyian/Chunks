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
    [[ "$mode" != INSTALL_LIE ]] && echo Success || echo 'Failure [INSTALL_FAILED]' ;;
  "shell pm path io.github.gamesbyian.chunks.ownedlab")
    [[ "$mode" != WRONG_PACKAGE ]] || exit 29
    echo 'package:/data/app/io.github.gamesbyian.chunks.ownedlab/base.apk' ;;
  "shell am instrument -w -r "*)
    [[ "$*" == *"OwnedJniSessionSmokeInstrumentedTest"* ]] || exit 30
    [[ "$*" == *"io.github.gamesbyian.chunks.ownedlab.test/androidx.test.runner.AndroidJUnitRunner" ]] || exit 31
    [[ "$mode" != INSTRUMENT_EXIT_FAIL ]] || exit 32
    [[ "$mode" != WRONG_TEST ]] && echo 'INSTRUMENTATION_STATUS: test=pinnedOwnedDiskConstructorStartsOnlyInsideDisposablePrivateRoot' || echo 'INSTRUMENTATION_STATUS: test=wrongClass'
    if [[ "$mode" == SKIPPED ]]; then echo 'INSTRUMENTATION_STATUS_CODE: -3'
    else echo 'INSTRUMENTATION_STATUS_CODE: 0'; fi
    if [[ "$mode" == FAILED_TEST ]]; then echo 'FAILURES!!!'; fi
    if [[ "$mode" == BAD_COUNT ]]; then echo 'OK (2 tests)'
    else echo 'OK (1 test)'; fi ;;
  *) echo "Unexpected adb call: $*" >&2; exit 33 ;;
esac
MOCK
chmod +x "$tmp/bin/adb"
export PATH="$tmp/bin:$PATH"
(cd "$tmp" && MOCK_MODE=SUCCESS bash "$root/native_owned_jni_installed_smoke.sh" \
    >"$tmp/good.log")
grep -q '^CHUNKS_OWNED_ANDROID_INSTALLED=PASS api=35' "$tmp/good.log"
for mode in ADB_FAIL BAD_API INSTALL_FAIL INSTALL_LIE WRONG_PACKAGE \
  INSTRUMENT_EXIT_FAIL WRONG_TEST SKIPPED FAILED_TEST BAD_COUNT; do
  if (cd "$tmp" && MOCK_MODE="$mode" bash "$root/native_owned_jni_installed_smoke.sh" \
      >"$tmp/failure.log" 2>&1); then
    echo "CHUNKS_OWNED_JNI_MOCK=FAIL accepted $mode" >&2
    exit 1
  fi
done
echo 'CHUNKS_OWNED_JNI_MOCK=PASS true-positive plus 10 negative receipt controls'
