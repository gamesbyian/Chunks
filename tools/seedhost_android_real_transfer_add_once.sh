#!/usr/bin/env bash
# Protected, trusted-main only. Install the exact APK offered to the owner.
# Both real Ubuntu fixture routes must independently download genuine bytes.
set -euo pipefail
test "$GITHUB_REF" = refs/heads/main
test "${GITHUB_RUN_ATTEMPT:-}" = 1
test "${CHUNKS_LIVE_RUTORRENT_URL:-}" = https://ianseeds.wood.seedhost.eu/rutorrent/
test -n "${CHUNKS_LIVE_RTORRENT_USER:-}"
test -n "${CHUNKS_LIVE_RTORRENT_PASS:-}"
test -s app/build/outputs/apk/debug/app-debug.apk

adb shell wm size 1080x2400
adb shell wm density 420
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
encode() { printf %s "$1" | base64 -w 0; }
url64=$(encode "$CHUNKS_LIVE_RUTORRENT_URL")
user64=$(encode "$CHUNKS_LIVE_RTORRENT_USER")
pass64=$(encode "$CHUNKS_LIVE_RTORRENT_PASS")
raw=$(mktemp)
trap 'rm -f "$raw"' EXIT
set +e
adb shell am instrument -w -r \
  -e class io.github.gamesbyian.chunks.SeedhostAndroidRealTransferAddInstrumentedTest \
  -e chunks_real_add authorized \
  -e chunks_live_url_b64 "$url64" \
  -e chunks_live_user_b64 "$user64" \
  -e chunks_live_pass_b64 "$pass64" \
  io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner \
  >"$raw" 2>&1
code=$?
set -e
unset url64 user64 pass64
# Print no stack trace, credentials, full tracker, host reply or test exception.
file=$(grep -Ec '^INSTRUMENTATION_STATUS: chunks_real_add_stage=file-passed' "$raw" || true)
magnet=$(grep -Ec '^INSTRUMENTATION_STATUS: chunks_real_add_stage=magnet-passed' "$raw" || true)
uncertain=$(grep -Ec '^INSTRUMENTATION_STATUS: chunks_real_add_stage=(file|magnet)-uncertain' "$raw" || true)
echo "CHUNKS_REAL_ANDROID_ADD: file=$file magnet=$magnet uncertain=$uncertain run=$GITHUB_RUN_ID"
if [[ "$code" -ne 0 || "$file" -ne 1 || "$magnet" -ne 1 ||
      "$uncertain" -ne 0 ]] ||
   ! grep -qx 'INSTRUMENTATION_CODE: -1' "$raw" ||
   grep -q '^INSTRUMENTATION_STATUS_CODE: -3' "$raw"; then
  echo '::error::Real installed-app .torrent + magnet received/piece-verified acceptance FAILED or inconclusive. No qualified APK; reconcile exact test hashes before a repeat.'
  exit 1
fi
mkdir -p "$RUNNER_TEMP/chunks-real-android-add"
printf 'status=passed\nsha=%s\nrun=%s\n' "$GITHUB_SHA" "$GITHUB_RUN_ID" \
  > "$RUNNER_TEMP/chunks-real-android-add/ACCEPTANCE.txt"
sha256sum app/build/outputs/apk/debug/app-debug.apk \
  >> "$RUNNER_TEMP/chunks-real-android-add/ACCEPTANCE.txt"
echo 'CHUNKS_REAL_ANDROID_ADD: both upstream public-source transfers started, retrieved real peer bytes and verified actual pieces; exact session cleanups independently verified.'
