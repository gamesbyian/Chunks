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
  echo "::error::Protected Android Seedhost UI acceptance failed or skipped. Raw output withheld because it could contain credentials."
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
    assert data[:8]==b"\\x89PNG\\r\\n\\x1a\\n"
    assert data[12:16]==b"IHDR"
    width,height=struct.unpack(">II", data[16:24])
    assert width>=300 and height>=500
    print("CHUNKS_ANDROID_SEEDHOST:", name, width, height)
PY
echo "CHUNKS_ANDROID_SEEDHOST: installed app connected over HTTPS, rendered a nonempty development queue and refreshed without visible error"
