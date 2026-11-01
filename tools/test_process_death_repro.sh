#!/usr/bin/env bash
# Offline fake-ADB adversarial controls for API35 process-death acceptance.
# No emulator, live peer or APK required.
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd -P)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin"
cat > "$tmp/bin/adb" <<'ADB_MOCK'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
  devices)
    printf 'List of devices attached\nMOCK_USB\tdevice\n'
    [[ "${MOCK_SECOND_DEVICE:-0}" != 1 ]] || printf 'SECOND\tdevice\n'
    [[ "${MOCK_DEVICES_FAILED:-0}" != 1 ]] || exit 31
    ;;
  get-state)
    printf 'device\n'
    [[ "${MOCK_STATE_FAILED:-0}" != 1 ]] || exit 32
    ;;
  logcat)
    if [[ "${2:-}" == -d ]]; then
      if [[ "${MOCK_ARM_MISSING:-0}" != 1 ]]; then
        printf 'CHUNKS_DEATH armed hash=0123456789012345678901234567890123456789\n'
      fi
      if [[ "${MOCK_LOG_FAILURE:-0}" == 1 ]]; then exit 23; fi
    fi
    ;;
  shell)
    shift
    if [[ "${1:-}" == getprop ]]; then
      case "${2:-}" in
        ro.kernel.qemu)
          if [[ "${MOCK_QEMU_UNKNOWN:-0}" == 1 ]]; then echo unknown
          elif [[ "${MOCK_PHYSICAL:-0}" == 1 ]]; then echo 0
          else echo 1; fi
          [[ "${MOCK_QEMU_FAILED:-0}" != 1 ]] || exit 33
          ;;
        ro.build.version.sdk)
          echo "${MOCK_SDK:-35}"
          [[ "${MOCK_SDK_FAILED:-0}" != 1 ]] || exit 34
          ;;
        *) exit 35 ;;
      esac
      exit 0
    fi
    if [[ "${1:-}" == pidof ]]; then
      [[ "${MOCK_PROCESS_ALIVE:-0}" == 1 ]]
      exit
    fi
    if [[ "${1:-} ${2:-}" == 'am instrument' ]]; then
      if [[ " $* " == *phaseA_seedUnderServiceThenDieWithoutWarning* ]]; then
        : > "$MOCK_PHASE_A"
        if [[ "${MOCK_NO_CRASH:-0}" != 1 ]]; then printf 'Process crashed\n'; fi
        exit 0
      fi
      if [[ " $* " == *phaseB_restoredStoppedWithBytesIntactAndRechecks* ]]; then
        : > "$MOCK_PHASE_B"
        case "${MOCK_PHASE_B_OUTCOME:-ok}" in
          ok) printf 'OK (1 test)\nINSTRUMENTATION_CODE: -1\n' ;;
          false_ok) printf 'INSTRUMENTATION_RESULT: stream=OK (1 test)\n' ;;
          skip) printf 'OK (1 test)\nINSTRUMENTATION_STATUS_CODE: -3\n' ;;
          fail) printf 'OK (1 test)\nINSTRUMENTATION_STATUS_CODE: -1\n' ;;
          other_negative) printf 'OK (1 test)\nINSTRUMENTATION_STATUS_CODE: -7\n' ;;
          duplicate) printf 'OK (1 test)\nOK (1 test)\n' ;;
          command_failure) printf 'OK (1 test)\nINSTRUMENTATION_CODE: -1\n'; exit 25 ;;
          terminal_missing) printf 'OK (1 test)\n' ;;
          terminal_failed) printf 'OK (1 test)\nINSTRUMENTATION_CODE: 0\n' ;;
          terminal_duplicate) printf 'OK (1 test)\nINSTRUMENTATION_CODE: -1\nINSTRUMENTATION_CODE: -1\n' ;;
          terminal_mixed) printf 'OK (1 test)\nINSTRUMENTATION_CODE: -1\nINSTRUMENTATION_CODE: 0\n' ;;
          *) exit 30 ;;
        esac
        exit 0
      fi
    fi
    echo "Unexpected Android shell operation: $*" >&2; exit 99
    ;;
  *) echo "Unexpected adb command: $*" >&2; exit 99 ;;
