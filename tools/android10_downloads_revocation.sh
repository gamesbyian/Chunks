#!/usr/bin/env bash
# Android 10 opt-in permission revocation witness, disposable emulator ONLY.
# Three different processes: Stop persisted -> OS grant revoked -> blocked
# Start -> grant restored -> stopped restore/recheck/seed/Remove keeping bytes.
# Never clears app data, never deletes Downloads and never runs on a handset.
set -euo pipefail

pkg=io.github.gamesbyian.chunks
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
class=io.github.gamesbyian.chunks.Android10DownloadsRevocationInstrumentedTest

# The test revokes OS permissions and force-stops an app. Pin exactly
# the positively enumerated emulator before *any* device mutation, even
# when this standalone script is invoked outside the normal CI harness.
[[ -z "${ADB_SERIAL:-}" || -z "${ANDROID_SERIAL:-}" ||
   "$ADB_SERIAL" == "$ANDROID_SERIAL" ]] || {
  echo 'CHUNKS_GRANT: conflicting ADB serial selectors' >&2; exit 1;
}
devices="$(adb devices)" || {
  echo 'CHUNKS_GRANT: ADB enumeration failed' >&2; exit 1;
}
selected="${ANDROID_SERIAL:-${ADB_SERIAL:-}}"
if [[ -z "$selected" ]]; then
  mapfile -t attached < <(awk 'NR > 1 && $2 == "device" { print $1 }' <<< "$devices")
  [[ "${#attached[@]}" == 1 ]] || {
    echo 'CHUNKS_GRANT: one authorized target required' >&2; exit 1;
  }
  selected="${attached[0]}"
fi
[[ "$(awk -v serial="$selected" '$1 == serial && $2 == "device" { n++ } END { print n+0 }' <<< "$devices")" == 1 ]] || {
  echo 'CHUNKS_GRANT: selected target not uniquely connected' >&2; exit 1;
}
export ANDROID_SERIAL="$selected"
state="$(adb get-state)" || {
  echo 'CHUNKS_GRANT: ADB get-state failed' >&2; exit 1;
}
state="$(tr -d '\r\n' <<< "$state")"
[[ "$state" == device ]] || {
  echo 'CHUNKS_GRANT: ADB target not ready' >&2; exit 1;
}
qemu="$(adb shell getprop ro.kernel.qemu | tr -d '\r\n')" || {
  echo 'CHUNKS_GRANT: ADB emulator identity command failed' >&2; exit 1;
}
[[ "$qemu" == 1 ]] || {
  echo 'CHUNKS_GRANT: refusing physical-device permission mutation' >&2; exit 1;
}
sdk="$(adb shell getprop ro.build.version.sdk | tr -d '\r\n')" || {
  echo 'CHUNKS_GRANT: ADB SDK command failed' >&2; exit 1;
}
[[ "$sdk" == 29 ]] || {
  echo 'CHUNKS_GRANT: API29 only; permission model differs on later Android' >&2; exit 1;
}

restore_grants() {
  # Phase A explicitly granted these to a disposable app. Always restore
  # them even if a later phase fails: a red test must not strand a grant.
  local failure=0
  adb shell pm grant "$pkg" android.permission.READ_EXTERNAL_STORAGE || failure=1
  adb shell pm grant "$pkg" android.permission.WRITE_EXTERNAL_STORAGE || failure=1
  return "$failure"
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
     [[ "$(grep -Fxc 'INSTRUMENTATION_CODE: -1' <<< "$result" || true)" != 1 ]] ||
     [[ "$(grep -c '^INSTRUMENTATION_CODE:' <<< "$result" || true)" != 1 ]] ||
     grep -Eq 'AssumptionViolatedException|^FAILURES|^INSTRUMENTATION_STATUS_CODE: -[1-9]' <<< "$result"; then
    echo "CHUNKS_GRANT: phase $phase lacked one executed passing test" >&2; return 1
  fi
  log="$(adb logcat -d -v raw -s ChunksGrant:I)"
  printf '%s\n' "$log" | grep 'CHUNKS_GRANT phase=' || true
  if [[ "$(grep -Fxc "$marker" <<< "$log" || true)" != 1 ]]; then
    echo "CHUNKS_GRANT: phase $phase missing unique native positive receipt" >&2; return 1
  fi
  if [[ "$phase" == B_* ]]; then
    [[ "$(grep -Ec '^CHUNKS_GRANT_OBSERVED access=(denied|owned-file)$' <<< "$log" || true)" == 1 ]] || {
      echo 'CHUNKS_GRANT: phase B missing real public-file access observation' >&2; return 1;
    }
    [[ "$(grep -Fxc 'CHUNKS_GRANT_DENIAL restored=true os_open=denied start=refused stopped=true' <<< "$log" || true)" == 1 ]] || {
      echo 'CHUNKS_GRANT: phase B missing native refused Start on real denied FD' >&2; return 1;
    }
    [[ "$(grep -Fxc 'CHUNKS_GRANT_ADVERSARIAL fifo=refused leaf_link=refused parent_link=refused' <<< "$log" || true)" == 1 ]] || {
      echo 'CHUNKS_GRANT: phase B missing FIFO and symlink negatives' >&2; return 1;
    }
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
   [[ "$(grep -Fxc 'INSTRUMENTATION_CODE: -1' <<< "$a" || true)" != 1 ]] ||
   [[ "$(grep -c '^INSTRUMENTATION_CODE:' <<< "$a" || true)" != 1 ]] ||
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
finish() {
  local previous=$?
  trap - EXIT
  if ! restore_grants; then
    echo 'CHUNKS_GRANT: storage grants could not both be restored' >&2
    exit 1
  fi
  exit "$previous"
}
trap finish EXIT
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
