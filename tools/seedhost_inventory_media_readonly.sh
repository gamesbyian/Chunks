#!/usr/bin/env bash
# Manual, trusted-main, read-only: the installed app connects to the primary Seedhost and
# its real inventory must receive automatic artwork without Identify or any remote command.
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
  -e class io.github.gamesbyian.chunks.SeedhostInventoryMediaReadOnlyInstrumentedTest \
  -e chunks_inventory_media authorized \
  -e chunks_live_url_b64 "$(b64 "$url")" \
  -e chunks_live_user_b64 "$(b64 "$CHUNKS_LIVE_RTORRENT_USER")" \
  -e chunks_live_pass_b64 "$(b64 "$CHUNKS_LIVE_RTORRENT_PASS")" \
  io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner >"$log" 2>&1
status=$?
set -e
allowed='^(authorized|inventory-empty|inventory-read|launch-failed|form-missing|form-input-failed|connect-control-missing|connect-failed|connections-missing|transfers-missing|connected|privacy-violation|inventory-changed|unchanged|no-automatic-assignment|automatic-assignment)$'
{ grep -E '^INSTRUMENTATION_STATUS: chunks_stage=' "$log" || true; } | sed 's/^INSTRUMENTATION_STATUS: chunks_stage=//; s/[[:cntrl:]]//g' |
  while read -r stage; do
    if [[ "$stage" =~ $allowed ]]; then echo "CHUNKS_STAGE: $stage"; else echo "CHUNKS_STAGE: unlisted"; fi
  done | tee -a "$GITHUB_STEP_SUMMARY"
# Counts, fixed provider ids and allowlisted public provider domains only.
counts='^inventory=[0-9]+ assigned=[0-9]+ automatic=[0-9]+ couldnt=[0-9]+ providers=(none|[a-z0-9]+:[0-9]+(,[a-z0-9]+:[0-9]+)*) hosts=(none|[a-z0-9.]+:[0-9]+(,[a-z0-9.]+:[0-9]+)*)$'
{ grep -E '^INSTRUMENTATION_STATUS: chunks_result=' "$log" || true; } | sed 's/^INSTRUMENTATION_STATUS: chunks_result=//; s/[[:cntrl:]]//g' |
  while read -r line; do
    if [[ "$line" =~ $counts ]]; then echo "CHUNKS_RESULT: $line"; else echo "CHUNKS_RESULT: unlisted"; fi
  done | tee -a "$GITHUB_STEP_SUMMARY"
if [ "$status" -ne 0 ] || ! grep -q '^OK (1 test)' "$log"; then
  grep -oE '^(java|kotlin|org\.junit|androidx\.compose\.ui\.test|io\.github\.gamesbyian)[A-Za-z0-9_.$]*(Exception|Error)\b' "$log" \
    | sort -u | head -5 | sed 's/^/CHUNKS_FAILURE_CLASS: /' || true
  grep -oE 'SeedhostInventoryMediaReadOnlyInstrumentedTest\.kt:[0-9]+' "$log" | head -4 | sed 's/^/CHUNKS_FAILURE_FRAME: /' || true
  echo "::error::Real-inventory media journey failed or skipped. Raw output withheld."
  exit 1
fi
echo "CHUNKS_INVENTORY_MEDIA: real primary inventory received automatic artwork; read-only, no private data in provider requests" | tee -a "$GITHUB_STEP_SUMMARY"
