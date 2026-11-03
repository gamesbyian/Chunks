#!/usr/bin/env bash
# Explicitly authorized disposable Android emulator ONLY.
# No delete request is launched and no user files are selected.
set -euo pipefail
: "${RUNNER_TEMP:?}" "${GITHUB_SHA:?}"
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
out="$RUNNER_TEMP/chunks-mediastore-posix"
mkdir -p "$out"
set +e
timeout 100 adb shell am instrument -w -r \
  -e chunks_disposable_media_store authorized \
  -e class io.github.gamesbyian.chunks.NativeDownloadsMediaStoreIdentityInstrumentedTest \
  io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner \
  > "$out/raw.txt" 2>&1
command_status=$?
python3 tools/parse_instrumentation_stream.py \
  --raw "$out/raw.txt" --junit "$out/TEST-mediastore-posix.xml"
junit_status=$?
set -e
if (( command_status != 0 || junit_status != 0 )); then
  echo "CHUNKS_MEDIASTORE_POSIX: source-bound disposable probe failed; inspect actual JUnit"
  exit 1
fi
echo "CHUNKS_MEDIASTORE_POSIX: exact Android test completed"
