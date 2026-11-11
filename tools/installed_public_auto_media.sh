#!/usr/bin/env bash
# Explicit, one-shot PUBLIC TVmaze acceptance on a disposable Android emulator.
# Reuse one source-exact build and independently verify installed APK bytes.
set -euo pipefail
test "${CHUNKS_PUBLIC_MEDIA_AUTHORIZED:-}" = "one-shot-20261110"
test "${GITHUB_EVENT_NAME:-}" = "pull_request"
test "${GITHUB_HEAD_REF:-}" = "qa/media-public-installed-20261110"
test "$(adb shell getprop ro.kernel.qemu | tr -d '[:space:]')" = "1"

pkg=io.github.gamesbyian.chunks
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
case_class=io.github.gamesbyian.chunks.InstalledAutomaticPublicMediaInstrumentedTest
candidate=app/build/outputs/apk/debug/app-debug.apk
test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
out="${RUNNER_TEMP:?}/chunks-public-auto-media"
mkdir -p "$out"
test -s "$candidate" && test -s "$test_apk"
test "$(git rev-parse HEAD)" = "${GITHUB_SHA:?}"
echo "CHUNKS_AUTO_MEDIA_SOURCE: commit=$(git rev-parse HEAD) tree=$(git rev-parse HEAD^{tree})" | tee "$out/source.txt"

adb install -r "$candidate" >/dev/null
adb install -r "$test_apk" >/dev/null
device_path="$(adb shell pm path "$pkg" | tr -d '[:space:]' | grep '^package:' | head -1 | cut -d: -f2-)"
test -n "$device_path"
aapt="${ANDROID_HOME:?}/build-tools/36.0.0/aapt"
version="$("$aapt" dump badging "$candidate" | grep -m1 '^package:' | awk -F "versionName='" '{print $2}' | cut -d "'" -f1)"
test -n "$version"
echo "CHUNKS_AUTO_MEDIA_PACKAGE: app_id=$pkg version=$version" | tee "$out/package.txt"
bash tools/verify_installed_apk_bytes.sh "$candidate" "$device_path" "$out/installed-pull-tmp.apk" | tee "$out/installed-sha256.txt"
test ! -e "$out/installed-pull-tmp.apk"

# An explicit authorization flag, emulator-only guard, and fresh stopped public
# fixture are enforced again inside the test. No real tracker or owner account.
adb logcat -c
cleanup() {
  adb shell settings put global airplane_mode_on 0 >/dev/null 2>&1 || true
  adb shell svc wifi enable >/dev/null 2>&1 || true
  adb shell svc data enable >/dev/null 2>&1 || true
  adb logcat -d -v raw -s ChunksAutoMedia:I 2>/dev/null |
    grep '^CHUNKS_AUTO_PUBLIC ' > "$out/public-witness.txt" || true
  adb pull "/sdcard/Android/data/$pkg/files/auto-real-media-evidence/." "$out/" >/dev/null 2>&1 || true
}
trap cleanup EXIT
run_phase() {
  local method="$1" result=success
  if ! timeout 240s adb shell am instrument -w -r \
      -e class "$case_class#$method" -e chunks_real_media authorized "$runner" \
      > "$out/$method.raw" 2>&1; then
    result=failure
  fi
  python3 tools/parse_instrumentation_stream.py --raw "$out/$method.raw" --junit "$out/$method.xml"
  python3 tools/ci_failure_digest.py --suite "installed-public-auto-media-$method" --status "$result" \
    --require-executed --glob "$out/$method.xml" --output "$out/$method-digest.json"
}

# Live phase: insert a stopped torrent with a private.invalid placeholder tracker,
# keep its row filtered OFF screen, require genuine authorized TVmaze/CDN HTTP,
# cache a processed JPEG, then inspect actual row pixels and full Details poster.
run_phase phaseA_liveAutomaticArtworkWithoutIdentify
adb shell am force-stop "$pkg"

# Real process boundary and offline radio state. The second phase rejects any
# attempted outbound HTTPS and demands restored pixels and persisted identity.
adb shell svc wifi disable
adb shell svc data disable
adb shell settings put global airplane_mode_on 1
adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true >/dev/null 2>&1 || true
run_phase phaseB_offlineColdProcessRestoresRealArtwork
adb shell am force-stop "$pkg"

adb logcat -d -v raw -s ChunksAutoMedia:I |
  grep '^CHUNKS_AUTO_PUBLIC ' > "$out/public-witness.txt"
grep -Fx 'CHUNKS_AUTO_PUBLIC phase=online consent=true automatic=true verified=true row_pixels=true details_poster=true sanitized=true' "$out/public-witness.txt"
grep -Fx 'CHUNKS_AUTO_PUBLIC phase=offline new_process=true persisted=true row_pixels=true requests=0' "$out/public-witness.txt"
adb pull "/sdcard/Android/data/$pkg/files/auto-real-media-evidence/." "$out/" >/dev/null 2>&1
test -s "$out/online.png" && test -s "$out/offline.png"
echo 'CHUNKS_AUTO_PUBLIC_ACCEPTANCE: passed phases=2 real_tvmaze_http=true real_cdn_jpeg=true offscreen=true canonical_pixels=true offline_restart=true'
