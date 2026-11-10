#!/usr/bin/env bash
# Disposable emulator only. Public synthetic TV metadata, never protected servers.
set -euo pipefail
package=io.github.gamesbyian.chunks
cls=io.github.gamesbyian.chunks.InstalledAutomaticPublicMediaInstrumentedTest
runner="$package.test/androidx.test.runner.AndroidJUnitRunner"
evidence="$RUNNER_TEMP/chunks-installed-auto-media"
mkdir -p "$evidence"
trap 'adb shell svc wifi enable; adb shell svc data enable' EXIT
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
actual_package_path="$(adb shell pm path "$package" | tr -d '\r' | head -1 | cut -d : -f 2-)"
test -n "$actual_package_path"
bash tools/verify_installed_apk_bytes.sh app/build/outputs/apk/debug/app-debug.apk "$actual_package_path" "$evidence/pulled.apk" | tee "$evidence/installed-apk-receipt.txt"
run_case() {
  local phase="$1"
  local raw="$evidence/$phase.raw" xml="$evidence/$phase.xml"
  local status=success
  if ! adb shell am instrument -w -r -e class "$cls#$phase" -e chunks_real_media authorized "$runner" > "$raw" 2>&1; then status=failure; fi
  echo "CHUNKS_AUTO_PUBLIC_INSTRUMENT phase=$phase shell_status=$status"
  python3 tools/parse_instrumentation_stream.py --raw "$raw" --junit "$xml"
  python3 - "$xml" <<'PY'
import sys, xml.etree.ElementTree as ET
r=ET.parse(sys.argv[1]).getroot()
print("CHUNKS_AUTO_INSTALLED_JUNIT tests=%s failures=%s errors=%s skipped=%s" %
      tuple(r.attrib[k] for k in ("tests","failures","errors","skipped")))
assert (r.get("tests"),r.get("failures"),r.get("errors"),r.get("skipped"))==("1","0","0","0")
PY
  adb shell am force-stop "$package"
}
adb logcat -c
run_case phaseA_liveAutomaticArtworkWithoutIdentify
adb shell svc wifi disable
adb shell svc data disable
run_case phaseB_offlineColdProcessRestoresRealArtwork
adb logcat -d -v raw -s ChunksAutoMedia:I > "$evidence/WITNESS.txt"
grep -Fx "CHUNKS_AUTO_PUBLIC phase=online consent=true automatic=true verified=true row_pixels=true details_poster=true sanitized=true" "$evidence/WITNESS.txt"
grep -Fx "CHUNKS_AUTO_PUBLIC phase=offline new_process=true persisted=true row_pixels=true requests=0" "$evidence/WITNESS.txt"
adb pull "/sdcard/Android/data/$package/files/auto-real-media-evidence/." "$evidence/" >/dev/null
