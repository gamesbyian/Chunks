#!/usr/bin/env bash
# Interrupted real-swarm download: phase A downloads part of the sanitized
# public torrent with the app's own engine and SIGKILLs itself mid-transfer;
# phase B (fresh process) checks honest restore and continued verified growth.
set -euo pipefail
: "${CHUNKS_PUBLIC_SWARM_TORRENT:?}" "${CHUNKS_PUBLIC_SWARM_BUDGET_BYTES:?}" "${CHUNKS_PUBLIC_SWARM_SECONDS:?}"
pkg=io.github.gamesbyian.chunks
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
cls="$pkg.AndroidPublicSwarmInterruptInstrumentedTest"
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
adb exec-in run-as "$pkg" sh -c 'mkdir -p cache && cat > cache/public-swarm.torrent' < "$CHUNKS_PUBLIC_SWARM_TORRENT"
adb logcat -c
set +e
outA=$(adb shell am instrument -w -r -e class "$cls#phaseA_downloadPartThenDieMidTransfer" \
  -e chunksInterruptPhase a -e chunksSwarmTorrent public-swarm.torrent \
  -e chunksSwarmBudgetBytes "$CHUNKS_PUBLIC_SWARM_BUDGET_BYTES" -e chunksSwarmSeconds "$CHUNKS_PUBLIC_SWARM_SECONDS" "$runner")
set -e
echo "$outA" | tail -8
adb logcat -d -s ChunksSwarm:I | grep -o 'CHUNKS_SWARM_INTERRUPT: .*' | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}" || true
adb logcat -d -s ChunksSwarm:I | grep -q "CHUNKS_SWARM_INTERRUPT: armed" || { echo "::error::phase A never armed (see INCONCLUSIVE above)"; exit 1; }
echo "$outA" | grep -q "Process crashed" || { echo "::error::phase A did not die mid-transfer"; exit 1; }
sleep 3
if adb shell pidof "$pkg" >/dev/null 2>&1; then echo "::error::process still alive"; exit 1; fi
adb logcat -c
outB=$(adb shell am instrument -w -r -e class "$cls#phaseB_restoredStoppedRechecksAndContinues" \
  -e chunksInterruptPhase b -e chunksSwarmSeconds "$CHUNKS_PUBLIC_SWARM_SECONDS" "$runner")
echo "$outB" | tail -8
adb logcat -d -s ChunksSwarm:I | grep -o 'CHUNKS_SWARM_INTERRUPT: .*' | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}" || true
echo "$outB" | grep -q "^OK (1 test)" || { echo "::error::phase B did not pass"; exit 1; }
if echo "$outB" | grep -q "INSTRUMENTATION_STATUS_CODE: -3"; then echo "::error::phase B skipped"; exit 1; fi
