#!/usr/bin/env bash
# Pure fake-adb controls for the opt-in real-peer interruption runner.
# This never starts an emulator, downloads payloads or changes Android state.
set -euo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin"
printf 'disposable metainfo\n' > "$tmp/public.torrent"
cat > "$tmp/bin/adb" <<'ADB_MOCK'
#!/usr/bin/env bash
set -euo pipefail
: "${MOCK_DIR:?}"
case "${1:-}" in
  devices)
    printf 'List of devices attached\nMOCK_USB\tdevice\n'
    [[ "${MOCK_MULTI:-0}" != 1 ]] || printf 'ANOTHER\tdevice\n'
    [[ "${MOCK_DEVICES_FAILURE:-0}" != 1 ]] || exit 28
    ;;
  get-state)
    [[ "${ANDROID_SERIAL:-}" == MOCK_USB ]] || exit 1
    printf 'device\n'
    [[ "${MOCK_GET_STATE_FAILURE:-0}" != 1 ]] || exit 1
    ;;
  install)
    printf 'install\n' >> "$MOCK_DIR/commands"
    if [[ "${MOCK_INSTALL_BAD:-0}" == 1 ]]; then
      printf 'Failure [INSTALL_FAILED_INVALID_APK]\n'
    else
      printf 'Performing Streamed Install\nSuccess\n'
    fi
    ;;
  exec-in) cat >/dev/null ;;
  logcat)
    if [[ "${2:-}" == "-d" ]]; then
      if [[ ! -e "$MOCK_DIR/b" ]]; then
        if [[ "${MOCK_ARM_MISSING:-0}" != 1 ]]; then
          before=8388608; wanted=16777216
          [[ "${MOCK_A_TOO_SMALL:-0}" != 1 ]] || before=100
          [[ "${MOCK_A_COMPLETE:-0}" != 1 ]] || wanted=8388608
          receipt="CHUNKS_SWARM_INTERRUPT: armed hash=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa verified_before_kill=$before wanted=$wanted"
          printf '10-31 12:00:00 I/ChunksSwarm: %s\n' "$receipt"
          [[ "${MOCK_DUPLICATE_A:-0}" != 1 ]] || printf '10-31 12:00:00 I/ChunksSwarm: %s\n' "$receipt"
        fi
        if [[ "${MOCK_LONG_LOG:-0}" == 1 ]]; then
          /bin/sleep 0.02
          for ((i=0; i<20000; i++)); do printf 'irrelevant fake log %d\n' "$i"; done
        fi
        [[ "${MOCK_A_INCONCLUSIVE:-0}" != 1 ]] || echo "CHUNKS_SWARM_INTERRUPT: INCONCLUSIVE before kill"
      else
        if [[ "${MOCK_B_MISSING:-0}" != 1 ]]; then
          recovered=4194304; before=8388608; continued=12000000
          [[ "${MOCK_B_BAD_BEFORE:-0}" != 1 ]] || before=7355222
          [[ "${MOCK_B_NO_GROWTH:-0}" != 1 ]] || continued=8388608
          [[ "${MOCK_B_BAD_RECOVERED:-0}" != 1 ]] || recovered=15000000
          [[ "${MOCK_B_OVER_WANTED:-0}" != 1 ]] || continued=18000000
          receipt="CHUNKS_SWARM_INTERRUPT: VERIFIED restored_stopped=true recovered=$recovered before=$before continued_to=$continued"
          printf '10-31 12:00:00 I/ChunksSwarm: %s\n' "$receipt"
          [[ "${MOCK_DUPLICATE_B:-0}" != 1 ]] || printf '10-31 12:00:00 I/ChunksSwarm: %s\n' "$receipt"
        fi
        [[ "${MOCK_B_INCONCLUSIVE:-0}" != 1 ]] || echo "CHUNKS_SWARM_INTERRUPT: INCONCLUSIVE after restore"
      fi
      [[ "${MOCK_LOGCAT_FAILURE:-0}" != 1 ]] || exit 29
    fi
    ;;
  shell)
    shift
    if [[ "${1:-}" == "pidof" ]]; then
      [[ "${MOCK_ALIVE:-0}" == 1 ]] && exit 0 || exit 1
    fi
    if [[ "${1:-}" == am && "${2:-}" == instrument ]]; then
      printf 'instrument\n' >> "$MOCK_DIR/commands"
      if [[ " $* " == *' chunksInterruptPhase a '* ]]; then
        if [[ "${MOCK_NO_CRASH:-0}" != 1 ]]; then printf 'Process crashed\n'; fi
        exit 0
      fi
      if [[ " $* " == *' chunksInterruptPhase b '* ]]; then
        : > "$MOCK_DIR/b"
        case "${MOCK_B_RESULT:-ok}" in
          skip) printf 'INSTRUMENTATION_STATUS_CODE: -3\nOK (1 test)\n' ;;
          fail) printf 'INSTRUMENTATION_STATUS_CODE: -1\nOK (1 test)\n' ;;
          unsupported_fail) printf 'INSTRUMENTATION_STATUS_CODE: -7\nOK (1 test)\n' ;;
          false_ok) printf 'INSTRUMENTATION_RESULT: stream=OK (1 test)\n' ;;
          duplicate) printf 'OK (1 test)\nOK (1 test)\n' ;;
          command_failure) printf 'OK (1 test)\n'; exit 26 ;;
          ok) printf 'INSTRUMENTATION_RESULT: stream=OK\nOK (1 test)\n' ;;
        esac
        exit 0
      fi
    fi
    echo 'Unexpected shell command' >&2; exit 1
    ;;
  *) echo "Unexpected adb command: $*" >&2; exit 1 ;;
