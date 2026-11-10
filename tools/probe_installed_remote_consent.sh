#!/usr/bin/env bash
# One-shot disposable real rTorrent + public TVmaze installed media evidence.
# Never connect to a protected server or external tracker.
set -euo pipefail
package=io.github.gamesbyian.chunks
runner="$package.test/androidx.test.runner.AndroidJUnitRunner"
evidence="$RUNNER_TEMP/chunks-remote-media-consent"
receipt="$RUNNER_TEMP/chunks-combined/receipt.json"
mkdir -p "$evidence"
trap 'adb shell svc wifi enable; adb shell svc data enable' EXIT
adb shell wm size 1080x2400
adb shell wm density 420
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
installed="$(adb shell pm path "$package" | tr -d '\r' | head -1 | cut -d : -f 2-)"
test -n "$installed"
bash tools/verify_installed_apk_bytes.sh app/build/outputs/apk/debug/app-debug.apk "$installed" "$evidence/pulled.apk" \
  | tee "$evidence/installed-apk-receipt.txt"
rm -f "$evidence/pulled.apk"
adb reverse tcp:18080 tcp:18080 >/dev/null
adb reverse tcp:18082 tcp:18082 >/dev/null
b64() { printf '%s' "$1" | base64 -w 0; }
shared_hash="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["shared_hash"])' "$receipt")"
run_case() {
  local method="$1"
  local raw="$evidence/$method.raw"
  local xml="$evidence/$method.xml"
  local status=success
  if ! adb shell am instrument -w -r \
    -e class "io.github.gamesbyian.chunks.InstalledDisposableRemotePublicMediaInstrumentedTest#$method" \
    -e chunks_remote_media authorized \
    -e chunks_media_a_b64 "$(b64 http://127.0.0.1:18080/RPC2)" \
    -e chunks_media_b_b64 "$(b64 http://127.0.0.1:18082/RPC2)" \
    -e chunks_media_hash_b64 "$(b64 "$shared_hash")" \
    "$runner" > "$raw" 2>&1; then status=failure; fi
  echo "CHUNKS_REMOTE_MEDIA_INSTRUMENT phase=$method shell_status=$status"
  python3 tools/parse_instrumentation_stream.py --raw "$raw" --junit "$xml"
  python3 - "$xml" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
print("CHUNKS_REMOTE_MEDIA_JUNIT tests=%s failures=%s errors=%s skipped=%s" %
      tuple(root.attrib[x] for x in ("tests", "failures", "errors", "skipped")))
assert (root.get("tests"), root.get("failures"), root.get("errors"), root.get("skipped")) == ("1", "0", "0", "0")
PY
}
adb logcat -c
run_case phase0_globalArtworkEnabledWithoutProviderConsentMakesNoHttpsRequests
run_case phaseA_authenticatedRemoteInventoryObtainsRealArtWhileOtherAccountExcluded
adb shell am force-stop "$package"
dead="$(adb shell "if pidof $package >/dev/null; then echo running; else echo stopped; fi" | tr -d '\r')"
test "$dead" = stopped
adb shell svc wifi disable
adb shell svc data disable
run_case phaseB_newProcessOfflineRestoresExactRemoteArtWithoutHttp
adb logcat -d -v raw -s ChunksRemoteMedia:I > "$evidence/WITNESS.txt"
grep -Fx "CHUNKS_REMOTE_CONSENT phase=negative two_real_rpc=true global_on=true provider_consent=false https=0 assignments=0" "$evidence/WITNESS.txt"
grep -Fx "CHUNKS_REMOTE_CANONICAL screen=Combined remote_auth=true row_pixels=true details_poster=true exact_account=true" "$evidence/WITNESS.txt"
grep -Fx "CHUNKS_REMOTE_PUBLIC phase=online rpc_auth=true same_hash_two_accounts=true exact_opt_out=true real_provider=true real_art=true sanitized=true" "$evidence/WITNESS.txt"
grep -Fx "CHUNKS_REMOTE_PUBLIC phase=offline new_process=true exact_opt_out=true bitmap=true requests=0" "$evidence/WITNESS.txt"
