#!/usr/bin/env bash
# Fresh Android emulator acceptance: genuine system fontScale=2.0 in MainActivity.
# Scope this run to the dedicated Compose test; ordinary Add suite remains separate.
set -euo pipefail
adb shell wm size 1080x2400
adb shell wm density 420
adb shell settings put system font_scale 2.0
scale="$(adb shell settings get system font_scale | tr -d '\r')"
test "$scale" = "2.0" || { echo "Android rejected system font scale: $scale" >&2; exit 1; }
python3 tools/check_compose_capture_contract.py --preflight
test_exit=0
gradle --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.gamesbyian.chunks.SystemFontScaleComposeInstrumentedTest || test_exit=$?
dest="${RUNNER_TEMP:?}/chunks-compose-system-font-200pct"
mkdir -p "$dest"
# Keep partial real pixels after an assertion; they explain the last completed
# screen. Preserve the original test failure and never qualify partial output.
pull_exit=0
adb pull /sdcard/Pictures/ChunksCompose200Pct/. "$dest" || pull_exit=$?
if [ "$test_exit" -ne 0 ]; then exit "$test_exit"; fi
if [ "$pull_exit" -ne 0 ]; then exit "$pull_exit"; fi
python3 tools/check_compose_capture_contract.py --verify-dir "$dest"
sha256sum "$dest"/*.png > "$dest/CHUNKS-COMPOSE-200PCT-SHA256.txt"
echo "Verified current source-derived installed MainActivity Compose screenshots at Android system fontScale=2.0"
