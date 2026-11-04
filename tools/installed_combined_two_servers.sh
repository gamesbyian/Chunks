#!/usr/bin/env bash
# Disposable-only D1/C1: installed app against two local real rTorrent daemons.
set -euo pipefail
receipt="$RUNNER_TEMP/chunks-combined/receipt.json"
out="$RUNNER_TEMP/chunks-combined/evidence"
mkdir -p "$out"
adb shell wm size 1080x2400
adb shell wm density 420
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
adb reverse tcp:18080 tcp:18080 >/dev/null
adb reverse tcp:18082 tcp:18082 >/dev/null
adb reverse tcp:18083 tcp:18083 >/dev/null
b64() { printf '%s' "$1" | base64 -w 0; }
field() { python3 -c "import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])" "$receipt" "$1"; }
set +e
adb shell am instrument -w -r \
  -e class io.github.gamesbyian.chunks.CombinedTwoRealServersInstrumentedTest#combinedShowsSameHashOnTwoRealServersWithDistinctOriginsAndReconnects \
  -e chunks_combined_disposable authorized \
  -e chunks_combined_a_b64 "$(b64 http://127.0.0.1:18080/RPC2)" \
  -e chunks_combined_b_b64 "$(b64 http://127.0.0.1:18082/RPC2)" \
  -e chunks_combined_shared_b64 "$(b64 "$(field shared)")" \
  -e chunks_combined_only_a_b64 "$(b64 "$(field only_a)")" \
  -e chunks_combined_only_b_b64 "$(b64 "$(field only_b)")" \
  -e chunks_combined_shared_hash_b64 "$(b64 "$(field shared_hash)")" \
  io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner \
  > "$out/instrumentation.txt" 2>&1
status=$?
set -e
adb logcat -d -t 2000 > "$out/logcat.txt" 2>&1 || true
grep -E '^INSTRUMENTATION_STATUS: chunks_stage=' "$out/instrumentation.txt" | tee "$out/stages.txt" || true
if [ "$status" -ne 0 ] || ! grep -q '^OK (1 test)' "$out/instrumentation.txt"; then
  tail -80 "$out/instrumentation.txt"
  echo "::error::Installed two-server Combined journey failed"
  exit 1
fi
echo "CHUNKS_COMBINED_TWO_SERVERS: phase 1 passed" | tee -a "$GITHUB_STEP_SUMMARY"

# Phase 2: a genuinely new app process must reconnect the remembered server.
adb shell am force-stop io.github.gamesbyian.chunks
dead="$(adb shell "if pidof io.github.gamesbyian.chunks >/dev/null; then echo running; else echo stopped; fi" | tr -d '\r')"
[[ "$dead" == stopped ]] || { echo "::error::app process still running after force-stop"; exit 1; }
set +e
adb shell am instrument -w -r \
  -e class io.github.gamesbyian.chunks.CombinedTwoRealServersInstrumentedTest#afterProcessDeathRememberedServerReconnects \
  -e chunks_combined_phase after-process-death \
  -e chunks_combined_b_b64 "$(b64 http://127.0.0.1:18082/RPC2)" \
  io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner \
  > "$out/instrumentation-phase2.txt" 2>&1
status=$?
set -e
grep -E '^INSTRUMENTATION_STATUS: chunks_stage=' "$out/instrumentation-phase2.txt" | tee -a "$out/stages.txt" || true
if [ "$status" -ne 0 ] || ! grep -q '^OK (1 test)' "$out/instrumentation-phase2.txt" ||
   ! grep -q 'chunks_stage=process-death-unchanged' "$out/instrumentation-phase2.txt"; then
  tail -80 "$out/instrumentation-phase2.txt"
  echo "::error::Process-death reconnect phase failed"
  exit 1
fi
echo "CHUNKS_COMBINED_TWO_SERVERS: passed" | tee -a "$GITHUB_STEP_SUMMARY"
