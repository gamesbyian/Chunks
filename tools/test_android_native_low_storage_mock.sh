#!/usr/bin/env bash
# No Android device or SDK: fail-closed controller and forged-receipt negatives.
set -euo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT
mkdir -p "$temp/bin" "$temp/state"
cat > "$temp/bin/adb" <<'MOCK_ADB'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
  devices)
    printf 'List of devices attached\nMOCK_USB\tdevice\n'
    [[ "${MOCK_SECOND:-0}" != 1 ]] || printf 'SECOND\tdevice\n'
    [[ "${MOCK_ENUMERATION_FAIL:-0}" != 1 ]] || exit 64
    ;;
  get-state)
    printf 'device\n'
    [[ "${MOCK_STATE_FAIL:-0}" != 1 ]] || exit 65
    ;;
  shell)
    shift
    case "$*" in
      'getprop ro.kernel.qemu')
        [[ "${MOCK_PHYSICAL:-0}" == 1 ]] && echo 0 || echo 1
        [[ "${MOCK_QEMU_FAIL:-0}" != 1 ]] || exit 66
        ;;
      'getprop ro.build.version.sdk')
        echo "${MOCK_SDK:-35}"
        [[ "${MOCK_SDK_FAIL:-0}" != 1 ]] || exit 67
        ;;
      am\ instrument*)
        : > "$MOCK_DIR/instrument"
        [[ "${MOCK_INSTRUMENT_FAIL:-0}" != 1 ]] || exit 68
        if [[ "${MOCK_SKIP:-0}" == 1 ]]; then
          printf 'OK (1 test)\nINSTRUMENTATION_STATUS_CODE: -3\n'
        else
          printf 'OK (1 test)\n'
        fi
        ;;
      *) echo "Unexpected adb shell: $*" >&2; exit 69 ;;
    esac
    ;;
  install)
    : > "$MOCK_DIR/install"
    [[ "${MOCK_INSTALL_FALSE:-0}" == 1 ]] &&
      echo 'Failure [INSTALL_FAILED_INVALID_APK]' || echo Success
    ;;
  logcat)
    if [[ "${2:-}" == '-d' ]]; then
      [[ "${MOCK_MISSING_PRESSURE:-0}" == 1 ]] ||
        echo 'CHUNKS_LOW_SPACE_PRESSURE baseline=40000000 free=1000000 payload_required=50331648'
      [[ "${MOCK_MISSING_BLOCKED:-0}" == 1 ]] ||
        echo 'CHUNKS_LOW_SPACE_BLOCKED free=1000000 state=DOWNLOADING stopped=true'
      [[ "${MOCK_MISSING_RECOVERED:-0}" == 1 ]] ||
        echo 'CHUNKS_LOW_SPACE_RECOVERED bytes=50331648 sha_equal=true stopped=true removed_keep_files=true'
      [[ "${MOCK_DUPLICATE_RECOVERED:-0}" == 1 ]] &&
        echo 'CHUNKS_LOW_SPACE_RECOVERED bytes=50331648 sha_equal=true stopped=true removed_keep_files=true'
    fi
    ;;
  *) exit 70 ;;
esac
MOCK_ADB
chmod +x "$temp/bin/adb"
export PATH="$temp/bin:$PATH" MOCK_DIR="$temp/state"
bash -n "$repo/android_native_low_storage_repro.sh"
bash -n "$repo/test_android_native_low_storage_mock.sh"
reset() { rm -f "$MOCK_DIR/install" "$MOCK_DIR/instrument"; }
fail_case() {
  reset
  if "$@" bash "$repo/android_native_low_storage_repro.sh" > "$temp/failure.log" 2>&1; then
    echo "CHUNKS_LOW_SPACE_MOCK: false acceptance: $*" >&2; exit 1
  fi
}
reset
env -u ANDROID_SERIAL -u ADB_SERIAL bash "$repo/android_native_low_storage_repro.sh" > "$temp/positive.log"
grep -q 'real allocated pressure' "$temp/positive.log"
[[ -f "$MOCK_DIR/install" && -f "$MOCK_DIR/instrument" ]]
for flag in MOCK_SECOND MOCK_ENUMERATION_FAIL MOCK_STATE_FAIL MOCK_PHYSICAL MOCK_QEMU_FAIL MOCK_SDK_FAIL; do
  fail_case env -u ANDROID_SERIAL -u ADB_SERIAL "$flag=1"
  [[ ! -e "$MOCK_DIR/install" && ! -e "$MOCK_DIR/instrument" ]] || {
    echo "CHUNKS_LOW_SPACE_MOCK: destructive preflight crossed for $flag" >&2; exit 1
  }
done
fail_case env -u ANDROID_SERIAL -u ADB_SERIAL MOCK_SDK=34
[[ ! -e "$MOCK_DIR/install" ]]
fail_case env ANDROID_SERIAL=UNLISTED
[[ ! -e "$MOCK_DIR/install" ]]
for flag in MOCK_INSTRUMENT_FAIL MOCK_SKIP MOCK_INSTALL_FALSE MOCK_MISSING_PRESSURE \
  MOCK_MISSING_BLOCKED MOCK_MISSING_RECOVERED MOCK_DUPLICATE_RECOVERED; do
  fail_case env -u ANDROID_SERIAL -u ADB_SERIAL "$flag=1"
done
echo 'CHUNKS_LOW_SPACE_MOCK: safe emulator-only ADB target, genuine receipt and failed/skipped negative controls accepted (offline only)'
