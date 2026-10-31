#!/usr/bin/env bash
# Native instrumentation and real PNG validation in one Bash invocation.
set -euo pipefail
adb shell wm size 1080x2400
adb shell wm density 420
adb shell settings put system font_scale 1.0
set +e
# The dedicated 200% Compose acceptance class intentionally rejects a normal
# system font scale; run it only through run_compose_system_font_200pct.sh.
# Keep ALL ordinary Add/transfer instrumentation here, excluding only that class.
gradle --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notClass=io.github.gamesbyian.chunks.SystemFontScaleComposeInstrumentedTest
test_status=$?
set -e
mkdir -p "$RUNNER_TEMP/chunks-add-native-review"
# The screenshots exist on the device once instrumentation has finished; an
# adb transport hiccup during the pull (seen once after all 210 tests passed)
# must not be mistaken for missing evidence. Retry the pull only, then let the
# PNG validation below fail loudly if anything is still absent.
for attempt in 1 2 3; do
  if adb pull /sdcard/Pictures/ChunksAddAtlas/. "$RUNNER_TEMP/chunks-add-native-review"; then break; fi
  echo "CHUNKS_NATIVE_DIAGNOSTIC: screenshot pull attempt $attempt failed"
  adb wait-for-device || true
  sleep 3
done
if [ "$test_status" -ne 0 ]; then
  echo "CHUNKS_NATIVE_DIAGNOSTIC: instrumentation failed; any captured PNGs were retained"
  exit "$test_status"
fi
python3 - <<'PY'
from pathlib import Path
import struct, os
root=Path(os.environ["RUNNER_TEMP"])/"chunks-add-native-review"
for name in ("add-confirmation-real-compose.png","add-invalid-path-real-compose.png","remove-confirmation-real-compose.png","add-uncertain-recheck-real-compose.png","files-dense-second-page-real-compose.png","file-priority-confirmation-real-compose.png","alias-suggestions-real-compose.png","alias-saved-real-compose.png","app-transfers-populated-real-compose.png","app-places-populated-real-compose.png","app-organize-populated-real-compose.png","app-server-populated-real-compose.png","app-files-populated-real-compose.png","app-alias-review-populated-real-compose.png","app-alias-saved-populated-real-compose.png","zip-local-review-safe-real-compose.png","zip-local-review-blocked-real-compose.png","zip-extract-confirm-unknown-space-real-compose.png","zip-extract-unavailable-real-compose.png","local-engine-add-review-real-compose.png","local-engine-remove-confirm-real-compose.png","local-engine-file-selection-real-compose.png","local-engine-speed-limits-real-compose.png","local-engine-copy-review-real-compose.png"):
    raw=(root/name).read_bytes()
    assert raw[:8]==b"\x89PNG\r\n\x1a\n", "Not a real PNG screenshot"
    assert raw[12:16]==b"IHDR"
    w,h=struct.unpack(">II",raw[16:24])
    assert w>=300 and h>=500
    print("CHUNKS_ADD_NATIVE_ATLAS validated",name,w,h)
PY
