#!/usr/bin/env bash
# Export exactly 30 native TextView screenshots from the installed debug app.
# reactivecircus invokes its 'script' input via sh -c; use this standalone
# executable to ensure nested loops and quoting survive intact.
set -euo pipefail
dest="${RUNNER_TEMP:?}/chunks-stacked-android-font"
mkdir -p "$dest"
echo 'Native Android font evidence preflight:'
adb shell pm path io.github.gamesbyian.chunks || true
adb shell run-as io.github.gamesbyian.chunks ls -la files || true
adb shell run-as io.github.gamesbyian.chunks ls -la files/font-acceptance || true
find app/build/outputs/androidTest-results/connected -type f -name '*.xml' -print -exec grep -Eh 'testsuite|testcase|skipped|failure' {} \; | tail -30 || true
set -x
for style in Regular Medium Semibold Bold ExtraBold; do
  for size in 100pct 200pct; do
    for sample in long_filename stacked_marks transfer_stats; do
      name="chunks-stacked-${style}-${size}-${sample}.png"
      adb exec-out run-as io.github.gamesbyian.chunks cat "files/font-acceptance/$name" > "$dest/$name"
      test -s "$dest/$name"
      # PNG header (89 50 4e 47) is independent of image content.
      test "$(od -An -tx1 -N4 "$dest/$name" | tr -d ' \n')" = "89504e47"
    done
  done
done
count="$(find "$dest" -maxdepth 1 -type f -name '*.png' | wc -l)"
test "$count" -eq 30
sha256sum "$dest"/*.png > "$dest/CHUNKS-NATIVE-FONT-RENDER-SHA256.txt"
printf 'Exported %s genuine native Android PNG screenshots\n' "$count"
