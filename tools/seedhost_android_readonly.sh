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
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null

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
  checkpoint=$(grep -E '^INSTRUMENTATION_STATUS: chunks_stage=' "$log" | tail -n 1 | sed 's/^INSTRUMENTATION_STATUS: chunks_stage=//' | tr -d '\\r' || true)
  case "$checkpoint" in
    arguments|form|connect|queue|inventory|first-capture|refresh-click|refresh-busy|refresh-idle|final-capture) ;;
    *) checkpoint=unavailable ;;
  esac
  echo "::error::Protected Android Seedhost UI acceptance failed or skipped. Last safe stage: $checkpoint. Raw output withheld."
  exit 1
fi

destination="$RUNNER_TEMP/chunks-seedhost-android"
mkdir -p "$destination"
for image in seedhost-android-queue-live.png seedhost-android-queue-refreshed-live.png; do
  adb pull "/sdcard/Pictures/ChunksAddAtlas/$image" "$destination/$image" >/dev/null
done
python3 - <<'PY'
from pathlib import Path
import os, struct
root=Path(os.environ["RUNNER_TEMP"])/"chunks-seedhost-android"
for name in ("seedhost-android-queue-live.png","seedhost-android-queue-refreshed-live.png"):
    data=(root/name).read_bytes()
    assert data[:8] == bytes((137, 80, 78, 71, 13, 10, 26, 10))
    assert data[12:16]==b"IHDR"
    width,height=struct.unpack(">II", data[16:24])
    assert width>=300 and height>=500
    print("CHUNKS_ANDROID_SEEDHOST:", name, width, height)
PY
echo "CHUNKS_ANDROID_SEEDHOST: installed app connected over HTTPS, rendered a nonempty development queue and refreshed without visible error"
