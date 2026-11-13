#!/usr/bin/env bash
# Doze with real peers: a foreground-service download runs, the device is
# forced into deep idle once bytes flow, and the test records whether
# verified bytes keep arriving. Restores battery/idle state afterwards.
set -euo pipefail
: "${CHUNKS_PUBLIC_SWARM_TORRENT:?}" "${CHUNKS_PUBLIC_SWARM_SECONDS:?}"
pkg=io.github.gamesbyian.chunks
# Freeze one adb target before any installation, radio/battery mutation or
# cleanup. More than one connected device is ambiguous without an explicit ID.
serial="${ADB_SERIAL:-${ANDROID_SERIAL:-}}"
# ADB may print a credible single connected serial and still exit unsuccessfully.
# Process substitution masks that exit status. Refuse without *any* install
# or physical battery/idle state changes on incomplete enumeration.
devices="$(adb devices)" || {
  echo "CHUNKS_SWARM_DOZE: ADB enumeration failed" >&2; exit 2;
}
if [[ -z "$serial" ]]; then
  mapfile -t serials < <(awk 'NR > 1 && $2 == "device" { print $1 }' <<< "$devices")
  [[ "${#serials[@]}" -eq 1 ]] || {
    echo "CHUNKS_SWARM_DOZE: exactly one authorized adb device required" >&2; exit 2;
  }
  serial="${serials[0]}"
fi
[[ "$(awk -v selected="$serial" '$1 == selected && $2 == "device" { n++ } END { print n+0 }' <<< "$devices")" == 1 ]] || {
  echo "CHUNKS_SWARM_DOZE: selected serial absent or not connected" >&2; exit 2;
}
export ANDROID_SERIAL="$serial"
# Plausible stdout from a failed ADB request is not a verified device state.
if ! device_state="$(adb get-state | tr -d '\r\n')" || [[ "$device_state" != device ]]; then
  echo "CHUNKS_SWARM_DOZE: selected adb device unavailable" >&2; exit 2
fi
# An adb process can exit zero even when package-manager output says Failure.
install_verified() {
  local output
  output="$(adb install -r "$1" 2>&1)" || {
    echo "CHUNKS_SWARM_DOZE: adb install failed" >&2; return 1;
  }
  grep -Fxq 'Success' <<< "$(printf '%s\n' "$output" | tr -d '\r')" || {
    echo "CHUNKS_SWARM_DOZE: package manager did not confirm install" >&2
    return 1
  }
}
install_verified app/build/outputs/apk/debug/app-debug.apk
install_verified app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb exec-in run-as "$pkg" sh -c 'mkdir -p cache && cat > cache/public-swarm.torrent' < "$CHUNKS_PUBLIC_SWARM_TORRENT"
log=""
idle_touched=0
battery_touched=0
restore() {
  local rc=$?
  trap - EXIT
  if [[ "$idle_touched" -eq 1 ]]; then
    if ! adb shell dumpsys deviceidle unforce >/dev/null 2>&1; then
      echo "CHUNKS_SWARM_DOZE: failed to release forced idle" >&2
      rc=1
    fi
  fi
  if [[ "$battery_touched" -eq 1 ]]; then
    if ! adb shell dumpsys battery reset >/dev/null 2>&1; then
      echo "CHUNKS_SWARM_DOZE: failed to reset simulated battery" >&2
      rc=1
    fi
  fi
  [[ -z "$log" ]] || rm -f -- "$log"
  exit "$rc"
}
trap restore EXIT
echo "CHUNKS_SWARM_DOZE allowlisted: $(adb shell dumpsys deviceidle whitelist | grep -c "$pkg" || true)"
adb logcat -c
log=$(mktemp)
adb shell am instrument -w -r -e class "$pkg.AndroidPublicSwarmDozeInstrumentedTest" \
  -e chunksDoze 1 -e chunksSwarmTorrent public-swarm.torrent -e chunksSwarmSeconds "$CHUNKS_PUBLIC_SWARM_SECONDS" \
  "$pkg.test/androidx.test.runner.AndroidJUnitRunner" >"$log" 2>&1 &
