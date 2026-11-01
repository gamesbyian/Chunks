#!/usr/bin/env bash
# Fake adb negative controls for the API29 real-grant revoke/regrant witness.
# No Android SDK or physical device is contacted.
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd -P)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin" "$tmp/state"
cat > "$tmp/bin/adb" <<'ADB_MOCK'
#!/usr/bin/env bash
set -euo pipefail
case "$1" in
  devices)
    echo 'List of devices attached'
    echo 'emulator-5554 device'
    [[ "${MOCK_TWO_DEVICES:-0}" != 1 ]] || echo 'emulator-5556 device'
    [[ "${MOCK_DEVICES_PARTIAL_FAIL:-0}" != 1 ]] || exit 73
    ;;
  get-state)
    [[ "${ANDROID_SERIAL:-}" == emulator-5554 ]] || exit 79
    echo device
    [[ "${MOCK_STATE_PARTIAL_FAIL:-0}" != 1 ]] || exit 74
    ;;
  shell)
    [[ "${ANDROID_SERIAL:-}" == emulator-5554 ]] || exit 79
    shift
    case "$1" in
      getprop)
        if [[ "$2" == ro.kernel.qemu ]]; then
          [[ "${MOCK_PHYSICAL:-0}" == 1 ]] && echo 0 || echo 1
        elif [[ "$2" == ro.build.version.sdk ]]; then
          [[ "${MOCK_WRONG_SDK:-0}" == 1 ]] && echo 35 || echo 29
        else exit 97; fi
        [[ "${MOCK_GETPROP_PARTIAL_FAIL:-}" != "$2" ]] || exit 75
        ;;
      am)
        if [[ "$2" == force-stop ]]; then
          echo force-stop >> "$MOCK_DIR/operations"
          [[ "${MOCK_FORCE_STOP_FAIL:-0}" != 1 ]] || exit 31
        elif [[ "$2" == instrument ]]; then
          phase=unknown
          case "$*" in
            *phaseA_completeNativeSeedInSharedDownloadsThenStop*) phase=a ;;
            *phaseB_storageRevokedRechecksOwnedFileOrSafelyRefuses*) phase=b ;;
            *phaseC_grantRestoredRechecksSeedsAndRemovesWithoutDeletingFiles*) phase=c ;;
          esac
          [[ "$phase" != unknown ]] || exit 32
          printf '%s\n' "$phase" > "$MOCK_DIR/last-phase"
          echo "instrument-$phase" >> "$MOCK_DIR/operations"
          if [[ "${MOCK_INSTRUMENT_FAIL:-}" == "$phase" ]]; then exit 30; fi
          if [[ "${MOCK_SKIP:-}" == "$phase" ]]; then
            echo 'INSTRUMENTATION_STATUS_CODE: -3'
          fi
          if [[ "${MOCK_PHASE_FAILURE:-}" == "$phase" ]]; then
            echo 'FAILURES!!!'
          fi
          echo 'OK (1 test)'
          if [[ "${MOCK_DUPLICATE_PASS:-}" == "$phase" ]]; then
            echo 'OK (1 test)'
          fi
          case "${MOCK_BAD_TERMINAL:-}" in
            "missing:$phase") ;;
            "failed:$phase") echo 'INSTRUMENTATION_CODE: 0' ;;
            "duplicate:$phase") echo 'INSTRUMENTATION_CODE: -1'; echo 'INSTRUMENTATION_CODE: -1' ;;
            "mixed:$phase") echo 'INSTRUMENTATION_CODE: -1'; echo 'INSTRUMENTATION_CODE: 0' ;;
            *) echo 'INSTRUMENTATION_CODE: -1' ;;
          esac
        else exit 33; fi
        ;;
      pm)
        case "$2" in
          revoke)
            echo "revoke-$4" >> "$MOCK_DIR/operations"
            [[ "${MOCK_REVOKE_FAIL:-0}" != 1 ]] || exit 34
            ;;
          grant)
            echo "grant-$4" >> "$MOCK_DIR/operations"
            [[ "${MOCK_GRANT_FAIL:-0}" != 1 ]] || exit 76
            ;;
          *) exit 35 ;;
        esac
        ;;
      *) exit 36 ;;
    esac
    ;;
  logcat)
    [[ "${ANDROID_SERIAL:-}" == emulator-5554 ]] || exit 79
    [[ "${2:-}" == -c ]] && exit 0
    if [[ "${2:-}" == -d ]]; then
      phase="$(cat "$MOCK_DIR/last-phase" 2>/dev/null || true)"
      [[ "${MOCK_MISSING_MARKER:-}" != "$phase" ]] || exit 0
      sha=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
      case "$phase" in
        a)
          hash=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
          [[ "${MOCK_BAD_HASH:-0}" != 1 ]] || hash=gggggggggggggggggggggggggggggggggggggggg
          echo "CHUNKS_GRANT phase=a persisted=true stopped=true bytes=262144 hash=$hash sha256=$sha"
          ;;
        b)
          [[ "${MOCK_MISSING_ACCESS:-0}" == 1 ]] ||
            echo 'CHUNKS_GRANT_OBSERVED access=denied'
          [[ "${MOCK_MISSING_DENIAL:-0}" == 1 ]] ||
            echo 'CHUNKS_GRANT_DENIAL restored=true os_open=denied start=refused stopped=true'
          [[ "${MOCK_MISSING_ADVERSARIAL:-0}" == 1 ]] ||
            echo 'CHUNKS_GRANT_ADVERSARIAL fifo=refused leaf_link=refused parent_link=refused'
          echo 'CHUNKS_GRANT phase=b permission_revoked=true stopped_restored=true path_checked=true'
          ;;
        c) echo "CHUNKS_GRANT phase=c restored=true seeded=true removed_keep_files=true sha256=$sha" ;;
        *) exit 37 ;;
      esac
      exit 0
    fi
    exit 38
    ;;
  *) exit 39 ;;
