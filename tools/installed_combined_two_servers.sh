#!/usr/bin/env bash
# Temporary PR route witness: no Native or Universal Add producer selected.
# Disposable-only D1/C1: installed app against two local real rTorrent daemons.
set -euo pipefail
# Bound every Android instrumentation phase independently; timeouts are failures.
receipt="$RUNNER_TEMP/chunks-combined/receipt.json"
out="$RUNNER_TEMP/chunks-combined/evidence"
mkdir -p "$out"
adb shell wm size 1080x2400
adb shell wm density 420
pkg=io.github.gamesbyian.chunks
candidate=app/build/outputs/apk/debug/app-debug.apk
adb install -r "$candidate" >/dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
test "$(git rev-parse HEAD)" = "${GITHUB_SHA:?}"
aapt="${ANDROID_HOME:?}/build-tools/36.0.0/aapt"
version="$("$aapt" dump badging "$candidate" | awk -F "versionName='" '/^package:/{split($2,fields,"\047"); print fields[1]}')"
test -n "$version"
device_path="$(adb shell pm path "$pkg" | tr -d '\r' | sed -n 's/^package://p')"
{
  printf 'CHUNKS_COMBINED_SOURCE commit=%s tree=%s\n' "$(git rev-parse HEAD)" "$(git rev-parse HEAD^{tree})"
  printf 'CHUNKS_COMBINED_PACKAGE app_id=%s version=%s\n' "$pkg" "$version"
  bash tools/verify_installed_apk_bytes.sh "$candidate" "$device_path" "$RUNNER_TEMP/chunks-combined/installed.apk"
} | tee "$out/installed-apk.txt"
adb reverse tcp:18080 tcp:18080 >/dev/null
adb reverse tcp:18082 tcp:18082 >/dev/null
adb reverse tcp:18083 tcp:18083 >/dev/null
# Run before the existing Combined fault campaign, which intentionally terminates
# server A. The snapshot test removes only its own job and verifies both queues
# are back to the preloaded inventories before the next test starts.
set +e
timeout --signal=TERM --kill-after=15s 4m adb shell am instrument -w -r \
  -e class io.github.gamesbyian.chunks.snapshots.RemoteSnapshotRestoreInstalledTest \
  -e chunks_combined_disposable authorized \
  io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner \
  > "$out/snapshot-restore-installed.txt" 2>&1
status=$?
set -e
adb pull "/sdcard/Android/data/$pkg/files/disposable-snapshot-review.png"   "$out/snapshot-review.png" >/dev/null 2>&1 || rm -f "$out/snapshot-review.png"
if [ "$status" -ne 0 ] || ! grep -q '^OK (1 test)' "$out/snapshot-restore-installed.txt" ||
   grep -q '^INSTRUMENTATION_STATUS_CODE: -3' "$out/snapshot-restore-installed.txt"; then
  tail -80 "$out/snapshot-restore-installed.txt"
  echo "::error::Installed remote snapshot restore did not pass"
  exit 1
fi
echo "CHUNKS_SNAPSHOT_REMOTE_RESTORE_INSTALLED: consent, exact Place and stopped hash verified on one server only" >> "$GITHUB_STEP_SUMMARY"

# Independent selected-folder restore. The same challenged tenant mount serves
# exact _getdir readback; the write still goes to the real disposable rTorrent.
# Run before the older Combined fault case shuts down server A.
set +e
timeout --signal=TERM --kill-after=15s 6m adb shell am instrument -w -r \
  -e class io.github.gamesbyian.chunks.snapshots.RemoteSnapshotSelectedPlaceInstalledTest \
  -e chunks_combined_disposable authorized \
  io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner \
  > "$out/snapshot-selected-place-installed.txt" 2>&1
selected_status=$?
set -e
if [ "$selected_status" -ne 0 ] ||
   ! grep -q '^OK (4 tests)' "$out/snapshot-selected-place-installed.txt" ||
   grep -q '^INSTRUMENTATION_STATUS_CODE: -3' "$out/snapshot-selected-place-installed.txt"; then
  tail -80 "$out/snapshot-selected-place-installed.txt"
  echo "::error::Authenticated selected-place stopped snapshot restore did not pass"
  exit 1
fi
echo "CHUNKS_SNAPSHOT_SELECTED_PLACE_INSTALLED: authenticated folder read, one stopped Add, two-item consented batch with before-all journal preflight, no replay, cross-account negative" >> "$GITHUB_STEP_SUMMARY"

b64() { printf '%s' "$1" | base64 -w 0; }
field() { python3 -c "import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])" "$receipt" "$1"; }
set +e
timeout --signal=TERM --kill-after=15s 10m adb shell am instrument -w -r \
  -e class io.github.gamesbyian.chunks.CombinedTwoRealServersInstrumentedTest#combinedShowsSameHashOnTwoRealServersWithDistinctOriginsAndReconnects \
  -e chunks_combined_disposable authorized \
  -e chunks_combined_a_b64 "$(b64 http://127.0.0.1:18080/RPC2)" \
  -e chunks_combined_a_user_b64 "$(b64 chunks)" \
  -e chunks_combined_a_pass_b64 "$(b64 chunks-one)" \
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
timeout --signal=TERM --kill-after=15s 10m adb shell am instrument -w -r \
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

# Snapshot priority consent is deliberately independent of live server writes:
# its installed Compose assertions must EXECUTE (compiling androidTest isn't a
# receipt). Reuse the same emulator and installed APKs to avoid another job.
set +e
timeout --signal=TERM --kill-after=15s 4m adb shell am instrument -w -r \
  -e class io.github.gamesbyian.chunks.snapshots.RemoteSnapshotPriorityConfirmationInstrumentedTest \
  io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner \
  > "$out/snapshot-priority-consent.txt" 2>&1
status=$?
set -e
if [ "$status" -ne 0 ] || ! grep -q '^OK (2 tests)' "$out/snapshot-priority-consent.txt"; then
  tail -80 "$out/snapshot-priority-consent.txt"
  echo "::error::Installed remote snapshot priority consent regressions failed or were skipped"
  exit 1
fi
echo "CHUNKS_SNAPSHOT_REMOTE_PRIORITY_INSTALLED: both explicit-consent Compose tests executed and passed" | tee -a "$GITHUB_STEP_SUMMARY"
