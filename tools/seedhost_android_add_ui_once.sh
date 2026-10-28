#!/usr/bin/env bash
# Protected, dispatch-only: the installed app's REAL Add UI adds one unique
# synthetic magnet on the development Seedhost at its default Place, then the
# test stops and session-erases only that identity. Credentials, raw output and
# stack traces never leave the runner; only fixed stage labels are printed.
set -euo pipefail
: "${CHUNKS_LIVE_RUTORRENT_URL:?Missing approved host}"
: "${CHUNKS_LIVE_RTORRENT_USER:?Missing protected user}"
: "${CHUNKS_LIVE_RTORRENT_PASS:?Missing protected password}"
test "${CHUNKS_LIVE_RUTORRENT_URL}" = "https://ianseeds.wood.seedhost.eu/rutorrent/"
[[ "${GITHUB_RUN_ID:-}" =~ ^[0-9]{5,20}$ ]] || exit 1

adb shell wm size 1080x2400
adb shell wm density 420
adb shell settings put system font_scale 1.0
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
 -e class io.github.gamesbyian.chunks.SeedhostAndroidAddUiInstrumentedTest \
 -e chunks_add_ui_once authorized \
 -e chunks_unique_run "$GITHUB_RUN_ID" \
 -e chunks_live_url_b64 "$url64" \
 -e chunks_live_user_b64 "$user64" \
 -e chunks_live_pass_b64 "$pass64" \
 io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner >"$log" 2>&1
status=$?
set -e
unset url64 user64 pass64
checkpoint=$(grep -E '^INSTRUMENTATION_STATUS: chunks_add_ui_stage=' "$log" | tail -n 1 |
 sed 's/^INSTRUMENTATION_STATUS: chunks_add_ui_stage=//' | tr -d '\r' || true)
case "$checkpoint" in
 arguments|preflight-inventory|preflight-default-place|preflight-custom-place|preflight|connect-failed|queue|add-draft|review|confirmed|outcome-not-verified|app-verified|independent-readback|erase-attempted|inventory-restored) ;;
 *) checkpoint=unavailable ;;
esac
echo "CHUNKS_ANDROID_ADD_UI: synthetic identity derives from run $GITHUB_RUN_ID; last fixed stage: $checkpoint"
destination="$RUNNER_TEMP/chunks-seedhost-add-ui"
mkdir -p "$destination"
for image in seedhost-android-add-ui-review.png seedhost-android-add-ui-outcome.png; do
 adb pull "/sdcard/Pictures/ChunksAddAtlas/$image" "$destination/$image" >/dev/null 2>&1 || true
done
if [ "$status" -ne 0 ] || ! grep -q '^INSTRUMENTATION_CODE: -1' "$log" ||
   ! grep -q '^INSTRUMENTATION_STATUS_CODE: 0' "$log" ||
   grep -q '^INSTRUMENTATION_STATUS_CODE: -3' "$log" ||
   [ "$checkpoint" != inventory-restored ]; then
 echo "::error::Installed Android Add UI acceptance failed or uncertain at stage $checkpoint. Raw credentials/logs withheld. Reconcile the run's synthetic identity on the server before any retry."
 exit 1
fi
echo 'CHUNKS_ANDROID_ADD_UI: installed app UI added one synthetic magnet at a verified server Place (default, or corroborated parent when none is reported) and reported Verified add+start; independent read confirmed exact hash, started state and directory; exact Stop and session-only erase restored unrelated inventory. No payload bytes, peers or physical handset.'
