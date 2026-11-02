#!/usr/bin/env bash
# Installed MainActivity at two real viewports and system 200% font.
# Only a pinned, disposable API35 emulator may have settings changed.
set -euo pipefail

[[ -z "${ADB_SERIAL:-}" || -z "${ANDROID_SERIAL:-}" || "$ADB_SERIAL" == "$ANDROID_SERIAL" ]] || {
  echo 'CHUNKS_FONT_200: conflicting ADB target selectors' >&2; exit 2;
}
devices="$(adb devices)" || { echo 'CHUNKS_FONT_200: ADB enumeration failed' >&2; exit 2; }
serial="${ADB_SERIAL:-${ANDROID_SERIAL:-}}"
if [[ -z "$serial" ]]; then
  mapfile -t connected < <(awk 'NR > 1 && $2 == "device" { print $1 }' <<< "$devices")
  [[ "${#connected[@]}" == 1 ]] || { echo 'CHUNKS_FONT_200: require one authorized emulator' >&2; exit 2; }
  serial="${connected[0]}"
fi
[[ "$(awk -v wanted="$serial" '$1 == wanted && $2 == "device" { n++ } END { print n+0 }' <<< "$devices")" == 1 ]] || {
  echo 'CHUNKS_FONT_200: selected target is unavailable' >&2; exit 2;
}
export ANDROID_SERIAL="$serial"
[[ "$(adb get-state | tr -d '\r\n')" == device ]] || { echo 'CHUNKS_FONT_200: target is not ready' >&2; exit 2; }
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r\n')" == 1 ]] || {
  echo 'CHUNKS_FONT_200: physical device refused before settings changes' >&2; exit 2;
}
[[ "$(adb shell getprop ro.build.version.sdk | tr -d '\r\n')" == 35 ]] || {
  echo 'CHUNKS_FONT_200: API35 emulator required' >&2; exit 2;
}

# Snapshot exact pre-existing overrides before any change. Refuse malformed or
# ambiguous responses rather than restoring guessed values to another target.
size_output="$(adb shell wm size | tr -d '\r')"
density_output="$(adb shell wm density | tr -d '\r')"
size_count="$(awk '/^Override size: / { n++ } END { print n+0 }' <<< "$size_output")"
density_count="$(awk '/^Override density: / { n++ } END { print n+0 }' <<< "$density_output")"
[[ "$size_count" -le 1 && "$density_count" -le 1 ]] || { echo 'CHUNKS_FONT_200: ambiguous prior settings' >&2; exit 2; }
[[ "$(awk '/^Physical size: [0-9]+x[0-9]+$/ { n++ } END { print n+0 }' <<< "$size_output")" == 1 &&
   "$(awk '/^Physical density: [0-9]+$/ { n++ } END { print n+0 }' <<< "$density_output")" == 1 ]] || {
  echo 'CHUNKS_FONT_200: prior display settings unavailable' >&2; exit 2;
}
old_size="$(awk '/^Override size: / { print $3 }' <<< "$size_output")"
old_density="$(awk '/^Override density: / { print $3 }' <<< "$density_output")"
old_scale="$(adb shell settings get system font_scale | tr -d '\r\n')"
[[ -z "$old_size" || "$old_size" =~ ^[0-9]+x[0-9]+$ ]] || { echo 'CHUNKS_FONT_200: invalid prior size' >&2; exit 2; }
[[ -z "$old_density" || "$old_density" =~ ^[0-9]+$ ]] || { echo 'CHUNKS_FONT_200: invalid prior density' >&2; exit 2; }
[[ "$old_scale" == null || "$old_scale" =~ ^[0-9]+([.][0-9]+)?$ ]] || { echo 'CHUNKS_FONT_200: invalid prior font scale' >&2; exit 2; }
restore() {
  local original=$? failed=0
  trap - EXIT
  set +e
  if [[ -n "$old_size" ]]; then adb shell wm size "$old_size" >/dev/null || failed=1
  else adb shell wm size reset >/dev/null || failed=1; fi
  if [[ -n "$old_density" ]]; then adb shell wm density "$old_density" >/dev/null || failed=1
  else adb shell wm density reset >/dev/null || failed=1; fi
  if [[ "$old_scale" == null ]]; then adb shell settings delete system font_scale >/dev/null || failed=1
  else adb shell settings put system font_scale "$old_scale" >/dev/null || failed=1; fi
  local restored_size restored_density restored_scale
  restored_size="$(adb shell wm size | tr -d '\r' | awk '/^Override size: / { print $3 }')" || failed=1
  restored_density="$(adb shell wm density | tr -d '\r' | awk '/^Override density: / { print $3 }')" || failed=1
  restored_scale="$(adb shell settings get system font_scale | tr -d '\r\n')" || failed=1
  [[ "$restored_size" == "$old_size" && "$restored_density" == "$old_density" && "$restored_scale" == "$old_scale" ]] || failed=1
  if [[ "$failed" != 0 ]]; then
    echo 'CHUNKS_FONT_200: could not restore one or more original settings' >&2
    [[ "$original" != 0 ]] || original=1
  fi
  exit "$original"
}
trap restore EXIT

