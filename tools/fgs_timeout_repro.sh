#!/usr/bin/env bash
# Opt-in Android 15+ dataSync foreground-service timeout on a DISPOSABLE
# emulator. Guard the exact witness and restore any temporary device_config.
set -euo pipefail
pkg=io.github.gamesbyian.chunks
config=activity_manager
timer=data_sync_fgs_timeout_duration
gradle --no-daemon :app:installDebug :app:installDebugAndroidTest

# Preserve an explicit prior timeout value instead of always deleting it.
# Android's documented device_config get prints "null" when unset.
before="$(adb shell device_config get "$config" "$timer" | tr -d '\r\n')" || {
  echo 'CHUNKS_FGS_TIMEOUT: cannot read prior timeout configuration' >&2; exit 1;
}
[[ "$before" == null || "$before" =~ ^[0-9]+$ ]] || {
  echo 'CHUNKS_FGS_TIMEOUT: ambiguous prior timeout configuration' >&2; exit 1;
}
compat_touched=0
timer_touched=0
restore() {
  local rc=$? restored=''
  trap - EXIT
  if (( timer_touched )); then
    if [[ "$before" == null ]]; then
      if ! adb shell device_config delete "$config" "$timer"; then
        echo 'CHUNKS_FGS_TIMEOUT: failed to delete temporary timeout' >&2; rc=1
      fi
    elif ! adb shell device_config put "$config" "$timer" "$before"; then
      echo 'CHUNKS_FGS_TIMEOUT: failed to restore previous timeout' >&2; rc=1
    fi
    if ! restored="$(adb shell device_config get "$config" "$timer" | tr -d '\r\n')"; then
      echo 'CHUNKS_FGS_TIMEOUT: timeout restoration readback failed' >&2; rc=1
    elif [[ "$restored" != "$before" ]]; then
      echo 'CHUNKS_FGS_TIMEOUT: timeout restoration not verified' >&2; rc=1
    fi
  fi
  if (( compat_touched )); then
    # Reset test override to platform default. Prior manual compat overrides
    # are not discoverable here; use only on a disposable test emulator.
    if ! adb shell am compat reset FGS_INTRODUCE_TIME_LIMITS "$pkg"; then
      echo 'CHUNKS_FGS_TIMEOUT: compat override reset failed' >&2; rc=1
    fi
  fi
  exit "$rc"
}
trap restore EXIT
compat_touched=1
adb shell am compat enable FGS_INTRODUCE_TIME_LIMITS "$pkg"
timer_touched=1
adb shell device_config put "$config" "$timer" 15000
configured="$(adb shell device_config get "$config" "$timer" | tr -d '\r\n')" || {
  echo 'CHUNKS_FGS_TIMEOUT: timeout setup readback failed' >&2; exit 1;
}
[[ "$configured" == 15000 ]] || {
  echo 'CHUNKS_FGS_TIMEOUT: shortened timeout not verified' >&2; exit 1;
}
echo 'CHUNKS_FGS_TIMEOUT config verified=15000'
adb logcat -c || true
cls=io.github.gamesbyian.chunks.LocalEngineServiceTimeoutInstrumentedTest
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
# Do not mistake a successful adb process exit or skipped test for a proof.
one_passing_test() {
  local result="$1"
  [[ "$(grep -Fxc 'OK (1 test)' <<< "$result" || true)" == 1 ]] &&
    ! grep -Eq 'AssumptionViolatedException|^INSTRUMENTATION_STATUS_CODE: -[1-9]|^FAILURES!!!|^FAILURES:' <<< "$result"
}
if ! phase_a="$(adb shell am instrument -w -r \
  -e class "$cls#shortenedDataSyncLimitPausesWithNoticeAndKeepsBytes" \
  -e chunksFgsTimeout 1 \
  "$runner")"; then
  echo 'CHUNKS_FGS_TIMEOUT: first instrumentation command failed' >&2; exit 1
fi
printf '%s\n' "$phase_a"
if ! one_passing_test "$phase_a"; then
  echo 'CHUNKS_FGS_TIMEOUT: timed-out pause and byte evidence failed' >&2; exit 1
fi
log_a="$(adb logcat -d -s ChunksFgsTimeout:I ActivityManager:W)"
grep -iE 'CHUNKS_FGS_TIMEOUT|timeout|dataSync' <<< "$log_a" | tail -40 || true
if ! grep -Eq 'CHUNKS_FGS_TIMEOUT armed=true paused_after_ms=[0-9]+' <<< "$log_a"; then
  echo 'CHUNKS_FGS_TIMEOUT: no positive two-job timeout receipt' >&2; exit 1
fi

# A real process boundary, with package data intact. Never pm clear: that
# erases the very metadata and shared Downloads ownership we need to test.
adb shell am force-stop "$pkg"
adb logcat -c || true
if ! phase_b="$(adb shell am instrument -w -r \
  -e class "$cls#coldRestoreAfterTimeoutRechecksSeedsAndKeepsPayload" \
  -e chunksFgsTimeout 1 -e chunksFgsColdRestore 1 \
  "$runner")"; then
  echo 'CHUNKS_FGS_TIMEOUT: cold-restore instrumentation command failed' >&2; exit 1
fi
printf '%s\n' "$phase_b"
if ! one_passing_test "$phase_b"; then
  echo 'CHUNKS_FGS_TIMEOUT: post-timeout cold restore and seeding failed' >&2; exit 1
fi
log_b="$(adb logcat -d -s ChunksFgsTimeout:I)"
grep 'CHUNKS_FGS_TIMEOUT' <<< "$log_b" | tail -20 || true
if [[ "$(grep -c 'CHUNKS_FGS_TIMEOUT cold_restore=true jobs=2 stopped=true rechecked=true seeded=true removed_keep_files=true' <<< "$log_b" || true)" != 1 ]]; then
  echo 'CHUNKS_FGS_TIMEOUT: no exact cold-recovery native receipt' >&2; exit 1
fi
echo 'CHUNKS_FGS_TIMEOUT: both disposable-emulator phases passed; not physical/OEM acceptance'
