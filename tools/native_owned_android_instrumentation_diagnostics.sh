#!/usr/bin/env bash
# Non-shipping #568 ownedlab ONLY. Captures precrash Android instrumentation
# main/system/crash events; never modifies device files or torrent payloads.
set -euo pipefail

if [[ "${CHUNKS_OWNED_JNI_LAB:-}" != "1" || "$#" -lt 1 ]]; then
  echo "CHUNKS_NATIVE_LAB_DIAGNOSTICS=REFUSED: explicit lab opt-in and command required" >&2
  exit 2
fi
package="io.github.gamesbyian.chunks.ownedlab"
workspace="$(mktemp -d "${TMPDIR:-/tmp}/chunks-ownedlab-diag.XXXXXXXX")"
log="$workspace/interval-logcat.txt"
output="$workspace/instrumentation-output.txt"
printf 'CHUNKS_NATIVE_LAB_DIAGNOSTICS_WORKSPACE=%s\n' "$workspace"

# Capture BEFORE instrumentation; crash-buffer-only diagnostics can show
# unrelated Bluetooth failures while the app's JVM/linker failure was in main.
adb logcat -b main -b system -b crash -v threadtime >"$log" 2>&1 &
collector=$!
stop_collector() {
  kill "$collector" >/dev/null 2>&1 || true
  wait "$collector" >/dev/null 2>&1 || true
}
trap stop_collector EXIT

set +e
"$@" >"$output" 2>&1
status=$?
set -e
stop_collector
trap - EXIT
cat "$output"

# The caller's exact installed JUnit smoke harness decides PASS. Never
# interpret Android's INSTRUMENTATION_CODE=0 alone as test success.
if [[ "$status" -eq 0 ]]; then
  echo 'CHUNKS_NATIVE_LAB_DIAGNOSTICS=PASS smoke-exit=0'
  exit 0
fi

echo "CHUNKS_NATIVE_LAB_DIAGNOSTICS=FAIL smoke-exit=$status"
echo 'CHUNKS_NATIVE_LAB_INTERVAL_EVENTS_BEGIN'
# Bounded context; package name, JVM, linker, activity process death, native
# crash. An unrelated process SIGABRT alone is not proof of a Chunks JNI bug.
grep -Ei \
  'io\.github\.gamesbyian\.chunks\.ownedlab|ownedjni|libtorrent4j|AndroidRuntime|FATAL EXCEPTION|am_crash|am_proc_died|Process crashed|UnsatisfiedLinkError|dlopen failed|linker|tombstone|Fatal signal|SIG(SEGV|ABRT|BUS)' \
  "$log" | tail -n 260 | cut -c 1-700 || true
echo 'CHUNKS_NATIVE_LAB_INTERVAL_EVENTS_END'
echo 'CHUNKS_NATIVE_LAB_EXIT_INFO_BEGIN'
# Exit-info is read-only and may not be supported on the disposable image.
# Never turn missing diagnostics into a passing test receipt.
adb shell dumpsys activity exit-info "$package" 2>&1 |
  grep -Ei 'ownedlab|ApplicationExitInfo|reason=|status=|description=|time=|Process|No historical|Error' |
  tail -n 80 | cut -c 1-700 || true
echo 'CHUNKS_NATIVE_LAB_EXIT_INFO_END'
exit "$status"
