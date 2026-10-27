#!/usr/bin/env bash
# One-shot protected installed-Android rTorrent ADAPTER mutation. Credentials,
# raw output and stack traces never leave the CI runner. No screenshot capture.
set -euo pipefail
: "${CHUNKS_LIVE_RUTORRENT_URL:?Missing approved host}"
: "${CHUNKS_LIVE_RTORRENT_USER:?Missing protected user}"
: "${CHUNKS_LIVE_RTORRENT_PASS:?Missing protected password}"
test "${CHUNKS_LIVE_RUTORRENT_URL}" = "https://ianseeds.wood.seedhost.eu/rutorrent/"
[[ "${GITHUB_RUN_ID:-}" =~ ^[0-9]{5,20}$ ]] || exit 1
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
encode() { printf '%s' "$1" | base64 -w 0; }
url64=$(encode "$CHUNKS_LIVE_RUTORRENT_URL")
user64=$(encode "$CHUNKS_LIVE_RTORRENT_USER")
pass64=$(encode "$CHUNKS_LIVE_RTORRENT_PASS")
log=$(mktemp)
trap 'rm -f "$log"' EXIT
set +e
adb shell am instrument -w -r \
 -e class io.github.gamesbyian.chunks.SeedhostAndroidScopedMutationInstrumentedTest \
 -e chunks_mutation_once authorized \
 -e chunks_unique_run "$GITHUB_RUN_ID" \
 -e chunks_live_url_b64 "$url64" \
 -e chunks_live_user_b64 "$user64" \
 -e chunks_live_pass_b64 "$pass64" \
 io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner >"$log" 2>&1
status=$?
set -e
unset url64 user64 pass64
checkpoint=$(grep -E '^INSTRUMENTATION_STATUS: chunks_mutation_stage=' "$log" | tail -n 1 |
 sed 's/^INSTRUMENTATION_STATUS: chunks_mutation_stage=//' | tr -d '\r' || true)
case "$checkpoint" in
 authorized|preflight|loaded|stopped-readback|active-readback|stopped-again|erase-attempted|inventory-restored) ;;
 *) checkpoint=unavailable ;;
esac
if [ "$status" -ne 0 ] || ! grep -q '^INSTRUMENTATION_CODE: -1' "$log" ||
   ! grep -q '^INSTRUMENTATION_STATUS_CODE: 0' "$log" ||
   [ "$checkpoint" != inventory-restored ]; then
 echo "::error::Android scoped Seedhost mutation acceptance failed or uncertain; last fixed stage: $checkpoint. Raw credentials/logs withheld. Independently reconcile the generated synthetic identity before any retry."
 exit 1
fi
echo 'CHUNKS_ANDROID_MUTATION: installed Android rTorrent adapter verified exact synthetic Add, active Start, Stop and session-only erase; unrelated inventory identity restored. No peer bytes or app-UI proof.'
