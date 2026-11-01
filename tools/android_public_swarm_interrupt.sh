#!/usr/bin/env bash
# Interrupted real-swarm download: phase A downloads part of the sanitized
# public torrent with the app's own engine and SIGKILLs itself mid-transfer;
# phase B (fresh process) checks honest restore and continued verified growth.
set -euo pipefail
: "${CHUNKS_PUBLIC_SWARM_TORRENT:?}" "${CHUNKS_PUBLIC_SWARM_BUDGET_BYTES:?}" "${CHUNKS_PUBLIC_SWARM_SECONDS:?}"
pkg=io.github.gamesbyian.chunks
# Bound direct/manual execution too; do not depend solely on workflow inputs.
[[ "$CHUNKS_PUBLIC_SWARM_BUDGET_BYTES" =~ ^[0-9]+$ &&
   "$CHUNKS_PUBLIC_SWARM_BUDGET_BYTES" -ge 1 &&
   "$CHUNKS_PUBLIC_SWARM_BUDGET_BYTES" -le 268435456 ]] || {
  echo "::error::Interruption byte budget must be 1..268435456" >&2; exit 2;
}
[[ "$CHUNKS_PUBLIC_SWARM_SECONDS" =~ ^[0-9]+$ &&
   "$CHUNKS_PUBLIC_SWARM_SECONDS" -ge 30 &&
   "$CHUNKS_PUBLIC_SWARM_SECONDS" -le 1800 ]] || {
  echo "::error::Interruption limit must be 30..1800 seconds" >&2; exit 2;
}
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
cls="$pkg.AndroidPublicSwarmInterruptInstrumentedTest"
# Resolve one authorized device and pin every later ADB operation to it.
serial="${ADB_SERIAL:-${ANDROID_SERIAL:-}}"
if [[ -z "$serial" ]]; then
  # A process-substitution mapfile can succeed after adb printed a plausible
  # inventory and exited nonzero; refuse before any APK or peer operation.
  devices="$(adb devices)" || {
    echo "::error::ADB device inventory failed" >&2; exit 2;
  }
  attached=()
  while read -r candidate state _; do
    [[ "$state" != device || -z "$candidate" ]] || attached+=("$candidate")
  done <<< "$devices"
  [[ "${#attached[@]}" -eq 1 ]] || { echo "::error::Exactly one authorized Android device required" >&2; exit 2; }
  serial="${attached[0]}"
fi
export ANDROID_SERIAL="$serial"
# Check the request's exit status as well as stdout before installing or running.
if ! device_state="$(adb get-state | tr -d '\r\n')" || [[ "$device_state" != device ]]; then
  echo "::error::Selected Android device unavailable" >&2; exit 2
fi
# Exit status alone is insufficient: adb can return zero for package errors.
install_verified() {
  local output
  output="$(adb install -r "$1" 2>&1)" || { echo "::error::adb install failed" >&2; return 1; }
  grep -qx 'Success' <<< "$(printf '%s' "$output" | tr -d '\r')" || {
    echo "::error::Android did not report installed APK success" >&2
    return 1
  }
}
install_verified app/build/outputs/apk/debug/app-debug.apk
install_verified app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb exec-in run-as "$pkg" sh -c 'mkdir -p cache && cat > cache/public-swarm.torrent' < "$CHUNKS_PUBLIC_SWARM_TORRENT"
adb logcat -c
set +e
outA=$(adb shell am instrument -w -r -e class "$cls#phaseA_downloadPartThenDieMidTransfer" \
  -e chunksInterruptPhase a -e chunksSwarmTorrent public-swarm.torrent \
  -e chunksSwarmBudgetBytes "$CHUNKS_PUBLIC_SWARM_BUDGET_BYTES" -e chunksSwarmSeconds "$CHUNKS_PUBLIC_SWARM_SECONDS" "$runner")
