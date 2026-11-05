#!/usr/bin/env bash
# Reuse the assembled candidate's installed APKs/emulator; do not rebuild or reinstall.
set -euo pipefail
pkg=${1:?installed candidate package}
[[ "$pkg" =~ ^io\.github\.gamesbyian\.chunks\.magnet[0-9a-f]{12}$ ]]
test "${CHUNKS_REAL_MEDIA:-}" = 1
test "${GITHUB_EVENT_NAME:-}" = workflow_dispatch
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r\n')" = 1
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
cls=io.github.gamesbyian.chunks.InstalledRealMediaInstrumentedTest
out="$RUNNER_TEMP/chunks-real-media"
mkdir -p "$out"
adb logcat -c
restore_network() { adb shell svc wifi enable; adb shell svc data enable; }
trap restore_network EXIT
for phase in phaseA_realLookupsConsentSelectionAndTransferArtwork phaseB_newOfflineProcessRestoresMatchesAndDrawnArtworkWithoutRequests; do
  if [[ "$phase" == phaseB_* ]]; then
    adb shell am force-stop "$pkg"
    adb shell svc wifi disable
    adb shell svc data disable
  fi
  adb shell am instrument -w -r -e class "$cls#$phase" -e chunks_real_media authorized \
    "$runner" > "$out/$phase.raw" 2>&1
  python3 tools/parse_instrumentation_stream.py --raw "$out/$phase.raw" --junit "$out/$phase.xml"
  python3 tools/ci_failure_digest.py --suite "installed-real-media-$phase" --status success \
    --require-executed --glob "$out/$phase.xml" --output "$out/$phase-digest.json"
done
adb logcat -d -v raw -s ChunksRealMedia:I > "$out/WITNESS.txt"
adb pull "/sdcard/Android/data/$pkg/files/real-media-evidence/." "$out/" >/dev/null
grep -E '^CHUNKS_REAL_MEDIA phase=online providers=3 consent=true selected=true pixels=true sanitized=true requests=[1-9][0-9]*$' "$out/WITNESS.txt"
grep -Fx 'CHUNKS_REAL_MEDIA phase=offline new_process=true providers=3 persisted=true pixels=true requests=0' "$out/WITNESS.txt"
adb shell am force-stop "$pkg"
printf 'source_sha=%s\n' "$GITHUB_SHA" > "$out/SOURCE.txt"
