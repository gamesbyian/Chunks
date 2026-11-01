#!/usr/bin/env bash
# Exact-head, disposable real Android API29/35 provider identity test.
set -euo pipefail
expected="${1:?expected Android API 29 or 35}"
[[ "$expected" == 29 || "$expected" == 35 ]] || {
  echo 'CHUNKS_OWNED_DOCS: unsupported API request' >&2
  exit 1
}
# Never install or mutate test-owned Downloads on an unverified ADB target.
# ANDROID_SERIAL may select one device; without it, require exactly one attached
# authorized device, not just the first response from an ambiguous ADB server.
devices="$(adb devices)" || {
  echo 'CHUNKS_OWNED_DOCS: cannot enumerate ADB devices' >&2
  exit 1
}
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  awk -v selected="$ANDROID_SERIAL" 'NR > 1 && $1 == selected && $2 == "device" { n++ } END { exit(n == 1 ? 0 : 1) }' <<<"$devices" || {
    echo 'CHUNKS_OWNED_DOCS: selected ADB device is unavailable' >&2
    exit 1
  }
else
  awk 'NR > 1 && NF > 0 && $1 != "*" { n++; if ($2 != "device") bad=1 } END { exit(n == 1 && !bad ? 0 : 1) }' <<<"$devices" || {
    echo 'CHUNKS_OWNED_DOCS: require one authorized ADB device' >&2
    exit 1
  }
fi
emulator="$(adb shell getprop ro.kernel.qemu | tr -d '\r\n')" || {
  echo 'CHUNKS_OWNED_DOCS: emulator identity cannot be read' >&2
  exit 1
}
[[ "$emulator" == 1 ]] || {
  echo 'CHUNKS_OWNED_DOCS: physical device refused' >&2
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
  -e class io.github.gamesbyian.chunks.storage.OwnedMediaStoreDocumentPublicationInstrumentedTest,io.github.gamesbyian.chunks.LocalSingleZipMediaStoreExportInstrumentedTest,io.github.gamesbyian.chunks.LocalSingleLibraryMediaStoreCopyInstrumentedTest \
  io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner >"$out" 2>&1
status=$?
set -e
cat "$out"
[[ "$status" == 0 ]] || {
  echo 'CHUNKS_OWNED_DOCS: instrumentation process failed' >&2
  exit 1
}
[[ "$(grep -Fxc 'OK (10 tests)' "$out" || true)" == 1 ]] || {
  echo 'CHUNKS_OWNED_DOCS: exact ten tests not confirmed' >&2
  exit 1
}
if [[ "$(grep -Fxc 'INSTRUMENTATION_CODE: -1' "$out" || true)" != 1 ]] ||
   grep -qE '^INSTRUMENTATION_STATUS_CODE: -(1|2|3|4)|^FAILURES|^Tests run: .*Failures: [1-9]' "$out"; then
  echo 'CHUNKS_OWNED_DOCS: failing/skipped tests cannot be accepted' >&2
  exit 1
fi
for case_name in \
  createsFreshPendingRowThenPublishesOnlyAfterIndependentByteReadback \
  anExistingOwnedDownloadWithSimilarNameRetainsItsExactBytes \
  malformedOrCancelledRequestDoesNotCreateAProviderDocument \
  durableExclusiveJournalConfirmsOnlyAnUnchangedExactPublishedRow \
  cancellationAfterJournaledRowPreservesAmbiguousPendingObjectForReview \
  independentlyVerifiedPendingRowIsNotConfusedWithPublishedAndTamperingIsRefused \
  userApprovedSingleMemberCreatesDistinctVerifiedDownloadAndKeepsBothSources \
  traversalNestedMultiMemberAndPreCancelledNeverPublishOrTouchCanary \
  seedingSingleFileCopiesThroughUiWhileOriginalAndExistingCanaryStayUntouched \
  pathnameSwapDuringPinnedLibraryReadRefusesBeforeDestinationCreation; do
  # Android emits test=name at both start (status 1) and completion (status 0).
  # One genuinely executed test therefore has exactly TWO named statuses.
  [[ "$(grep -Fxc "INSTRUMENTATION_STATUS: test=$case_name" "$out" || true)" == 2 ]] || {
    echo "CHUNKS_OWNED_DOCS: missing required real Android case $case_name" >&2
    exit 1
  }
done
echo "CHUNKS_OWNED_MEDIASTORE_DOCUMENTS=PASS api=$expected fresh-row-fsync-zip-new-recovery-foreign-preservation"
