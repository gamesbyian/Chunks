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
      printf 'CHUNKS_ANDROID_SWARM: VERIFIED downloaded=4194304\n'
    fi
    ;;
  shell)
    shift
    if [[ "${1:-} ${2:-}" == 'am instrument' ]]; then
      printf 'instrument\n' >> "$MOCK_DIR/commands"
      printf 'INSTRUMENTATION_RESULT: stream=OK (1 test)\nOK (1 test)\n'
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
reset
if ADB_SERIAL=WRONG bash "$root/android_public_swarm.sh" > "$temp/wrong.log" 2>&1; then
  echo 'Unreachable explicit target accepted' >&2; exit 1
fi
[[ ! -s "$MOCK_DIR/commands" ]]
echo 'CHUNKS_ANDROID_SWARM_MOCK: successful single target plus failed inventory/state, second device, install Failure, failed instrumentation and bad serial refused'