esac
ADB_MOCK
chmod +x "$tmp/bin/adb"
export PATH="$tmp/bin:$PATH" MOCK_DIR="$tmp/state"
bash -n "$root/android10_downloads_revocation.sh"
bash -n "$root/test_android10_downloads_revocation_mock.sh"

reset() {
  : > "$MOCK_DIR/operations"
  rm -f "$MOCK_DIR/last-phase"
}
run_ok() {
  bash "$root/android10_downloads_revocation.sh" > "$tmp/ok.log" 2>&1
  grep -q 'API29 revoke/refuse/regrant/recheck/Remove passed' "$tmp/ok.log"
  [[ "$(grep -c '^instrument-' "$MOCK_DIR/operations")" == 3 ]]
  [[ "$(grep -c '^revoke-' "$MOCK_DIR/operations")" == 2 ]]
  [[ "$(grep -c '^grant-' "$MOCK_DIR/operations")" -ge 2 ]]
  grep -q '^instrument-a$' "$MOCK_DIR/operations"
  grep -q '^instrument-b$' "$MOCK_DIR/operations"
  grep -q '^instrument-c$' "$MOCK_DIR/operations"
}
run_bad() {
  if "$@" bash "$root/android10_downloads_revocation.sh" > "$tmp/bad.log" 2>&1; then
    echo "CHUNKS_GRANT_MOCK: false-positive accepted for $*" >&2; exit 1
  fi
}
reset; run_ok
reset; run_bad env MOCK_TWO_DEVICES=1
[[ ! -s "$MOCK_DIR/operations" ]]
reset; run_bad env ADB_SERIAL=OTHER ANDROID_SERIAL=emulator-5554
[[ ! -s "$MOCK_DIR/operations" ]]
reset; run_bad env ANDROID_SERIAL=UNLISTED
[[ ! -s "$MOCK_DIR/operations" ]]
reset; run_bad env MOCK_DEVICES_PARTIAL_FAIL=1
[[ ! -s "$MOCK_DIR/operations" ]]
reset; run_bad env MOCK_STATE_PARTIAL_FAIL=1
[[ ! -s "$MOCK_DIR/operations" ]]
reset; run_bad env MOCK_GETPROP_PARTIAL_FAIL=ro.kernel.qemu
[[ ! -s "$MOCK_DIR/operations" ]]
reset; run_bad env MOCK_GETPROP_PARTIAL_FAIL=ro.build.version.sdk
[[ ! -s "$MOCK_DIR/operations" ]]
reset; run_bad env MOCK_PHYSICAL=1
[[ ! -s "$MOCK_DIR/operations" ]]
reset; run_bad env MOCK_WRONG_SDK=1
[[ ! -s "$MOCK_DIR/operations" ]]
reset; run_bad env MOCK_SKIP=a
[[ "$(grep -c '^revoke-' "$MOCK_DIR/operations" || true)" == 0 ]]
reset; run_bad env MOCK_PHASE_FAILURE=a
[[ "$(grep -c '^revoke-' "$MOCK_DIR/operations" || true)" == 0 ]]
reset; run_bad env MOCK_DUPLICATE_PASS=a
[[ "$(grep -c '^revoke-' "$MOCK_DIR/operations" || true)" == 0 ]]
reset; run_bad env MOCK_BAD_HASH=1
[[ "$(grep -c '^revoke-' "$MOCK_DIR/operations" || true)" == 0 ]]
# A false terminal result at any of the three independent processes is
# never enough to authorize a later permission mutation or success claim.
for phase in a b c; do
  for kind in missing failed duplicate mixed; do
    reset; run_bad env MOCK_BAD_TERMINAL="$kind:$phase"
    if [[ "$phase" == a ]]; then
      [[ "$(grep -c '^revoke-' "$MOCK_DIR/operations" || true)" == 0 ]]
    else
      [[ "$(grep -c '^grant-' "$MOCK_DIR/operations" || true)" -ge 2 ]]
    fi
  done
