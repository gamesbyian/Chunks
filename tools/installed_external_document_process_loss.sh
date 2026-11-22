#!/usr/bin/env bash
# Emulator-only cross-UID .torrent review and real OS task restoration.
# Target-process instrumentation may NOT kill itself for this journey:
# ActivityManager force-stops the package and discards its task in that case.
set -euo pipefail
pkg=io.github.gamesbyian.chunks
out="$RUNNER_TEMP/interop/evidence"
mkdir -p "$out"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
fail() { echo "::error::Document task process-loss: $1" >&2; return 1; }

qemu="$(adb shell getprop ro.kernel.qemu | tr -d '\r\n')"
sdk="$(adb shell getprop ro.build.version.sdk | tr -d '\r\n')"
[[ "$qemu" == 1 && "$sdk" =~ ^[0-9]+$ && "$sdk" -ge 35 ]] ||
  { fail "requires disposable API35 emulator"; exit 1; }

source_name="external-first.bin"
notice="Android restarted Chunks. Check Transfers, then reopen the .torrent to review it again."
adb shell pm clear --user 0 "$pkg" >/dev/null || { fail "could not reset disposable app"; exit 1; }
adb logcat -c || { fail "could not isolate lifecycle logs"; exit 1; }

# A real different-UID provider Activity prepares and grants the test document.
open_source() {
  local flags=""
  if [ "$#" -gt 0 ]; then flags="$1"; fi
  timeout 20s adb shell am start -W \
    -n "$pkg.test/io.github.gamesbyian.chunks.external.ExternalSourceSenderActivity" \
    --es fixture_mode view --es fixture_target "$pkg" $flags \
    > "$scratch/source-launch.txt" 2>&1 || return 1
  grep -Fq 'Status: ok' "$scratch/source-launch.txt"
}
# MAIN/LAUNCHER contains no .torrent VIEW URI, source bytes or provider grant.
launch_without_source() {
  timeout 20s adb shell am start -W \
    -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
    -n "$pkg/.MainActivity" > "$scratch/launcher.txt" 2>&1 || return 1
  grep -Fq 'Status: ok' "$scratch/launcher.txt"
}
task_id() {
  timeout 15s adb shell dumpsys activity activities > "$scratch/tasks.txt" || return 1
  python3 - "$scratch/tasks.txt" <<'PY'
import re, sys
from pathlib import Path
data = Path(sys.argv[1]).read_text(errors="replace")
ids = set(re.findall(r"io\.github\.gamesbyian\.chunks/\.MainActivity t([0-9]+)\b", data))
if len(ids) != 1: sys.exit(1)
print(next(iter(ids)))
PY
}
app_pid() {
  local value
  value="$(adb shell pidof -s "$pkg" | tr -d '\r\n')" || return 1
  [[ "$value" =~ ^[0-9]{1,7}$ ]] || return 1
  printf '%s\n' "$value"
}
# The raw UI XML stays in a private temporary directory, never in CI logs.
screen_contains() {
  timeout 12s adb shell uiautomator dump /sdcard/chunks-process-task.xml > "$scratch/dump-status.txt" 2>&1 || return 1
  timeout 12s adb shell cat /sdcard/chunks-process-task.xml > "$scratch/window.xml" 2>/dev/null || return 1
  python3 - "$scratch/window.xml" "$1" <<'PY'
import sys, xml.etree.ElementTree as ET
try:
    root = ET.parse(sys.argv[1]).getroot()
    if any(sys.argv[2] in n.attrib.get(attr, "")
           for n in root.iter() for attr in ("text", "content-desc")): sys.exit(0)
except ET.ParseError:
    pass
sys.exit(1)
PY
}
await_text() {
  for attempt in {1..20}; do
    if screen_contains "$1"; then return 0; fi
    sleep 0.7
  done
  return 1
}
open_source || { fail "foreign provider VIEW not delivered"; exit 1; }
await_text "$source_name" || { fail "first document review not visible"; exit 1; }
old_task="$(task_id)" || { fail "original task identity unavailable"; exit 1; }
old_pid="$(app_pid)" || { fail "original app process identity unavailable"; exit 1; }
echo "CHUNKS_DOCUMENT_TASK: foreign-provider-original-review"

# Home/stop then real SIGKILL from the exact app's debug UID, OUTSIDE target
# instrumentation. Never use am force-stop, which removes saved task state.
adb shell input keyevent KEYCODE_HOME || { fail "could not background app"; exit 1; }
sleep 2
[[ "$(task_id)" == "$old_task" ]] || { fail "task missing before process kill"; exit 1; }
timeout 15s adb shell run-as "$pkg" /system/bin/kill -9 "$old_pid" >/dev/null 2>&1 ||
  { fail "exact UID process kill was refused"; exit 1; }
live=""
for attempt in {1..30}; do
  live="$(app_pid || true)"
  if [[ "$live" != "$old_pid" ]]; then break; fi
  sleep 0.2
done
[[ "$live" != "$old_pid" ]] || { fail "original app process survived"; exit 1; }
[[ "$(task_id)" == "$old_task" ]] || { fail "saved task was removed after process loss"; exit 1; }
echo "CHUNKS_DOCUMENT_TASK: stopped-task-survived-real-process-death"

launch_without_source || { fail "source-free saved task launch failed"; exit 1; }
new_task="$(task_id)" || { fail "restored task identity unavailable"; exit 1; }
new_pid="$(app_pid)" || { fail "restored app process identity unavailable"; exit 1; }
if [[ "$new_task" != "$old_task" ]]; then fail "task changed during restoration"; exit 1; fi
if [[ "$new_pid" == "$old_pid" ]]; then fail "app process did not change"; exit 1; fi
await_text "$notice" || { fail "restored task lacks interrupted-document notice"; exit 1; }
if screen_contains "$source_name"; then
  fail "old reviewed source replayed without fresh grant"; exit 1
fi
echo "CHUNKS_DOCUMENT_TASK: same-task-new-process-no-source-replay"

# The explicit second VIEW is the only route to reacquire a foreign URI grant.
open_source "--ez fixture_keep_task true" ||
  { fail "explicit foreign document reopen failed"; exit 1; }
await_text "$source_name" || { fail "regranted source review not visible"; exit 1; }
[[ "$(task_id)" == "$old_task" ]] || { fail "fresh VIEW changed task identity"; exit 1; }
echo "CHUNKS_DOCUMENT_TASK: explicit-provider-reopen-same-task"
echo "CHUNKS_INSTALLED_PROTOCOL: foreign document task recovered after independent process death" |
  tee -a "$GITHUB_STEP_SUMMARY"
