#!/usr/bin/env bash
# Opt-in device reboot repro (#569) on a DISPOSABLE emulator. APKs must be
# installed. Phase A seeds under the service; the device reboots; phase B
# checks that nothing auto-started and the transfer restores stopped.
set -euo pipefail
pkg=io.github.gamesbyian.chunks
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
cls=io.github.gamesbyian.chunks.LocalEngineRebootInstrumentedTest
# The boot-completed property can already be 1 on the original boot. Take an
# OS kernel identity witness BEFORE arming the transfer, and require it to
# change before phase B can assert a reboot recovery.
read_boot_id() { adb shell cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r\n'; }
valid_boot_id() { [[ "$1" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]]; }
before_boot_id="$(read_boot_id)"
valid_boot_id "$before_boot_id" || { echo "CHUNKS_REBOOT: no valid original kernel boot ID"; exit 1; }

# The adb process can exit 0 while a test was skipped/failed, and a result
# stream can echo "OK (1 test)" without reporting the actual terminal status.
# Keep the exact completion line, prohibit negative status and skipped reports.
instrument_passed_once() {
  local output="$1"
  [[ "$(grep -Fxc 'OK (1 test)' <<< "$output" || true)" == 1 ]] &&
    ! grep -Eq '^INSTRUMENTATION_STATUS_CODE: -[1-9]|^FAILURES|AssumptionViolatedException' <<< "$output"
}
adb logcat -c || true
outA=$(adb shell am instrument -w -r -e class "$cls#phaseA_seedUnderServiceBeforeReboot" -e chunksRebootPhase a "$runner")
echo "$outA" | tail -6
instrument_passed_once "$outA" || {
  echo "CHUNKS_REBOOT: phase A did not report exactly one passing, non-skipped test"; exit 1;
}
adb logcat -d -s ChunksReboot:I | grep "CHUNKS_REBOOT armed" || { echo "CHUNKS_REBOOT: phase A never armed"; exit 1; }
adb reboot
adb wait-for-device
after_boot_id=""
for _ in $(seq 1 120); do
  complete="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n')" || complete=""
  candidate="$(read_boot_id)" || candidate=""
  if [[ "$complete" == "1" ]] && valid_boot_id "$candidate" && [[ "$candidate" != "$before_boot_id" ]]; then
    after_boot_id="$candidate"
    break
  fi
  sleep 2
done
[[ -n "$after_boot_id" ]] || {
  echo "CHUNKS_REBOOT: no observed new kernel boot with completed Android startup"
  exit 1
}
echo "CHUNKS_REBOOT kernel_boot_id_changed=true"
sleep 10
if adb shell pidof "$pkg" >/dev/null 2>&1; then echo "CHUNKS_REBOOT: Chunks process started by itself after boot"; exit 1; fi
adb logcat -c || true
outB=$(adb shell am instrument -w -r -e class "$cls#phaseB_nothingStartsAfterBootAndRestoreIsStopped" -e chunksRebootPhase b "$runner")
echo "$outB" | tail -6
adb logcat -d -s ChunksReboot:I | tail -3
instrument_passed_once "$outB" || {
  echo "CHUNKS_REBOOT: phase B did not report exactly one passing, non-skipped test"; exit 1;
}