done
reset; run_bad env MOCK_REVOKE_FAIL=1
[[ "$(grep -c '^grant-' "$MOCK_DIR/operations" || true)" -ge 2 ]]
reset; run_bad env MOCK_SKIP=b
[[ "$(grep -c '^grant-' "$MOCK_DIR/operations" || true)" -ge 2 ]]
reset; run_bad env MOCK_MISSING_MARKER=b
[[ "$(grep -c '^grant-' "$MOCK_DIR/operations" || true)" -ge 2 ]]
reset; run_bad env MOCK_MISSING_ACCESS=1
[[ "$(grep -c '^grant-' "$MOCK_DIR/operations" || true)" -ge 2 ]]
reset; run_bad env MOCK_MISSING_DENIAL=1
[[ "$(grep -c '^grant-' "$MOCK_DIR/operations" || true)" -ge 2 ]]
reset; run_bad env MOCK_MISSING_ADVERSARIAL=1
[[ "$(grep -c '^grant-' "$MOCK_DIR/operations" || true)" -ge 2 ]]
reset; run_bad env MOCK_GRANT_FAIL=1
[[ "$(grep -c '^grant-' "$MOCK_DIR/operations" || true)" -ge 2 ]]
reset; run_bad env MOCK_PHASE_FAILURE=c
[[ "$(grep -c '^grant-' "$MOCK_DIR/operations" || true)" -ge 2 ]]
reset; run_bad env MOCK_MISSING_MARKER=c
[[ "$(grep -c '^grant-' "$MOCK_DIR/operations" || true)" -ge 2 ]]
reset; run_bad env MOCK_FORCE_STOP_FAIL=1
[[ "$(grep -c '^revoke-' "$MOCK_DIR/operations" || true)" == 0 ]]
echo 'CHUNKS_GRANT_MOCK: three-phase grant recovery accepted; false/unsafe/missing results refused'
