#!/usr/bin/env bash
# Network loss and return during a real-peer foreground download: turns
# Wi-Fi and mobile data off for a fixed outage, then back on, signalling the
# test at each edge. Always restores connectivity on exit.
set -euo pipefail
: "${CHUNKS_PUBLIC_SWARM_TORRENT:?}" "${CHUNKS_PUBLIC_SWARM_SECONDS:?}"
pkg=io.github.gamesbyian.chunks
outage=${CHUNKS_NET_OUTAGE_SECONDS:-30}
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
adb exec-in run-as "$pkg" sh -c 'mkdir -p cache && cat > cache/public-swarm.torrent' < "$CHUNKS_PUBLIC_SWARM_TORRENT"
restore() {
  adb shell svc wifi enable >/dev/null 2>&1 || true
  adb shell svc data enable >/dev/null 2>&1 || true
}
trap restore EXIT
adb logcat -c
log=$(mktemp)
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
if [ "$ready" -eq 1 ]; then
  adb shell svc wifi disable
  adb shell svc data disable
  adb exec-out run-as "$pkg" touch files/net-off
  echo "CHUNKS_SWARM_NETFLAP network off for ${outage}s"
  sleep "$outage"
  adb shell svc wifi enable
  adb shell svc data enable
  adb exec-out run-as "$pkg" touch files/net-on
  echo "CHUNKS_SWARM_NETFLAP network back on"
fi
set +e
wait "$inst"
set -e
adb logcat -d -s ChunksSwarm:I | grep -o 'CHUNKS_SWARM_NETFLAP: .*' | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}" || true
if ! grep -q '^OK (1 test)' "$log" || grep -qE '^INSTRUMENTATION_STATUS_CODE: -(1|2|3|4)' "$log"; then
  grep -A12 '^INSTRUMENTATION_STATUS: stack=' "$log" | head -40 || true
  echo "::error::Network test failed or was skipped (INCONCLUSIVE means peer availability)"
  exit 1
fi
