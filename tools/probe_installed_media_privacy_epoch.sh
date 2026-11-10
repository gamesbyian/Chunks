#!/usr/bin/env bash
set -euo pipefail
package=io.github.gamesbyian.chunks
cls=io.github.gamesbyian.chunks.InstalledMediaPrivacyEpochInstrumentedTest
runner="$package.test/androidx.test.runner.AndroidJUnitRunner"
out="$RUNNER_TEMP/chunks-media-privacy-epoch"
mkdir -p "$out"
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
actual="$(adb shell pm path "$package" | tr -d '\r' | head -1 | cut -d : -f 2-)"
test -n "$actual"
bash tools/verify_installed_apk_bytes.sh app/build/outputs/apk/debug/app-debug.apk "$actual" "$out/pulled.apk" | tee "$out/installed-apk-receipt.txt"
status=success
if ! adb shell am instrument -w -r -e class "$cls" "$runner" > "$out/raw-instrumentation.txt" 2>&1; then status=failed; fi
python3 tools/parse_instrumentation_stream.py --raw "$out/raw-instrumentation.txt" --junit "$out/privacy-epoch.xml"
python3 - "$out/privacy-epoch.xml" <<'PY'
import sys
from xml.etree import ElementTree as ET
r=ET.parse(sys.argv[1]).getroot()
facts=tuple(r.get(k) for k in ('tests','failures','errors','skipped'))
print('CHUNKS_INSTALLED_MEDIA_PRIVACY_EPOCH tests=%s failures=%s errors=%s skipped=%s' % facts)
assert facts==('1','0','0','0'), facts
PY
test "$status" = success
echo 'CHUNKS_PRIVACY_EPOCH: installed lifecycle and authorization regression passed' | tee "$out/safe-run-receipt.txt"
rm -f "$out/raw-instrumentation.txt"
