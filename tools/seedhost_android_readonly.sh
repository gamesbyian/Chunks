#!/usr/bin/env bash
# Manual-only: actual installed APK -> protected development Seedhost.
# Never echo, upload or retain credentials or raw instrumentation output.
set -euo pipefail
: "${CHUNKS_LIVE_RUTORRENT_URL:?The operator must provide a credential-free HTTPS URL}"
: "${CHUNKS_LIVE_RTORRENT_USER:?Protected username secret required}"
: "${CHUNKS_LIVE_RTORRENT_PASS:?Protected password secret required}"
case "$CHUNKS_LIVE_RUTORRENT_URL" in
  https://*) ;;
  *) echo "::error::Protected remote Android QA requires HTTPS"; exit 1 ;;
esac

adb shell wm size 1080x2400
adb shell wm density 420
adb shell settings put system font_scale 1.0
test "$(git rev-parse HEAD)" = "${GITHUB_SHA:?}"
candidate=app/build/outputs/apk/debug/app-debug.apk
adb install -r "$candidate" >/dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
out="$RUNNER_TEMP/chunks-seedhost-android"
mkdir -p "$out"
device_path="$(adb shell pm path io.github.gamesbyian.chunks | tr -d '\r' | sed -n 's/^package://p')"
bash tools/verify_installed_apk_bytes.sh "$candidate" "$device_path" "$out/installed.apk" | tee "$out/installed.txt"
git show -s --format='source=%H tree=%T' HEAD >> "$out/installed.txt"

encode() { printf '%s' "$1" | base64 -w 0; }
url64=$(encode "$CHUNKS_LIVE_RUTORRENT_URL")
user64=$(encode "$CHUNKS_LIVE_RTORRENT_USER")
pass64=$(encode "$CHUNKS_LIVE_RTORRENT_PASS")
log=$(mktemp)
trap 'rm -f "$log"' EXIT
# All credentials are base64-encoded solely for safe shell argument framing;
# they are NOT encrypted. Never print the command, arguments or raw log.
set +e
adb shell am instrument -w -r \
  -e class io.github.gamesbyian.chunks.SeedhostAndroidReadOnlyInstrumentedTest \
  -e chunks_live_url_b64 "$url64" \
  -e chunks_live_user_b64 "$user64" \
  -e chunks_live_pass_b64 "$pass64" \
  io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner \
  >"$log" 2>&1
status=$?
set -e
unset url64 user64 pass64

if [ "$status" -ne 0 ] || ! grep -q '^INSTRUMENTATION_CODE: -1' "$log" ||
   ! grep -q '^INSTRUMENTATION_STATUS_CODE: 0' "$log" ||
   grep -q '^INSTRUMENTATION_STATUS_CODE: -3' "$log"; then
  # Enumerated milestones are generated in the test and contain no untrusted
  # strings. Never print raw stack traces, instrumentation output or logcat:
  # those could include user/pass or auth headers.
  checkpoint=$(grep -E '^INSTRUMENTATION_STATUS: chunks_stage=' "$log" | tail -n 1 | sed 's/^INSTRUMENTATION_STATUS: chunks_stage=//' | sed 's/[[:cntrl:]]//g' || true)
  case "$checkpoint" in
    arguments|ground-truth|launch-failed|form-missing|form-open|form-input-failed|form|connect-visible|connect|verified|connections|transfers|inventory|inventory-mismatch|folder-readback|folder-picker|add-open|add-preview|add-return|connect-error|connect-busy|connect-login-form|connect-no-visible-state|connect-identity-unverified|connect-auth-rejected|connect-tls-failed|connect-network-failed|connect-protocol-failed|connect-service-failed|connect-endpoint-missing|connect-proxy-or-rate-limited) ;;
    *) checkpoint=unavailable ;;
  esac
  grep -oE 'SeedhostAndroidReadOnlyInstrumentedTest\.kt:[0-9]+' "$log" | head -4 | sed 's/^/CHUNKS_FAILURE_FRAME: /' || true
  echo "::error::Protected Android Seedhost UI acceptance failed or skipped. Last safe stage: $checkpoint. Raw output withheld."
  exit 1
fi

for stage in verified inventory folder-readback folder-picker add-return; do
  grep -Eq "^INSTRUMENTATION_STATUS: chunks_stage=$stage[[:space:]]*$" "$log" || {
    echo "::error::Required protected read-only stage absent: $stage"
    exit 1
  }
done
echo "CHUNKS_ANDROID_SEEDHOST: authenticated_https=true independent_inventory=true folder_picker=true place_unchanged=true writes=false" | tee "$out/receipt.txt"
