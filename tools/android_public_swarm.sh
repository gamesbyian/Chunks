#!/usr/bin/env bash
# Android public swarm: copy a collector-sanitized torrent into the debuggable
# app's cache and run the opt-in swarm test. Fails if the test was skipped, so
# a green run always means the native engine verified the byte budget.
set -euo pipefail
: "${CHUNKS_PUBLIC_SWARM_TORRENT:?}" "${CHUNKS_PUBLIC_SWARM_BUDGET_BYTES:?}" "${CHUNKS_PUBLIC_SWARM_SECONDS:?}"
pkg=io.github.gamesbyian.chunks
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
adb logcat -d -s ChunksSwarm:I | grep -o 'CHUNKS_ANDROID_SWARM: .*' | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}" || true
if [ "$status" -ne 0 ] || ! grep -q '^OK (1 test)' "$log" ||
   grep -qE '^INSTRUMENTATION_STATUS_CODE: -(1|2|3|4)' "$log"; then
 grep -A12 '^INSTRUMENTATION_STATUS: stack=' "$log" | head -40 || true
 echo "::error::Android swarm test failed or was skipped (INCONCLUSIVE means peer availability, not engine acceptance)"
 exit 1
fi
