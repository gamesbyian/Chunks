#!/usr/bin/env bash
# R8-shrunk (qaMinified) smoke on a running emulator, with diagnostics in the
# job log when instrumentation fails or hangs.
set -uo pipefail
out=app/build/outputs
mkdir -p "$out"
timeout 900 gradle --no-daemon --info -Pchunks.testBuildType=qaMinified \
  :app:connectedQaMinifiedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.gamesbyian.chunks.MinifiedBuildSmokeInstrumentedTest \
  > "$out/qaminified-gradle.txt" 2>&1
status=$?
grep -E -i "install|instrument|Starting|tests on|FAIL|exception|timed out" "$out/qaminified-gradle.txt" | tail -80
if [ "$status" -ne 0 ]; then
  echo "CHUNKS_MINIFIED_SMOKE: gradle exit $status"
  adb shell pm list instrumentation
  adb shell pm list packages | grep -i chunks
  adb logcat -d > "$out/qaminified-logcat.txt"
  grep -iE "AndroidRuntime|FATAL|TestRunner|Instrumentation|ClassNotFound|NoSuchMethod|NoClassDef|gamesbyian" \
    "$out/qaminified-logcat.txt" | tail -150
  exit 1
fi
