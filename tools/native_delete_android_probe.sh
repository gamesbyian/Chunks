#!/usr/bin/env bash
# Deliberately isolated research: no production Remove call and no public files.
set -euo pipefail
: "${RUNNER_TEMP:?}" "${GITHUB_SHA:?}"
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
out="$RUNNER_TEMP/chunks-native-delete-android"
mkdir -p "$out"
set +e
timeout 90 adb shell am instrument -w -r \
  -e chunks_disposable_native_delete authorized \
  -e class io.github.gamesbyian.chunks.NativeDeleteFilesBoundaryInstrumentedTest \
  io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner \
  > "$out/raw.txt" 2>&1
command_status=$?
python3 tools/parse_instrumentation_stream.py \
  --raw "$out/raw.txt" --junit "$out/TEST-native-delete.xml"
junit_status=$?
set -e
if (( command_status != 0 || junit_status != 0 )); then
  echo "CHUNKS_DELETE_RESEARCH: actual isolated Android assertion/runner failed"
  exit 1
fi
echo "CHUNKS_DELETE_RESEARCH: isolated Android JUnit executed and passed"
