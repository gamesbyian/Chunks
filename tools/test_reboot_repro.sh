#!/usr/bin/env bash
# Offline fake-adb negative controls for the opt-in emulator reboot witness.
# No APKs, emulator, private files, or Android connection needed.
set -euo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT
mkdir -p "$temp/bin"
cat > "$temp/bin/adb" <<'MOCK_ADB'
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
    if [[ "${2:-}" == "-d" ]]; then
      printf 'CHUNKS_REBOOT armed\n'
      if [[ -e "${MOCK_PHASE_B_FLAG:?}" ]]; then
        if [[ "${MOCK_NO_RESEED:-0}" != 1 ]]; then
          printf 'CHUNKS_REBOOT restored_stopped=true no_autostart=true sha_unchanged=true reseeded_bytes=96000 removed_keep_files=true\n'
          [[ "${MOCK_DUPLICATE_RESEED:-0}" != 1 ]] ||
            printf 'CHUNKS_REBOOT restored_stopped=true no_autostart=true sha_unchanged=true reseeded_bytes=96000 removed_keep_files=true\n'
        fi
      fi
    fi
    ;;
  reboot)
    : > "${MOCK_REBOOT_FLAG:?}"
    ;;
  wait-for-device) ;;
  shell)
    shift
    case "${1:-}" in
      cat)
        [[ "${2:-}" == "/proc/sys/kernel/random/boot_id" ]] || exit 1
        if [[ "${MOCK_BOOT_INVALID:-0}" == 1 ]]; then
          printf 'unavailable\n'
        elif [[ -e "${MOCK_REBOOT_FLAG:?}" && "${MOCK_REBOOT_STUCK:-0}" != 1 ]]; then
          printf 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\n'
        else
          printf '11111111-2222-3333-4444-555555555555\n'
        fi
        ;;
      getprop)
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
          sys.boot_completed) printf '1\n' ;;
          *) exit 1 ;;
        esac
        ;;
      pidof) exit 1 ;;
      am)
        [[ "${2:-}" == "instrument" ]] || exit 1
        phase=a
        if [[ " $* " == *phaseB_nothingStartsAfterBootAndRestoreIsStopped* ]]; then
          phase=b
          : > "${MOCK_PHASE_B_FLAG:?}"
        fi
        if [[ "${MOCK_BAD_PHASE:-}" == "$phase" ]]; then
          case "${MOCK_BAD_KIND:-}" in
            false_ok) printf 'INSTRUMENTATION_RESULT: stream=OK (1 test)\n' ;;
            skip) printf 'OK (1 test)\nINSTRUMENTATION_STATUS_CODE: -3\n' ;;
            fail) printf 'OK (1 test)\nINSTRUMENTATION_STATUS_CODE: -1\n' ;;
            duplicate) printf 'OK (1 test)\nOK (1 test)\n' ;;
            command_failure) printf 'OK (1 test)\nINSTRUMENTATION_CODE: -1\n'; exit 25 ;;
            terminal_missing) printf 'OK (1 test)\n' ;;
            terminal_failed) printf 'OK (1 test)\nINSTRUMENTATION_CODE: 0\n' ;;
            terminal_duplicate) printf 'OK (1 test)\nINSTRUMENTATION_CODE: -1\nINSTRUMENTATION_CODE: -1\n' ;;
            terminal_mixed) printf 'OK (1 test)\nINSTRUMENTATION_CODE: -1\nINSTRUMENTATION_CODE: 0\n' ;;
            *) echo 'Missing mock bad kind' >&2; exit 30 ;;
          esac
        else
          printf 'OK (1 test)\nINSTRUMENTATION_CODE: -1\n'
        fi
        ;;
      *) exit 1 ;;
    esac
    ;;
  *) exit 1 ;;
esac
MOCK_ADB
cat > "$temp/bin/sleep" <<'MOCK_SLEEP'
#!/usr/bin/env bash
# Eliminate the 120 * 2-second negative polling delay in this offline test.
exit 0
MOCK_SLEEP
chmod +x "$temp/bin/adb" "$temp/bin/sleep"
export PATH="$temp/bin:$PATH" MOCK_REBOOT_FLAG="$temp/rebooted" MOCK_PHASE_B_FLAG="$temp/phase-b"

bash -n "$repo/tools/reboot_repro.sh"
bash -n "$repo/tools/test_reboot_repro.sh"

# Reboot is device-wide. Reject unreliable target identity BEFORE phase A.
for negative in MOCK_DEVICES_FAILED MOCK_SECOND_DEVICE MOCK_STATE_FAILED \
  MOCK_QEMU_FAILED MOCK_QEMU_UNKNOWN MOCK_SDK_FAILED MOCK_PHYSICAL; do
  rm -f "$MOCK_REBOOT_FLAG" "$MOCK_PHASE_B_FLAG"
  if env -u ADB_SERIAL -u ANDROID_SERIAL "$negative=1" \
      bash "$repo/tools/reboot_repro.sh" > "$temp/$negative.log" 2>&1; then
    echo "Unsafe Android reboot target accepted: $negative" >&2; exit 1
  fi
  [[ ! -e "$MOCK_REBOOT_FLAG" && ! -e "$MOCK_PHASE_B_FLAG" ]] || {
    echo "Reboot or recovery instrumentation ran before $negative preflight" >&2; exit 1
  }