esac
ADB_MOCK
cat > "$tmp/bin/sleep" <<'MOCK_SLEEP'
#!/usr/bin/env bash
exit 0
MOCK_SLEEP
chmod +x "$tmp/bin/adb" "$tmp/bin/sleep"
export PATH="$tmp/bin:$PATH" MOCK_PHASE_A="$tmp/phase-a" MOCK_PHASE_B="$tmp/phase-b"
bash -n "$root/process_death_repro.sh"
bash -n "$root/test_process_death_repro.sh"
rm -f "$MOCK_PHASE_A" "$MOCK_PHASE_B"
env -u ADB_SERIAL -u ANDROID_SERIAL bash "$root/process_death_repro.sh" > "$tmp/positive.log"
[[ -e "$MOCK_PHASE_A" && -e "$MOCK_PHASE_B" ]]
grep -q 'OK (1 test)' "$tmp/positive.log"

for fixture in MOCK_ARM_MISSING MOCK_NO_CRASH MOCK_PROCESS_ALIVE MOCK_LOG_FAILURE; do
  rm -f "$MOCK_PHASE_A" "$MOCK_PHASE_B"
  if env -u ADB_SERIAL -u ANDROID_SERIAL "$fixture=1" bash "$root/process_death_repro.sh" > "$tmp/$fixture.log" 2>&1; then
    echo "False-positive process death acceptance: $fixture" >&2; exit 1
  fi
  [[ ! -e "$MOCK_PHASE_B" ]] || {
    echo "Recovery phase was entered despite broken phase A: $fixture" >&2; exit 1;
  }
done
for outcome in false_ok skip fail other_negative duplicate command_failure \
  terminal_missing terminal_failed terminal_duplicate terminal_mixed; do
  rm -f "$MOCK_PHASE_A" "$MOCK_PHASE_B"
  if env -u ADB_SERIAL -u ANDROID_SERIAL "MOCK_PHASE_B_OUTCOME=$outcome" bash "$root/process_death_repro.sh" > "$tmp/$outcome.log" 2>&1; then
    echo "False-positive process death recovery: $outcome" >&2; exit 1
  fi
  [[ -e "$MOCK_PHASE_B" ]] || {
    echo "Adversarial recovery phase was never exercised: $outcome" >&2; exit 1;
  }
done
# The standalone controller can kill an installed app's process. All unsafe
# device identity failures must be rejected before even Phase A instrumentation.
for fixture in MOCK_SECOND_DEVICE MOCK_DEVICES_FAILED MOCK_STATE_FAILED \
  MOCK_PHYSICAL MOCK_QEMU_UNKNOWN MOCK_QEMU_FAILED MOCK_SDK_FAILED; do
  rm -f "$MOCK_PHASE_A" "$MOCK_PHASE_B"
  if env -u ADB_SERIAL -u ANDROID_SERIAL "$fixture=1" bash "$root/process_death_repro.sh" > "$tmp/$fixture.log" 2>&1; then
    echo "Unsafe process-death target accepted: $fixture" >&2; exit 1
  fi
  [[ ! -e "$MOCK_PHASE_A" && ! -e "$MOCK_PHASE_B" ]] || {
    echo "Process death attempted before exact target preflight: $fixture" >&2; exit 1;
  }
done
rm -f "$MOCK_PHASE_A" "$MOCK_PHASE_B"
if env -u ADB_SERIAL -u ANDROID_SERIAL MOCK_SDK=28 bash "$root/process_death_repro.sh" > "$tmp/old-sdk.log" 2>&1; then
  echo 'Unsupported API28 process-death target accepted' >&2; exit 1
fi
if ADB_SERIAL=WRONG ANDROID_SERIAL=MOCK_USB bash "$root/process_death_repro.sh" > "$tmp/conflict.log" 2>&1; then
  echo 'Contradictory process-death device selectors accepted' >&2; exit 1
fi
if ANDROID_SERIAL=UNLISTED bash "$root/process_death_repro.sh" > "$tmp/unlisted.log" 2>&1; then
  echo 'Unlisted process-death target accepted' >&2; exit 1
fi
[[ ! -e "$MOCK_PHASE_A" && ! -e "$MOCK_PHASE_B" ]] || {
  echo 'Unsafe target crossed process-death preflight' >&2; exit 1;
}
echo 'CHUNKS_DEATH_WITNESS_TEST: exact emulator preflight and all terminal/phase negatives rejected (offline only)'
