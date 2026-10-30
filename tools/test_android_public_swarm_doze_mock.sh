#!/usr/bin/env bash
# Offline fake-adb regression tests for opt-in forced Doze, no phone/emulator.
set -euo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT
mkdir -p "$temp/bin" "$temp/device"
printf 'synthetic torrent fixture\n' > "$temp/torrent"
cat > "$temp/bin/adb" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
: "${MOCK_DIR:?}"
case "${1:-}" in
  devices)
    printf 'List of devices attached\nMOCK_USB\tdevice\n'
    [[ "${MOCK_SECOND_DEVICE:-0}" != 1 ]] || printf 'SECOND\tdevice\n'
    ;;
  get-state)
    [[ "${ANDROID_SERIAL:-}" == "MOCK_USB" ]] || exit 1
    printf 'device\n'
    [[ "${MOCK_GET_STATE_FAILURE:-0}" != 1 ]] || exit 1
    ;;
  install)
    printf 'install\n' >> "$MOCK_DIR/commands"
    if [[ "${MOCK_INSTALL_FAILURE:-0}" == 1 ]]; then
      printf 'Failure [INSTALL_FAILED_INVALID_APK]\n'
    else
      printf 'Success\n'
    fi
    ;;
  exec-in) cat >/dev/null ;;
  exec-out)
    [[ "$*" == *"touch files/doze-on"* ]] || exit 1
    printf 'flag\n' >> "$MOCK_DIR/actions"
    ;;
  logcat)
    if [[ "${2:-}" == "-d" && "${MOCK_READY:-1}" == 1 ]]; then
      printf 'CHUNKS_SWARM_DOZE: ready verified=4194304\n'
      if [[ "${MOCK_LARGE_LOGCAT:-0}" == 1 ]]; then
        sleep 0.02
        for ((i=0; i<20000; i++)); do printf 'irrelevant log line %d\n' "$i"; done
      fi
    fi
    ;;
  shell)
    shift
    if [[ "${1:-}" == "am" && "${2:-}" == "instrument" ]]; then
      printf 'instrument\n' >> "$MOCK_DIR/commands"
      printf 'INSTRUMENTATION_RESULT: stream=OK (1 test)\nOK (1 test)\n'
      [[ "${MOCK_INSTRUMENT_FAILURE:-0}" != 1 ]] || exit 19
      exit 0
    fi
    if [[ "${1:-}" != "dumpsys" ]]; then exit 1; fi
    case "${2:-} ${3:-} ${4:-}" in
      'deviceidle whitelist ') printf 'no allowlisted clients\n' ;;
      'battery unplug ')
        printf 'unplug\n' >> "$MOCK_DIR/actions"
        printf 'unplugged\n' > "$MOCK_DIR/battery"
        ;;
      'battery reset ')
        printf 'reset\n' >> "$MOCK_DIR/actions"
        [[ "${MOCK_RESET_FAILURE:-0}" != 1 ]] || exit 1
        printf 'normal\n' > "$MOCK_DIR/battery"
        ;;
      'deviceidle force-idle ')
        printf 'force-idle\n' >> "$MOCK_DIR/actions"
        if [[ "${MOCK_DEEP_NOT_IDLE:-0}" != 1 ]]; then printf 'IDLE\n' > "$MOCK_DIR/deep"; fi
        ;;
      'deviceidle unforce ')
        printf 'unforce\n' >> "$MOCK_DIR/actions"
        printf 'ACTIVE\n' > "$MOCK_DIR/deep"
        ;;
      'deviceidle get deep') cat "$MOCK_DIR/deep" ;;
      *) echo "Unknown mocked device operation: $*" >&2; exit 1 ;;
    esac
    ;;
  *) echo "Unexpected mocked adb call: $*" >&2; exit 1 ;;
esac
MOCK
chmod +x "$temp/bin/adb"
export PATH="$temp/bin:$PATH" MOCK_DIR="$temp/device"
export CHUNKS_PUBLIC_SWARM_TORRENT="$temp/torrent" CHUNKS_PUBLIC_SWARM_SECONDS=30
reset() {
  printf 'normal\n' > "$MOCK_DIR/battery"
  printf 'ACTIVE\n' > "$MOCK_DIR/deep"
  : > "$MOCK_DIR/actions"
  : > "$MOCK_DIR/commands"
}
bash -n "$repo/android_public_swarm_doze.sh"
bash -n "$repo/test_android_public_swarm_doze_mock.sh"
reset
env -u ADB_SERIAL -u ANDROID_SERIAL MOCK_READY=1 bash "$repo/android_public_swarm_doze.sh" > "$temp/ok.log"
[[ "$(cat "$MOCK_DIR/battery")" == normal && "$(cat "$MOCK_DIR/deep")" == ACTIVE ]]
[[ "$(cat "$MOCK_DIR/actions")" == $'unplug\nforce-idle\nflag\nunforce\nreset' ]]
grep -q 'deep_state=IDLE' "$temp/ok.log"

