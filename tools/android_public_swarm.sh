#!/usr/bin/env bash
# Android public swarm: copy a collector-sanitized torrent into the debuggable
# app's cache and run the opt-in swarm test. Fails if the test was skipped, so
# a green run always means the native engine verified the byte budget.
set -euo pipefail
: "${CHUNKS_PUBLIC_SWARM_TORRENT:?}" "${CHUNKS_PUBLIC_SWARM_BUDGET_BYTES:?}" "${CHUNKS_PUBLIC_SWARM_SECONDS:?}"
pkg=io.github.gamesbyian.chunks
# Bound direct/manual invocations too, not only workflow_dispatch inputs.
[[ "$CHUNKS_PUBLIC_SWARM_BUDGET_BYTES" =~ ^[0-9]+$ &&
   "$CHUNKS_PUBLIC_SWARM_BUDGET_BYTES" -ge 1 &&
   "$CHUNKS_PUBLIC_SWARM_BUDGET_BYTES" -le 268435456 ]] || {
  echo 'CHUNKS_ANDROID_SWARM: budget must be 1..268435456 verified bytes' >&2; exit 2;
}
[[ "$CHUNKS_PUBLIC_SWARM_SECONDS" =~ ^[0-9]+$ &&
   "$CHUNKS_PUBLIC_SWARM_SECONDS" -ge 30 &&
   "$CHUNKS_PUBLIC_SWARM_SECONDS" -le 1800 ]] || {
  echo 'CHUNKS_ANDROID_SWARM: duration must be 30..1800 seconds' >&2; exit 2;
}
# Resolve and pin a single authorized adb target before any installation.
# Process substitution must not hide a failed device-list command.
serial="${ADB_SERIAL:-${ANDROID_SERIAL:-}}"
if [[ -z "$serial" ]]; then
  devices="$(adb devices)" || {
    echo 'CHUNKS_ANDROID_SWARM: adb device inventory failed' >&2; exit 2;
  }
  serials=()
  while read -r candidate state _; do
    [[ "$state" != device || -z "$candidate" ]] || serials+=("$candidate")
  done <<< "$devices"
  [[ "${#serials[@]}" -eq 1 ]] || {
    echo 'CHUNKS_ANDROID_SWARM: expected exactly one authorized adb device' >&2; exit 2;
  }
  serial="${serials[0]}"
fi
export ANDROID_SERIAL="$serial"
if ! state="$(adb get-state | tr -d '\r\n')" || [[ "$state" != device ]]; then
  echo 'CHUNKS_ANDROID_SWARM: selected adb device unavailable' >&2; exit 2;
fi
# adb can return exit 0 with an actual package-manager Failure.
install_verified() {
  local output
  output="$(adb install -r "$1" 2>&1)" || {
    echo 'CHUNKS_ANDROID_SWARM: adb install failed' >&2; return 1;
  }
  grep -Fxq 'Success' <<< "$(printf '%s\n' "$output" | tr -d '\r')" || {
    echo 'CHUNKS_ANDROID_SWARM: package manager did not report Success' >&2
    return 1
  }
}
install_verified app/build/outputs/apk/debug/app-debug.apk
install_verified app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb exec-in run-as "$pkg" sh -c 'mkdir -p cache && cat > cache/public-swarm.torrent' < "$CHUNKS_PUBLIC_SWARM_TORRENT"
adb logcat -c
log=$(mktemp)
trap 'rm -f "$log"' EXIT
set +e
adb shell am instrument -w -r \
 -e class "$pkg.AndroidPublicSwarmInstrumentedTest" \
 -e chunksSwarmTorrent public-swarm.torrent \
 -e chunksSwarmBudgetBytes "$CHUNKS_PUBLIC_SWARM_BUDGET_BYTES" \
 -e chunksSwarmSeconds "$CHUNKS_PUBLIC_SWARM_SECONDS" \
 "$pkg.test/androidx.test.runner.AndroidJUnitRunner" >"$log" 2>&1
status=$?
set -e
# An adb success and an 'OK (1 test)' substring do not prove an executed
# positive peer-byte witness. Require one complete test result and exactly
# one matching native receipt from this freshly cleared logcat buffer.
native_log="$(adb logcat -d -s ChunksSwarm:I)" || {
  echo "::error::Cannot read app-side public swarm evidence" >&2; exit 1;
}
# Real adb logcat prefixes each application message with timestamp/tag.
# Inspect the app-owned marker, not the full logcat prefix.
native_receipts="$(grep -o 'CHUNKS_ANDROID_SWARM: .*' <<< "$native_log" || true)"
printf '%s\n' "$native_receipts" |
  tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
if [[ "$status" -ne 0 ]] ||
   [[ "$(grep -Fxc 'OK (1 test)' "$log" || true)" != 1 ]] ||
   grep -Eq '^INSTRUMENTATION_STATUS_CODE: -[1-9]|^FAILURES|AssumptionViolatedException' "$log" ||
   grep -Fq 'CHUNKS_ANDROID_SWARM: INCONCLUSIVE' <<< "$native_receipts"; then
  grep -A12 '^INSTRUMENTATION_STATUS: stack=' "$log" | head -40 || true
  echo "::error::Android swarm execution failed, was skipped or had an inconclusive peer result" >&2
  exit 1
fi
positive="$(grep -E '^CHUNKS_ANDROID_SWARM: VERIFIED hash=[0-9a-f]{40} verified=[0-9]+ target=[0-9]+ maxPeers=[0-9]+ trackers=.* seconds=[0-9]+
if [[ "$(grep -c '^CHUNKS_ANDROID_SWARM: VERIFIED ' <<< "$native_receipts" || true)" != 1 ]] ||
   [[ "$(grep -c '^CHUNKS_ANDROID_SWARM: VERIFIED ' <<< "$positive" || true)" != 1 ]] ||
   [[ ! "$positive" =~ ^CHUNKS_ANDROID_SWARM:\ VERIFIED\ hash=[0-9a-f]{40}\ verified=([0-9]+)\ target=([0-9]+)\ maxPeers=[0-9]+\ trackers=.*\ seconds=[0-9]+$ ]]; then
  echo "::error::Missing or ambiguous complete native peer-byte receipt" >&2
  exit 1
fi
observed="${BASH_REMATCH[1]}"
required="${BASH_REMATCH[2]}"
if (( required < 1 || required > CHUNKS_PUBLIC_SWARM_BUDGET_BYTES || observed < required )); then
  echo "::error::Native public-peer verified bytes did not satisfy the requested positive budget" >&2
  exit 1
fi
 <<< "$native_receipts" || true)"
if [[ "$(grep -c '^CHUNKS_ANDROID_SWARM: VERIFIED ' <<< "$native_receipts" || true)" != 1 ]] ||
   [[ "$(grep -c '^CHUNKS_ANDROID_SWARM: VERIFIED ' <<< "$positive" || true)" != 1 ]] ||
   [[ ! "$positive" =~ ^CHUNKS_ANDROID_SWARM:\ VERIFIED\ hash=[0-9a-f]{40}\ verified=([0-9]+)\ target=([0-9]+)\ maxPeers=[0-9]+\ trackers=.*\ seconds=[0-9]+$ ]]; then
  echo "::error::Missing or ambiguous complete native peer-byte receipt" >&2
  exit 1
fi
observed="${BASH_REMATCH[1]}"
required="${BASH_REMATCH[2]}"
if (( required < 1 || required > CHUNKS_PUBLIC_SWARM_BUDGET_BYTES || observed < required )); then
  echo "::error::Native public-peer verified bytes did not satisfy the requested positive budget" >&2
  exit 1
fi
