#!/usr/bin/env bash
# Disposable-only remote L6: installed app -> pinned ruTorrent httprpc/erasedata
# -> real rTorrent; then the real upstream collector and an independent file witness.
set -euo pipefail
hash="$(cat "$RUNNER_TEMP/chunks-remote-delete-hash.txt")"
[[ "$hash" =~ ^[0-9a-f]{40}$ ]] || { echo "::error::no staged hash"; exit 1; }
out="$RUNNER_TEMP/chunks-remote-delete-evidence"
mkdir -p "$out"
source_sha="$(git rev-parse HEAD)"
test "$source_sha" = "${GITHUB_SHA:?}"
test "$source_sha" = "${CHUNKS_SOURCE_SHA:?}"
source_tree="$(git rev-parse HEAD^{tree})"
printf 'CHUNKS_REMOTE_DELETE_SOURCE: commit=%s tree=%s\n' "$source_sha" "$source_tree" | tee "$out/source.txt"
adb shell wm size 1080x2400
adb shell wm density 420
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
# Preserve the actual installed identity before any removal assertion can fail.
# Reuse the shared path-free pull/hash gate; never retain the pulled APK.
pkg=io.github.gamesbyian.chunks
installed_version="$(adb shell dumpsys package "$pkg" | sed -n 's/^[[:space:]]*versionName=//p' | tr -d '\r' | head -1)"
test "$installed_version" = "0.1.0-dev+${source_sha:0:12}" || {
  echo 'CHUNKS_REMOTE_DELETE_APK: unexpected-installed-version' >&2; exit 1;
}
installed_path="$(adb shell pm path "$pkg" | sed -n 's/^package://p' | tr -d '\r' | head -1)"
bash tools/verify_installed_apk_bytes.sh app/build/outputs/apk/debug/app-debug.apk \
  "$installed_path" "$out/installed-pull-tmp.apk" | tee "$out/installed-sha256.txt"
test ! -e "$out/installed-pull-tmp.apk"
python3 - "$source_sha" "$source_tree" "$pkg" "$installed_version" "$out" <<'PY'
import json
from pathlib import Path
import re
import sys
source, tree, package, version, output = sys.argv[1:]
out = Path(output)
match = re.fullmatch(r"CHUNKS_INSTALLED_APK: matched size_bytes=([1-9][0-9]*) sha256=([0-9a-f]{64})\n",
                     (out / "installed-sha256.txt").read_text())
if not match:
    raise SystemExit("CHUNKS_REMOTE_DELETE_APK: missing-byte-receipt")
receipt = dict(source_sha=source, git_tree_sha=tree, application_id=package,
               version_name=version, installed_apk_size_bytes=int(match[1]),
               installed_apk_sha256=match[2], installed_equals_built_bytes=True)
(out / "APK-IDENTITY.json").write_text(json.dumps(receipt, indent=2) + "\n")
print("CHUNKS_REMOTE_DELETE_APK: source-bound-installed-identity-recorded")
PY
adb reverse tcp:18082 tcp:18082 >/dev/null
b64() { printf '%s' "$1" | base64 -w 0; }
set +e
adb shell am instrument -w -r \
  -e class io.github.gamesbyian.chunks.RemoteDeleteDataInstalledInstrumentedTest \
  -e chunks_remote_delete authorized \
  -e chunks_remote_delete_url_b64 "$(b64 http://127.0.0.1:18082/rutorrent1/)" \
  -e chunks_remote_delete_hash_b64 "$(b64 "$hash")" \
  io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner \
  > "$out/instrumentation.txt" 2>&1
status=$?
set -e
adb logcat -d -t 2000 > "$out/logcat.txt" 2>&1 || true
grep -E '^INSTRUMENTATION_STATUS: chunks_stage=' "$out/instrumentation.txt" | tee "$out/stages.txt" || true
if [ "$status" -ne 0 ] || ! grep -q '^OK (1 test)' "$out/instrumentation.txt" ||
   ! grep -q 'chunks_stage=session-absent' "$out/instrumentation.txt"; then
  tail -80 "$out/instrumentation.txt"
  echo "::error::Installed remote Remove transfer and data journey failed"
  exit 1
fi
# The app only QUEUED deletion. Run the real upstream collector and verify
# exact payload absence and the foreign canary independently.
python3 tools/stage_disposable_rutorrent_adapter_delete.py verify | tee "$out/collector.txt"
grep -q '^PASS production Kotlin adapter' "$out/collector.txt"
echo "CHUNKS_REMOTE_DELETE_INSTALLED: app queued exact erasedata; collector deleted exact file; canary intact" | tee -a "$GITHUB_STEP_SUMMARY"
