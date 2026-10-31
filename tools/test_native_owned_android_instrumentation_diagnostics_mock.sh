#!/usr/bin/env bash
# Offline negative controls. No SDK, real emulator, native libtorrent or user files.
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd -P)"
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT
mkdir -p "$temp/bin"
cat >"$temp/bin/adb" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
case "$1" in
  logcat)
    # Contains a matching app crash and a second UNRELATED platform crash.
    printf '10-31 20:32:01.123  444  555 E AndroidRuntime: FATAL EXCEPTION: main\n'
    printf '10-31 20:32:01.124  444  555 E AndroidRuntime: Process: io.github.gamesbyian.chunks.ownedlab, PID: 444\n'
    printf '10-31 20:32:01.125  444  555 E linker: dlopen failed libtorrent4j JNI fixture\n'
    printf '10-31 20:32:01.126  999  999 F libc: Fatal signal 6 in com.android.bluetooth\n'
    ;;
  shell)
    [[ "$2 $3 $4" == 'dumpsys activity exit-info' ]] || exit 8
    [[ "$5" == 'io.github.gamesbyian.chunks.ownedlab' ]] || exit 9
    echo 'ApplicationExitInfo{io.github.gamesbyian.chunks.ownedlab reason=CRASH status=6}'
    ;;
  *)
    echo "Unexpected ADB diagnostic operation: $*" >&2
    exit 10
    ;;
esac
MOCK
sed -i 's/$/\$/g' "$temp/bin/adb"
chmod +x "$temp/bin/adb"
export PATH="$temp/bin:$PATH"
script="$root/native_owned_android_instrumentation_diagnostics.sh"
bash -n "$script"
if bash "$script" bash -c 'echo no opt in' >"$temp/refused" 2>&1; then
  echo "Crash diagnostics ran without explicit disposable lab opt-in" >&2
  exit 1
fi
grep -q 'CHUNKS_NATIVE_LAB_DIAGNOSTICS=REFUSED' "$temp/refused"

CHUNKS_OWNED_JNI_LAB=1 bash "$script" bash -c \
  'echo "OK (4 tests)"; sleep 0.15' >"$temp/success" 2>&1
grep -q 'OK (4 tests)' "$temp/success"
grep -q 'CHUNKS_NATIVE_LAB_DIAGNOSTICS=PASS smoke-exit=0' "$temp/success"
! grep -q 'CHUNKS_NATIVE_LAB_INTERVAL_EVENTS_BEGIN' "$temp/success"

set +e
CHUNKS_OWNED_JNI_LAB=1 bash "$script" bash -c \
  'echo "INSTRUMENTATION_RESULT: shortMsg=Process crashed."; sleep 0.15; exit 17' \
  >"$temp/failure" 2>&1
status=$?
set -e
[[ "$status" -eq 17 ]] || { echo "Lost exact smoke failure exit code: $status" >&2; exit 1; }
grep -q 'INSTRUMENTATION_RESULT: shortMsg=Process crashed.' "$temp/failure"
grep -q 'CHUNKS_NATIVE_LAB_DIAGNOSTICS=FAIL smoke-exit=17' "$temp/failure"
grep -q 'FATAL EXCEPTION: main' "$temp/failure"
grep -q 'Process: io.github.gamesbyian.chunks.ownedlab' "$temp/failure"
grep -q 'dlopen failed libtorrent4j JNI fixture' "$temp/failure"
grep -q 'ApplicationExitInfo.*reason=CRASH' "$temp/failure"
grep -q 'CHUNKS_NATIVE_LAB_EXIT_INFO_END' "$temp/failure"
echo 'CHUNKS_NATIVE_LAB_DIAGNOSTICS_MOCK=PASS opt-in/no-optimism/full-interval/failure-exit'
