#!/usr/bin/env bash
# Manual one-shot: installed automatic artwork on a DISPOSABLE remote rTorrent
# inventory. Public TVmaze/CDN HTTP only; never PR/push, never an owner host.
set -euo pipefail
test "${GITHUB_EVENT_NAME:-}" = workflow_dispatch
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r\n')" = 1
receipt="$RUNNER_TEMP/chunks-remote-media/receipt.json"
out="$RUNNER_TEMP/chunks-remote-media/evidence"
mkdir -p "$out"
pkg=io.github.gamesbyian.chunks
adb shell wm size 1080x2400
adb shell wm density 420
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
adb shell pm path "$pkg" | head -1 | sed 's/^package://' | tr -d '\r' > "$out/installed-path.txt"
adb pull "$(cat "$out/installed-path.txt")" "$out/installed.apk" >/dev/null
sha256sum "$out/installed.apk" | tee "$out/installed-apk.sha256"
stat -c '%s bytes' "$out/installed.apk" | tee -a "$out/installed-apk.sha256"
rm -f "$out/installed.apk"
echo "source_sha=${GITHUB_SHA:-unknown}" | tee -a "$out/installed-apk.sha256"
adb reverse tcp:18082 tcp:18082 >/dev/null
b64() { printf '%s' "$1" | base64 -w 0; }
field() { python3 -c "import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])" "$receipt" "$1"; }
adb logcat -c
set +e
adb shell am instrument -w -r \
  -e class io.github.gamesbyian.chunks.InstalledRemotePublicMediaInstrumentedTest \
  -e chunks_remote_media authorized \
  -e chunks_remote_media_url_b64 "$(b64 http://127.0.0.1:18082/RPC2)" \
  -e chunks_remote_media_name_b64 "$(b64 "$(field name)")" \
  -e chunks_remote_media_hash_b64 "$(b64 "$(field hash)")" \
  io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner \
  > "$out/instrumentation.txt" 2>&1
status=$?
set -e
adb logcat -d -v raw -s ChunksRemoteMedia:I > "$out/WITNESS.txt" || true
adb pull "/sdcard/Android/data/$pkg/files/remote-media-evidence/." "$out/" >/dev/null 2>&1 || true
if [ "$status" -ne 0 ] || ! grep -q '^OK (1 test)' "$out/instrumentation.txt"; then
  tail -80 "$out/instrumentation.txt"
  echo "::error::Installed remote automatic artwork proof failed or skipped"
  exit 1
fi
grep -q 'CHUNKS_REMOTE_PUBLIC_MEDIA automatic=true' "$out/WITNESS.txt"
echo "CHUNKS_REMOTE_PUBLIC_MEDIA: passed" | tee -a "$GITHUB_STEP_SUMMARY"