root="${RUNNER_TEMP:?}/chunks-compose-system-font-200pct"
mkdir -p "$root"
gradle --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest
for apk in app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk; do
  installed="$(adb install -r "$apk")" || { echo 'CHUNKS_FONT_200: APK install failed' >&2; exit 1; }
  grep -Fxq 'Success' <<< "$installed" || { echo 'CHUNKS_FONT_200: package manager did not confirm installation' >&2; exit 1; }
done
class=io.github.gamesbyian.chunks.SystemFontScaleComposeInstrumentedTest
case_name=actualChunksActivityKeepsItsCoreScreensAccessibleAtRealSystemFontScale200Percent
for entry in "320x700:960x2100" "412x892:1236x2676"; do
  viewport="${entry%%:*}"
  pixels="${entry#*:}"
  adb shell wm size "$pixels"
  adb shell wm density 480
  adb shell settings put system font_scale 2.0
  adb shell wm size | tr -d '\r' | grep -Fx "Override size: $pixels"
  adb shell wm density | tr -d '\r' | grep -Fx 'Override density: 480'
  scale="$(adb shell settings get system font_scale | tr -d '\r')"
  [[ "$scale" == 2.0 ]] || { echo "Android rejected font scale for $viewport" >&2; exit 1; }

  # Preserve partial pixels after a failure, without qualifying that pass.
  dest="$root/$viewport"
  mkdir -p "$dest"
  test_exit=0
  adb shell am instrument -w -r -e class "$class#$case_name" -e viewport "$viewport" \
    io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner > "$dest/instrumentation.txt" 2>&1 || test_exit=$?
  pull_exit=0
  adb pull "/sdcard/Pictures/ChunksCompose200Pct/$viewport/." "$dest" || pull_exit=$?
  [[ "$test_exit" == 0 ]] || exit "$test_exit"
  [[ "$pull_exit" == 0 ]] || exit "$pull_exit"
  # A successful adb process alone can hide skipped or failed tests.
  report="$dest/instrumentation.txt"
  [[ "$(grep -Fxc "INSTRUMENTATION_STATUS: test=$case_name" "$report" || true)" == 2 ]] &&
  [[ "$(grep -Fxc 'OK (1 test)' "$report" || true)" == 1 ]] &&
  [[ "$(grep -Fxc 'INSTRUMENTATION_CODE: -1' "$report" || true)" == 1 ]] &&
  [[ "$(grep -c '^INSTRUMENTATION_CODE:' "$report" || true)" == 1 ]] &&
  ! grep -Eq '^INSTRUMENTATION_STATUS_CODE: -[1-9]|^FAILURES|AssumptionViolatedException' "$report" || {
    echo "CHUNKS_FONT_200: missing or unsuccessful $viewport test completion" >&2; exit 1;
  }
  for screen in connection transfers-initial places organize server transfers-return combined; do
    file="$dest/chunks-compose-200pct-$screen.png"
    [[ -s "$file" ]] || { echo "Missing real $viewport / 200% Compose capture $screen" >&2; exit 1; }
    [[ "$(od -An -tx1 -N8 "$file" | tr -d ' \n')" == 89504e470d0a1a0a ]]
    [[ "$(dd if="$file" bs=1 skip=12 count=4 status=none)" == IHDR ]]
  done
  count="$(find "$dest" -maxdepth 1 -type f -name '*.png' | wc -l)"
  [[ "$count" -eq 7 ]] || { echo "Expected 7 exact $viewport Compose images; found $count" >&2; exit 1; }
  sha256sum "$dest"/*.png > "$dest/CHUNKS-COMPOSE-200PCT-SHA256.txt"
  printf 'CHUNKS_REAL_COMPOSE_200PCT=PASS api=35 viewport=%s pixels=%s 7_pngs=true\n' "$viewport" "$pixels"
done
[[ "$(find "$root" -mindepth 2 -maxdepth 2 -name '*.png' | wc -l)" -eq 14 ]]
printf 'CHUNKS_REAL_COMPOSE_200PCT=PASS both_viewports=320x700,412x892 captures=14\n'
