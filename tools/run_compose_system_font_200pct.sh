#!/usr/bin/env bash
# Fresh Android emulator acceptance: genuine system fontScale=2.0 in MainActivity.
# Scope this run to the dedicated Compose test; ordinary Add suite remains separate.
set -euo pipefail
adb shell wm size 1080x2400
adb shell wm density 420
adb shell settings put system font_scale 2.0
scale="$(adb shell settings get system font_scale | tr -d '\r')"
test "$scale" = "2.0" || { echo "Android rejected system font scale: $scale" >&2; exit 1; }
gradle --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.gamesbyian.chunks.SystemFontScaleComposeInstrumentedTest
dest="${RUNNER_TEMP:?}/chunks-compose-system-font-200pct"
mkdir -p "$dest"
adb shell ls -la /sdcard/Pictures/ChunksCompose200Pct
adb pull /sdcard/Pictures/ChunksCompose200Pct/. "$dest"
for screen in connection transfers-initial places organize server transfers-return; do
  file="$dest/chunks-compose-200pct-$screen.png"
  test -s "$file" || { echo "Missing real 200% Compose capture $screen" >&2; exit 1; }
  test "$(od -An -tx1 -N8 "$file" | tr -d ' \n')" = "89504e470d0a1a0a"
  test "$(dd if="$file" bs=1 skip=12 count=4 status=none)" = "IHDR"
done
count="$(find "$dest" -maxdepth 1 -type f -name '*.png' | wc -l)"
test "$count" -eq 6 || { echo "Expected 6 real 200% Compose images, saw $count" >&2; exit 1; }
sha256sum "$dest"/*.png > "$dest/CHUNKS-COMPOSE-200PCT-SHA256.txt"
echo "Verified six real installed MainActivity Compose screenshots at Android system fontScale=2.0"
