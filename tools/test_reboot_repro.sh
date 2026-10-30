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
  logcat)
    if [[ "${2:-}" == "-d" ]]; then
      printf 'CHUNKS_REBOOT armed\n'
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
        [[ "${2:-}" == "sys.boot_completed" ]] || exit 1
        printf '1\n'
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
            command_failure) printf 'OK (1 test)\n'; exit 25 ;;
            *) echo 'Missing mock bad kind' >&2; exit 30 ;;
          esac
        else
          printf 'OK (1 test)\n'
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
  for kind in false_ok skip fail duplicate command_failure; do
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

echo "CHUNKS_REBOOT_WITNESS_TEST: authentic reboot, no-reboot/invalid-ID, and ten false instrumentation receipts correctly rejected"