# A large logcat dump may contain ready near the start. Do not SIGPIPE adb.
reset
env -u ADB_SERIAL -u ANDROID_SERIAL MOCK_LARGE_LOGCAT=1 bash "$repo/android_public_swarm_doze.sh" > "$temp/large.log"
[[ "$(cat "$MOCK_DIR/battery")" == normal && "$(cat "$MOCK_DIR/deep")" == ACTIVE ]]
[[ "$(cat "$MOCK_DIR/actions")" == $'unplug\nforce-idle\nflag\nunforce\nreset' ]]
grep -q 'deep_state=IDLE' "$temp/large.log"

# An adb install may print Failure on a successful command exit; refuse
# before altering battery or idle state.
reset
if env -u ADB_SERIAL -u ANDROID_SERIAL MOCK_INSTALL_FAILURE=1 bash "$repo/android_public_swarm_doze.sh" > "$temp/install-failed.log" 2>&1; then
  echo 'False-positive Doze install accepted' >&2; exit 1
fi
[[ ! -s "$MOCK_DIR/actions" ]]
[[ "$(cat "$MOCK_DIR/battery")" == normal && "$(cat "$MOCK_DIR/deep")" == ACTIVE ]]

# Even a plausible OK (1 test) cannot override failed instrumentation exit.
reset
if env -u ADB_SERIAL -u ANDROID_SERIAL MOCK_INSTRUMENT_FAILURE=1 bash "$repo/android_public_swarm_doze.sh" > "$temp/instrument-failed.log" 2>&1; then
  echo 'False-positive Doze instrumentation accepted' >&2; exit 1
fi
[[ "$(cat "$MOCK_DIR/battery")" == normal && "$(cat "$MOCK_DIR/deep")" == ACTIVE ]]
grep -q 'CHUNKS_SWARM_DOZE deep_state=IDLE' "$temp/instrument-failed.log"

# When peer warmup never reaches ready, no synthetic battery/idle mutation.
reset
if env -u ADB_SERIAL -u ANDROID_SERIAL MOCK_READY=0 bash "$repo/android_public_swarm_doze.sh" > "$temp/unready.log" 2>&1; then
  echo 'Not-ready Doze workflow was accepted' >&2; exit 1
fi
[[ ! -s "$MOCK_DIR/actions" ]]
[[ "$(cat "$MOCK_DIR/battery")" == normal && "$(cat "$MOCK_DIR/deep")" == ACTIVE ]]

# If idle isn't actually entered, refuse and still compensate battery/idle.
reset
if env -u ADB_SERIAL -u ANDROID_SERIAL MOCK_DEEP_NOT_IDLE=1 bash "$repo/android_public_swarm_doze.sh" > "$temp/noidle.log" 2>&1; then
  echo 'Non-idle run was accepted' >&2; exit 1
fi
[[ "$(cat "$MOCK_DIR/battery")" == normal && "$(cat "$MOCK_DIR/deep")" == ACTIVE ]]
[[ "$(cat "$MOCK_DIR/actions")" == $'unplug\nforce-idle\nunforce\nreset' ]]

# A failed restore cannot be converted into a PASS.
reset
if env -u ADB_SERIAL -u ANDROID_SERIAL MOCK_RESET_FAILURE=1 bash "$repo/android_public_swarm_doze.sh" > "$temp/restore-fail.log" 2>&1; then
  echo 'Failed battery reset was accepted' >&2; exit 1
fi
grep -q 'failed to reset simulated battery' "$temp/restore-fail.log"
[[ "$(cat "$MOCK_DIR/battery")" == unplugged ]]

# Refuse multiple authorized devices without a specific identity.
reset
if env -u ADB_SERIAL -u ANDROID_SERIAL MOCK_SECOND_DEVICE=1 bash "$repo/android_public_swarm_doze.sh" > "$temp/multi.log" 2>&1; then
  echo 'Ambiguous adb selection was accepted' >&2; exit 1
fi
[[ ! -s "$MOCK_DIR/actions" ]]
reset
if ADB_SERIAL=UNREACHABLE bash "$repo/android_public_swarm_doze.sh" > "$temp/wrong.log" 2>&1; then
  echo 'Unavailable explicit adb target was accepted' >&2; exit 1
fi
[[ ! -s "$MOCK_DIR/actions" ]]

# A failed query may still print the expected state. Refuse before installation,
# instrumentation or battery/idle changes, even with that plausible output.
reset
if ADB_SERIAL=MOCK_USB MOCK_GET_STATE_FAILURE=1 bash "$repo/android_public_swarm_doze.sh" > "$temp/state-failed.log" 2>&1; then
  echo 'Failed adb get-state with plausible output was accepted' >&2; exit 1
fi
grep -q 'selected adb device unavailable' "$temp/state-failed.log"
[[ ! -s "$MOCK_DIR/commands" && ! -s "$MOCK_DIR/actions" ]]
[[ "$(cat "$MOCK_DIR/battery")" == normal && "$(cat "$MOCK_DIR/deep")" == ACTIVE ]]

echo 'CHUNKS_DOZE_HARNESS_TEST: selected target, success, no ready, failed state query, failed idle, failed restore, multi-device, install and failed-instrumentation negative controls passed'
