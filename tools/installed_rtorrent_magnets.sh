#!/usr/bin/env bash
# Install and exercise the same preview APK that can subsequently be offered.
set -euo pipefail
umask 077
test "${CHUNKS_CONTROLLED_PEER:-}" = 1
test "${GITHUB_RUN_ATTEMPT:-}" = 1
test "${CHUNKS_SOURCE_SHA:-}" = "$GITHUB_SHA"
test "$(git rev-parse HEAD)" = "$GITHUB_SHA"
test -s app/build/outputs/apk/debug/app-debug.apk
test -s app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
args=(-e chunks_controlled_peer authorized -e chunks_acceptance_run "$GITHUB_RUN_ID")
if [[ "${CHUNKS_PUBLIC_DISCOVERY:-}" = 1 ]]; then
  args+=(-e chunks_public_discovery authorized)
fi
if [[ "${CHUNKS_DISPOSABLE:-}" = 1 ]]; then
  test "$CHUNKS_LIVE_RUTORRENT_URL" = http://127.0.0.1:18080/RPC2
  args+=(-e chunks_disposable authorized)
else
  test "$GITHUB_REF" = refs/heads/main
  test "$CHUNKS_LIVE_RUTORRENT_URL" = https://ianseeds.wood.seedhost.eu/rutorrent/
  test -n "${CHUNKS_LIVE_RTORRENT_USER:-}"
  test -n "${CHUNKS_LIVE_RTORRENT_PASS:-}"
  user64=$(printf %s "$CHUNKS_LIVE_RTORRENT_USER" | base64 -w 0)
  pass64=$(printf %s "$CHUNKS_LIVE_RTORRENT_PASS" | base64 -w 0)
  args+=(-e chunks_live_user_b64 "$user64" -e chunks_live_pass_b64 "$pass64")
fi
url64=$(printf %s "$CHUNKS_LIVE_RUTORRENT_URL" | base64 -w 0)
adb reverse tcp:18080 tcp:18080 >/dev/null
adb reverse tcp:18081 tcp:18081 >/dev/null
adb shell wm size 1080x2400
adb shell wm density 420
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
candidate_package="io.github.gamesbyian.chunks.${CHUNKS_PREVIEW_SUFFIX:?}"
installed_package=$(adb shell dumpsys package "$candidate_package")
grep -Fq "versionName=0.1.0-dev+${GITHUB_SHA:0:12}" <<< "$installed_package"
unset installed_package
installed_apk=$(adb shell pm path "$candidate_package")
installed_apk=${installed_apk#package:}
installed_apk=${installed_apk//$'\r'/}
adb pull "$installed_apk" "$RUNNER_TEMP/chunks-installed-base.apk" >/dev/null 2>&1
cmp -s app/build/outputs/apk/debug/app-debug.apk "$RUNNER_TEMP/chunks-installed-base.apk"
rm "$RUNNER_TEMP/chunks-installed-base.apk"
unset installed_apk
acceptance_started=$(date +%s)
set +e
adb shell am instrument -w -r \
  -e class io.github.gamesbyian.chunks.SeedhostAndroidAddUiInstrumentedTest#installedAppAddsOneSyntheticMagnetAtAVerifiedServerPlace \
  -e chunks_real_add authorized -e chunks_live_url_b64 "$url64" "${args[@]}" \
  "${candidate_package}.test/androidx.test.runner.AndroidJUnitRunner" \
  > "$RUNNER_TEMP/chunks-installed-magnets.raw" 2>&1
acceptance_status=$?
set -e
printf '%s\n' "$acceptance_status" > "$RUNNER_TEMP/chunks-installed-magnets.exit"
printf '%s\n' "$(( $(date +%s) - acceptance_started ))" > "$RUNNER_TEMP/chunks-installed-magnets.runtime"
unset url64 user64 pass64 args
echo 'CHUNKS_INSTALLED_MAGNET: instrumentation process completed; structured evidence follows'
