#!/usr/bin/env bash
# Opt-in real-peer outage and recovery. Never leave a physical handset's
# Wi-Fi/mobile-data toggles different from their original verified states.
set -euo pipefail
: "${CHUNKS_PUBLIC_SWARM_TORRENT:?}" "${CHUNKS_PUBLIC_SWARM_SECONDS:?}"
source "$(dirname "${BASH_SOURCE[0]}")/android_radio_baseline.sh"
pkg=io.github.gamesbyian.chunks
outage=${CHUNKS_NET_OUTAGE_SECONDS:-30}
[[ "$outage" =~ ^[0-9]+$ && "$outage" -ge 1 && "$outage" -le 300 ]] || {
  echo "CHUNKS_NET_OUTAGE_SECONDS must be an integer from 1 to 300" >&2; exit 2;
}
[[ "$CHUNKS_PUBLIC_SWARM_SECONDS" =~ ^[0-9]+$ && "$CHUNKS_PUBLIC_SWARM_SECONDS" -ge 30 && "$CHUNKS_PUBLIC_SWARM_SECONDS" -le 1800 ]] || {
  echo "CHUNKS_PUBLIC_SWARM_SECONDS must be an integer from 30 to 1800" >&2; exit 2;
}
# A wireless adb connection would disappear when the script disables radios.
# Pin an explicit serial before *any* install or radio operation.
serial="${ADB_SERIAL:-${ANDROID_SERIAL:-}}"
# Process substitution hides the ADB exit status. A failed adb devices that
# prints one plausible serial must never authorize physical radio mutations.
devices="$(adb devices)" || {
  echo "Could not enumerate ADB devices: no install or radio operations" >&2; exit 2;
}
if [[ -z "$serial" ]]; then
  mapfile -t serials < <(awk 'NR > 1 && $2 == "device" { print $1 }' <<< "$devices")
  [[ "${#serials[@]}" -eq 1 ]] || {
    echo "Select exactly one authorized device (ADB_SERIAL)" >&2; exit 2;
  }
  serial="${serials[0]}"
fi
# Even an explicitly selected serial must be connected and uniquely listed;
# adb get-state alone is insufficient when a selector is stale/misconfigured.
[[ "$(awk -v selected="$serial" '$1 == selected && $2 == "device" { n++ } END { print n+0 }' <<< "$devices")" == 1 ]] || {
  echo "Selected ADB_SERIAL is absent or not uniquely connected" >&2; exit 2;
}
export ANDROID_SERIAL="$serial"
[[ "$serial" != *:* ]] || { echo "Network-flap acceptance requires USB adb, not wireless adb" >&2; exit 2; }
export ANDROID_SERIAL="$serial"
# Failed ADB queries can leave plausible output; neither alone verifies a target.
if ! device_state="$(adb get-state | tr -d '\r\n')" || [[ "$device_state" != device ]]; then
  echo "Selected adb device unavailable" >&2; exit 2
fi

CHUNKS_WIFI_BASELINE= CHUNKS_DATA_BASELINE=
# svc returns an acknowledgement, not proof that a requested radio state
# changed. The app's separate ConnectivityManager witness must ALSO observe
# loss/return of validated Internet; neither observation replaces the other.
verify_radios_disabled() {
  local wifi data
  for _ in 1 2 3; do
    if ! wifi="$(adb shell settings get global wifi_on | tr -d '\r\n')" ||
       ! data="$(adb shell settings get global mobile_data | tr -d '\r\n')"; then
      echo "CHUNKS_SWARM_NETFLAP: radio readback failed" >&2
      return 1
    fi
    if [[ ! "$wifi" =~ ^[01]$ || ! "$data" =~ ^[01]$ ]]; then
      echo "CHUNKS_SWARM_NETFLAP: ambiguous radio readback" >&2
      return 1
    fi
    if [[ "$wifi" == 0 && "$data" == 0 ]]; then return 0; fi
    sleep 1
  done
  echo "CHUNKS_SWARM_NETFLAP: radios did not both turn off (wifi=$wifi data=$data)" >&2
  return 1
}
# Reject unsupported OEM settings *before* installing APKs or launching
# instrumentation; refresh the baseline again immediately before toggling.
chunks_read_radio_baseline || { echo "Radio baseline unavailable: no test started" >&2; exit 1; }
radio_changed=0
log="$(mktemp)"
restore() {
  local rc=$?
  trap - EXIT
  if [[ "$radio_changed" -eq 1 ]]; then
    if ! chunks_restore_radio_baseline; then
      echo "Failed to restore the device's original Wi-Fi/mobile-data settings" >&2
      rc=1
    fi
  fi
  rm -f "$log"
  exit "$rc"
}
trap restore EXIT
# Command success alone cannot certify installation; adb sometimes exits zero
# while printing a package-manager Failure.
install_verified() {
  local output
  output="$(adb install -r "$1" 2>&1)" || {
    echo "CHUNKS_SWARM_NETFLAP: adb install failed" >&2; return 1;
  }
  grep -Fxq 'Success' <<< "$(printf '%s\n' "$output" | tr -d '\r')" || {
    echo "CHUNKS_SWARM_NETFLAP: package manager did not confirm install" >&2
    return 1
  }
}
install_verified app/build/outputs/apk/debug/app-debug.apk
install_verified app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb exec-in run-as "$pkg" sh -c 'mkdir -p cache && cat > cache/public-swarm.torrent' < "$CHUNKS_PUBLIC_SWARM_TORRENT"
adb logcat -c
adb shell am instrument -w -r -e class "$pkg.AndroidPublicSwarmNetworkFlapInstrumentedTest" \
  -e chunksNetFlap 1 -e chunksSwarmTorrent public-swarm.torrent -e chunksSwarmSeconds "$CHUNKS_PUBLIC_SWARM_SECONDS" \
  "$pkg.test/androidx.test.runner.AndroidJUnitRunner" >"$log" 2>&1 &
