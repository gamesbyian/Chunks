#!/usr/bin/env bash
# Source-only installed Android filesystem witness: no torrent engine, no
# remote RPC, no ContentResolver delete and no original file mutation.
set -euo pipefail

api="${1:-}"
[[ "$api" == 29 || "$api" == 35 ]] || {
  echo "CHUNKS_SOURCE_ARCHIVE_INSTALLED: invalid-api" >&2
  exit 64
}
out="$RUNNER_TEMP/chunks-source-archive-api$api"
mkdir -p "$out"
sha256sum app/build/outputs/apk/debug/app-debug.apk \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
  > "$out/apks.sha256"
printf 'checkout=%s\nrequested_head=%s\n' "$GITHUB_SHA" \
  "${CHUNKS_ARCHIVE_REQUESTED_HEAD:-}" > "$out/source.txt"
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null

# Both deployed APKs must byte-match the ones produced from this exact tree.
app_path="$(adb shell pm path io.github.gamesbyian.chunks | sed -n 's/^package://p' | tr -d '\r' | head -1)"
test_path="$(adb shell pm path io.github.gamesbyian.chunks.test | sed -n 's/^package://p' | tr -d '\r' | head -1)"
[[ -n "$app_path" && -n "$test_path" ]] || {
  echo "CHUNKS_SOURCE_ARCHIVE_INSTALLED: missing-package" >&2
  exit 1
}
bash tools/verify_installed_apk_bytes.sh \
  app/build/outputs/apk/debug/app-debug.apk "$app_path" "$out/installed-app.apk" \
  | tee "$out/installed-apk-identity.txt"
bash tools/verify_installed_apk_bytes.sh \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
  "$test_path" "$out/installed-test.apk" \
  | tee -a "$out/installed-apk-identity.txt"

adb shell pm clear io.github.gamesbyian.chunks >/dev/null
set +e
adb shell am instrument -w -r \
  -e class io.github.gamesbyian.chunks.SourceTorrentArchiveInstrumentedTest \
  io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner \
  > "$out/instrumentation.txt" 2>&1
status=$?
set -e
adb logcat -d -t 1000 > "$out/logcat.txt" 2>&1 || true
if [ "$status" -ne 0 ] || ! grep -q '^OK (3 tests)' "$out/instrumentation.txt"; then
  tail -100 "$out/instrumentation.txt"
  echo "::error::Installed source archive filesystem safety failed on Android API $api"
  exit 1
fi
echo "CHUNKS_ARCHIVE_ANDROID: API $api three installed filesystem tests and both installed APK byte identities passed; no original deletion or torrent engine called" | tee -a "$GITHUB_STEP_SUMMARY"
