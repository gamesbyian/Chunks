#!/usr/bin/env bash
# Experimental build-once native Add runner. Proven Gradle instrumentation is unchanged.
set -euo pipefail
# Validate privacy gate before any emulator work; never print arbitrary stacks.
python3 -m unittest -q tools.test_parse_instrumentation_openwith
: "$RUNNER_TEMP" "$GITHUB_SHA" "$CHUNKS_NATIVE_ADD_SHARD_COUNT" "$CHUNKS_NATIVE_ADD_SHARD_INDEX"
dir="$RUNNER_TEMP/chunks-prebuilt"
count="$CHUNKS_NATIVE_ADD_SHARD_COUNT"
index="$CHUNKS_NATIVE_ADD_SHARD_INDEX"
[[ "$count" =~ ^[1-9][0-9]*$ && "$index" =~ ^[0-9]+$ ]] || exit 2
(( count == 2 && index < count )) || exit 2
test "$(cat "$dir/source-sha")" = "$GITHUB_SHA"
test -s "$dir/chunks-app.apk"
test -s "$dir/chunks-test.apk"
(cd "$dir" && sha256sum -c SHA256SUMS)
adb shell wm size 1080x2400
adb shell wm density 420
adb shell settings put system font_scale 1.0
# Emulators may report a hardware keyboard and then hide the soft keyboard, which makes the
# installed footer/IME journey skip (seen twice in a row on #1318). Always show the IME.
adb shell settings put secure show_ime_with_hard_keyboard 1
adb install -r -t "$dir/chunks-app.apk"
adb install -r -t "$dir/chunks-test.apk"
adb shell pm list instrumentation | grep -F \
  'instrumentation:io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner (target=io.github.gamesbyian.chunks)'
# The controlled HTTPS first-positive and opt-out is an independent hard
# requirement. Test it first, before the expensive main shard, then restore
# the shard's original clean app-data starting point. Same APK bytes.
if (( index == 0 )); then
  bash tools/installed_second_add_media_consent.sh
  adb shell pm clear --user 0 io.github.gamesbyian.chunks | grep -Fx Success
fi
raw="$RUNNER_TEMP/chunks-prebuilt-status-$index.txt"
junit="app/build/outputs/androidTest-results/connected/prebuilt-shard-$index/TEST-chunks-prebuilt-$index.xml"
set +e
timeout 900 adb shell am instrument -w -r \
  -e notClass io.github.gamesbyian.chunks.SystemFontScaleComposeInstrumentedTest,io.github.gamesbyian.chunks.InstalledRealMediaInstrumentedTest,io.github.gamesbyian.chunks.InstalledSecondAddMediaConsentInstrumentedTest,io.github.gamesbyian.chunks.snapshots.RemoteSnapshotRestoreInstalledTest \
  -e numShards "$count" -e shardIndex "$index" \
  io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner > "$raw" 2>&1
adb_status=$?
python3 tools/parse_instrumentation_stream.py --raw "$raw" --junit "$junit"
junit_status=$?
set -e
mkdir -p "$RUNNER_TEMP/chunks-add-native-review"
# Retrying screenshot retrieval is allowed; never rerun test mutations.
for attempt in 1 2 3; do
  if adb pull /sdcard/Pictures/ChunksAddAtlas/. "$RUNNER_TEMP/chunks-add-native-review"; then
    break
  fi
  echo "CHUNKS_PREBUILT_INSTRUMENT: screenshot_pull_attempt=$attempt"
  sleep 3
done
if (( adb_status != 0 || junit_status != 0 )); then
  echo "CHUNKS_PREBUILT_INSTRUMENT: actual instrumentation failed; examine JUnit receipt"
  exit 1
fi
echo "CHUNKS_PREBUILT_INSTRUMENT: shard=$index completed with real JUnit and screenshots"