inst=$!
ready=0
for _ in $(seq 1 $(( CHUNKS_PUBLIC_SWARM_SECONDS / 2 + 30 ))); do
  if adb logcat -d -s ChunksSwarm:I | grep -q "CHUNKS_SWARM_NETFLAP: ready"; then ready=1; break; fi
  kill -0 "$inst" 2>/dev/null || break
  sleep 2
done
if [[ "$ready" -eq 1 ]]; then
  # The mobile-data setting is sometimes unavailable on multi-SIM/older OEM
  # builds. Refuse instead of changing a state we cannot later restore.
  chunks_read_radio_baseline || { echo "Radio baseline unavailable: no radios changed" >&2; exit 1; }
  radio_changed=1
  adb shell svc wifi disable
  adb shell svc data disable
  # Never signal a network outage to the instrumented app on svc ACK alone.
  # The exit trap reverts even a partially executed radio command.
  verify_radios_disabled || exit 1
  adb exec-out run-as "$pkg" touch files/net-off
  echo "CHUNKS_SWARM_NETFLAP network off for ${outage}s"
  sleep "$outage"
  chunks_restore_radio_baseline
  radio_changed=0
  adb exec-out run-as "$pkg" touch files/net-on
  echo "CHUNKS_SWARM_NETFLAP network returned to original settings"
fi
set +e
wait "$inst"
instrument_status=$?
set -e
# Do not infer an actual validated-network outage or post-return payload
# recovery from an adb/JUnit OK line. Inspect a fresh app-owned positive
# witness for every indispensable step of the real instrumented journey.
native_log="$(adb logcat -d -s ChunksSwarm:I)" || {
  echo "::error::Could not read native network-loss evidence" >&2; exit 1;
}
native_receipts="$(grep -o 'CHUNKS_SWARM_NETFLAP: .*' <<< "$native_log" || true)"
printf '%s\n' "$native_receipts" | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"

if [[ "$instrument_status" -ne 0 || "$ready" -ne 1 ]] ||
   [[ "$(grep -Fxc 'OK (1 test)' "$log" || true)" != 1 ]] ||
   grep -Eq '^INSTRUMENTATION_STATUS_CODE: -[1-9]|^FAILURES|AssumptionViolatedException' "$log" ||
   grep -Fq 'CHUNKS_SWARM_NETFLAP: INCONCLUSIVE' <<< "$native_receipts"; then
  awk '/^INSTRUMENTATION_STATUS: stack=/{p=1} p && /^INSTRUMENTATION_STATUS(_CODE)?: / && !/stack=/{p=0} p' "$log" | head -200 || true
  echo "::error::Network flap instrumented journey was not a verified executed success" >&2
  exit 1
fi
for witness in 'default_network_baseline_validated=true' \
               'network_witness_default_internet_lost=true' \
               'network_witness_default_internet_restored=true'; do
  if [[ "$(grep -Fxc "CHUNKS_SWARM_NETFLAP: $witness" <<< "$native_receipts" || true)" != 1 ]]; then
    echo "::error::Missing or duplicated native validated-Internet witness: $witness" >&2
    exit 1
  fi
done
ready_re='^CHUNKS_SWARM_NETFLAP: ready verified=([0-9]{1,16})$'
return_re='^CHUNKS_SWARM_NETFLAP: outage verified_at_loss=([0-9]{1,16}) verified_at_return=([0-9]{1,16}) stopped=false error=(true|false)$'
success_re='^CHUNKS_SWARM_NETFLAP: VERIFIED resumed_after_s=([0-9]{1,16}) verified=([0-9]{1,16}) peers=([0-9]{1,16})$'
ready_line="$(grep -E "$ready_re" <<< "$native_receipts" || true)"
return_line="$(grep -E "$return_re" <<< "$native_receipts" || true)"
success_line="$(grep -E "$success_re" <<< "$native_receipts" || true)"
if [[ "$(grep -c '^CHUNKS_SWARM_NETFLAP: ready ' <<< "$native_receipts" || true)" != 1 ||
      "$(grep -c '^CHUNKS_SWARM_NETFLAP: outage ' <<< "$native_receipts" || true)" != 1 ||
      "$(grep -c '^CHUNKS_SWARM_NETFLAP: VERIFIED ' <<< "$native_receipts" || true)" != 1 ]] ||
   [[ ! "$ready_line" =~ $ready_re ]]; then
  echo "::error::Missing, repeated or malformed native netflap byte receipt" >&2
  exit 1
fi
ready_bytes="${BASH_REMATCH[1]}"
if [[ ! "$return_line" =~ $return_re ]]; then
  echo "::error::Missing native exact outage-return state receipt" >&2
  exit 1
fi
at_loss="${BASH_REMATCH[1]}"
at_return="${BASH_REMATCH[2]}"
if [[ ! "$success_line" =~ $success_re ]]; then
  echo "::error::Missing native post-network verified-byte receipt" >&2
  exit 1
fi
continued="${BASH_REMATCH[2]}"
# The actual Android test independently requires 4 MiB before toggles
# and 2 MiB fresh hash-verified bytes after network return; enforce the
# same numerical contract in external acceptance with no plausible OK-only pass.
if (( ready_bytes < 4194304 || at_loss < ready_bytes ||
      at_return < at_loss || continued < at_return + 2097152 )); then
  echo "::error::Native per-hash bytes did not grow after the validated network returned" >&2
  exit 1
fi
