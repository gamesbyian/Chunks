#!/usr/bin/env bash
# Disposable-only: installed app against the runner's loopback Transmission,
# qBittorrent and Deluge daemons (one emulator boot, app data cleared per client).
set -euo pipefail
# Bound every Android instrumentation phase independently; timeouts are failures.
out="$RUNNER_TEMP/interop/evidence"
mkdir -p "$out"
source_sha="$(git rev-parse HEAD)"
test "$source_sha" = "${CHUNKS_SOURCE_SHA:?}"
test "$source_sha" = "${GITHUB_SHA:?}"
source_tree="$(git rev-parse HEAD^{tree})"
pkg=io.github.gamesbyian.chunks
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
app_apk=app/build/outputs/apk/debug/app-debug.apk
adb install -r "$app_apk" >/dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
version="$(adb shell dumpsys package "$pkg" | sed -n 's/^[[:space:]]*versionName=//p' | tr -d '\r' | head -1)"
test "$version" = "0.1.0-dev+${source_sha:0:12}"
installed="$(adb shell pm path "$pkg" | sed -n 's/^package://p' | tr -d '\r' | head -1)"
{
  printf 'CHUNKS_PROTOCOL_SOURCE: commit=%s tree=%s package=%s version=%s\n' "$source_sha" "$source_tree" "$pkg" "$version"
  bash tools/verify_installed_apk_bytes.sh "$app_apk" "$installed" "$out/installed-pull-tmp.apk"
} | tee "$out/installed-identity.txt"
proxy_pids=()
trap 'kill "${proxy_pids[@]}" 2>/dev/null || true' EXIT
start_proxy() {
  local kind=$1 port=$2
  python3 -I tools/interop/add_response_fault_proxy.py "$kind" "$port" &
  proxy_pids+=("$!")
  for attempt in {1..10}; do
    if curl -fsS --max-time 1 -o /dev/null "http://127.0.0.1:$port/__chunks_add_response_fault/counts"; then
      return 0
    fi
    sleep 1
  done
  echo "::error::Disposable Add fault relay failed to start for $kind"
  return 1
}
start_proxy transmission 19191
start_proxy qbittorrent 18180
start_proxy deluge 18212
start_proxy rtorrent 18190
for port in 19091 18080 18112 18090 19191 18180 18212 18190; do
  adb reverse tcp:$port tcp:$port >/dev/null
