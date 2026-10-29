#!/usr/bin/env bash
# Doze with real peers: a foreground-service download runs, the device is
# forced into deep idle once bytes flow, and the test records whether
# verified bytes keep arriving. Restores battery/idle state afterwards.
set -euo pipefail
: "${CHUNKS_PUBLIC_SWARM_TORRENT:?}" "${CHUNKS_PUBLIC_SWARM_SECONDS:?}"
pkg=io.github.gamesbyian.chunks
# Freeze one adb target before any installation, radio/battery mutation or
# cleanup. More than one connected device is ambiguous without an explicit ID.
serial="${ADB_SERIAL:-${ANDROID_SERIAL:-}}"
if [[ -z "$serial" ]]; then
  mapfile -t serials < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
  [[ "${#serials[@]}" -eq 1 ]] || { echo "CHUNKS_SWARM_DOZE: exactly one authorized adb device required" >&2; exit 2; }
  serial="${serials[0]}"
fi
export ANDROID_SERIAL="$serial"
[[ "$(adb get-state | tr -d '\r\n')" == "device" ]] || { echo "CHUNKS_SWARM_DOZE: selected adb device unavailable" >&2; exit 2; }
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
adb exec-in run-as "$pkg" sh -c 'mkdir -p cache && cat > cache/public-swarm.torrent' < "$CHUNKS_PUBLIC_SWARM_TORRENT"
log=""
idle_touched=0
battery_touched=0
restore() {
  local rc=$?
  trap - EXIT
  if [[ "$idle_touched" -eq 1 ]]; then
    if ! adb shell dumpsys deviceidle unforce >/dev/null 2>&1; then
      echo "CHUNKS_SWARM_DOZE: failed to release forced idle" >&2
      rc=1
    fi
  fi
  if [[ "$battery_touched" -eq 1 ]]; then
    if ! adb shell dumpsys battery reset >/dev/null 2>&1; then
      echo "CHUNKS_SWARM_DOZE: failed to reset simulated battery" >&2
      rc=1
    fi
  fi
  [[ -z "$log" ]] || rm -f -- "$log"
  exit "$rc"
}
trap restore EXIT
echo "CHUNKS_SWARM_DOZE allowlisted: $(adb shell dumpsys deviceidle whitelist | grep -c "$pkg" || true)"
adb logcat -c
log=$(mktemp)
adb shell am instrument -w -r -e class "$pkg.AndroidPublicSwarmDozeInstrumentedTest" \
  -e chunksDoze 1 -e chunksSwarmTorrent public-swarm.torrent -e chunksSwarmSeconds "$CHUNKS_PUBLIC_SWARM_SECONDS" \
  "$pkg.test/androidx.test.runner.AndroidJUnitRunner" >"$log" 2>&1 &
inst=$!
ready=0
for _ in $(seq 1 $(( CHUNKS_PUBLIC_SWARM_SECONDS / 2 + 30 ))); do
  if adb logcat -d -s ChunksSwarm:I | grep -q "CHUNKS_SWARM_DOZE: ready"; then ready=1; break; fi
  kill -0 "$inst" 2>/dev/null || break
  sleep 2
done
if [ "$ready" -eq 1 ]; then
  # Set touched flags BEFORE each operation: a failed adb request may still
  # have partially changed Android state and must be compensated.
  battery_touched=1
  adb shell dumpsys battery unplug
  idle_touched=1
  adb shell dumpsys deviceidle force-idle
  state=$(adb shell dumpsys deviceidle get deep | tr -d '\r')
  echo "CHUNKS_SWARM_DOZE deep_state=$state"
  [ "$state" = "IDLE" ] || { echo "::error::device did not enter deep idle ($state)"; kill "$inst" || true; exit 1; }
  adb exec-out run-as "$pkg" touch files/doze-on
fi
set +e
wait "$inst"
set -e
adb logcat -d -s ChunksSwarm:I | grep -o 'CHUNKS_SWARM_DOZE: .*' | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}" || true
if [[ "$ready" -ne 1 ]]; then
  echo "::error::Doze test never reached ready state; no forced-idle proof" >&2
  exit 1
fi
if ! grep -q '^OK (1 test)' "$log" || grep -qE '^INSTRUMENTATION_STATUS_CODE: -(1|2|3|4)' "$log"; then
  grep -A12 '^INSTRUMENTATION_STATUS: stack=' "$log" | head -40 || true
  echo "::error::Doze test failed or was skipped (INCONCLUSIVE means peer availability)"
  exit 1
fi
