#!/usr/bin/env bash
# Native instrumentation and real PNG validation in one Bash invocation.
set -euo pipefail
adb shell wm size 1080x2400
adb shell wm density 420
adb shell settings put system font_scale 1.0
gradle --no-daemon :app:connectedDebugAndroidTest
mkdir -p "$RUNNER_TEMP/chunks-add-native-review"
adb pull /sdcard/Pictures/ChunksAddAtlas/. "$RUNNER_TEMP/chunks-add-native-review"
python3 - <<'PY'
from pathlib import Path
import struct, os
root=Path(os.environ["RUNNER_TEMP"])/"chunks-add-native-review"
for name in ("add-confirmation-real-compose.png","add-invalid-path-real-compose.png","remove-confirmation-real-compose.png","add-uncertain-recheck-real-compose.png","files-dense-second-page-real-compose.png","file-priority-confirmation-real-compose.png","alias-suggestions-real-compose.png","alias-saved-real-compose.png"):
    raw=(root/name).read_bytes()
    assert raw[:8]==b"\x89PNG\r\n\x1a\n", "Not a real PNG screenshot"
    assert raw[12:16]==b"IHDR"
    w,h=struct.unpack(">II",raw[16:24])
    assert w>=300 and h>=500
    print("CHUNKS_ADD_NATIVE_ATLAS validated",name,w,h)
PY
