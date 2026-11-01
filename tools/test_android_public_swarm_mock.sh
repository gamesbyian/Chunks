#!/usr/bin/env bash
# Offline false-success controls for the baseline manual public-peer experiment.
# No SDK, peer network or Android device is accessed.
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd -P)"
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT
mkdir -p "$temp/bin" "$temp/device"
printf 'synthetic public torrent\n' > "$temp/torrent"
cat > "$temp/bin/adb" <<'ADB_MOCK'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
  devices)
    printf 'List of devices attached\nMOCK_USB\tdevice\n'
    if [[ "${MOCK_SECOND_DEVICE:-0}" == 1 ]]; then printf 'SECOND\tdevice\n'; fi
    [[ "${MOCK_DEVICE_INVENTORY_FAILURE:-0}" != 1 ]] || exit 21
    ;;
  get-state)
    [[ "${ANDROID_SERIAL:-}" == MOCK_USB ]] || exit 22
    printf 'device\n'
    [[ "${MOCK_DEVICE_STATE_FAILURE:-0}" != 1 ]] || exit 23
    ;;
  install)
    printf 'install\n' >> "$MOCK_DIR/commands"
    if [[ "${MOCK_INSTALL_FAILURE:-0}" == 1 ]]; then
      printf 'Failure [INSTALL_FAILED_INVALID_APK]\n'
    else
      printf 'Performing Streamed Install\nSuccess\n'
    fi
    ;;
  exec-in) cat >/dev/null ;;
  logcat)
    if [[ "${2:-}" == -d ]]; then
      if [[ "${MOCK_MISSING_NATIVE:-0}" != 1 ]]; then
        amount=4194304
        [[ "${MOCK_SHORT_NATIVE:-0}" != 1 ]] || amount=1024
        receipt="CHUNKS_ANDROID_SWARM: VERIFIED hash=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa verified=$amount target=4194304 maxPeers=2 trackers=LocalTrackerSummary(total=1, working=1, updating=0, failing=0) seconds=5"
        printf '%s\n' "$receipt"
        [[ "${MOCK_DUPLICATE_NATIVE:-0}" != 1 ]] || printf '%s\n' "$receipt"
      fi
      [[ "${MOCK_INCONCLUSIVE_NATIVE:-0}" != 1 ]] || echo 'CHUNKS_ANDROID_SWARM: INCONCLUSIVE no peers'
      [[ "${MOCK_LOGCAT_FAILURE:-0}" != 1 ]] || exit 27
    fi
    ;;
  shell)
    shift
    if [[ "${1:-} ${2:-}" == 'am instrument' ]]; then
      printf 'instrument\n' >> "$MOCK_DIR/commands"
      printf 'INSTRUMENTATION_RESULT: stream=OK (1 test)\n'
      if [[ "${MOCK_PREFIX_ONLY_OK:-0}" == 1 ]]; then
        echo 'OK (1 test) but not complete'
      else
        echo 'OK (1 test)'
      fi
      [[ "${MOCK_DUPLICATE_OK:-0}" != 1 ]] || echo 'OK (1 test)'
      [[ "${MOCK_SKIPPED_RESULT:-0}" != 1 ]] || echo 'INSTRUMENTATION_STATUS_CODE: -3'
      [[ "${MOCK_FAILURE_RESULT:-0}" != 1 ]] || echo 'FAILURES!!!'
      [[ "${MOCK_INSTRUMENT_FAILURE:-0}" != 1 ]] || exit 24
    else
      echo "unexpected device operation: $*" >&2; exit 1
    fi
    ;;
  *) echo "unexpected adb operation: $*" >&2; exit 1 ;;
esac
ADB_MOCK
chmod +x "$temp/bin/adb"
export PATH="$temp/bin:$PATH" MOCK_DIR="$temp/device"
export CHUNKS_PUBLIC_SWARM_TORRENT="$temp/torrent"
export CHUNKS_PUBLIC_SWARM_BUDGET_BYTES=4194304 CHUNKS_PUBLIC_SWARM_SECONDS=30
bash -n "$root/android_public_swarm.sh"
bash -n "$root/test_android_public_swarm_mock.sh"

reset() { : > "$MOCK_DIR/commands"; }
reset
env -u ADB_SERIAL -u ANDROID_SERIAL bash "$root/android_public_swarm.sh" > "$temp/passed.log"
[[ "$(grep -c '^install$' "$MOCK_DIR/commands")" -eq 2 ]]
[[ "$(grep -c '^instrument$' "$MOCK_DIR/commands")" -eq 1 ]]

refuse() {
  reset
  if env -u ADB_SERIAL -u ANDROID_SERIAL "$1=1" bash "$root/android_public_swarm.sh" > "$temp/$1.log" 2>&1; then
    echo "False-positive baseline swarm acceptance: $1" >&2; exit 1
  fi
  if [[ "$1" != MOCK_INSTRUMENT_FAILURE ]]; then
    [[ "$(grep -c '^instrument$' "$MOCK_DIR/commands" || true)" -eq 0 ]]
  fi
}
refuse MOCK_DEVICE_INVENTORY_FAILURE
refuse MOCK_DEVICE_STATE_FAILURE
refuse MOCK_SECOND_DEVICE
refuse MOCK_INSTALL_FAILURE
refuse MOCK_INSTRUMENT_FAILURE
# Once the real instrumented test was invoked, a forged/partial success must
# still be red. Do not confuse this with a pre-install device refusal.
for negative in MOCK_MISSING_NATIVE MOCK_SHORT_NATIVE MOCK_DUPLICATE_NATIVE \
                MOCK_INCONCLUSIVE_NATIVE MOCK_LOGCAT_FAILURE MOCK_PREFIX_ONLY_OK \
                MOCK_DUPLICATE_OK MOCK_SKIPPED_RESULT MOCK_FAILURE_RESULT; do
  reset
  if env -u ADB_SERIAL -u ANDROID_SERIAL "$negative=1" bash "$root/android_public_swarm.sh" > "$temp/$negative.log" 2>&1; then
    echo "False-positive public-peer runtime receipt accepted: $negative" >&2; exit 1
  fi
  [[ "$(grep -c '^instrument bash "$root/android_public_swarm.sh" > "$temp/wrong.log" 2>&1; then
  echo 'Unreachable explicit target accepted' >&2; exit 1
fi
[[ ! -s "$MOCK_DIR/commands" ]]
echo 'CHUNKS_ANDROID_SWARM_MOCK: successful single target plus failed inventory/state, second device, install Failure, failed instrumentation, bogus native bytes/status and bad serial refused'
 "$MOCK_DIR/commands" || true)" -eq 1 ]]
done
reset
if ADB_SERIAL=WRONG bash "$root/android_public_swarm.sh" > "$temp/wrong.log" 2>&1; then
  echo 'Unreachable explicit target accepted' >&2; exit 1
fi
[[ ! -s "$MOCK_DIR/commands" ]]
echo 'CHUNKS_ANDROID_SWARM_MOCK: successful single target plus failed inventory/state, second device, install Failure, failed instrumentation and bad serial refused'
