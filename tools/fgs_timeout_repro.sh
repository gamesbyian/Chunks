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
if ! out="$(adb shell am instrument -w -r \
  -e class io.github.gamesbyian.chunks.LocalEngineServiceTimeoutInstrumentedTest \
  -e chunksFgsTimeout 1 \
  "$pkg.test/androidx.test.runner.AndroidJUnitRunner")"; then
  echo 'CHUNKS_FGS_TIMEOUT: instrumentation adb command failed' >&2
  exit 1
fi
printf '%s\n' "$out"
adb logcat -d -s ChunksFgsTimeout:I ActivityManager:W | grep -iE 'CHUNKS_FGS_TIMEOUT|timeout|dataSync' | tail -40 || true
# am instrument can exit 0 after test failure; reject skips, failures and
# missing exact one-test completion, even when a plausible OK line appears.
if ! grep -Fxq 'OK (1 test)' <<< "$out" ||
   grep -Eq 'AssumptionViolatedException|^INSTRUMENTATION_STATUS_CODE: -[1-9]|^FAILURES!!!|^FAILURES:' <<< "$out"; then
  echo 'CHUNKS_FGS_TIMEOUT: one executed passing test was not verified' >&2
  exit 1
fi
