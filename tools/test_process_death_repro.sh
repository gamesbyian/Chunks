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
    if [[ "${1:-}" == pidof ]]; then
      [[ "${MOCK_PROCESS_ALIVE:-0}" == 1 ]]
      exit
    fi
    if [[ "${1:-} ${2:-}" == 'am instrument' ]]; then
      if [[ " $* " == *phaseA_seedUnderServiceThenDieWithoutWarning* ]]; then
        if [[ "${MOCK_NO_CRASH:-0}" != 1 ]]; then printf 'Process crashed\n'; fi
        exit 0
      fi
      if [[ " $* " == *phaseB_restoredStoppedWithBytesIntactAndRechecks* ]]; then
        : > "$MOCK_PHASE_B"
        case "${MOCK_PHASE_B_OUTCOME:-ok}" in
          ok) printf 'OK (1 test)\n' ;;
          false_ok) printf 'INSTRUMENTATION_RESULT: stream=OK (1 test)\n' ;;
          skip) printf 'OK (1 test)\nINSTRUMENTATION_STATUS_CODE: -3\n' ;;
          fail) printf 'OK (1 test)\nINSTRUMENTATION_STATUS_CODE: -1\n' ;;
          other_negative) printf 'OK (1 test)\nINSTRUMENTATION_STATUS_CODE: -7\n' ;;
          duplicate) printf 'OK (1 test)\nOK (1 test)\n' ;;
          command_failure) printf 'OK (1 test)\n'; exit 25 ;;
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
export PATH="$tmp/bin:$PATH" MOCK_PHASE_B="$tmp/phase-b"
bash -n "$root/process_death_repro.sh"
bash -n "$root/test_process_death_repro.sh"
rm -f "$MOCK_PHASE_B"
bash "$root/process_death_repro.sh" > "$tmp/positive.log"
[[ -e "$MOCK_PHASE_B" ]]
grep -q 'OK (1 test)' "$tmp/positive.log"

for fixture in MOCK_ARM_MISSING MOCK_NO_CRASH MOCK_PROCESS_ALIVE MOCK_LOG_FAILURE; do
  rm -f "$MOCK_PHASE_B"
  if env "$fixture=1" bash "$root/process_death_repro.sh" > "$tmp/$fixture.log" 2>&1; then
    echo "False-positive process death acceptance: $fixture" >&2; exit 1
  fi
  [[ ! -e "$MOCK_PHASE_B" ]] || {
    echo "Recovery phase was entered despite broken phase A: $fixture" >&2; exit 1;
  }
done
for outcome in false_ok skip fail other_negative duplicate command_failure; do
  rm -f "$MOCK_PHASE_B"
  if env "MOCK_PHASE_B_OUTCOME=$outcome" bash "$root/process_death_repro.sh" > "$tmp/$outcome.log" 2>&1; then
    echo "False-positive process death recovery: $outcome" >&2; exit 1
  fi
  [[ -e "$MOCK_PHASE_B" ]] || {
    echo "Adversarial recovery phase was never exercised: $outcome" >&2; exit 1;
  }
done
echo 'CHUNKS_DEATH_WITNESS_TEST: valid recovery and 10 failed ADB, arm, crash and forged/skipped recovery receipts correctly classified'
