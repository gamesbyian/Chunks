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
: > "$MOCK_DIR/gradle-installed"
MOCK_GRADLE
cat > "$temp/bin/adb" <<'MOCK_ADB'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
  devices)
    printf 'List of devices attached\nMOCK_USB\tdevice\n'
    [[ "${MOCK_SECOND_DEVICE:-0}" != 1 ]] || printf 'SECOND\tdevice\n'
    [[ "${MOCK_ENUMERATION_FAILED:-0}" != 1 ]] || exit 48
    exit 0 ;;
  get-state)
    printf 'device\n'
    [[ "${MOCK_STATE_FAILED:-0}" != 1 ]] || exit 49
    exit 0 ;;
  shell|logcat) ;;
  *) exit 97 ;;
esac
if [[ "$1" == logcat ]]; then
  if [[ "${2:-}" == -d ]]; then
    case "$(cat "$MOCK_DIR/last-phase" 2>/dev/null || true)" in
      a) [[ "${MOCK_MISSING_ARM:-0}" == 1 ]] ||
        echo 'CHUNKS_FGS_TIMEOUT armed=true paused_after_ms=15000 sdk=35' ;;
      b) [[ "${MOCK_MISSING_COLD:-0}" == 1 ]] ||
        echo 'CHUNKS_FGS_TIMEOUT cold_restore=true jobs=2 stopped=true rechecked=true seeded=true removed_keep_files=true' ;;
    esac
  fi
  exit 0
fi
shift
case "$1 $2" in
  'getprop ro.kernel.qemu')
    if [[ "${MOCK_QEMU_UNKNOWN:-0}" == 1 ]]; then
      echo unknown
    elif [[ "${MOCK_PHYSICAL:-0}" == 1 ]]; then
      echo 0
    else
      echo 1
    fi
    [[ "${MOCK_IDENTITY_FAILED:-0}" != 1 ]] || exit 50
    ;;
  'getprop ro.build.version.sdk')
    echo "${MOCK_SDK:-35}"
    [[ "${MOCK_SDK_FAILED:-0}" != 1 ]] || exit 51
    ;;
  'device_config get')
    if [[ -f "$MOCK_DIR/timeout" ]]; then
      cat "$MOCK_DIR/timeout"
    else
      echo null
    fi
    [[ "${MOCK_GET_FAILURE:-0}" != 1 ]] || exit 21
    ;;
  'device_config put')
    printf 'timeout-override\n' >> "$MOCK_DIR/mutations"
    printf '%s\n' "$5" > "$MOCK_DIR/timeout"
    if [[ "${MOCK_RESTORE_FAILURE:-0}" == 1 && "$5" != 15000 ]]; then exit 22; fi
    ;;
  'device_config delete')
    if [[ "${MOCK_RESTORE_FAILURE:-0}" == 1 ]]; then exit 23; fi
    rm -f "$MOCK_DIR/timeout"
    ;;
  'am compat')
    printf 'compat-override\n' >> "$MOCK_DIR/mutations"
    if [[ "$3" == reset && "${MOCK_COMPAT_RESET_FAILURE:-0}" == 1 ]]; then exit 24; fi
    ;;
  'am instrument')
    if [[ "$*" == *coldRestoreAfterTimeoutRechecksSeedsAndKeepsPayload* ]]; then
      printf 'b\n' > "$MOCK_DIR/last-phase"
      : > "$MOCK_DIR/cold-instrument-called"
    else
      printf 'a\n' > "$MOCK_DIR/last-phase"
      : > "$MOCK_DIR/timeout-instrument-called"
    fi
    printf 'INSTRUMENTATION_STATUS: test=realTest\nOK (1 test)\n'
    if [[ "${MOCK_SKIP:-0}" == 1 ]]; then echo 'INSTRUMENTATION_STATUS_CODE: -3'; fi
    if [[ "${MOCK_TEST_FAILURE:-0}" == 1 ]]; then echo 'INSTRUMENTATION_STATUS_CODE: -1'; fi
    if [[ "${MOCK_COLD_FAILURE:-0}" == 1 &&
          "$(cat "$MOCK_DIR/last-phase")" == b ]]; then
      echo 'INSTRUMENTATION_STATUS_CODE: -2'
    fi
    if [[ "${MOCK_ADB_FAILURE:-0}" == 1 ]]; then exit 25; fi
    ;;
  'am force-stop')
    : > "$MOCK_DIR/force-stop-called"
    if [[ "${MOCK_FORCE_STOP_FAILURE:-0}" == 1 ]]; then exit 26; fi
    ;;
  *) echo "Unexpected adb operation: $*" >&2; exit 99 ;;
esac
MOCK_ADB
chmod +x "$temp/bin/adb" "$temp/bin/gradle"
export PATH="$temp/bin:$PATH" MOCK_DIR="$temp/state"
bash -n "$root/fgs_timeout_repro.sh"
bash -n "$root/test_fgs_timeout_repro.sh"

