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
# The emulator reaches the runner's SCGI proxy (18080) and fixture controller
# (18081) only through adb reverse. A rule dropped by an adbd reconnect shows up
# inside the app as a bare ConnectException (run 43171621341) and would be
# misread as an Add or test defect. Re-assert and verify right before every
# instrumentation; a missing rule or dead controller fails closed by name.
tunnel() {
  adb reverse tcp:18080 tcp:18080 >/dev/null
  adb reverse tcp:18081 tcp:18081 >/dev/null
  local rules port
  rules=$(adb reverse --list)
  for port in 18080 18081; do
    grep -Eq "tcp:${port} tcp:${port}" <<< "$rules" || {
      echo "CHUNKS_INSTALLED_MAGNET_TUNNEL: adb reverse rule missing for loopback port ${port}"
      return 1
    }
  done
  curl -fsS --max-time 5 -o /dev/null http://127.0.0.1:18081/ready || {
    echo 'CHUNKS_INSTALLED_MAGNET_TUNNEL: fixture controller unreachable on the runner'
    return 1
  }
  echo 'CHUNKS_INSTALLED_MAGNET_TUNNEL: verified reverse rules and live controller'
}
milestone 'configuring emulator and installing APKs'
tunnel
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
# Keep the exact byte gate, but distinguish failed emulator pull from changed
# installed bytes. The helper prints fixed diagnostics and deletes its temp copy.
# Preserve the cryptographic installed-byte identity even when a later
# network/Details/lifecycle step fails. Pipefail forbids a false green pull.
bash tools/verify_installed_apk_bytes.sh \
  app/build/outputs/apk/debug/app-debug.apk "$installed_apk" "$RUNNER_TEMP/chunks-installed-base.apk" \
  | tee "$RUNNER_TEMP/chunks-installed-apk-identity.txt"
unset installed_apk
if [[ "${CHUNKS_HTTPS_ADD:-0}" = 1 ]]; then
  milestone 'running production local HTTPS and one-time Place journey'
  tunnel
  adb logcat -c
  adb shell am instrument -w -r \
    -e class io.github.gamesbyian.chunks.HttpsLocalAddJourneyInstrumentedTest \
    -e chunks_https_add authorized \
    "${candidate_package}.test/androidx.test.runner.AndroidJUnitRunner" \
    > "$RUNNER_TEMP/chunks-https-local.raw" 2>&1
  # Capture the disposable public-fixture process crash before parsing can fail.
  # No account has been entered at this point; never retain a protected host log.
  if [[ "${CHUNKS_DISPOSABLE:-0}" = 1 ]]; then
    adb logcat -d -b crash > "$RUNNER_TEMP/chunks-https-local-crash.txt"
  fi
  adb logcat -d -v raw -s ChunksHttps:I > "$RUNNER_TEMP/chunks-https-local-witness.txt"
  python3 tools/parse_instrumentation_stream.py --raw "$RUNNER_TEMP/chunks-https-local.raw" \
    --junit "$RUNNER_TEMP/chunks-https-local.xml"
  python3 tools/ci_failure_digest.py --suite https-local --status success --require-executed \
    --glob "$RUNNER_TEMP/chunks-https-local.xml" --output "$RUNNER_TEMP/chunks-https-local-digest.json"
  grep -E '^CHUNKS_HTTPS_LOCAL verified=true received=[1-9][0-9]* pieces_bytes=[1-9][0-9]* complete=false default_preserved=true piece_sha256=[0-9a-f]{64}$' \
    "$RUNNER_TEMP/chunks-https-local-witness.txt"
  adb shell am force-stop "$candidate_package"
fi
milestone 'starting Android instrumentation'
tunnel
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
