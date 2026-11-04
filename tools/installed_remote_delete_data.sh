#!/usr/bin/env bash
# Disposable-only remote L6: installed app -> pinned ruTorrent httprpc/erasedata
# -> real rTorrent; then the real upstream collector and an independent file witness.
set -euo pipefail
hash="$(cat "$RUNNER_TEMP/chunks-remote-delete-hash.txt")"
[[ "$hash" =~ ^[0-9a-f]{40}$ ]] || { echo "::error::no staged hash"; exit 1; }
out="$RUNNER_TEMP/chunks-remote-delete-evidence"
mkdir -p "$out"
adb shell wm size 1080x2400
adb shell wm density 420
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
adb reverse tcp:18082 tcp:18082 >/dev/null
b64() { printf '%s' "$1" | base64 -w 0; }
set +e
adb shell am instrument -w -r \
  -e class io.github.gamesbyian.chunks.RemoteDeleteDataInstalledInstrumentedTest \
  -e chunks_remote_delete authorized \
  -e chunks_remote_delete_url_b64 "$(b64 http://127.0.0.1:18082/rutorrent1/)" \
  -e chunks_remote_delete_hash_b64 "$(b64 "$hash")" \
  io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner \
  > "$out/instrumentation.txt" 2>&1
status=$?
set -e
adb logcat -d -t 2000 > "$out/logcat.txt" 2>&1 || true
grep -E '^INSTRUMENTATION_STATUS: chunks_stage=' "$out/instrumentation.txt" | tee "$out/stages.txt" || true
if [ "$status" -ne 0 ] || ! grep -q '^OK (1 test)' "$out/instrumentation.txt" ||
   ! grep -q 'chunks_stage=session-absent' "$out/instrumentation.txt"; then
  tail -80 "$out/instrumentation.txt"
  echo "::error::Installed remote Remove transfer and data journey failed"
  exit 1
fi
# The app only QUEUED deletion. Run the real upstream collector and verify
# exact payload absence and the foreign canary independently.
python3 tools/stage_disposable_rutorrent_adapter_delete.py verify | tee "$out/collector.txt"
grep -q '^PASS production Kotlin adapter' "$out/collector.txt"
echo "CHUNKS_REMOTE_DELETE_INSTALLED: app queued exact erasedata; collector deleted exact file; canary intact" | tee -a "$GITHUB_STEP_SUMMARY"
