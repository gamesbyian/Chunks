#!/usr/bin/env bash
# Disposable-only: installed app against the runner's loopback Transmission daemon.
set -euo pipefail
out="$RUNNER_TEMP/interop/evidence"
mkdir -p "$out"
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
adb reverse tcp:19091 tcp:19091 >/dev/null
b64() { printf '%s' "$1" | base64 -w 0; }
set +e
adb shell am instrument -w -r \
  -e class io.github.gamesbyian.chunks.ProtocolClientInstalledJourneyTest \
  -e chunks_protocol_disposable authorized \
  -e chunks_protocol_url_b64 "$(b64 http://127.0.0.1:19091/transmission/rpc)" \
  -e chunks_protocol_user_b64 "$(b64 chunks)" \
  -e chunks_protocol_pass_b64 "$(b64 chunkspass)" \
  io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner \
  > "$out/instrumentation.txt" 2>&1
status=$?
set -e
adb logcat -d -t 2000 > "$out/logcat.txt" 2>&1 || true
grep -E '^INSTRUMENTATION_STATUS: chunks_stage=' "$out/instrumentation.txt" | tee "$out/stages.txt" || true
if [ "$status" -ne 0 ] || ! grep -q '^OK (1 test)' "$out/instrumentation.txt"; then
  tail -80 "$out/instrumentation.txt"
  echo "::error::Installed protocol-client journey failed"
  exit 1
fi
echo "CHUNKS_INSTALLED_PROTOCOL: Transmission Connect -> Transfers -> Details Start -> Remove passed" | tee -a "$GITHUB_STEP_SUMMARY"
