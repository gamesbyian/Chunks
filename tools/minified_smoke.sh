#!/usr/bin/env bash
# R8-shrunk (qaMinified) smoke on a running emulator, with diagnostics in the
# job log when instrumentation fails or hangs.
set -uo pipefail
out=app/build/outputs
mkdir -p "$out"
# Reuse the build-step Gradle daemon rather than launching another cold JVM.
# The R8-shrunk app and instrumentation APKs are already built at this source.
smoke_started=$SECONDS
timeout 900 gradle --daemon --info -Pchunks.testBuildType=qaMinified \
  :app:connectedQaMinifiedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.gamesbyian.chunks.MinifiedBuildSmokeInstrumentedTest \
  > "$out/qaminified-gradle.txt" 2>&1
status=$?
smoke_seconds=$((SECONDS - smoke_started))
echo "CHUNKS_R8_INSTALLED_SMOKE_SECONDS=$smoke_seconds"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  printf 'Installed R8 Android smoke: %ss (result: %s)\\n' "$smoke_seconds" "$status" >> "$GITHUB_STEP_SUMMARY"
fi
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
