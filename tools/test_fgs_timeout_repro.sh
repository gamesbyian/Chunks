#!/usr/bin/env bash
# Fake-adb tests for timeout acceptance. No emulator, Android SDK or downloads.
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd -P)"
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT
mkdir -p "$temp/bin" "$temp/state"
cat > "$temp/bin/gradle" <<'MOCK_GRADLE'
#!/usr/bin/env bash
set -euo pipefail
[[ "$*" == '--no-daemon :app:installDebug :app:installDebugAndroidTest' ]]
MOCK_GRADLE
cat > "$temp/bin/adb" <<'MOCK_ADB'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == shell || "$1" == logcat ]] || exit 97
if [[ "$1" == logcat ]]; then
  [[ "${2:-}" != -d ]] || echo 'CHUNKS_FGS_TIMEOUT log diagnostic'
  exit 0
fi
shift
case "$1 $2" in
  'device_config get')
    if [[ -f "$MOCK_DIR/timeout" ]]; then
      cat "$MOCK_DIR/timeout"
    else
      echo null
    fi
    [[ "${MOCK_GET_FAILURE:-0}" != 1 ]] || exit 21
    ;;
  'device_config put')
    printf '%s\n' "$5" > "$MOCK_DIR/timeout"
    if [[ "${MOCK_RESTORE_FAILURE:-0}" == 1 && "$5" != 15000 ]]; then exit 22; fi
    ;;
  'device_config delete')
    if [[ "${MOCK_RESTORE_FAILURE:-0}" == 1 ]]; then exit 23; fi
    rm -f "$MOCK_DIR/timeout"
    ;;
  'am compat')
    if [[ "$3" == reset && "${MOCK_COMPAT_RESET_FAILURE:-0}" == 1 ]]; then exit 24; fi
    ;;
  'am instrument')
    : > "$MOCK_DIR/instrument-called"
    printf 'INSTRUMENTATION_STATUS: test=realTest\nOK (1 test)\n'
    if [[ "${MOCK_SKIP:-0}" == 1 ]]; then echo 'INSTRUMENTATION_STATUS_CODE: -3'; fi
    if [[ "${MOCK_TEST_FAILURE:-0}" == 1 ]]; then echo 'INSTRUMENTATION_STATUS_CODE: -1'; fi
    if [[ "${MOCK_ADB_FAILURE:-0}" == 1 ]]; then exit 25; fi
    ;;
  *) echo "Unexpected adb operation: $*" >&2; exit 99 ;;
esac
MOCK_ADB
chmod +x "$temp/bin/adb" "$temp/bin/gradle"
export PATH="$temp/bin:$PATH" MOCK_DIR="$temp/state"
bash -n "$root/fgs_timeout_repro.sh"
bash -n "$root/test_fgs_timeout_repro.sh"

reset() { rm -f "$MOCK_DIR/timeout" "$MOCK_DIR/instrument-called"; }
run_ok() {
  bash "$root/fgs_timeout_repro.sh" > "$temp/ok.log" 2>&1
  [[ -e "$MOCK_DIR/instrument-called" ]]
  grep -q 'config verified=15000' "$temp/ok.log"
}
run_bad() {
  if "$@" bash "$root/fgs_timeout_repro.sh" > "$temp/failed.log" 2>&1; then
    echo "False-positive timeout acceptance: $*" >&2; exit 1
  fi
}
reset
run_ok
[[ ! -e "$MOCK_DIR/timeout" ]]
reset
printf '73000\n' > "$MOCK_DIR/timeout"
run_ok
[[ "$(cat "$MOCK_DIR/timeout")" == 73000 ]]
reset
run_bad env MOCK_ADB_FAILURE=1
[[ ! -e "$MOCK_DIR/timeout" && -e "$MOCK_DIR/instrument-called" ]]
reset
run_bad env MOCK_SKIP=1
[[ ! -e "$MOCK_DIR/timeout" ]]
reset
run_bad env MOCK_TEST_FAILURE=1
[[ ! -e "$MOCK_DIR/timeout" ]]
reset
run_bad env MOCK_GET_FAILURE=1
[[ ! -e "$MOCK_DIR/instrument-called" ]]
reset
run_bad env MOCK_RESTORE_FAILURE=1
[[ -e "$MOCK_DIR/timeout" && -e "$MOCK_DIR/instrument-called" ]]
reset
run_bad env MOCK_COMPAT_RESET_FAILURE=1
[[ ! -e "$MOCK_DIR/timeout" && -e "$MOCK_DIR/instrument-called" ]]
echo 'CHUNKS_FGS_TIMEOUT_MOCK: default/prior config restored; failed adb, skipped/failing test, invalid readback and restore refusal rejected'
