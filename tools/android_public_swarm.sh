#!/usr/bin/env bash
# Android public swarm: copy a collector-sanitized torrent into the debuggable
# app's cache and run the opt-in swarm test. Fails if the test was skipped, so
# a green run always means the native engine verified the byte budget.
set -euo pipefail
: "${CHUNKS_PUBLIC_SWARM_TORRENT:?}" "${CHUNKS_PUBLIC_SWARM_BUDGET_BYTES:?}" "${CHUNKS_PUBLIC_SWARM_SECONDS:?}"
pkg=io.github.gamesbyian.chunks
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
adb exec-in run-as "$pkg" sh -c 'mkdir -p cache && cat > cache/public-swarm.torrent' < "$CHUNKS_PUBLIC_SWARM_TORRENT"
adb logcat -c
log=$(mktemp)
trap 'rm -f "$log"' EXIT
set +e
adb shell am instrument -w -r \
 -e class "$pkg.AndroidPublicSwarmInstrumentedTest" \
 -e chunksSwarmTorrent public-swarm.torrent \
 -e chunksSwarmBudgetBytes "$CHUNKS_PUBLIC_SWARM_BUDGET_BYTES" \
 -e chunksSwarmSeconds "$CHUNKS_PUBLIC_SWARM_SECONDS" \
 "$pkg.test/androidx.test.runner.AndroidJUnitRunner" >"$log" 2>&1
status=$?
set -e
adb logcat -d -s ChunksSwarm:I | grep -o 'CHUNKS_ANDROID_SWARM: .*' | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}" || true
if [ "$status" -ne 0 ] || ! grep -q '^OK (1 test)' "$log" ||
   grep -qE '^INSTRUMENTATION_STATUS_CODE: -(1|2|3|4)' "$log"; then
 grep -A12 '^INSTRUMENTATION_STATUS: stack=' "$log" | head -40 || true
 echo "::error::Android swarm test failed or was skipped (INCONCLUSIVE means peer availability, not engine acceptance)"
 exit 1
fi
