#!/usr/bin/env bash
# Manual, trusted-main, owner-authorized: installed app Stops then Starts ONE existing
# completed seeding transfer on the primary Seedhost, ending in its original state.
# Never print raw instrumentation output, logcat, names, URLs or credentials.
set -euo pipefail
: "${CHUNKS_LIVE_RTORRENT_USER:?}" "${CHUNKS_LIVE_RTORRENT_PASS:?}"
url="https://ianseeds.wood.seedhost.eu/rutorrent/"
adb shell wm size 1080x2400
adb shell wm density 420
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
b64() { printf '%s' "$1" | base64 -w 0; }
log=$(mktemp)
trap 'rm -f "$log"' EXIT
set +e
adb shell am instrument -w -r \
  -e class io.github.gamesbyian.chunks.SeedhostExistingTransferStopStartInstrumentedTest \
  -e chunks_existing_stop_start authorized \
  -e chunks_live_url_b64 "$(b64 "$url")" \
  -e chunks_live_user_b64 "$(b64 "$CHUNKS_LIVE_RTORRENT_USER")" \
  -e chunks_live_pass_b64 "$(b64 "$CHUNKS_LIVE_RTORRENT_PASS")" \
  io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner >"$log" 2>&1
status=$?
set -e
allowed='^(authorized|no-candidate|candidate|launch-failed|connect-control-missing|connect-failed|connections-missing|transfers-missing|connected|row-missing|stop-control-missing|row-expanded|stop-sent|stop-unverified|stopped-readback|start-control-missing|start-sent|start-unverified|started-readback|restored)$'
{ grep -E '^INSTRUMENTATION_STATUS: chunks_stage=' "$log" || true; } | sed 's/^INSTRUMENTATION_STATUS: chunks_stage=//; s/[[:cntrl:]]//g' |
  while read -r stage; do
    if [[ "$stage" =~ $allowed ]]; then echo "CHUNKS_STAGE: $stage"; else echo "CHUNKS_STAGE: unlisted"; fi
  done | tee -a "$GITHUB_STEP_SUMMARY"
if [ "$status" -ne 0 ] || ! grep -q '^OK (1 test)' "$log"; then
  grep -oE '^(java|kotlin|org\.junit|androidx\.compose\.ui\.test|io\.github\.gamesbyian)[A-Za-z0-9_.$]*(Exception|Error)\b' "$log" \
    | sort -u | head -5 | sed 's/^/CHUNKS_FAILURE_CLASS: /' || true
  echo "::error::Existing-transfer Stop/Start journey failed or skipped. Raw output withheld; if stopped-readback appears without started-readback, the transfer may remain stopped."
  exit 1
fi
echo "CHUNKS_EXISTING_STOP_START: installed app stopped and restarted one existing seeding transfer with exact-hash readback" | tee -a "$GITHUB_STEP_SUMMARY"
