#!/usr/bin/env bash
# Opt-in two-process sudden-death repro (#569) on a DISPOSABLE emulator.
# Phase A arms a seeding transfer under the service and SIGKILLs itself;
# phase B (fresh process) checks honest restore. APKs must be installed.
set -euo pipefail
pkg=io.github.gamesbyian.chunks
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
cls=io.github.gamesbyian.chunks.LocalEngineProcessDeathInstrumentedTest

# This script deliberately kills a Chunks test process. Before touching logcat
# or package state, select and PIN a disposable emulator, not a first arbitrary
# attached device. ADB may print plausible devices and still exit nonzero.
[[ -z "${ADB_SERIAL:-}" || -z "${ANDROID_SERIAL:-}" ||
   "$ADB_SERIAL" == "$ANDROID_SERIAL" ]] || {
  echo 'CHUNKS_DEATH: conflicting ADB target selectors' >&2; exit 2;
}
devices="$(adb devices)" || {
  echo 'CHUNKS_DEATH: ADB enumeration failed' >&2; exit 2;
}
serial="${ADB_SERIAL:-${ANDROID_SERIAL:-}}"
if [[ -z "$serial" ]]; then
  mapfile -t connected < <(awk 'NR > 1 && $2 == "device" {print $1}' <<< "$devices")
  [[ "${#connected[@]}" == 1 ]] || {
    echo 'CHUNKS_DEATH: require one connected disposable emulator' >&2; exit 2;
  }
  serial="${connected[0]}"
fi
[[ "$(awk -v wanted="$serial" '$1 == wanted && $2 == "device" {n++} END {print n+0}' <<< "$devices")" == 1 ]] || {
  echo 'CHUNKS_DEATH: selected ADB target is missing or unavailable' >&2; exit 2;
}
export ANDROID_SERIAL="$serial"
state="$(adb get-state)" || { echo 'CHUNKS_DEATH: ADB state probe failed' >&2; exit 2; }
[[ "$(printf '%s' "$state" | tr -d '\r\n')" == device ]] || {
  echo 'CHUNKS_DEATH: selected emulator is not ready' >&2; exit 2;
}
qemu="$(adb shell getprop ro.kernel.qemu)" || {
  echo 'CHUNKS_DEATH: emulator identity probe failed' >&2; exit 2;
}
sdk="$(adb shell getprop ro.build.version.sdk)" || {
  echo 'CHUNKS_DEATH: SDK identity probe failed' >&2; exit 2;
}
[[ "$(printf '%s' "$qemu" | tr -d '\r\n')" == 1 ]] || {
  echo 'CHUNKS_DEATH: refusing physical or unknown device identity' >&2; exit 2;
}
sdk="$(printf '%s' "$sdk" | tr -d '\r\n')"
[[ "$sdk" =~ ^[0-9]+$ && "$sdk" -ge 29 ]] || {
  echo 'CHUNKS_DEATH: Android API29+ required' >&2; exit 2;
}
adb logcat -c || true
set +e
outA=$(adb shell am instrument -w -r -e class "$cls#phaseA_seedUnderServiceThenDieWithoutWarning" -e chunksDeathPhase a "$runner")
set -e
echo "$outA" | tail -20
# A healthy adb logcat producer can receive SIGPIPE from a `grep -q` pipeline.
# Capture the complete successful command before looking for the arming witness.
logA="$(adb logcat -d -s ChunksDeath:I)"
grep -Fq "CHUNKS_DEATH armed" <<< "$logA" || {
  echo "CHUNKS_DEATH: phase A never armed"; exit 1;
}
# Phase A must have died by its own SIGKILL, not passed or been skipped.
grep -Fq "Process crashed" <<< "$outA" || { echo "CHUNKS_DEATH: phase A did not die as intended"; exit 1; }
sleep 3
if adb shell pidof "$pkg" >/dev/null 2>&1; then echo "CHUNKS_DEATH: process still alive"; exit 1; fi
outB=$(adb shell am instrument -w -r -e class "$cls#phaseB_restoredStoppedWithBytesIntactAndRechecks" -e chunksDeathPhase b "$runner")
echo "$outB" | tail -20
adb logcat -d -s ChunksDeath:I | tail -5
# A streaming message may contain OK (1 test) even when no terminal
# passing test exists. Refuse duplicates, failures and every skipped status.
if [[ "$(grep -Fxc 'OK (1 test)' <<< "$outB" || true)" != 1 ]] ||
   [[ "$(grep -Fxc 'INSTRUMENTATION_CODE: -1' <<< "$outB" || true)" != 1 ]] ||
   [[ "$(grep -c '^INSTRUMENTATION_CODE:' <<< "$outB" || true)" != 1 ]] ||
   grep -Eq '^INSTRUMENTATION_STATUS_CODE: -[1-9]|^FAILURES|AssumptionViolatedException' <<< "$outB"; then
  echo "CHUNKS_DEATH: phase B did not verify exactly one executed, passing test"
  exit 1
fi
