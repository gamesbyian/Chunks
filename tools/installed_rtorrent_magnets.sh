#!/usr/bin/env bash
# Install and exercise the same preview APK that can subsequently be offered.
set -euo pipefail
umask 077
milestone() { printf 'CHUNKS_INSTALLED_MAGNET_PREFLIGHT: %s\n' "$1"; }
milestone 'checking source and fixture configuration'
test "${CHUNKS_CONTROLLED_PEER:-}" = 1
if [[ "${CHUNKS_DISPOSABLE:-}" != 1 ]]; then
  # Owner-authorized live Seedhost changes are one-time only. A failed
  # PR disposable run may be retried after transient GitHub runner trouble.
  test "${GITHUB_RUN_ATTEMPT:-}" = 1
fi
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
if [[ "${CHUNKS_HTTPS_ADD:-0}" = 1 ]]; then
  # Public URLs are fetched by the production Android source path. Never enable
  # this test-only selector through an incoming URL or an app preference.
  args+=(-e chunks_https_add authorized)
fi
url64=$(printf %s "$CHUNKS_LIVE_RUTORRENT_URL" | base64 -w 0)
milestone 'configuring emulator and installing APKs'
adb reverse tcp:18080 tcp:18080 >/dev/null
adb reverse tcp:18081 tcp:18081 >/dev/null
adb shell wm size 1080x2400
adb shell wm density 420
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
candidate_package="io.github.gamesbyian.chunks.${CHUNKS_PREVIEW_SUFFIX:?}"
milestone 'checking installed version'
installed_package=$(adb shell dumpsys package "$candidate_package")
grep -Fq "versionName=0.1.0-dev+${GITHUB_SHA:0:12}" <<< "$installed_package"
unset installed_package
installed_apk=$(adb shell pm path "$candidate_package")
installed_apk=${installed_apk#package:}
installed_apk=${installed_apk//$'\r'/}
milestone 'comparing installed APK bytes'
adb pull "$installed_apk" "$RUNNER_TEMP/chunks-installed-base.apk" >/dev/null 2>&1
cmp -s app/build/outputs/apk/debug/app-debug.apk "$RUNNER_TEMP/chunks-installed-base.apk"
rm "$RUNNER_TEMP/chunks-installed-base.apk"
unset installed_apk
phone_status=0
if [[ "${CHUNKS_HTTPS_ADD:-0}" = 1 ]]; then
  set +e
  bash tools/installed_https_phone.sh "$candidate_package"
  phone_status=$?
  set -e
  adb shell am force-stop "$candidate_package"
fi
milestone 'starting Android instrumentation'
acceptance_started=$(date +%s)
set +e
adb shell am instrument -w -r \
  -e class io.github.gamesbyian.chunks.SeedhostAndroidAddUiInstrumentedTest#installedAppAddsOneSyntheticMagnetAtAVerifiedServerPlace \
  -e chunks_real_add authorized -e chunks_live_url_b64 "$url64" "${args[@]}" \
  "${candidate_package}.test/androidx.test.runner.AndroidJUnitRunner" \
  > "$RUNNER_TEMP/chunks-installed-magnets.raw" 2>&1
acceptance_status=$?
set -e
adb logcat -d -v raw -s ChunksPeer:I > "$RUNNER_TEMP/chunks-peer-ui-witness.txt"
# Snapshot the core attempt before longer independent provider cases can age out
# the bounded native ring. This is a read-only diagnostic, not acceptance success.
curl -fsS --max-time 5 http://127.0.0.1:18081/diagnostics \
  -o "$RUNNER_TEMP/chunks-peer-core-timeline.json" || echo 'CHUNKS_PEER: core timing snapshot unavailable'
printf '%s\n' "$acceptance_status" > "$RUNNER_TEMP/chunks-installed-magnets.exit"
printf '%s\n' "$(( $(date +%s) - acceptance_started ))" > "$RUNNER_TEMP/chunks-installed-magnets.runtime"
unset url64 user64 pass64 args
echo 'CHUNKS_INSTALLED_MAGNET: instrumentation process completed; structured evidence follows'

# Preserve separately attributable outcomes on the same installed APK/emulator.
# Finish still refuses publication unless every requested source-bound case passed.
media_status=0
if [[ "${CHUNKS_REAL_MEDIA:-0}" = 1 ]]; then
  set +e
  bash tools/installed_real_media.sh "$candidate_package"
  media_status=$?
  set -e
  adb shell am force-stop "$candidate_package"
fi
if (( phone_status != 0 || media_status != 0 )); then
  echo 'CHUNKS_INSTALLED_SOURCE: a requested phone/media case failed; all evidence retained; no qualified APK'
  exit 1
fi
