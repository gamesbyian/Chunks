#!/usr/bin/env bash
# Doze with real peers: a foreground-service download runs, the device is
# forced into deep idle once bytes flow, and the test records whether
# verified bytes keep arriving. Restores battery/idle state afterwards.
set -euo pipefail
: "${CHUNKS_PUBLIC_SWARM_TORRENT:?}" "${CHUNKS_PUBLIC_SWARM_SECONDS:?}"
pkg=io.github.gamesbyian.chunks
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
adb exec-in run-as "$pkg" sh -c 'mkdir -p cache && cat > cache/public-swarm.torrent' < "$CHUNKS_PUBLIC_SWARM_TORRENT"
restore() {
  adb shell dumpsys deviceidle unforce >/dev/null 2>&1 || true
  adb shell dumpsys battery reset >/dev/null 2>&1 || true
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
  adb shell dumpsys battery unplug
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
if ! grep -q '^OK (1 test)' "$log" || grep -qE '^INSTRUMENTATION_STATUS_CODE: -(1|2|3|4)' "$log"; then
  grep -A12 '^INSTRUMENTATION_STATUS: stack=' "$log" | head -40 || true
  echo "::error::Doze test failed or was skipped (INCONCLUSIVE means peer availability)"
  exit 1
fi
