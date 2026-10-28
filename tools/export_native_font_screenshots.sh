#!/usr/bin/env bash
# Export 30 genuine native Android font screenshots after Gradle may have
# uninstalled the test app. MediaStore files survive this lifecycle event.
set -euo pipefail
dest="${RUNNER_TEMP:?}/chunks-stacked-android-font"
mkdir -p "$dest"
src="/sdcard/Pictures/ChunksFontAtlas"
echo "Inspecting published native font evidence: $src"
adb shell ls -la "$src"
adb pull "$src/." "$dest"
for style in Regular Medium Semibold Bold ExtraBold; do
  for size in 100pct 200pct; do
    for sample in long_filename stacked_marks transfer_stats; do
      name="chunks-stacked-${style}-${size}-${sample}.png"
      file="$dest/$name"
      test -s "$file" || { echo "Missing screenshot: $file" >&2; exit 1; }
      # Verify PNG signature and IHDR rather than trusting a nonempty adb error.
      test "$(od -An -tx1 -N8 "$file" | tr -d ' \n')" = "89504e470d0a1a0a"
      test "$(dd if="$file" bs=1 skip=12 count=4 status=none)" = "IHDR"
    done
  done
done
count="$(find "$dest" -maxdepth 1 -type f -name '*.png' | wc -l)"
test "$count" -eq 30 || { echo "Expected exactly 30 actual PNGs; saw $count" >&2; exit 1; }
sha256sum "$dest"/*.png > "$dest/CHUNKS-NATIVE-FONT-RENDER-SHA256.txt"
printf 'Verified %s persistent native Android MediaStore screenshots\n' "$count"
