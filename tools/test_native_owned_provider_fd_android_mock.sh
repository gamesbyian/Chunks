#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd -P)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/bin" "$work/app/build/outputs/apk/debug" "$work/app/build/outputs/apk/androidTest/debug"
touch "$work/app/build/outputs/apk/debug/app-debug.apk"
touch "$work/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
cat >"$work/bin/adb" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
mode="${MOCK_MODE:-GOOD}"
case "$*" in
  "shell getprop ro.build.version.sdk")
    [[ "$mode" != GETPROP_FAIL ]] || exit 8
    [[ "$mode" != WRONG_API ]] && echo 35 || echo 29 ;;
  "install -r "*)
    [[ "$mode" != INSTALL_FAIL ]] || exit 9
    [[ "$mode" != INSTALL_LIE ]] && echo Success || echo 'Failure [INSTALL_FAILED]' ;;
  "shell am instrument -w -r "*)
    [[ "$*" == *"OwnedProviderFdAndroidProbe"* ]] || exit 10
    [[ "$mode" != INSTRUMENT_FAIL ]] || exit 11
    [[ "$mode" != FIRST_MISSING ]] && echo "INSTRUMENTATION_STATUS: test=ownedPendingDownloadsDescriptorCrossesJniAndSurvivesPublication"
    [[ "$mode" != SECOND_MISSING ]] && echo "INSTRUMENTATION_STATUS: test=nativeRefusesPipeWithoutBlockingOrWriting"
    [[ "$mode" != SKIPPED ]] && echo "INSTRUMENTATION_STATUS_CODE: 0" || echo "INSTRUMENTATION_STATUS_CODE: -3"
    [[ "$mode" != BAD_COUNT ]] && echo "OK (2 tests)" || echo "OK (1 test)"
    [[ "$mode" != FAILED ]] || echo "FAILURES!!!" ;;
  *) echo "unknown mocked adb operation: $*" >&2; exit 12 ;;
esac
MOCK
chmod +x "$work/bin/adb"
export PATH="$work/bin:$PATH"
(cd "$work" && MOCK_MODE=GOOD bash "$root/test_native_owned_provider_fd_android.sh" >"$work/good.log")
grep -q '^CHUNKS_NATIVE_MEDIASTORE_PFD_JNI=PASS api=35 tests=2' "$work/good.log"
for mode in GETPROP_FAIL WRONG_API INSTALL_FAIL INSTALL_LIE INSTRUMENT_FAIL \
    FIRST_MISSING SECOND_MISSING SKIPPED BAD_COUNT FAILED; do
  if (cd "$work" && MOCK_MODE="$mode" bash "$root/test_native_owned_provider_fd_android.sh" >"$work/fail.log" 2>&1); then
    echo "CHUNKS_NATIVE_MEDIASTORE_JNI_MOCK=FAIL accepted $mode" >&2
    exit 1
  fi
done
echo "CHUNKS_NATIVE_MEDIASTORE_JNI_MOCK=PASS positive-plus-10-negative-controls"
