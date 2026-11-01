#!/usr/bin/env bash
# Installed MainActivity at TWO real Android viewport overrides and system 200% font.
# The API35 emulator is disposable; no app data or user files are deleted.
set -euo pipefail

root="${RUNNER_TEMP:?}/chunks-compose-system-font-200pct"
mkdir -p "$root"
sdk="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
test "$sdk" = "35" || { echo "Expected Android API35, saw $sdk" >&2; exit 1; }

for entry in "320x700:960x2100" "412x892:1236x2676"; do
  viewport="${entry%%:*}"
  pixels="${entry#*:}"
  adb shell wm size "$pixels"
  adb shell wm density 480
  adb shell settings put system font_scale 2.0
  adb shell wm size | tr -d '\r' | grep -Fx "Override size: $pixels"
  adb shell wm density | tr -d '\r' | grep -Fx "Override density: 480"
  scale="$(adb shell settings get system font_scale | tr -d '\r')"
  test "$scale" = "2.0" || { echo "Android rejected font scale for $viewport: $scale" >&2; exit 1; }

  # Actual MainActivity, localhost read-only rTorrent, navigation, screenshot and
  # semantics checks. A failed instrumentation pass aborts before the next size.
  gradle --no-daemon :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=io.github.gamesbyian.chunks.SystemFontScaleComposeInstrumentedTest \
    "-Pandroid.testInstrumentationRunnerArguments.viewport=$viewport"

  dest="$root/$viewport"
  mkdir -p "$dest"
  adb shell ls -la "/sdcard/Pictures/ChunksCompose200Pct/$viewport"
  adb pull "/sdcard/Pictures/ChunksCompose200Pct/$viewport/." "$dest"
  for screen in connection transfers-initial places organize server transfers-return combined; do
    file="$dest/chunks-compose-200pct-$screen.png"
    test -s "$file" || { echo "Missing real $viewport / 200% Compose capture $screen" >&2; exit 1; }
    test "$(od -An -tx1 -N8 "$file" | tr -d ' \n')" = "89504e470d0a1a0a"
    test "$(dd if="$file" bs=1 skip=12 count=4 status=none)" = "IHDR"
  done
  count="$(find "$dest" -maxdepth 1 -type f -name '*.png' | wc -l)"
  test "$count" -eq 7 || { echo "Expected 7 exact $viewport Compose images; found $count" >&2; exit 1; }
  sha256sum "$dest"/*.png > "$dest/CHUNKS-COMPOSE-200PCT-SHA256.txt"
  printf 'CHUNKS_REAL_COMPOSE_200PCT=PASS api=35 viewport=%s pixels=%s 7_pngs=true\n' "$viewport" "$pixels"
done

# Fail if either size was skipped or reused the other's screenshot directory.
test "$(find "$root" -mindepth 2 -maxdepth 2 -name '*.png' | wc -l)" -eq 14
printf 'CHUNKS_REAL_COMPOSE_200PCT=PASS both_viewports=320x700,412x892 captures=14\n'
