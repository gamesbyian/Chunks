#!/usr/bin/env bash
# Android 10 opt-in permission revocation witness, disposable emulator ONLY.
# Three different processes: Stop persisted -> OS grant revoked -> blocked
# Start -> grant restored -> stopped restore/recheck/seed/Remove keeping bytes.
# Never clears app data, never deletes Downloads and never runs on a handset.
set -euo pipefail

pkg=io.github.gamesbyian.chunks
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
class=io.github.gamesbyian.chunks.Android10DownloadsRevocationInstrumentedTest

[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || {
  echo 'CHUNKS_GRANT: refusing physical-device permission mutation' >&2; exit 1;
}
[[ "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" == 29 ]] || {
  echo 'CHUNKS_GRANT: API29 only; permission model differs on later Android' >&2; exit 1;
}

restore_grants() {
  # Phase A explicitly granted these to a disposable app. Always restore
  # them even if a later phase fails: a red test must not strand a grant.
  adb shell pm grant "$pkg" android.permission.READ_EXTERNAL_STORAGE &&
  adb shell pm grant "$pkg" android.permission.WRITE_EXTERNAL_STORAGE
}

positive() {
  local phase="$1" marker="$2" result log
  adb shell am force-stop "$pkg"
  adb logcat -c
  if ! result="$(adb shell am instrument -w -r \
      -e class "$class#phase$phase" \
      -e chunksGrantPhase "$(printf '%s' "${phase:0:1}" | tr '[:upper:]' '[:lower:]')" "$runner")"; then
    echo "CHUNKS_GRANT: instrumentation command failed in $phase" >&2; return 1
  fi
  printf '%s\n' "$result"
  # adb may exit zero for a failing/assumption-skipped JUnit invocation.
  if [[ "$(grep -Fxc 'OK (1 test)' <<< "$result" || true)" != 1 ]] ||
     grep -Eq 'AssumptionViolatedException|^FAILURES|^INSTRUMENTATION_STATUS_CODE: -[1-9]' <<< "$result"; then
    echo "CHUNKS_GRANT: phase $phase lacked one executed passing test" >&2; return 1
  fi
  log="$(adb logcat -d -v raw -s ChunksGrant:I)"
  printf '%s\n' "$log" | grep 'CHUNKS_GRANT phase=' || true
  if [[ "$(grep -Fxc "$marker" <<< "$log" || true)" != 1 ]]; then
    echo "CHUNKS_GRANT: phase $phase missing unique native positive receipt" >&2; return 1
  fi
}

# The A receipt embeds an unpredictable exact hash/digest. Validate its full
# syntax and exact uniqueness instead of hardcoding one known positive marker.
adb shell am force-stop "$pkg"
adb logcat -c
if ! a="$(adb shell am instrument -w -r \
  -e class "$class#phaseA_completeNativeSeedInSharedDownloadsThenStop" \
  -e chunksGrantPhase a "$runner")"; then
  echo 'CHUNKS_GRANT: phase A could not execute' >&2; exit 1
fi
printf '%s\n' "$a"
if [[ "$(grep -Fxc 'OK (1 test)' <<< "$a" || true)" != 1 ]] ||
   grep -Eq 'AssumptionViolatedException|^FAILURES|^INSTRUMENTATION_STATUS_CODE: -[1-9]' <<< "$a"; then
  echo 'CHUNKS_GRANT: phase A not executed or not passing' >&2; exit 1
fi
log_a="$(adb logcat -d -v raw -s ChunksGrant:I)"
marker_a="$(grep -E '^CHUNKS_GRANT phase=a persisted=true stopped=true bytes=262144 hash=[0-9a-f]{40} sha256=[0-9a-f]{64}$' <<< "$log_a" || true)"
[[ "$(grep -c '^CHUNKS_GRANT' <<< "$marker_a" || true)" == 1 ]] || {
  echo 'CHUNKS_GRANT: no unique durable native stopped receipt' >&2; exit 1;
}
echo "$marker_a"

# No permission changes until a successful phase A. The trap restores both
# grants on any command failure, including a failed phase B.
trap restore_grants EXIT
adb shell am force-stop "$pkg"
adb shell pm revoke "$pkg" android.permission.WRITE_EXTERNAL_STORAGE
adb shell pm revoke "$pkg" android.permission.READ_EXTERNAL_STORAGE

positive B_storageRevokedRechecksOwnedFileOrSafelyRefuses \
  'CHUNKS_GRANT phase=b permission_revoked=true stopped_restored=true path_checked=true'

restore_grants
adb shell am force-stop "$pkg"
positive C_grantRestoredRechecksSeedsAndRemovesWithoutDeletingFiles \
  "CHUNKS_GRANT phase=c restored=true seeded=true removed_keep_files=true sha256=${marker_a##*sha256=}"
adb shell am force-stop "$pkg"
trap - EXIT
echo 'CHUNKS_GRANT: API29 revoke/refuse/regrant/recheck/Remove passed on disposable emulator only'
