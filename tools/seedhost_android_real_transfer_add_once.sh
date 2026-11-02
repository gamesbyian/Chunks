#!/usr/bin/env bash
# Protected, trusted-main only. Install the exact APK offered to the owner.
# Two different real magnet fixture routes must independently download genuine bytes.
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
  -e class io.github.gamesbyian.chunks.SeedhostAndroidRealTransferAddInstrumentedTest#installedMagnetAddResolvesMetadataAndReceivesVerifiedPeerBytes,io.github.gamesbyian.chunks.SeedhostAndroidRealTransferAddInstrumentedTest#installedAlternateMagnetResolvesMetadataAndReceivesVerifiedPeerBytes \
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
magnet=$(grep -Ec '^INSTRUMENTATION_STATUS: chunks_real_add_stage=magnet-passed' "$raw" || true)
magnet2=$(grep -Ec '^INSTRUMENTATION_STATUS: chunks_real_add_stage=magnet2-passed' "$raw" || true)
uncertain=$(grep -Ec '^INSTRUMENTATION_STATUS: chunks_real_add_stage=(magnet|magnet2)-uncertain' "$raw" || true)
# Independently print only allowlisted test-phase counts. Never print raw
# instrumentation payload, URLs, exceptions or credentials.
for label in magnet magnet2; do
  summary=""
  for stage in preflight review ui-verified registered readback-error metadata peer-progress bytes-verified restarted passed uncertain; do
    count=$(grep -Ec "^INSTRUMENTATION_STATUS: chunks_real_add_stage=${label}-${stage}$" "$raw" || true)
    summary="${summary} ${stage}=${count}"
  done
  echo "CHUNKS_REAL_ANDROID_ADD_PHASE: test=${label}${summary} run=$GITHUB_RUN_ID"
done
last=$(grep -E '^INSTRUMENTATION_STATUS: chunks_real_add_stage=(magnet|magnet2)-(preflight|review|ui-verified|registered|readback-error|metadata|peer-progress|bytes-verified|restarted|passed|uncertain)$' "$raw" | tail -n 1 | sed 's/^INSTRUMENTATION_STATUS: chunks_real_add_stage=//' || true)
[[ "$last" =~ ^(magnet|magnet2)-(preflight|review|ui-verified|registered|readback-error|metadata|peer-progress|bytes-verified|restarted|passed|uncertain)$ ]] || last=unavailable
echo "CHUNKS_REAL_ANDROID_ADD: magnet=$magnet magnet2=$magnet2 uncertain=$uncertain last_stage=$last run=$GITHUB_RUN_ID"
if [[ "$code" -ne 0 || "$magnet" -ne 1 || "$magnet2" -ne 1 ||
      "$uncertain" -ne 0 ]] ||
   ! grep -qx 'INSTRUMENTATION_CODE: -1' "$raw" ||
   grep -q '^INSTRUMENTATION_STATUS_CODE: -3' "$raw"; then
  echo '::error::Real installed-app two-magnet received/piece-verified acceptance FAILED or inconclusive. No qualified APK; reconcile exact test hashes before a repeat.'
  exit 1
fi
mkdir -p "$RUNNER_TEMP/chunks-real-android-add"
printf 'status=two-magnets-passed\nprior-file-receipt=41363266446\nsha=%s\nrun=%s\n' "$GITHUB_SHA" "$GITHUB_RUN_ID" \
  > "$RUNNER_TEMP/chunks-real-android-add/ACCEPTANCE.txt"
sha256sum app/build/outputs/apk/debug/app-debug.apk \
  >> "$RUNNER_TEMP/chunks-real-android-add/ACCEPTANCE.txt"
echo 'CHUNKS_REAL_ANDROID_ADD: both distinct upstream magnet transfers started, retrieved real peer bytes and verified actual pieces; exact session cleanups independently verified.'