done
rm -f "$MOCK_REBOOT_FLAG" "$MOCK_PHASE_B_FLAG"
if env -u ADB_SERIAL -u ANDROID_SERIAL MOCK_SDK=28 \
    bash "$repo/tools/reboot_repro.sh" > "$temp/old-api.log" 2>&1; then
  echo 'Unsupported Android API reboot was accepted' >&2; exit 1
fi
[[ ! -e "$MOCK_REBOOT_FLAG" ]]
if ANDROID_SERIAL=UNLISTED bash "$repo/tools/reboot_repro.sh" > "$temp/bad-serial.log" 2>&1; then
  echo 'Unlisted explicit ADB target was accepted' >&2; exit 1
fi
[[ ! -e "$MOCK_REBOOT_FLAG" ]]
if ADB_SERIAL=OTHER ANDROID_SERIAL=MOCK_USB \
    bash "$repo/tools/reboot_repro.sh" > "$temp/conflict.log" 2>&1; then
  echo 'Conflicting explicit ADB selectors were accepted' >&2; exit 1
fi
[[ ! -e "$MOCK_REBOOT_FLAG" ]]
ANDROID_SERIAL=MOCK_USB CHUNKS_ALLOW_PHYSICAL_REBOOT_TEST=1 MOCK_PHYSICAL=1 \
    bash "$repo/tools/reboot_repro.sh" > "$temp/physical-opt-in.log"
[[ -e "$MOCK_REBOOT_FLAG" && -e "$MOCK_PHASE_B_FLAG" ]]
rm -f "$MOCK_REBOOT_FLAG" "$MOCK_PHASE_B_FLAG"

out="$(bash "$repo/tools/reboot_repro.sh")"
[[ "$out" == *"kernel_boot_id_changed=true"* ]]
[[ -e "$MOCK_REBOOT_FLAG" && -e "$MOCK_PHASE_B_FLAG" ]]
rm "$MOCK_REBOOT_FLAG" "$MOCK_PHASE_B_FLAG"

if MOCK_REBOOT_STUCK=1 bash "$repo/tools/reboot_repro.sh" >"$temp/no-reboot.log" 2>&1; then
  echo "A reboot with unchanged kernel boot ID was accepted" >&2
  exit 1
fi
grep -q 'no observed new kernel boot' "$temp/no-reboot.log"
[[ ! -e "$MOCK_PHASE_B_FLAG" ]]
rm -f "$MOCK_REBOOT_FLAG" "$MOCK_PHASE_B_FLAG"

if MOCK_BOOT_INVALID=1 bash "$repo/tools/reboot_repro.sh" >"$temp/invalid.log" 2>&1; then
  echo "A missing kernel boot ID was accepted" >&2
  exit 1
fi
grep -q 'no valid original kernel boot ID' "$temp/invalid.log"
[[ ! -e "$MOCK_REBOOT_FLAG" && ! -e "$MOCK_PHASE_B_FLAG" ]]

# A plausible but false "OK (1 test)" receipt may appear in an
# instrumentation stream or alongside a skip/failure status. Refuse both
# phases independently. Phase A failures must never reach the reboot call.
for phase in a b; do
  for kind in false_ok skip fail duplicate command_failure \
    terminal_missing terminal_failed terminal_duplicate terminal_mixed; do
    rm -f "$MOCK_REBOOT_FLAG" "$MOCK_PHASE_B_FLAG"
    if MOCK_BAD_PHASE="$phase" MOCK_BAD_KIND="$kind" bash "$repo/tools/reboot_repro.sh" >"$temp/$phase-$kind.log" 2>&1; then
      echo "False reboot acceptance for phase=$phase kind=$kind" >&2; exit 1
    fi
    if [[ "$phase" == a ]]; then
      [[ ! -e "$MOCK_REBOOT_FLAG" && ! -e "$MOCK_PHASE_B_FLAG" ]] || {
        echo "Reboot or phase B ran after failed phase A: $kind" >&2; exit 1;
      }
    else
      [[ -e "$MOCK_REBOOT_FLAG" && -e "$MOCK_PHASE_B_FLAG" ]] || {
        echo "Phase B negative fixture was not exercised: $kind" >&2; exit 1;
      }
    fi
  done
done

# A successful JUnit code cannot stand in for the exact real peer counter/
# independent payload receipt emitted after the *actual* boot identity change.
for bad in MOCK_NO_RESEED MOCK_DUPLICATE_RESEED; do
  rm -f "$MOCK_REBOOT_FLAG" "$MOCK_PHASE_B_FLAG"
  if env -u ADB_SERIAL -u ANDROID_SERIAL "$bad=1" bash "$repo/tools/reboot_repro.sh" >"$temp/$bad.log" 2>&1; then
    echo "False post-reboot byte evidence accepted: $bad" >&2; exit 1
  fi
  [[ -e "$MOCK_REBOOT_FLAG" && -e "$MOCK_PHASE_B_FLAG" ]] || {
    echo "Phase B did not run for post-reboot receipt negative: $bad" >&2; exit 1
  }
done

echo "CHUNKS_REBOOT_WITNESS_TEST: exact ADB target, real boot, eighteen false instrumentation receipts, and two false post-boot seeding receipts rejected"
