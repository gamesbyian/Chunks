#!/usr/bin/env bash
# Disposable-only: installed app against the runner's loopback Transmission,
# qBittorrent and Deluge daemons (one emulator boot, app data cleared per client).
set -euo pipefail
out="$RUNNER_TEMP/interop/evidence"
mkdir -p "$out"
source_sha="$(git rev-parse HEAD)"
test "$source_sha" = "${CHUNKS_SOURCE_SHA:?}"
test "$source_sha" = "${GITHUB_SHA:?}"
source_tree="$(git rev-parse HEAD^{tree})"
pkg=io.github.gamesbyian.chunks
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
for port in 19091 18080 18112 19191 18180 18212; do
  adb reverse tcp:$port tcp:$port >/dev/null
done
b64() { printf '%s' "$1" | base64 -w 0; }
run_one() {
  local kind=$1 url=$2 app_url=$3 user=$4 pass=$5 mode=$6
  local class=io.github.gamesbyian.chunks.ProtocolClientInstalledJourneyTest
  local test_method=protocolClientConnectTransfersDetailsAddAndRemove
  if [ "$mode" = fault ]; then test_method=installedAddAfterTheDaemonResponseIsWithheld; fi
  local raw="$out/instrumentation-$kind-$mode.txt" stages="$out/stages-$kind-$mode.txt"
  local custom_folder="$RUNNER_TEMP/interop/dl/custom-${kind,,}"
  mkdir -p "$custom_folder"
  # Android shell sometimes resolves 'current' to USER_CURRENT (-2) after prior instrumentation.
  # This disposable emulator has a known primary user; clear exactly user 0.
  adb shell pm clear --user 0 "$pkg" >/dev/null || return 1
  set +e
  # Give instrumentation an explicit primary user; CURRENT (-2) is not resolved
  # reliably after repeated app/test process launches in this disposable AVD.
  adb shell am instrument --user 0 -w -r \
    -e class "$class#$test_method" \
    -e chunks_protocol_disposable authorized \
    -e chunks_protocol_kind_b64 "$(b64 "$kind")" \
    -e chunks_protocol_url_b64 "$(b64 "$url")" \
    -e chunks_protocol_app_url_b64 "$(b64 "$app_url")" \
    -e chunks_protocol_custom_folder_b64 "$(b64 "$custom_folder")" \
    -e chunks_protocol_user_b64 "$(b64 "$user")" \
    -e chunks_protocol_pass_b64 "$(b64 "$pass")" \
    io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner \
    > "$raw" 2>&1
  local status=$?
  set -e
  tr -d '\r' < "$raw" | grep -E '^INSTRUMENTATION_STATUS: chunks_stage=' | tee "$stages" || true
  if [ "$status" -ne 0 ] || ! grep -q '^OK (1 test)' "$raw"; then
    tail -80 "$raw"
    echo "::error::Installed protocol-client $mode journey failed for $kind"
    return 1
  fi
  if [ "$mode" = fault ]; then
    for fault in drop timeout; do
      if ! grep -Fxq "INSTRUMENTATION_STATUS: chunks_stage=add-fault-$fault-one-write-present-origin-unverified" "$stages"; then
        echo "::error::Missing installed $kind $fault witness"
        return 1
      fi
    done
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
adb logcat -d -t 3000 > "$out/logcat.txt" 2>&1 || true
exit $failed
