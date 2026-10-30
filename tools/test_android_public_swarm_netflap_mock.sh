#!/usr/bin/env bash
# Safe end-to-end shell mock of the opt-in netflap controller. No real adb.
set -euo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT
mkdir -p "$temp/bin" "$temp/fixture"
printf 'synthetic torrent fixture\n' > "$temp/torrent"
cat > "$temp/bin/adb" <<'MOCK_ADB'
#!/usr/bin/env bash
set -euo pipefail
case "$*" in
  devices)
    printf 'List of devices attached\nMOCK_USB\tdevice\n'
    if [[ "${MOCK_SECOND_DEVICE:-0}" == 1 ]]; then printf 'SECOND\tdevice\n'; fi ;;
  get-state)
    printf 'device\n'
    [[ "${MOCK_GET_STATE_FAILURE:-0}" != 1 ]] || exit 1 ;;
  'shell settings get global wifi_on') cat "$MOCK_DIR/wifi" ;;
  'shell settings get global mobile_data')
    if [[ "${MOCK_UNREADABLE_DATA:-0}" == 1 ]]; then printf 'null\n'
    else cat "$MOCK_DIR/data"; fi ;;
  'shell svc wifi enable') printf '1\n' > "$MOCK_DIR/wifi"; printf 'wifi-on\n' >> "$MOCK_DIR/commands" ;;
  'shell svc wifi disable') printf '0\n' > "$MOCK_DIR/wifi"; printf 'wifi-off\n' >> "$MOCK_DIR/commands" ;;
  'shell svc data enable') printf '1\n' > "$MOCK_DIR/data"; printf 'data-on\n' >> "$MOCK_DIR/commands" ;;
  'shell svc data disable') printf '0\n' > "$MOCK_DIR/data"; printf 'data-off\n' >> "$MOCK_DIR/commands" ;;
  'logcat -c') : ;;
  'logcat -d -s ChunksSwarm:I') printf 'CHUNKS_SWARM_NETFLAP: ready verified=4194304\n' ;;
  install\ *)
    printf 'install\n' >> "$MOCK_DIR/commands"
    if [[ "${MOCK_INSTALL_FAILURE:-0}" == 1 ]]; then
      printf 'Failure [INSTALL_FAILED_INVALID_APK]\n'
    else
      printf 'Success\n'
    fi
    ;;
  exec-in\ run-as\ *) cat >/dev/null ;;
  exec-out\ run-as\ *)
    case "$*" in *'files/net-off') touch "$MOCK_DIR/net-off";; *'files/net-on') touch "$MOCK_DIR/net-on";; esac ;;
  shell\ am\ instrument\ *)
    printf 'instrument\n' >> "$MOCK_DIR/commands"
    printf 'INSTRUMENTATION_RESULT: stream=OK (1 test)\nOK (1 test)\n'
    [[ "${MOCK_INSTRUMENT_FAILURE:-0}" != 1 ]] || exit 19
    ;;
  *) printf 'Unexpected mock adb operation: %s\n' "$*" >&2; exit 1 ;;
esac
MOCK_ADB
chmod +x "$temp/bin/adb"
export PATH="$temp/bin:$PATH" MOCK_DIR="$temp/fixture"
export CHUNKS_PUBLIC_SWARM_TORRENT="$temp/torrent" CHUNKS_PUBLIC_SWARM_SECONDS=30 CHUNKS_NET_OUTAGE_SECONDS=1
for state in '1 0' '0 1'; do
  read -r wifi data <<< "$state"
  printf '%s\n' "$wifi" > "$MOCK_DIR/wifi"
  printf '%s\n' "$data" > "$MOCK_DIR/data"
  : > "$MOCK_DIR/commands"
  rm -f "$MOCK_DIR/net-off" "$MOCK_DIR/net-on"
  env -u ADB_SERIAL -u ANDROID_SERIAL bash "$repo/android_public_swarm_netflap.sh" > "$temp/output"
  [[ "$(cat "$MOCK_DIR/wifi")" == "$wifi" && "$(cat "$MOCK_DIR/data")" == "$data" ]]
  [[ -f "$MOCK_DIR/net-off" && -f "$MOCK_DIR/net-on" ]]
  [[ "$(grep -c -E '^(wifi|data)-(off|on)$' "$MOCK_DIR/commands")" == 4 ]]
  grep -q 'network returned to original settings' "$temp/output"
done
# A spawned instrumentation can print OK and still exit unsuccessfully.
# A zero-exit adb install can report Failure instead of a real installation.
for negative in MOCK_INSTRUMENT_FAILURE MOCK_INSTALL_FAILURE; do
  printf '1\n' > "$MOCK_DIR/wifi"; printf '0\n' > "$MOCK_DIR/data"
  : > "$MOCK_DIR/commands"
  rm -f "$MOCK_DIR/net-off" "$MOCK_DIR/net-on"
  if env -u ADB_SERIAL -u ANDROID_SERIAL "$negative=1" bash "$repo/android_public_swarm_netflap.sh" > "$temp/$negative.log" 2>&1; then
    echo "Misleading netflap acceptance was accepted: $negative" >&2; exit 1
  fi
  [[ "$(cat "$MOCK_DIR/wifi")" == 1 && "$(cat "$MOCK_DIR/data")" == 0 ]] || {
    echo "Network settings not restored after failed netflap: $negative" >&2; exit 1
  }
done
printf '1\n' > "$MOCK_DIR/wifi"; printf '0\n' > "$MOCK_DIR/data"; : > "$MOCK_DIR/commands"
if MOCK_UNREADABLE_DATA=1 bash "$repo/android_public_swarm_netflap.sh" > "$temp/fail" 2>&1; then
  echo 'Unknown baseline was accepted' >&2; exit 1
fi
[[ "$(cat "$MOCK_DIR/wifi")" == 1 && "$(cat "$MOCK_DIR/data")" == 0 ]]
[[ "$(grep -Ec '^(wifi|data)-(off|on)$' "$MOCK_DIR/commands" || true)" == 0 ]]
: > "$MOCK_DIR/commands"
if MOCK_SECOND_DEVICE=1 bash "$repo/android_public_swarm_netflap.sh" > "$temp/fail" 2>&1; then
  echo 'Multiple adb devices were accepted' >&2; exit 1
fi
[[ ! -s "$MOCK_DIR/commands" ]]
if ADB_SERIAL=192.0.2.10:5555 bash "$repo/android_public_swarm_netflap.sh" > "$temp/fail" 2>&1; then
  echo 'Wireless adb was accepted' >&2; exit 1
fi
[[ ! -s "$MOCK_DIR/commands" ]]
# A query printing "device" before failing must not authorize any device work.
rm -f "$MOCK_DIR/net-off" "$MOCK_DIR/net-on"
if ADB_SERIAL=MOCK_USB MOCK_GET_STATE_FAILURE=1 bash "$repo/android_public_swarm_netflap.sh" > "$temp/state-failed.log" 2>&1; then
  echo 'Failed adb get-state with plausible output was accepted' >&2; exit 1
fi
grep -q 'Selected adb device unavailable' "$temp/state-failed.log"
[[ ! -s "$MOCK_DIR/commands" && ! -e "$MOCK_DIR/net-off" && ! -e "$MOCK_DIR/net-on" ]]
[[ "$(cat "$MOCK_DIR/wifi")" == 1 && "$(cat "$MOCK_DIR/data")" == 0 ]]
echo 'CHUNKS_NETFLAP_MOCK: baseline preservation, outage signaling, failed state query, unknown state, ambiguous/wireless adb, zero-exit install failures and nonzero instrumentation all passed'