esac
ADB_MOCK
cat > "$tmp/bin/sleep" <<'MOCK_SLEEP'
#!/usr/bin/env bash
exit 0
MOCK_SLEEP
chmod +x "$tmp/bin/"*
export PATH="$tmp/bin:$PATH" MOCK_DIR="$tmp" CHUNKS_PUBLIC_SWARM_TORRENT="$tmp/public.torrent"
export CHUNKS_PUBLIC_SWARM_BUDGET_BYTES=4194304 CHUNKS_PUBLIC_SWARM_SECONDS=30
bash -n "$repo/android_public_swarm_interrupt.sh"
bash -n "$repo/test_android_public_swarm_interrupt_mock.sh"

run_case() {
  rm -f "$tmp/b"
  env -u ADB_SERIAL -u ANDROID_SERIAL "$@" bash "$repo/android_public_swarm_interrupt.sh"
}
run_case MOCK_LONG_LOG=1 >"$tmp/success.log"
grep -q 'VERIFIED restored_stopped=true' "$tmp/success.log"
for fixture in MOCK_ARM_MISSING MOCK_NO_CRASH MOCK_ALIVE MOCK_INSTALL_BAD MOCK_MULTI \
               MOCK_DUPLICATE_A MOCK_A_TOO_SMALL MOCK_A_COMPLETE MOCK_A_INCONCLUSIVE \
               MOCK_DUPLICATE_B MOCK_B_MISSING MOCK_B_BAD_BEFORE MOCK_B_NO_GROWTH \
               MOCK_B_BAD_RECOVERED MOCK_B_OVER_WANTED MOCK_B_INCONCLUSIVE MOCK_LOGCAT_FAILURE; do
  if run_case "$fixture=1" >"$tmp/$fixture.log" 2>&1; then
    echo "False-positive interruption acceptance: $fixture" >&2
    exit 1
  fi
done
for fixture in skip fail unsupported_fail false_ok duplicate command_failure; do
  if run_case "MOCK_B_RESULT=$fixture" >"$tmp/$fixture.log" 2>&1; then
    echo "False-positive phase B acceptance: $fixture" >&2
    exit 1
  fi
done
if ADB_SERIAL=WRONG bash "$repo/android_public_swarm_interrupt.sh" >"$tmp/wrong.log" 2>&1; then
  echo "Unavailable explicit ADB serial was accepted" >&2
  exit 1
fi
# The entire adb devices command must exit successfully even if its
# captured output contains a plausible sole attached USB target.
: > "$MOCK_DIR/commands"
rm -f "$tmp/b"
if run_case MOCK_DEVICES_FAILURE=1 >"$tmp/inventory-failed.log" 2>&1; then
  echo 'Failed adb devices with plausible inventory was accepted' >&2; exit 1
fi
grep -q 'ADB device inventory failed' "$tmp/inventory-failed.log"
[[ ! -s "$MOCK_DIR/commands" && ! -e "$tmp/b" ]]

# The device query's status must be successful even when stdout looks correct.
: > "$MOCK_DIR/commands"
rm -f "$tmp/b"
if run_case MOCK_GET_STATE_FAILURE=1 >"$tmp/state-failed.log" 2>&1; then
  echo 'Failed adb get-state with plausible output was accepted' >&2; exit 1
fi
grep -q 'Selected Android device unavailable' "$tmp/state-failed.log"
[[ ! -s "$MOCK_DIR/commands" && ! -e "$tmp/b" ]]
echo 'CHUNKS_INTERRUPT_HARNESS_TEST: large logs, exact cross-phase positive native bytes, missing/duplicate/insufficient/contradictory receipts, failed inventory/state and false-success phase B controls passed'
