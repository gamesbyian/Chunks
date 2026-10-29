#!/usr/bin/env bash
# Pure fake-adb controls for the opt-in real-peer interruption runner.
# This never starts an emulator, downloads payloads or changes Android state.
set -euo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin"
printf 'disposable metainfo\n' > "$tmp/public.torrent"
cat > "$tmp/bin/adb" <<'ADB_MOCK'
#!/usr/bin/env bash
set -euo pipefail
: "${MOCK_DIR:?}"
case "${1:-}" in
  devices)
    printf 'List of devices attached\nMOCK_USB\tdevice\n'
    [[ "${MOCK_MULTI:-0}" != 1 ]] || printf 'ANOTHER\tdevice\n'
    ;;
  get-state)
    [[ "${ANDROID_SERIAL:-}" == MOCK_USB ]] || exit 1
    printf 'device\n'
    ;;
  install)
    if [[ "${MOCK_INSTALL_BAD:-0}" == 1 ]]; then
      printf 'Failure [INSTALL_FAILED_INVALID_APK]\n'
    else
      printf 'Performing Streamed Install\nSuccess\n'
    fi
    ;;
  exec-in) cat >/dev/null ;;
  logcat)
    if [[ "${2:-}" == "-d" ]]; then
      if [[ "${MOCK_ARM_MISSING:-0}" != 1 && ! -e "$MOCK_DIR/b" ]]; then
        printf 'CHUNKS_SWARM_INTERRUPT: armed verified_before_kill=8388608\n'
        if [[ "${MOCK_LONG_LOG:-0}" == 1 ]]; then
          /bin/sleep 0.02
          for ((i=0; i<20000; i++)); do printf 'irrelevant fake log %d\n' "$i"; done
        fi
      else
        printf 'CHUNKS_SWARM_INTERRUPT: VERIFIED restored_stopped=true\n'
      fi
    fi
    ;;
  shell)
    shift
    if [[ "${1:-}" == "pidof" ]]; then
      [[ "${MOCK_ALIVE:-0}" == 1 ]] && exit 0 || exit 1
    fi
    if [[ "${1:-}" == am && "${2:-}" == instrument ]]; then
      if [[ " $* " == *' chunksInterruptPhase a '* ]]; then
        if [[ "${MOCK_NO_CRASH:-0}" != 1 ]]; then printf 'Process crashed\n'; fi
        exit 0
      fi
      if [[ " $* " == *' chunksInterruptPhase b '* ]]; then
        : > "$MOCK_DIR/b"
        case "${MOCK_B_RESULT:-ok}" in
          skip) printf 'INSTRUMENTATION_STATUS_CODE: -3\nOK (1 test)\n' ;;
          fail) printf 'INSTRUMENTATION_STATUS_CODE: -1\nOK (1 test)\n' ;;
          ok) printf 'INSTRUMENTATION_RESULT: stream=OK\nOK (1 test)\n' ;;
        esac
        exit 0
      fi
    fi
    echo 'Unexpected shell command' >&2; exit 1
    ;;
  *) echo "Unexpected adb command: $*" >&2; exit 1 ;;
esac
ADB_MOCK
cat > "$tmp/bin/sleep" <<'MOCK_SLEEP'
#!/usr/bin/env bash
exit 0
MOCK_SLEEP
chmod +x "$tmp/bin/"*
export PATH="$tmp/bin:$PATH" MOCK_DIR="$tmp" CHUNKS_PUBLIC_SWARM_TORRENT="$tmp/public.torrent"
export CHUNKS_PUBLIC_SWARM_BUDGET_BYTES=4194304 CHUNKS_PUBLIC_SWARM_SECONDS=30
bash -n "$repo/android_public_swarm_interrupt.sh"
bash -n "$repo/test_android_public_swarm_interrupt_mock.sh"

run_case() {
  rm -f "$tmp/b"
  env -u ADB_SERIAL -u ANDROID_SERIAL "$@" bash "$repo/android_public_swarm_interrupt.sh"
}
run_case MOCK_LONG_LOG=1 >"$tmp/success.log"
grep -q 'VERIFIED restored_stopped=true' "$tmp/success.log"
for fixture in MOCK_ARM_MISSING MOCK_NO_CRASH MOCK_ALIVE MOCK_INSTALL_BAD MOCK_MULTI; do
  if run_case "$fixture=1" >"$tmp/$fixture.log" 2>&1; then
    echo "False-positive interruption acceptance: $fixture" >&2
    exit 1
  fi
done
for fixture in skip fail; do
  if run_case "MOCK_B_RESULT=$fixture" >"$tmp/$fixture.log" 2>&1; then
    echo "False-positive phase B acceptance: $fixture" >&2
    exit 1
  fi
done
if ADB_SERIAL=WRONG bash "$repo/android_public_swarm_interrupt.sh" >"$tmp/wrong.log" 2>&1; then
  echo "Unavailable explicit ADB serial was accepted" >&2
  exit 1
fi
echo 'CHUNKS_INTERRUPT_HARNESS_TEST: large logs, positive recovery and seven fail-closed controls passed'
