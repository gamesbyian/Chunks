#!/usr/bin/env bash
# Two separate instrumented Android app processes. No real phones, no app data
# clear and no permission mutations. Fixed nonsecret receipts only.
set -euo pipefail
api="$1"
[[ "$api" == 29 || "$api" == 35 ]] || {
  echo 'CHUNKS_BATCH_COLD: unsupported SDK request' >&2; exit 1;
}
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r\n')" == 1 ]] || {
  echo 'CHUNKS_BATCH_COLD: physical device refused' >&2; exit 1;
}
[[ "$(adb shell getprop ro.build.version.sdk | tr -d '\r\n')" == "$api" ]] || {
  echo 'CHUNKS_BATCH_COLD: Android SDK identity mismatch' >&2; exit 1;
}
run_id="$(od -An -N8 -tx1 /dev/urandom | tr -d '[:space:]')"
[[ "$run_id" =~ ^[0-9a-f]{16}$ ]] || {
  echo 'CHUNKS_BATCH_COLD: no safe unique fixture identity' >&2; exit 1;
}
pkg=io.github.gamesbyian.chunks
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
class=io.github.gamesbyian.chunks.LocalZipBatchColdRestoreInstrumentedTest

run_phase() {
  local phase="$1" test="$2" marker="$3" result output log
  adb shell am force-stop "$pkg"
  adb logcat -c
  output="$(mktemp)"
  if ! adb shell am instrument -w -r \
      -e class "$class#$test" \
      -e chunksBatchPhase "$phase" \
      -e chunksBatchRunId "$run_id" "$runner" >"$output" 2>&1; then
    rm -f "$output"
    echo "CHUNKS_BATCH_COLD: instrumentation command failed phase=$phase" >&2
    return 1
  fi
  result="$(cat "$output")"
  rm -f "$output"
  if [[ "$(grep -Fxc 'OK (1 test)' <<<"$result" || true)" != 1 ]] ||
     ! grep -Fqx 'INSTRUMENTATION_CODE: -1' <<<"$result" ||
     grep -Eq 'AssumptionViolatedException|^FAILURES|^INSTRUMENTATION_STATUS_CODE: -[1-9]' <<<"$result"; then
    echo "CHUNKS_BATCH_COLD: phase=$phase missing one executed passing test" >&2
    return 1
  fi
  log="$(adb logcat -d -v raw -s ChunksBatchCold:I)"
  if [[ "$(grep -Fxc "$marker" <<<"$log" || true)" != 1 ]]; then
    echo "CHUNKS_BATCH_COLD: phase=$phase missing exact unique positive receipt" >&2
    return 1
  fi
  echo "$marker"
}
run_phase a phaseA_publishOneThenPersistStoppedBatchBeforeForceStop \
  'CHUNKS_BATCH_COLD phase=a partial=true verified=1 original=true'
# run_phase begins with a real am force-stop, without pm clear.
run_phase b phaseB_coldProcessRecoveryShowsWarningWithoutCreatingAnotherRow \
  'CHUNKS_BATCH_COLD phase=b cold=true warning=true original=true no_duplicate=true'
adb shell am force-stop "$pkg"
echo "CHUNKS_BATCH_COLD=PASS api=$api real_two_process_partial_and_readonly_recovery"
