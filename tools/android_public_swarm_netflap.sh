#!/usr/bin/env bash
# Opt-in real-peer outage and recovery. Never leave a physical handset's
# Wi-Fi/mobile-data toggles different from their original verified states.
set -euo pipefail
: "${CHUNKS_PUBLIC_SWARM_TORRENT:?}" "${CHUNKS_PUBLIC_SWARM_SECONDS:?}"
source "$(dirname "${BASH_SOURCE[0]}")/android_radio_baseline.sh"
pkg=io.github.gamesbyian.chunks
outage=${CHUNKS_NET_OUTAGE_SECONDS:-30}
[[ "$outage" =~ ^[0-9]+$ && "$outage" -ge 1 && "$outage" -le 300 ]] || {
  echo "CHUNKS_NET_OUTAGE_SECONDS must be an integer from 1 to 300" >&2; exit 2;
}
[[ "$CHUNKS_PUBLIC_SWARM_SECONDS" =~ ^[0-9]+$ && "$CHUNKS_PUBLIC_SWARM_SECONDS" -ge 30 && "$CHUNKS_PUBLIC_SWARM_SECONDS" -le 1800 ]] || {
  echo "CHUNKS_PUBLIC_SWARM_SECONDS must be an integer from 30 to 1800" >&2; exit 2;
}
# A wireless adb connection would disappear when the script disables radios.
# Pin an explicit serial before *any* install or radio operation.
serial="${ADB_SERIAL:-${ANDROID_SERIAL:-}}"
if [[ -z "$serial" ]]; then
  mapfile -t serials < <(adb devices | awk 'NR > 1 && $2 == "device" {print $1}')
  [[ "${#serials[@]}" -eq 1 ]] || { echo "Select exactly one authorized device (ADB_SERIAL)" >&2; exit 2; }
  serial="${serials[0]}"
fi
[[ "$serial" != *:* ]] || { echo "Network-flap acceptance requires USB adb, not wireless adb" >&2; exit 2; }
export ANDROID_SERIAL="$serial"
[[ "$(adb get-state | tr -d '\r\n')" == device ]] || { echo "Selected adb device unavailable" >&2; exit 2; }

CHUNKS_WIFI_BASELINE= CHUNKS_DATA_BASELINE=
# Reject unsupported OEM settings *before* installing APKs or launching
# instrumentation; refresh the baseline again immediately before toggling.
chunks_read_radio_baseline || { echo "Radio baseline unavailable: no test started" >&2; exit 1; }
radio_changed=0
log="$(mktemp)"
restore() {
  local rc=$?
  trap - EXIT
  if [[ "$radio_changed" -eq 1 ]]; then
    if ! chunks_restore_radio_baseline; then
      echo "Failed to restore the device's original Wi-Fi/mobile-data settings" >&2
      rc=1
    fi
  fi
  rm -f "$log"
  exit "$rc"
}
trap restore EXIT
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
adb exec-in run-as "$pkg" sh -c 'mkdir -p cache && cat > cache/public-swarm.torrent' < "$CHUNKS_PUBLIC_SWARM_TORRENT"
adb logcat -c
adb shell am instrument -w -r -e class "$pkg.AndroidPublicSwarmNetworkFlapInstrumentedTest" \
  -e chunksNetFlap 1 -e chunksSwarmTorrent public-swarm.torrent -e chunksSwarmSeconds "$CHUNKS_PUBLIC_SWARM_SECONDS" \
  "$pkg.test/androidx.test.runner.AndroidJUnitRunner" >"$log" 2>&1 &
inst=$!
ready=0
for _ in $(seq 1 $(( CHUNKS_PUBLIC_SWARM_SECONDS / 2 + 30 ))); do
  if adb logcat -d -s ChunksSwarm:I | grep -q "CHUNKS_SWARM_NETFLAP: ready"; then ready=1; break; fi
  kill -0 "$inst" 2>/dev/null || break
  sleep 2
done
if [[ "$ready" -eq 1 ]]; then
  # The mobile-data setting is sometimes unavailable on multi-SIM/older OEM
  # builds. Refuse instead of changing a state we cannot later restore.
  chunks_read_radio_baseline || { echo "Radio baseline unavailable: no radios changed" >&2; exit 1; }
  radio_changed=1
  adb shell svc wifi disable
  adb shell svc data disable
  adb exec-out run-as "$pkg" touch files/net-off
  echo "CHUNKS_SWARM_NETFLAP network off for ${outage}s"
  sleep "$outage"
  chunks_restore_radio_baseline
  radio_changed=0
  adb exec-out run-as "$pkg" touch files/net-on
  echo "CHUNKS_SWARM_NETFLAP network returned to original settings"
fi
set +e
wait "$inst"
set -e
adb logcat -d -s ChunksSwarm:I | grep -o 'CHUNKS_SWARM_NETFLAP: .*' | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}" || true
if [[ "$ready" -ne 1 ]] || ! grep -q '^OK (1 test)' "$log" || grep -qE '^INSTRUMENTATION_STATUS_CODE: -(1|2|3|4)' "$log"; then
  grep -A12 '^INSTRUMENTATION_STATUS: stack=' "$log" | head -40 || true
  echo "::error::Network flap was not independently executed and verified (peer availability may be inconclusive)"
  exit 1
fi