inst=$!
ready=0
for _ in $(seq 1 $(( CHUNKS_PUBLIC_SWARM_SECONDS / 2 + 30 ))); do
  # Consume all logcat output; grep -q can SIGPIPE a healthy adb producer under pipefail.
  if adb logcat -d -s ChunksSwarm:I | grep -F "CHUNKS_SWARM_DOZE: ready" >/dev/null; then ready=1; break; fi
  kill -0 "$inst" 2>/dev/null || break
  sleep 2
done
if [ "$ready" -eq 1 ]; then
  # Set touched flags BEFORE each operation: a failed adb request may still
  # have partially changed Android state and must be compensated.
  battery_touched=1
  adb shell dumpsys battery unplug
  idle_touched=1
  adb shell dumpsys deviceidle force-idle
  state=$(adb shell dumpsys deviceidle get deep | tr -d '\r')
  echo "CHUNKS_SWARM_DOZE deep_state=$state"
  [ "$state" = "IDLE" ] || { echo "::error::device did not enter deep idle ($state)"; kill "$inst" || true; exit 1; }
  adb exec-out run-as "$pkg" touch files/doze-on
fi
set +e
wait "$inst"
instrument_status=$?
set -e
# A passing JUnit result alone does not establish actual sustained Doze:
# the Android test intentionally permits both GROWTH and STALLED, but requires
# nineteen observed isDeviceIdleMode samples spanning ninety seconds.
native_log="$(adb logcat -d -s ChunksSwarm:I)" || {
  echo "::error::Doze native evidence logcat is unavailable" >&2; exit 1;
}
receipts="$(grep -o 'CHUNKS_SWARM_DOZE: .*' <<< "$native_log" || true)"
printf '%s\n' "$receipts" | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
if [[ "$ready" -ne 1 || "$instrument_status" -ne 0 ]] ||
   [[ "$(grep -Fxc 'OK (1 test)' "$log" || true)" != 1 ]] ||
   grep -Eq '^INSTRUMENTATION_STATUS_CODE: -[1-9]|^FAILURES|AssumptionViolatedException' "$log" ||
   grep -Fq 'CHUNKS_SWARM_DOZE: INCONCLUSIVE' <<< "$receipts"; then
  awk '/^INSTRUMENTATION_STATUS: stack=/{p=1} p && /^INSTRUMENTATION_STATUS(_CODE)?: / && !/stack=/{p=0} p' "$log" | head -200 || true
  echo "::error::Doze test did not complete with actual native idle proof" >&2
  exit 1
fi
ready_re='^CHUNKS_SWARM_DOZE: ready verified=([0-9]{1,17})$'
window_re='^CHUNKS_SWARM_DOZE: (GROWTH|STALLED) idle_mode=true idle_samples=19 window_ms=90000 verified_before=([0-9]{1,17}) verified_after=([0-9]{1,17}) grew=(-?[0-9]{1,17}) peers=([0-9]{1,17})$'
ready_line="$(grep -E "$ready_re" <<< "$receipts" || true)"
window_line="$(grep -E "$window_re" <<< "$receipts" || true)"
if [[ "$(grep -c '^CHUNKS_SWARM_DOZE: ready ' <<< "$receipts" || true)" != 1 ||
      "$(grep -Ec '^CHUNKS_SWARM_DOZE: (GROWTH|STALLED) ' <<< "$receipts" || true)" != 1 ]] ||
   [[ ! "$ready_line" =~ $ready_re ]]; then
  echo "::error::Missing or ambiguous native Doze warmup/idle-window receipt" >&2
  exit 1
fi
ready_bytes="${BASH_REMATCH[1]}"
if [[ ! "$window_line" =~ $window_re ]]; then
  echo "::error::The app never certified the sustained 19-sample idle window" >&2
  exit 1
fi
observation="${BASH_REMATCH[1]}"
before="${BASH_REMATCH[2]}"
after="${BASH_REMATCH[3]}"
delta="${BASH_REMATCH[4]}"
if (( ready_bytes < 4194304 || before < ready_bytes || after < 0 ||
      after - before != delta )) ||
   { [[ "$observation" == GROWTH ]] && (( delta <= 0 )); } ||
   { [[ "$observation" == STALLED ]] && (( delta > 0 )); }; then
  echo "::error::Doze idle-window native byte observation is contradictory" >&2
  exit 1
fi
echo "CHUNKS_SWARM_DOZE: accepted 90-second observed idle window with outcome=$observation (not a long-run background guarantee)"