done
b64() { printf '%s' "$1" | base64 -w 0; }
# A timed-out host adb command does not guarantee its Android instrumentation
# process has stopped. Never start the next mutating journey against that AVD.
abort_on_instrument_timeout() {
  local status=$1 phase=$2
  if [ "$status" -eq 124 ] || [ "$status" -eq 137 ]; then
    echo "::error::Installed $phase instrumentation exceeded its deadline; stopping the campaign"
    timeout 15s adb logcat -d -t 3000 > "$out/logcat-timeout-$phase.txt" 2>&1 || true
    exit 124
  fi
}
run_one() {
  local kind=$1 url=$2 app_url=$3 user=$4 pass=$5 mode=$6
  local class=io.github.gamesbyian.chunks.ProtocolClientInstalledJourneyTest
  local test_method=protocolClientConnectTransfersDetailsAddAndRemove
  if [ "$mode" = fault ]; then test_method=installedAddAfterTheDaemonResponseIsWithheld; fi
  local raw="$out/instrumentation-$kind-$mode.txt" stages="$out/stages-$kind-$mode.txt"
  local phase_started=$SECONDS
  echo "CHUNKS_INSTALLED_PROTOCOL: $kind $mode started (12m instrumentation deadline)"
  local custom_folder="$RUNNER_TEMP/interop/dl/custom-${kind,,}"
  mkdir -p "$custom_folder"
  # Android shell sometimes resolves 'current' to USER_CURRENT (-2) after prior instrumentation.
  # This disposable emulator has a known primary user; clear exactly user 0.
  adb shell pm clear --user 0 "$pkg" >/dev/null || return 1
  set +e
  # Give instrumentation an explicit primary user; CURRENT (-2) is not resolved
  # reliably after repeated app/test process launches in this disposable AVD.
  timeout --signal=TERM --kill-after=15s 12m adb shell am instrument --user 0 -w -r \
    -e class "$class#$test_method" \
    -e chunks_protocol_disposable authorized \
    -e chunks_protocol_kind_b64 "$(b64 "$kind")" \
    -e chunks_protocol_url_b64 "$(b64 "$url")" \
    -e chunks_protocol_app_url_b64 "$(b64 "$app_url")" \
    -e chunks_protocol_custom_folder_b64 "$(b64 "$custom_folder")" \
    -e chunks_protocol_user_b64 "$(b64 "$user")" \
    -e chunks_protocol_pass_b64 "$(b64 "$pass")" \
    "$runner" \
    2>&1 | tee "$raw" | awk '/^INSTRUMENTATION_STATUS: chunks_stage=/ { sub(/\r$/, ""); print; fflush() }'
  local status=${PIPESTATUS[0]}
  set -e
  tr -d '\r' < "$raw" | grep -E '^INSTRUMENTATION_STATUS: chunks_stage=' > "$stages" || true
  echo "CHUNKS_INSTALLED_PROTOCOL: $kind $mode finished after $((SECONDS - phase_started))s (instrumentation status=$status)"
  if [ "$status" -ne 0 ] || ! grep -q '^OK (1 test)' "$raw"; then
    tail -80 "$raw"
    abort_on_instrument_timeout "$status" "$kind-$mode"
    echo "::error::Installed protocol-client $mode journey failed for $kind"
    return 1
  fi
  if [ "$mode" = positive ]; then
    if ! grep -Fxq 'INSTRUMENTATION_STATUS: chunks_stage=smart-routing-exact-live-default-no-write' "$stages"; then
      echo "::error::Missing installed $kind exact-client Smart Routing and no-write witness"
      return 1
    fi
  fi
  if [ "$mode" = fault ]; then
    for fault in drop timeout; do
      if ! grep -Fxq "INSTRUMENTATION_STATUS: chunks_stage=add-fault-$fault-one-write-present-origin-unverified" "$stages"; then
        echo "::error::Missing installed $kind $fault witness"
        return 1
      fi
    done
    if [ "$kind" = TRANSMISSION ]; then
      for witness in add-delayed-valid-ack-then-exact-verified-readback changed-default-refused-without-write; do
        if ! grep -Fxq "INSTRUMENTATION_STATUS: chunks_stage=$witness" "$stages"; then
          echo "::error::Missing installed Transmission witness: $witness"
          return 1
        fi
      done
    fi
  fi
  echo "CHUNKS_INSTALLED_PROTOCOL: $kind $mode passed" | tee -a "$GITHUB_STEP_SUMMARY"
}
run_pair() {
  local result=0
  run_one "$@" positive || result=1
  run_one "$@" fault || result=1
  return "$result"
}
failed=0
run_pair TRANSMISSION http://127.0.0.1:19091/transmission/rpc http://127.0.0.1:19191/transmission/rpc chunks chunkspass || failed=1
run_pair QBITTORRENT http://127.0.0.1:18080/ http://127.0.0.1:18180/ chunks chunkspass || failed=1
# Deluge authenticates with a password only; a blank shell extra is swallowed by
# adb shell, shifting every following am instrument argument out of position.
# Send a non-empty unused username placeholder (ignored by the Deluge adapter).
run_pair DELUGE http://127.0.0.1:18112/json http://127.0.0.1:18212/json unused deluge || failed=1
rtorrent_test=io.github.gamesbyian.chunks.RTorrentInstalledAddResponseFaultTest
run_rtorrent_faults() {
  local phase_started=$SECONDS
  echo "CHUNKS_INSTALLED_PROTOCOL: RTORRENT response faults started (12m instrumentation deadline)"
  adb shell pm clear --user 0 "$pkg" >/dev/null || return 1
  local raw="$out/instrumentation-RTORRENT.txt" stages="$out/stages-RTORRENT.txt"
  local status=0
  timeout --signal=TERM --kill-after=15s 12m adb shell am instrument --user 0 -w -r \
    -e class "$rtorrent_test#stoppedDocumentAddRemainsUnattributedAfterTheRealServerReplyIsLost" \
    -e chunks_rtorrent_add_fault authorized \
    "$runner" > "$raw" 2>&1 || status=$?
  abort_on_instrument_timeout "$status" "RTORRENT-response-faults"
  if [ "$status" -ne 0 ] || ! grep -q '^OK (1 test)' "$raw"; then
    tail -80 "$raw"
    echo "::error::Installed rTorrent Add response fault journey failed"
    return 1
  fi
  tr -d '\r' < "$raw" | grep -E '^INSTRUMENTATION_STATUS: chunks_stage=' | tee "$stages"
  for fault in drop timeout; do
    grep -Fxq "INSTRUMENTATION_STATUS: chunks_stage=add-fault-$fault-one-write-present-origin-unverified" "$stages" || return 1
  done
  grep -Fxq 'INSTRUMENTATION_STATUS: chunks_stage=add-fault-hidden-main-session-found-with-origin-unverified' "$stages" ||
    return 1
  echo "CHUNKS_INSTALLED_PROTOCOL: RTORRENT response faults finished after $((SECONDS - phase_started))s"
  echo "CHUNKS_INSTALLED_PROTOCOL: RTORRENT installed document Add and post-write response loss/timeout passed" |
    tee -a "$GITHUB_STEP_SUMMARY"
}

