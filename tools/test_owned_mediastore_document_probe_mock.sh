#!/usr/bin/env bash
# Offline fake-adb acceptance for the real MediaStore test harness. No device.
set -Eeuo pipefail
root="$(cd "$(dirname "$0")" && pwd -P)"
temp="$(mktemp -d)"
trap 'rc=$?; echo "CHUNKS_OWNED_DOCS_MOCK: unexpected failure at line $LINENO exit=$rc" >&2; if [[ -f "$temp/accepted" ]]; then sed -n "1,80p" "$temp/accepted" >&2; fi; if [[ -f "$temp/rejected" ]]; then sed -n "1,80p" "$temp/rejected" >&2; fi; exit "$rc"' ERR
trap 'rm -rf "$temp"' EXIT
mkdir -p "$temp/bin"
cat > "$temp/bin/adb" <<'ADB_MOCK'
#!/usr/bin/env bash
set -euo pipefail
case "$1" in
  devices)
    [[ "${MOCK_ADB_ENUM_FAIL:-0}" != 1 ]] || exit 71
    echo 'List of devices attached'
    [[ "${MOCK_NO_DEVICE:-0}" != 1 ]] || exit 0
    echo 'emulator-5554 device'
    [[ "${MOCK_TWO_DEVICES:-0}" != 1 ]] || echo 'emulator-5556 device'
    [[ "${MOCK_UNAUTHORIZED:-0}" != 1 ]] || echo 'emulator-5558 unauthorized'
    ;;
  shell)
    shift
    case "$1" in
      getprop)
        case "$2" in
          ro.kernel.qemu) [[ "${MOCK_PHYSICAL:-0}" != 1 ]] && echo 1 || echo 0 ;;
          ro.build.version.sdk) [[ "${MOCK_WRONG_API:-0}" != 1 ]] && echo 29 || echo 35 ;;
          *) exit 72 ;;
        esac
        ;;
      am)
        [[ "$2" == instrument ]] || exit 73
        echo instrument >> "$MOCK_EVENTS"
        [[ "${MOCK_INSTRUMENT_FAIL:-0}" != 1 ]] || exit 74
        for name in \
          createsFreshPendingRowThenPublishesOnlyAfterIndependentByteReadback \
          anExistingOwnedDownloadWithSimilarNameRetainsItsExactBytes \
          malformedOrCancelledRequestDoesNotCreateAProviderDocument \
          durableExclusiveJournalConfirmsOnlyAnUnchangedExactPublishedRow \
          cancellationAfterJournaledRowPreservesAmbiguousPendingObjectForReview \
          independentlyVerifiedPendingRowIsNotConfusedWithPublishedAndTamperingIsRefused \
          userApprovedSingleMemberCreatesDistinctVerifiedDownloadAndKeepsBothSources \
          traversalNestedMultiMemberAndPreCancelledNeverPublishOrTouchCanary \
          seedingSingleFileCopiesThroughUiWhileOriginalAndExistingCanaryStayUntouched \
          pathnameSwapDuringPinnedLibraryReadRefusesBeforeDestinationCreation \
          composeTwoFlatFilesPublishIndependentlyAndPreserveCanaryAndArchive \
          userCancellationAfterFirstPublishedFileRetainsItsBytesAndSkipsNext \
          nestedUnknownAndTooManyMembersFailWithoutPublishingAnything; do
          [[ "${MOCK_MISSING_CASE:-0}" != 1 || "$name" != pathnameSwapDuringPinnedLibraryReadRefusesBeforeDestinationCreation ]] || continue
          # Mimic Android start/completion status records for ONE executed test.
          echo "INSTRUMENTATION_STATUS: test=$name"
          echo "INSTRUMENTATION_STATUS: test=$name"
          [[ "${MOCK_DUPLICATE_CASE:-0}" != 1 || "$name" != pathnameSwapDuringPinnedLibraryReadRefusesBeforeDestinationCreation ]] || echo "INSTRUMENTATION_STATUS: test=$name"
        done
        echo 'OK (13 tests)'
        [[ "${MOCK_DUPLICATE_OK:-0}" != 1 ]] || echo 'OK (13 tests)'
        [[ "${MOCK_SKIP:-0}" != 1 ]] || echo 'INSTRUMENTATION_STATUS_CODE: -3'
        [[ "${MOCK_TEST_FAILURE:-0}" != 1 ]] || echo 'FAILURES!!!'
        [[ "${MOCK_MISSING_FINAL:-0}" == 1 ]] || echo 'INSTRUMENTATION_CODE: -1'
        ;;
      *) exit 75 ;;
    esac
    ;;
  install)
    echo install >> "$MOCK_EVENTS"
    [[ "${MOCK_INSTALL_FAIL:-0}" != 1 ]] || exit 76
    echo Success
    ;;
  *) exit 77 ;;
esac
ADB_MOCK
chmod +x "$temp/bin/adb"
export PATH="$temp/bin:$PATH" MOCK_EVENTS="$temp/events"
bash -n "$root/owned_mediastore_document_probe.sh"
bash -n "$root/test_owned_mediastore_document_probe_mock.sh"
reset() { : > "$MOCK_EVENTS"; }
good() {
  bash "$root/owned_mediastore_document_probe.sh" 29 > "$temp/accepted" 2>&1
  grep -Fq 'CHUNKS_OWNED_MEDIASTORE_DOCUMENTS=PASS api=29' "$temp/accepted"
  [[ "$(grep -Fc install "$MOCK_EVENTS")" == 2 ]]
  [[ "$(grep -Fc instrument "$MOCK_EVENTS")" == 1 ]]
}
bad() {
  if "$@" bash "$root/owned_mediastore_document_probe.sh" 29 > "$temp/rejected" 2>&1; then
    echo "CHUNKS_OWNED_DOCS_MOCK: false success for $*" >&2
    exit 1
  fi
}
reset; good
for item in MOCK_ADB_ENUM_FAIL MOCK_NO_DEVICE MOCK_TWO_DEVICES MOCK_UNAUTHORIZED MOCK_PHYSICAL MOCK_WRONG_API; do
  reset; bad env "$item=1"
  [[ ! -s "$MOCK_EVENTS" ]] || {
    echo "CHUNKS_OWNED_DOCS_MOCK: device preflight reached a mutation with $item" >&2
    exit 1
  }
done
reset; bad env ANDROID_SERIAL=unknown
[[ ! -s "$MOCK_EVENTS" ]]
reset; good
for item in MOCK_INSTALL_FAIL MOCK_INSTRUMENT_FAIL MOCK_MISSING_CASE MOCK_DUPLICATE_CASE MOCK_DUPLICATE_OK MOCK_SKIP MOCK_TEST_FAILURE MOCK_MISSING_FINAL; do
  reset; bad env "$item=1"
done
echo 'CHUNKS_OWNED_DOCS_MOCK: unique physical target, negative receipts and positive emulator acceptance checked'
