#!/usr/bin/env bash
# Opt-in device reboot repro (#569) on a DISPOSABLE emulator. APKs must be
# installed. Phase A seeds under the service; the device reboots; phase B
# checks that nothing auto-started and the transfer restores stopped.
set -euo pipefail
pkg=io.github.gamesbyian.chunks
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
cls=io.github.gamesbyian.chunks.LocalEngineRebootInstrumentedTest
adb logcat -c || true
outA=$(adb shell am instrument -w -r -e class "$cls#phaseA_seedUnderServiceBeforeReboot" -e chunksRebootPhase a "$runner")
echo "$outA" | tail -6
echo "$outA" | grep -q "OK (1 test)" || { echo "CHUNKS_REBOOT: phase A failed"; exit 1; }
adb logcat -d -s ChunksReboot:I | grep "CHUNKS_REBOOT armed" || { echo "CHUNKS_REBOOT: phase A never armed"; exit 1; }
adb reboot
adb wait-for-device
for _ in $(seq 1 120); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 2
done
[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ] || { echo "CHUNKS_REBOOT: device did not finish booting"; exit 1; }
sleep 10
if adb shell pidof "$pkg" >/dev/null 2>&1; then echo "CHUNKS_REBOOT: Chunks process started by itself after boot"; exit 1; fi
adb logcat -c || true
outB=$(adb shell am instrument -w -r -e class "$cls#phaseB_nothingStartsAfterBootAndRestoreIsStopped" -e chunksRebootPhase b "$runner")
echo "$outB" | tail -6
adb logcat -d -s ChunksReboot:I | tail -3
echo "$outB" | grep -q "OK (1 test)" || { echo "CHUNKS_REBOOT: phase B did not pass"; exit 1; }
if echo "$outB" | grep -q "INSTRUMENTATION_STATUS_CODE: -3"; then echo "CHUNKS_REBOOT: phase B skipped"; exit 1; fi
