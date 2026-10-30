#!/usr/bin/env bash
# Interrupted real-swarm download: phase A downloads part of the sanitized
# public torrent with the app's own engine and SIGKILLs itself mid-transfer;
# phase B (fresh process) checks honest restore and continued verified growth.
set -euo pipefail
: "${CHUNKS_PUBLIC_SWARM_TORRENT:?}" "${CHUNKS_PUBLIC_SWARM_BUDGET_BYTES:?}" "${CHUNKS_PUBLIC_SWARM_SECONDS:?}"
pkg=io.github.gamesbyian.chunks
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
cls="$pkg.AndroidPublicSwarmInterruptInstrumentedTest"
# Resolve one authorized device and pin every later ADB operation to it.
serial="${ADB_SERIAL:-${ANDROID_SERIAL:-}}"
if [[ -z "$serial" ]]; then
  # A process-substitution mapfile can succeed after adb printed a plausible
  # inventory and exited nonzero; refuse before any APK or peer operation.
  devices="$(adb devices)" || {
    echo "::error::ADB device inventory failed" >&2; exit 2;
  }
  attached=()
  while read -r candidate state _; do
    [[ "$state" != device || -z "$candidate" ]] || attached+=("$candidate")
  done <<< "$devices"
  [[ "${#attached[@]}" -eq 1 ]] || { echo "::error::Exactly one authorized Android device required" >&2; exit 2; }
  serial="${attached[0]}"
fi
export ANDROID_SERIAL="$serial"
# Check the request's exit status as well as stdout before installing or running.
if ! device_state="$(adb get-state | tr -d '\r\n')" || [[ "$device_state" != device ]]; then
  echo "::error::Selected Android device unavailable" >&2; exit 2
fi
# Exit status alone is insufficient: adb can return zero for package errors.
install_verified() {
  local output
  output="$(adb install -r "$1" 2>&1)" || { echo "::error::adb install failed" >&2; return 1; }
  grep -qx 'Success' <<< "$(printf '%s' "$output" | tr -d '\r')" || {
    echo "::error::Android did not report installed APK success" >&2
    return 1
  }
}
install_verified app/build/outputs/apk/debug/app-debug.apk
install_verified app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb exec-in run-as "$pkg" sh -c 'mkdir -p cache && cat > cache/public-swarm.torrent' < "$CHUNKS_PUBLIC_SWARM_TORRENT"
adb logcat -c
set +e
outA=$(adb shell am instrument -w -r -e class "$cls#phaseA_downloadPartThenDieMidTransfer" \
  -e chunksInterruptPhase a -e chunksSwarmTorrent public-swarm.torrent \
  -e chunksSwarmBudgetBytes "$CHUNKS_PUBLIC_SWARM_BUDGET_BYTES" -e chunksSwarmSeconds "$CHUNKS_PUBLIC_SWARM_SECONDS" "$runner")
set -e
echo "$outA" | tail -8
# Capture once. With pipefail, grep -q may close the adb producer early and
# cause SIGPIPE 141 on a large valid log. Inspect the captured response.
logA="$(adb logcat -d -s ChunksSwarm:I)"
grep -o 'CHUNKS_SWARM_INTERRUPT: .*' <<< "$logA" | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}" || true
grep -Fq "CHUNKS_SWARM_INTERRUPT: armed" <<< "$logA" || { echo "::error::phase A never armed (see INCONCLUSIVE above)"; exit 1; }
grep -Fq "Process crashed" <<< "$outA" || { echo "::error::phase A did not die mid-transfer"; exit 1; }
sleep 3
if adb shell pidof "$pkg" >/dev/null 2>&1; then echo "::error::process still alive"; exit 1; fi
adb logcat -c
outB=$(adb shell am instrument -w -r -e class "$cls#phaseB_restoredStoppedRechecksAndContinues" \
  -e chunksInterruptPhase b -e chunksSwarmSeconds "$CHUNKS_PUBLIC_SWARM_SECONDS" "$runner")
echo "$outB" | tail -8
logB="$(adb logcat -d -s ChunksSwarm:I)"
grep -o 'CHUNKS_SWARM_INTERRUPT: .*' <<< "$logB" | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}" || true
# Nested output may quote OK (1 test) without terminal success. A failed
# adb command is already fatal under set -e; require exactly one full-line
# terminal result and reject every negative test status or skip.
if [[ "$(grep -Fxc 'OK (1 test)' <<< "$outB" || true)" != 1 ]] ||
   grep -Eq '^INSTRUMENTATION_STATUS_CODE: -[1-9]|^FAILURES|AssumptionViolatedException' <<< "$outB"; then
  echo "::error::phase B did not verify exactly one executed, passing test"
  exit 1
fi
