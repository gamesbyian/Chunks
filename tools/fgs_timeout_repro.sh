#!/usr/bin/env bash
# Opt-in Android 15+ dataSync foreground-service timeout repro (#569) on a
# DISPOSABLE emulator. Shortens the platform limit per Android's documented
# test controls, runs one instrumented class, then restores the settings.
set -euo pipefail
pkg=io.github.gamesbyian.chunks
gradle --no-daemon :app:installDebug :app:installDebugAndroidTest
restore() {
  adb shell device_config delete activity_manager data_sync_fgs_timeout_duration || true
  adb shell am compat reset FGS_INTRODUCE_TIME_LIMITS "$pkg" || true
}
trap restore EXIT
adb shell am compat enable FGS_INTRODUCE_TIME_LIMITS "$pkg"
adb shell device_config put activity_manager data_sync_fgs_timeout_duration 15000
echo "CHUNKS_FGS_TIMEOUT config: $(adb shell device_config get activity_manager data_sync_fgs_timeout_duration)"
adb logcat -c || true
set +e
out=$(adb shell am instrument -w -r \
  -e class io.github.gamesbyian.chunks.LocalEngineServiceTimeoutInstrumentedTest \
  -e chunksFgsTimeout 1 \
  "$pkg.test/androidx.test.runner.AndroidJUnitRunner")
set -e
echo "$out"
adb logcat -d -s ChunksFgsTimeout:I ActivityManager:W | grep -iE "CHUNKS_FGS_TIMEOUT|timeout|dataSync" | tail -40 || true
# am instrument exits 0 even on failure: require exactly one passing, non-skipped test.
echo "$out" | grep -q "OK (1 test)" || { echo "CHUNKS_FGS_TIMEOUT: instrumentation did not report one passing test"; exit 1; }
if echo "$out" | grep -q "AssumptionViolatedException\|INSTRUMENTATION_STATUS_CODE: -3"; then
  echo "CHUNKS_FGS_TIMEOUT: test was skipped, not run"; exit 1
fi
