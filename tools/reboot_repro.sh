#!/usr/bin/env bash
# Opt-in device reboot repro (#569) on a DISPOSABLE emulator. APKs must be
# installed. Phase A seeds under the service; the device reboots; phase B
# checks that nothing auto-started and the transfer restores stopped.
set -euo pipefail
pkg=io.github.gamesbyian.chunks
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
cls=io.github.gamesbyian.chunks.LocalEngineRebootInstrumentedTest
# Bound ADB calls so an unresponsive emulator cannot consume the whole job.
adb_bounded() { timeout --signal=TERM --kill-after=5s 90s adb "$@"; }
milestone() { printf 'CHUNKS_REBOOT [%s] %s\n' "$(date -u +%FT%TZ)" "$*"; }

# A reboot is device-wide and cannot be undone. This standalone script is
# directly callable, so it must not inherit unverified identity from an
# optional preceding timeout harness. Refuse before instrumentation or reboot.
[[ -z "${ADB_SERIAL:-}" || -z "${ANDROID_SERIAL:-}" ||
   "$ADB_SERIAL" == "$ANDROID_SERIAL" ]] || {
  echo 'CHUNKS_REBOOT: conflicting ADB serial selectors' >&2; exit 2;
}
devices="$(adb devices)" || {
  echo 'CHUNKS_REBOOT: adb devices failed' >&2; exit 2;
}
serial="${ADB_SERIAL:-${ANDROID_SERIAL:-}}"
explicit=1
if [[ -z "$serial" ]]; then
  explicit=0
  mapfile -t connected < <(awk 'NR > 1 && $2 == "device" {print $1}' <<< "$devices")
  [[ "${#connected[@]}" == 1 ]] || {
    echo 'CHUNKS_REBOOT: require unique connected target' >&2; exit 2;
  }
  serial="${connected[0]}"
fi
[[ "$(awk -v wanted="$serial" '$1 == wanted && $2 == "device" {n++} END {print n+0}' <<< "$devices")" == 1 ]] || {
  echo 'CHUNKS_REBOOT: selected target missing or unavailable' >&2; exit 2;
}
export ANDROID_SERIAL="$serial"
state="$(adb get-state)" || {
  echo 'CHUNKS_REBOOT: adb target readiness probe failed' >&2; exit 2;
}
[[ "$(printf '%s' "$state" | tr -d '\r\n')" == device ]] || {
  echo 'CHUNKS_REBOOT: adb target not ready' >&2; exit 2;
}
qemu="$(adb shell getprop ro.kernel.qemu)" || {
  echo 'CHUNKS_REBOOT: emulator identity probe failed' >&2; exit 2;
}
sdk="$(adb shell getprop ro.build.version.sdk)" || {
  echo 'CHUNKS_REBOOT: SDK identity probe failed' >&2; exit 2;
}
qemu="$(printf '%s' "$qemu" | tr -d '\r\n')"
sdk="$(printf '%s' "$sdk" | tr -d '\r\n')"
[[ "$sdk" =~ ^[0-9]+$ && "$sdk" -ge 29 ]] || {
  echo 'CHUNKS_REBOOT: Android API29+ required' >&2; exit 2;
}
[[ "$qemu" == 0 || "$qemu" == 1 ]] || {
  echo 'CHUNKS_REBOOT: ambiguous emulator/physical identity' >&2; exit 2;
}
if [[ "$qemu" == 0 ]]; then
  [[ "$explicit" == 1 && "${CHUNKS_ALLOW_PHYSICAL_REBOOT_TEST:-0}" == 1 ]] || {
    echo 'CHUNKS_REBOOT: refusing physical reboot without explicit serial and consent' >&2; exit 2;
  }
  echo 'CHUNKS_REBOOT: physical test explicitly authorized; device WILL reboot' >&2
fi

