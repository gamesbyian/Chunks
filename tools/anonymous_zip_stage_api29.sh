#!/usr/bin/env bash
# Strict actual-Android29 acceptance for anonymous ZIP review.
# No native torrent engine, external Downloads or protected host is exercised.
set -euo pipefail
api="$(adb shell getprop ro.build.version.sdk | tr -d '\r\n')"
if [[ "$api" != "29" ]]; then
  echo "::error::Anonymous ZIP acceptance must run on Android API 29 (got $api)"
  exit 1
fi
app=app/build/outputs/apk/debug/app-debug.apk
tests=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
install_checked() {
  local output
  output="$(adb install "$@" 2>&1)" || { echo "::error::APK installation failed"; exit 1; }
  if ! printf '%s\n' "$output" | tr -d '\r' | grep -qx Success ||
     printf '%s\n' "$output" | grep -q Failure; then
    echo "::error::APK install status was not Success"
    exit 1
  fi
}
install_checked "$app"
install_checked -r "$tests"
log="$(mktemp)"
trap 'rm -f "$log"' EXIT
set +e
adb shell am instrument -w -r \
  -e class io.github.gamesbyian.chunks.storage.AnonymousZipReviewStageInstrumentedTest \
  io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner >"$log" 2>&1
exit_code=$?
set -e
grep -E '^INSTRUMENTATION_STATUS: (class|test)=|^INSTRUMENTATION_STATUS_CODE:|^OK |^FAILURES' "$log" || true
if [[ "$exit_code" -ne 0 ]] ||
   ! grep -Eq '^OK \(3 tests\)' "$log" ||
   grep -qE '^INSTRUMENTATION_STATUS_CODE: -(1|2|3|4)' "$log" ||
   grep -qE '^FAILURES|^INSTRUMENTATION_STATUS: stack=' "$log"; then
  echo "::error::Anonymous ZIP API29 exact class did not execute all three tests successfully"
  tail -80 "$log"
  exit 1
fi
for name in zipVerifierCanReadAnonymousBytesWithoutAnyCacheDirectoryEntry \
            cancelledOrOverBudgetInputCannotWriteOrDeleteForeignCanary \
            indirectCacheRootIsRefusedBeforeCreatingAnything; do
  if ! grep -Fq "INSTRUMENTATION_STATUS: test=$name" "$log"; then
    echo "::error::Missing required anonymous ZIP test: $name"
    exit 1
  fi
done
echo "CHUNKS_ANON_ZIP_API29: 3 distinct actual Android29 tests passed; anonymous descriptor, no cache pathname, nonclobber"