set -e
echo "$outA" | tail -8
# Capture once. With pipefail, grep -q may close the adb producer early and
# cause SIGPIPE 141 on a large valid log. Inspect the captured response.
logA="$(adb logcat -d -s ChunksSwarm:I)" || {
  echo "::error::Cannot read phase A native evidence" >&2; exit 1;
}
# Extract app-owned markers from adb logcat prefixes, never trust arbitrary
# prior stdout or a substring matching a native acceptance result.
phaseA="$(grep -o 'CHUNKS_SWARM_INTERRUPT: .*' <<< "$logA" || true)"
printf '%s\n' "$phaseA" | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
armed_re='^CHUNKS_SWARM_INTERRUPT: armed hash=([0-9a-f]{40}|[0-9a-f]{64}) verified_before_kill=([1-9][0-9]{0,16}) wanted=([1-9][0-9]{0,16})$'
armed="$(grep -E "$armed_re" <<< "$phaseA" || true)"
if [[ "$(grep -c '^CHUNKS_SWARM_INTERRUPT: armed ' <<< "$phaseA" || true)" != 1 ]] ||
   grep -Fq 'CHUNKS_SWARM_INTERRUPT: INCONCLUSIVE' <<< "$phaseA" ||
   [[ ! "$armed" =~ $armed_re ]]; then
  echo "::error::Phase A missing one valid positive native partial-byte receipt" >&2
  exit 1
fi
hash="${BASH_REMATCH[1]}"
before="${BASH_REMATCH[2]}"
wanted="${BASH_REMATCH[3]}"
if (( before < CHUNKS_PUBLIC_SWARM_BUDGET_BYTES || before >= wanted )); then
  echo "::error::Phase A was not a verified incomplete peer download at the requested budget" >&2
  exit 1
fi
grep -Fq "Process crashed" <<< "$outA" || {
  echo "::error::Phase A never died in the middle of its download" >&2; exit 1;
}
sleep 3
if adb shell pidof "$pkg" >/dev/null 2>&1; then echo "::error::process still alive"; exit 1; fi
adb logcat -c
outB=$(adb shell am instrument -w -r -e class "$cls#phaseB_restoredStoppedRechecksAndContinues" \
  -e chunksInterruptPhase b -e chunksSwarmSeconds "$CHUNKS_PUBLIC_SWARM_SECONDS" "$runner")
echo "$outB" | tail -8
logB="$(adb logcat -d -s ChunksSwarm:I)" || {
  echo "::error::Cannot read phase B native recovery evidence" >&2; exit 1;
}
phaseB="$(grep -o 'CHUNKS_SWARM_INTERRUPT: .*' <<< "$logB" || true)"
printf '%s\n' "$phaseB" | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
# A green instrumented phase B without the exact app-side positive native
# readback proves nothing about restore, recheck or fresh peer-byte growth.
if [[ "$(grep -Fxc 'OK (1 test)' <<< "$outB" || true)" != 1 ]] ||
   grep -Eq '^INSTRUMENTATION_STATUS_CODE: -[1-9]|^FAILURES|AssumptionViolatedException' <<< "$outB" ||
   grep -Fq 'CHUNKS_SWARM_INTERRUPT: INCONCLUSIVE' <<< "$phaseB"; then
  echo "::error::Phase B did not execute exactly one successful recovery test" >&2
  exit 1
fi
verified_re='^CHUNKS_SWARM_INTERRUPT: VERIFIED restored_stopped=true recovered=([0-9]{1,17}) before=([1-9][0-9]{0,16}) continued_to=([1-9][0-9]{0,16})$'
verified="$(grep -E "$verified_re" <<< "$phaseB" || true)"
if [[ "$(grep -c '^CHUNKS_SWARM_INTERRUPT: VERIFIED ' <<< "$phaseB" || true)" != 1 ]] ||
   [[ ! "$verified" =~ $verified_re ]]; then
  echo "::error::Phase B native restored-and-continued receipt absent or ambiguous" >&2
  exit 1
fi
recovered="${BASH_REMATCH[1]}"
recorded_before="${BASH_REMATCH[2]}"
continued="${BASH_REMATCH[3]}"
if (( recorded_before != before || recovered > continued || continued <= before ||
      continued <= recovered || continued > wanted )); then
  echo "::error::Restored native byte growth does not match the precise phase A torrent" >&2
  exit 1
fi
