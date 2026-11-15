#!/usr/bin/env bash
# Installed Android two-process witness: a real loopback download killed
# part-way, then a fresh app process restores the engine-owned checkpoint.
# Disposable app-private fixture only; no user Place, remote, tracker or DHT.
set -euo pipefail
api="${1:-}"
[[ "$api" == 29 || "$api" == 35 ]] || { echo "CHUNKS_RESUME_DEATH: invalid-api" >&2; exit 64; }
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r\n')" == 1 ]] || {
  echo "CHUNKS_RESUME_DEATH: physical device refused" >&2; exit 1; }
[[ "$(adb shell getprop ro.build.version.sdk | tr -d '\r\n')" == "$api" ]] || {
  echo "CHUNKS_RESUME_DEATH: Android SDK identity mismatch" >&2; exit 1; }
pkg=io.github.gamesbyian.chunks
out="${RUNNER_TEMP:-/tmp}/chunks-resume-death-api$api"
mkdir -p "$out"
app=app/build/outputs/apk/debug/app-debug.apk
test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb install -r "$app" >/dev/null
adb install -r "$test_apk" >/dev/null
app_path="$(adb shell pm path "$pkg" | sed -n 's/^package://p' | tr -d '\r' | head -1)"
test_path="$(adb shell pm path "$pkg.test" | sed -n 's/^package://p' | tr -d '\r' | head -1)"
[[ -n "$app_path" && -n "$test_path" ]] || { echo "CHUNKS_RESUME_DEATH: missing-package" >&2; exit 1; }
bash tools/verify_installed_apk_bytes.sh "$app" "$app_path" "$out/installed-app.apk" | tee "$out/installed-apk-identity.txt"
bash tools/verify_installed_apk_bytes.sh "$test_apk" "$test_path" "$out/installed-test.apk" | tee -a "$out/installed-apk-identity.txt"
class=io.github.gamesbyian.chunks.engine.LocalPartialResumeProcessDeathInstrumentedTest
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
adb logcat -c || true
set +e
adb shell am instrument -w -r -e chunks_resume_death_phase download-then-die \
  -e class "$class#downloadThenDie" "$runner" > "$out/phase1.txt" 2>&1
set -e
adb logcat -d -s ChunksResumeDeath > "$out/phase1-log.txt" 2>&1 || true
# Phase 1 must end by its own deliberate kill, never by a pass or an assertion.
grep -q "CHUNKS_RESUME_DEATH: killing" "$out/phase1-log.txt" || {
  tail -60 "$out/phase1.txt"; echo "::error::Phase 1 did not reach its deliberate process kill"; exit 1; }
if grep -q '^OK (' "$out/phase1.txt"; then
  echo "::error::Phase 1 completed instead of dying"; exit 1; fi
# The killed process is gone: no state survives in memory.
for _ in $(seq 1 50); do adb shell pidof "$pkg" >/dev/null 2>&1 || break; sleep 0.2; done
adb shell pidof "$pkg" >/dev/null 2>&1 && { echo "::error::app process still alive after kill"; exit 1; }
adb logcat -c || true
set +e
adb shell am instrument -w -r -e chunks_resume_death_phase restore \
  -e class "$class#restoreKeepsCheckpointedPieces" "$runner" > "$out/phase2.txt" 2>&1
status=$?
set -e
adb logcat -d -s ChunksResumeDeath > "$out/phase2-log.txt" 2>&1 || true
if [[ "$status" -ne 0 ]] || ! grep -q '^OK (1 test)' "$out/phase2.txt" ||
   ! grep -q "CHUNKS_RESUME_DEATH: verified=true" "$out/phase2-log.txt"; then
  tail -80 "$out/phase2.txt"; echo "::error::Restore after process death failed on API $api"; exit 1
fi
grep -h "CHUNKS_RESUME_DEATH" "$out/phase1-log.txt" "$out/phase2-log.txt" | sed 's/^.*CHUNKS_RESUME_DEATH/CHUNKS_RESUME_DEATH/' | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
echo "CHUNKS_RESUME_DEATH: API $api real loopback download killed mid-transfer, restored checkpointed pieces in a fresh process and finished to exact bytes; installed APK bytes verified" | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