# The boot-completed property can already be 1 on the original boot. Take an
# OS kernel identity witness BEFORE arming the transfer, and require it to
# change before phase B can assert a reboot recovery.
read_boot_id() { adb_bounded shell cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r\n'; }
valid_boot_id() { [[ "$1" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]]; }
before_boot_id="$(read_boot_id)"
valid_boot_id "$before_boot_id" || { echo "CHUNKS_REBOOT: no valid original kernel boot ID"; exit 1; }

# The adb process can exit 0 while a test was skipped/failed, and a result
# stream can echo "OK (1 test)" without reporting the actual terminal status.
# Require one completed JUnit result AND one successful terminal Android
# instrumentation code. An echoed OK line alone never authorizes reboot
# or a successful post-reboot recovery claim.
instrument_passed_once() {
  local output="$1"
  [[ "$(grep -Fxc 'OK (1 test)' <<< "$output" || true)" == 1 ]] &&
    [[ "$(grep -Fxc 'INSTRUMENTATION_CODE: -1' <<< "$output" || true)" == 1 ]] &&
    [[ "$(grep -c '^INSTRUMENTATION_CODE:' <<< "$output" || true)" == 1 ]] &&
    ! grep -Eq '^INSTRUMENTATION_STATUS_CODE: -[1-9]|^FAILURES|AssumptionViolatedException' <<< "$output"
}
adb_bounded logcat -c || true
milestone "phase A instrumentation started"
outA=$(adb_bounded shell am instrument -w -r -e class "$cls#phaseA_seedUnderServiceBeforeReboot" -e chunksRebootPhase a "$runner")
echo "$outA" | tail -6
instrument_passed_once "$outA" || {
  echo "CHUNKS_REBOOT: phase A did not report exactly one passing, non-skipped test"; exit 1;
}
adb_bounded logcat -d -s ChunksReboot:I | grep "CHUNKS_REBOOT armed" || { echo "CHUNKS_REBOOT: phase A never armed"; exit 1; }
milestone "phase A passed; requesting reboot"
timeout --signal=TERM --kill-after=5s 20s adb reboot || {
  echo 'CHUNKS_REBOOT: reboot command failed or timed out' >&2; exit 1;
}
milestone "waiting for ADB reconnect (150s maximum)"
timeout --signal=TERM --kill-after=5s 150s adb wait-for-device || {
  echo 'CHUNKS_REBOOT: ADB did not reconnect within 150s' >&2; exit 1;
}
milestone "ADB reconnected; verifying new boot identity"
after_boot_id=""
boot_deadline=$((SECONDS + 180))
for _ in $(seq 1 90); do
  (( SECONDS < boot_deadline )) || break
  complete="$(timeout --signal=TERM --kill-after=2s 8s adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n')" || complete=""
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
milestone "kernel_boot_id_changed=true"
sleep 10
if adb_bounded shell pidof "$pkg" >/dev/null 2>&1; then echo "CHUNKS_REBOOT: Chunks process started by itself after boot"; exit 1; fi
adb_bounded logcat -c || true
milestone "phase B instrumentation started"
outB=$(adb_bounded shell am instrument -w -r -e class "$cls#phaseB_nothingStartsAfterBootAndRestoreIsStopped" -e chunksRebootPhase b "$runner")
echo "$outB" | tail -6
log_b="$(adb_bounded logcat -d -s ChunksReboot:I)" || {
  echo 'CHUNKS_REBOOT: post-reboot peer evidence unavailable' >&2; exit 1;
}
printf '%s\n' "$log_b" | tail -3
instrument_passed_once "$outB" || {
  echo "CHUNKS_REBOOT: phase B did not report exactly one passing, non-skipped test"; exit 1;
}
# A stopped-restore receipt cannot establish that manual Start after the
# actual reboot can upload verified bytes to a second, initially empty peer.
# Require the instrumentation's byte/hash and session-only Remove witness.
[[ "$(grep -Fc 'CHUNKS_REBOOT restored_stopped=true no_autostart=true sha_unchanged=true reseeded_bytes=96000 removed_keep_files=true' <<< "$log_b" || true)" == 1 ]] || {
  echo 'CHUNKS_REBOOT: no unique real post-reboot fresh-peer seeding receipt' >&2; exit 1;
}

milestone "phase B passed; fresh-peer seeding verified"