run_rtorrent_death() {
  local phase_started=$SECONDS
  echo "CHUNKS_INSTALLED_PROTOCOL: RTORRENT process-death journey started"
  [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r\n')" == 1 ]] || return 1
  adb shell pm clear --user 0 "$pkg" >/dev/null || return 1
  adb logcat -c || return 1
  local phase1_status=0
  timeout --signal=TERM --kill-after=15s 7m adb shell am instrument --user 0 -w -r -e class "$rtorrent_test#submitThenKillTheAppProcess" \
    -e chunks_rtorrent_add_fault authorized "$runner" > "$out/process-death-phase1.txt" 2>&1 || phase1_status=$?
  abort_on_instrument_timeout "$phase1_status" "RTORRENT-death-phase1"
  adb logcat -d -s ChunksAddFault:I > "$out/process-death-boundary.txt" || return 1
  if ! grep -Fq 'process-death-real-write-awaits-ack' "$out/process-death-boundary.txt" ||
    grep -q '^OK (' "$out/process-death-phase1.txt" ||
    adb shell pidof "$pkg" >/dev/null 2>&1; then
    tail -80 "$out/process-death-phase1.txt"
    echo "::error::The installed app did not die at the held Add write boundary"
    return 1
  fi
  local phase2_status=0
  timeout --signal=TERM --kill-after=15s 7m adb shell am instrument --user 0 -w -r -e class "$rtorrent_test#freshProcessDoesNotReplayTheHeldAdd" \
    -e chunks_rtorrent_add_fault authorized "$runner" > "$out/process-death-phase2.txt" 2>&1 || phase2_status=$?
  abort_on_instrument_timeout "$phase2_status" "RTORRENT-death-phase2"
  if [ "$phase2_status" -ne 0 ] ||
    ! grep -q '^OK (1 test)' "$out/process-death-phase2.txt" ||
    ! grep -q '^INSTRUMENTATION_STATUS: chunks_stage=process-death-no-replay-no-start-original-unchanged' "$out/process-death-phase2.txt"; then
    tail -80 "$out/process-death-phase2.txt"
    echo "::error::Fresh-process rTorrent Add safety verification failed"
    return 1
  fi
  echo "CHUNKS_INSTALLED_PROTOCOL: RTORRENT process-death journey finished after $((SECONDS - phase_started))s"
  echo "CHUNKS_INSTALLED_PROTOCOL: RTORRENT survived installed process-death proof with one stopped job and original intact" |
    tee -a "$GITHUB_STEP_SUMMARY"
}
run_rtorrent_faults || failed=1
run_rtorrent_death || failed=1
run_local_add_death() {
  local phase_started=$SECONDS
  echo "CHUNKS_INSTALLED_PROTOCOL: LOCAL process-death journey started"
  [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r\n')" == 1 ]] || return 1
  adb shell pm clear --user 0 "$pkg" >/dev/null || return 1
  adb logcat -c || return 1
  local class=io.github.gamesbyian.chunks.LocalInstalledAddProcessDeathTest
  local before="$out/local-add-death-phase1.txt" after="$out/local-add-death-phase2.txt"
  local phase1_status=0
  timeout --signal=TERM --kill-after=15s 7m adb shell am instrument --user 0 -w -r -e class "$class#killAfterRealLocalRegistrationWhileStartIsUnverified" \
    -e chunks_local_add_death authorized "$runner" > "$before" 2>&1 || phase1_status=$?
  abort_on_instrument_timeout "$phase1_status" "LOCAL-death-phase1"
  adb logcat -d -s ChunksLocalAddDeath:I > "$out/local-add-death-boundary.txt" || return 1
  if ! grep -Fq 'exact-job-registered-start-unverified' "$out/local-add-death-boundary.txt" ||
    grep -q '^OK (' "$before" || adb shell pidof "$pkg" >/dev/null 2>&1; then
    tail -80 "$before"
    echo "::error::The installed app did not die during the Local Add observation"
    return 1
  fi
  local phase2_status=0
  timeout --signal=TERM --kill-after=15s 7m adb shell am instrument --user 0 -w -r -e class "$class#freshProcessCanReviewOriginalMagnetWithoutReplayingIt" \
    -e chunks_local_add_death authorized "$runner" > "$after" 2>&1 || phase2_status=$?
  abort_on_instrument_timeout "$phase2_status" "LOCAL-death-phase2"
  if [ "$phase2_status" -ne 0 ] || ! grep -q '^OK (1 test)' "$after"; then
    tail -80 "$after"
    echo "::error::Fresh-process Local Add safety verification failed"
    return 1
  fi
  adb logcat -d -s ChunksLocalAddDeath:I | grep -Fq 'fresh-review-without-replayed-add' || return 1
  echo "CHUNKS_INSTALLED_PROTOCOL: LOCAL process-death journey finished after $((SECONDS - phase_started))s"
  echo "CHUNKS_INSTALLED_PROTOCOL: LOCAL unresolved metadata observation did not replay on process restart" |
    tee -a "$GITHUB_STEP_SUMMARY"
}
run_local_add_death || failed=1
run_external_document_restore() {
  [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r\n')" == 1 ]] || return 1
  adb shell pm clear --user 0 "$pkg" >/dev/null || return 1
  adb logcat -c || return 1
  # The SAVE phase may start normally because no reviewed task exists
  # yet. It kills the process after stopping its task. Only RESTORE uses
  # --no-restart, and only after focusing the saved task into a live process.
  local testcase=io.github.gamesbyian.chunks.ExternalDocumentProcessLossInstrumentedTest#externalReviewTaskSurvivesProcessLossWithoutReplayingTheDocument
  local before="$out/document-restore-save.txt" after="$out/document-restore-return.txt"
  local status=0
  timeout --signal=TERM --kill-after=15s 3m adb shell am instrument --user 0 -w -r -e class "$testcase" \
    -e chunks_document_restore_phase save "$runner" > "$before" 2>&1 || status=$?
  abort_on_instrument_timeout "$status" "external-document-save"
  adb logcat -d -s ChunksDocumentRestore:I > "$out/document-restore-boundary.txt" || return 1
  if ! grep -Fq 'external-review-task-stopped-before-process-loss' "$out/document-restore-boundary.txt" ||
    grep -q '^OK (' "$before" || adb shell pidof "$pkg" >/dev/null 2>&1; then
    echo "::error::Did not observe process death with the external source task stopped"
    return 1
  fi
  # Save phase created and killed the app task. Revive that exact Android
  # task with NO new torrent source Intent and NO provider grant, before
  # --no-restart instrumentation attaches to the new live process.
  local task_id
  task_id="$(sed -nE 's/.*external-review-task-stopped-before-process-loss task=([0-9]+).*/\1/p' "$out/document-restore-boundary.txt" | tail -1)"
  if [[ ! "$task_id" =~ ^[0-9]{1,7}$ ]]; then
    tail -80 "$before"
    echo "::error::Save phase did not supply a stopped task ID"
    return 1
  fi
  timeout 15s adb shell am task focus "$task_id" > "$out/document-restore-focus.txt" 2>&1 || {
    echo "::error::Could not focus the original stopped Android task"
    return 1
  }
  if ! grep -Fq "Setting focus to task $task_id" "$out/document-restore-focus.txt"; then
    echo "::error::Android did not confirm the exact saved task focus"
    return 1
  fi
  local alive=false
  for attempt in {1..40}; do
    if adb shell pidof "$pkg" >/dev/null 2>&1; then alive=true; break; fi
    sleep 0.25
  done
  if [ "$alive" != true ]; then
    echo "::error::Saved task focus did not revive the Chunks process"
    return 1
  fi
  status=0
  timeout --signal=TERM --kill-after=15s 3m adb shell am instrument --user 0 --no-restart -w -r -e class "$testcase" \
    -e chunks_document_restore_phase restore "$runner" > "$after" 2>&1 || status=$?
  abort_on_instrument_timeout "$status" "external-document-return"
  if [ "$status" -ne 0 ] || ! grep -q '^OK (1 test)' "$after"; then
    tail -80 "$after"
    return 1
  fi
  adb logcat -d -s ChunksDocumentRestore:I > "$out/document-restore-boundary.txt" || return 1
  grep -Fq 'same-task-new-process-notice-explicit-reopen-no-add' "$out/document-restore-boundary.txt" || return 1
  echo "CHUNKS_INSTALLED_PROTOCOL: external document task restored after real process death and required explicit reopen" |
    tee -a "$GITHUB_STEP_SUMMARY"
}
run_external_document_restore || failed=1
adb logcat -d -t 3000 > "$out/logcat.txt" 2>&1 || true
exit $failed
