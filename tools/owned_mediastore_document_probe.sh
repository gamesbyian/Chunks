#!/usr/bin/env bash
# Exact-head, disposable real Android API29/35 provider identity test.
set -euo pipefail
expected="${1:?expected Android API 29 or 35}"
[[ "$expected" == 29 || "$expected" == 35 ]] || {
  echo 'CHUNKS_OWNED_DOCS: unsupported API request' >&2
  exit 1
}
observed="$(adb shell getprop ro.build.version.sdk | tr -d '\r\n')" || exit 1
[[ "$observed" == "$expected" ]] || {
  echo "CHUNKS_OWNED_DOCS: API $observed != $expected" >&2
  exit 1
}
install_checked() {
  local output
  output="$(adb install -r "$1" 2>&1)" || {
    echo 'CHUNKS_OWNED_DOCS: APK installation failed' >&2
    exit 1
  }
  if ! grep -qx 'Success' <<< "$output" || grep -qi 'Failure' <<< "$output"; then
    echo 'CHUNKS_OWNED_DOCS: package manager did not confirm Success' >&2
    exit 1
  fi
}
install_checked app/build/outputs/apk/debug/app-debug.apk
install_checked app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
out="$(mktemp)"
trap 'rm -f "$out"' EXIT
set +e
adb shell am instrument -w -r \
  -e class io.github.gamesbyian.chunks.storage.OwnedMediaStoreDocumentPublicationInstrumentedTest \
  io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner >"$out" 2>&1
status=$?
set -e
cat "$out"
[[ "$status" == 0 ]] || {
  echo 'CHUNKS_OWNED_DOCS: instrumentation process failed' >&2
  exit 1
}
grep -qx 'OK (5 tests)' "$out" || {
  echo 'CHUNKS_OWNED_DOCS: exact five tests not confirmed' >&2
  exit 1
}
if grep -qE '^INSTRUMENTATION_STATUS_CODE: -(1|2|3|4)|^FAILURES|^Tests run: .*Failures: [1-9]' "$out"; then
  echo 'CHUNKS_OWNED_DOCS: failing/skipped tests cannot be accepted' >&2
  exit 1
fi
for case_name in \
  createsFreshPendingRowThenPublishesOnlyAfterIndependentByteReadback \
  anExistingOwnedDownloadWithSimilarNameRetainsItsExactBytes \
  malformedOrCancelledRequestDoesNotCreateAProviderDocument \
  durableExclusiveJournalConfirmsOnlyAnUnchangedExactPublishedRow \
  cancellationAfterJournaledRowPreservesAmbiguousPendingObjectForReview; do
  grep -Fxq "INSTRUMENTATION_STATUS: test=$case_name" "$out" || {
    echo "CHUNKS_OWNED_DOCS: missing required real Android case $case_name" >&2
    exit 1
  }
done
echo "CHUNKS_OWNED_MEDIASTORE_DOCUMENTS=PASS api=$expected fresh-row-fsync-journal-readonly-recovery-foreign-preservation"
