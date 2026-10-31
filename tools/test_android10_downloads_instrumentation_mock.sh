#!/usr/bin/env bash
# Offline API29 acceptance-harness negative controls. No SDK or handset.
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd -P)"
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT
mkdir -p "$temp/bin" "$temp/device"
cat > "$temp/bin/adb" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
case "$1" in
  uninstall)
    if [[ "$MOCK_UNINSTALL_STICKY" == 1 ]]; then
      echo 'Failure [DELETE_FAILED_INTERNAL_ERROR]'
      exit 0
    fi
    rm -f "$MOCK_DIR/installed"
    echo 'Success'
    ;;
  install)
    if [[ "$MOCK_INSTALL_FAIL" == 1 ]]; then
      echo 'Failure [INSTALL_FAILED_INVALID_APK]'
      exit 0
    fi
    : > "$MOCK_DIR/installed"
    printf 'Performing Streamed Install\nSuccess\n'
    ;;
  shell)
    shift
    case "$1" in
      getprop)
        printf '29\n'
        [[ "$MOCK_GETPROP_FAIL" != 1 ]] || exit 22
        ;;
      pm)
        [[ "$2" == list && "$3" == packages ]] || exit 1
        [[ "$MOCK_PM_LIST_FAIL" != 1 ]] || exit 23
        [[ ! -e "$MOCK_DIR/installed" ]] || echo 'package:io.github.gamesbyian.chunks'
        ;;
      am)
        printf 'instrument\n' >> "$MOCK_DIR/instruments"
        case "$*" in
          *Android10StoragePermissionDialogInstrumentedTest*) printf 'OK (1 test)\n' ;;
          *LocalPublicDownloadsPlaceInstrumentedTest*)
            [[ "$MOCK_SKIP" != 1 ]] || echo 'INSTRUMENTATION_STATUS_CODE: -3'
            printf 'OK (18 tests)\n'
            ;;
          *LocalEngineServiceInstrumentedTest*) printf 'OK (3 tests)\n' ;;
          *LocalAdmissionReceiptInstrumentedTest*) printf 'OK (3 tests)\n' ;;
          *) exit 1 ;;
        esac
        ;;
      *) exit 1 ;;
    esac
    ;;
  *) echo "Unexpected mocked adb operation: $*" >&2; exit 1 ;;
esac
MOCK
chmod +x "$temp/bin/adb"
export PATH="$temp/bin:$PATH" MOCK_DIR="$temp/device"
export MOCK_UNINSTALL_STICKY=0 MOCK_INSTALL_FAIL=0 MOCK_GETPROP_FAIL=0 MOCK_PM_LIST_FAIL=0 MOCK_SKIP=0
: > "$MOCK_DIR/instruments"
bash -n "$root/android10_downloads_instrumentation.sh"
bash -n "$root/test_android10_downloads_instrumentation_mock.sh"

output="$(bash "$root/android10_downloads_instrumentation.sh")"
[[ "$output" == *'CHUNKS_ANDROID10_DOWNLOADS: API 29'* ]]
[[ "$(wc -l < "$MOCK_DIR/instruments")" -eq 4 ]]

refuse_before_tests() {
  : > "$MOCK_DIR/instruments"
  if "$@" bash "$root/android10_downloads_instrumentation.sh" > "$temp/rejected.log" 2>&1; then
    echo 'Unreliable API29 setup was accepted' >&2
    exit 1
  fi
  [[ ! -s "$MOCK_DIR/instruments" ]] || {
    echo 'Instrumentation launched after unreliable install/device state' >&2
    exit 1
  }
}
refuse_before_tests env MOCK_INSTALL_FAIL=1
refuse_before_tests env MOCK_PM_LIST_FAIL=1
refuse_before_tests env MOCK_GETPROP_FAIL=1
: > "$MOCK_DIR/installed"
refuse_before_tests env MOCK_UNINSTALL_STICKY=1
rm "$MOCK_DIR/installed"

: > "$MOCK_DIR/instruments"
if MOCK_SKIP=1 bash "$root/android10_downloads_instrumentation.sh" > "$temp/skipped.log" 2>&1; then
  echo 'A deliberately skipped API29 instrumentation was accepted' >&2
  exit 1
fi
[[ "$(wc -l < "$MOCK_DIR/instruments")" -eq 2 ]]
echo 'CHUNKS_ANDROID10_HARNESS_TEST: valid 1+18+3+3 case passed; stale install, failed adb readbacks, zero-exit install Failure and skipped instrumentation refused'