reset() {
  rm -f "$MOCK_DIR/timeout" "$MOCK_DIR/last-phase" \
    "$MOCK_DIR/timeout-instrument-called" "$MOCK_DIR/cold-instrument-called" \
    "$MOCK_DIR/force-stop-called" "$MOCK_DIR/gradle-installed"
  : > "$MOCK_DIR/mutations"
}
run_ok() {
  bash "$root/fgs_timeout_repro.sh" > "$temp/ok.log" 2>&1
  [[ -e "$MOCK_DIR/timeout-instrument-called" &&
     -e "$MOCK_DIR/force-stop-called" &&
     -e "$MOCK_DIR/cold-instrument-called" ]]
  grep -q 'config verified=15000' "$temp/ok.log"
  grep -q 'both phases passed tier=emulator' "$temp/ok.log"
}
run_bad() {
  if "$@" bash "$root/fgs_timeout_repro.sh" > "$temp/failed.log" 2>&1; then
    echo "False-positive timeout acceptance: $*" >&2; exit 1
  fi
}
# Fatal target-selection checks are BEFORE Gradle installation, compat,
# device_config or test instrumentation, even with plausible ADB stdout.
for negative in MOCK_ENUMERATION_FAILED MOCK_SECOND_DEVICE MOCK_STATE_FAILED \
  MOCK_IDENTITY_FAILED MOCK_SDK_FAILED; do
  reset
  run_bad env "$negative=1"
  [[ ! -e "$MOCK_DIR/gradle-installed" && ! -s "$MOCK_DIR/mutations" ]] || {
    echo "Unsafe target probe reached install or settings: $negative" >&2; exit 1;
  }
done
reset
run_bad env MOCK_SDK=34
[[ ! -e "$MOCK_DIR/gradle-installed" && ! -s "$MOCK_DIR/mutations" ]]
reset
run_bad env MOCK_QEMU_UNKNOWN=1
[[ ! -e "$MOCK_DIR/gradle-installed" && ! -s "$MOCK_DIR/mutations" ]]
grep -q 'unrecognized emulator/physical identity' "$temp/failed.log"
reset
run_bad env MOCK_PHYSICAL=1
[[ ! -e "$MOCK_DIR/gradle-installed" && ! -s "$MOCK_DIR/mutations" ]]
reset
run_bad env ANDROID_SERIAL=UNLISTED
[[ ! -e "$MOCK_DIR/gradle-installed" && ! -s "$MOCK_DIR/mutations" ]]
reset
run_bad env ANDROID_SERIAL=MOCK_USB ADB_SERIAL=SECOND
[[ ! -e "$MOCK_DIR/gradle-installed" && ! -s "$MOCK_DIR/mutations" ]]
reset
# Intentional handset acceptance remains possible only with exact serial and
# separately explicit physical consent, and must never masquerade as emulator.
env ANDROID_SERIAL=MOCK_USB CHUNKS_ALLOW_PHYSICAL_LIFECYCLE_TEST=1 MOCK_PHYSICAL=1 \
  bash "$root/fgs_timeout_repro.sh" > "$temp/physical-opt-in.log" 2>&1
grep -q 'both phases passed tier=physical-explicit' "$temp/physical-opt-in.log"
reset
run_ok
[[ ! -e "$MOCK_DIR/timeout" ]]
reset
printf '73000\n' > "$MOCK_DIR/timeout"
run_ok
[[ "$(cat "$MOCK_DIR/timeout")" == 73000 ]]
reset
run_bad env MOCK_ADB_FAILURE=1
[[ ! -e "$MOCK_DIR/timeout" && -e "$MOCK_DIR/timeout-instrument-called" ]]
reset
run_bad env MOCK_SKIP=1
[[ ! -e "$MOCK_DIR/timeout" ]]
reset
run_bad env MOCK_TEST_FAILURE=1
[[ ! -e "$MOCK_DIR/timeout" ]]
reset
run_bad env MOCK_GET_FAILURE=1
[[ ! -e "$MOCK_DIR/timeout-instrument-called" ]]
reset
run_bad env MOCK_RESTORE_FAILURE=1
[[ -e "$MOCK_DIR/timeout" && -e "$MOCK_DIR/timeout-instrument-called" ]]
reset
run_bad env MOCK_COMPAT_RESET_FAILURE=1
[[ ! -e "$MOCK_DIR/timeout" && -e "$MOCK_DIR/timeout-instrument-called" ]]
reset
run_bad env MOCK_COLD_FAILURE=1
[[ -e "$MOCK_DIR/timeout-instrument-called" && -e "$MOCK_DIR/cold-instrument-called" ]]
reset
run_bad env MOCK_FORCE_STOP_FAILURE=1
[[ -e "$MOCK_DIR/timeout-instrument-called" && ! -e "$MOCK_DIR/cold-instrument-called" ]]
reset
run_bad env MOCK_MISSING_ARM=1
[[ ! -e "$MOCK_DIR/cold-instrument-called" ]]
reset
run_bad env MOCK_MISSING_COLD=1
[[ -e "$MOCK_DIR/cold-instrument-called" ]]
echo 'CHUNKS_FGS_TIMEOUT_MOCK: pre-install device selection/physical consent fail closed; two-phase recovery and exact receipts required; prior settings restored; failed/skipped tests and failed cleanup refused'
