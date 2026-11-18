#!/usr/bin/env bash
# Disposable-only: installed app against the runner's loopback Transmission,
# qBittorrent and Deluge daemons (one emulator boot, app data cleared per client).
set -euo pipefail
out="$RUNNER_TEMP/interop/evidence"
mkdir -p "$out"
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
for port in 19091 18080 18112; do adb reverse tcp:$port tcp:$port >/dev/null; done
b64() { printf '%s' "$1" | base64 -w 0; }
run_one() {
  local kind=$1 url=$2 user=$3 pass=$4
  # Android shell sometimes resolves 'current' to USER_CURRENT (-2) after prior instrumentation.
  # This disposable emulator has a known primary user; clear exactly user 0.
  adb shell pm clear --user 0 io.github.gamesbyian.chunks >/dev/null
  set +e
  adb shell am instrument -w -r \
    -e class io.github.gamesbyian.chunks.ProtocolClientInstalledJourneyTest \
    -e chunks_protocol_disposable authorized \
    -e chunks_protocol_kind_b64 "$(b64 "$kind")" \
    -e chunks_protocol_url_b64 "$(b64 "$url")" \
    -e chunks_protocol_user_b64 "$(b64 "$user")" \
    -e chunks_protocol_pass_b64 "$(b64 "$pass")" \
    io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner \
    > "$out/instrumentation-$kind.txt" 2>&1
  local status=$?
  set -e
  grep -E '^INSTRUMENTATION_STATUS: chunks_stage=' "$out/instrumentation-$kind.txt" | tee "$out/stages-$kind.txt" || true
  if [ "$status" -ne 0 ] || ! grep -q '^OK (1 test)' "$out/instrumentation-$kind.txt"; then
    tail -80 "$out/instrumentation-$kind.txt"
    echo "::error::Installed protocol-client journey failed for $kind"
    return 1
  fi
  echo "CHUNKS_INSTALLED_PROTOCOL: $kind Connect -> Transfers -> Details Start -> Remove -> Add stopped passed" | tee -a "$GITHUB_STEP_SUMMARY"
}
failed=0
run_one TRANSMISSION http://127.0.0.1:19091/transmission/rpc chunks chunkspass || failed=1
run_one QBITTORRENT http://127.0.0.1:18080/ chunks chunkspass || failed=1
run_one DELUGE http://127.0.0.1:18112/json "" deluge || failed=1
adb logcat -d -t 3000 > "$out/logcat.txt" 2>&1 || true
exit $failed
