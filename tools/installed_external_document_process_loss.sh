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
# Foreground the already attached task, as choosing it from Recents does.
# Starting a new MAIN/LAUNCHER Intent after process death can choose a new
# task, especially when the saved root was opened through a foreign VIEW.
# No new Activity Intent, source bytes or provider grant enter this command.
resume_saved_task() {
  local exact_task="$1"
  [[ "$exact_task" =~ ^[0-9]+$ ]] || return 1
  timeout 20s adb shell am task focus "$exact_task" > "$scratch/task-focus.txt" 2>&1
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
has_saved_task_state() {
  timeout 10s adb shell dumpsys activity -a activities > "$scratch/saved-task.txt" 2>/dev/null || return 1
  python3 - "$scratch/saved-task.txt" "$pkg" "$1" <<'PY'
import re, sys
from pathlib import Path

lines = Path(sys.argv[1]).read_text(errors="replace").splitlines()
component = re.escape(sys.argv[2] + "/.MainActivity")
task = re.escape(sys.argv[3])
header = re.compile(rf"^([ \t]*)\* Hist\s+#\d+: ActivityRecord\{{[^}}]*{component} t{task}\b")
records = []
for index, line in enumerate(lines):
    if not (match := header.search(line)):
        continue
    prefix = match.group(1) + "  "
    details = []
    for detail in lines[index + 1:]:
        if not detail.startswith(prefix):
            break
        details.append(detail.strip())
    records.append(details)
if len(records) != 1:
    sys.exit(1)
details = records[0]
has_state = any(line.startswith("mHaveState=true ") for line in details)
stopped = any(re.match(r"state=STOPPED\b.*\bfinishing=false\b", line) for line in details)
sys.exit(0 if has_state and stopped else 1)
PY
}
# The raw UI XML stays in a private temporary directory, never in CI logs.
# Some UiAutomator errors leave an old dump on-device even with exit status 0.
# Clear it before each request and require nodes from the exact app package.
screen_contains() {
  local capture='f=/sdcard/chunks-process-task.xml; rm -f "$f" && uiautomator dump "$f" >/dev/null && cat "$f"'
  timeout 15s adb shell "$capture" > "$scratch/window.xml" 2>/dev/null || return 1
  python3 - "$scratch/window.xml" "$pkg" "$1" "${2:-}" <<'PY'
import sys, xml.etree.ElementTree as ET
try:
    root = ET.parse(sys.argv[1]).getroot()
    values = [node.get(attr, "") for node in root.iter("node")
              if node.get("package") == sys.argv[2] for attr in ("text", "content-desc")]
    if sys.argv[4] and any(sys.argv[4] in value for value in values):
        sys.exit(2)
    if any(sys.argv[3] in value for value in values):
        sys.exit(0)
except (OSError, ET.ParseError):
    pass
sys.exit(1)
PY
}
await_text() {
  local status
  for attempt in {1..20}; do
    if screen_contains "$@"; then return 0; else status=$?; fi
    if [[ "$status" == 2 ]]; then return 2; fi
    sleep 0.7
  done
  return 1
}
# The single source-first Add screen is scrollable. UIAutomator exposes only
# currently composed/visible nodes, so an Android font/IME/layout variation
# can hide the validated review below the first fold. Require the actual
# source text to become visible after a bounded, non-mutating user scroll.
# Do not accept a task, intake log or preview in memory as a substitute.
await_source_review() {
  if await_text "$source_name"; then return 0; fi
  for swipe in 1 2 3; do
    timeout 12s adb shell input swipe 540 1750 540 650 380 >/dev/null || return 1
    for attempt in 1 2 3 4; do
      if screen_contains "$source_name"; then
        echo "CHUNKS_DOCUMENT_TASK: source-review-visible-after-user-scroll"
        return 0
      fi
      sleep 0.5
    done
  done
  return 1
}
# Expose only allowlisted diagnostics. Never upload raw UI XML, foreign URIs,
# tracker-bearing source content, screenshots or arbitrary logcat excerpts.
diagnose_missing_source_review() {
  local pid_present=false task_present=false xml_state=missing intake_delivered=false
  local ui_owner=unknown
  if app_pid >/dev/null 2>&1; then pid_present=true; fi
  if task_id >/dev/null 2>&1; then task_present=true; fi
  if [[ -s "$scratch/window.xml" ]]; then
    read -r xml_state ui_owner < <(python3 - "$scratch/window.xml" "$pkg" "$source_name" <<'PY_UI'
import sys
from xml.etree import ElementTree as ET

app, source = sys.argv[2:]
try:
    nodes = list(ET.parse(sys.argv[1]).getroot().iter("node"))
except (OSError, ET.ParseError):
    print("invalid-ui-xml unknown")
    raise SystemExit(0)

app_nodes = [node for node in nodes if node.get("package") == app]
visible = any(source in node.get(field, "") for node in app_nodes for field in ("text", "content-desc"))
state = "app-nodes-source-visible" if visible else "app-nodes-no-source" if app_nodes else "no-app-nodes"
packages = {node.get("package", "") for node in nodes} - {"", "android"}
if app_nodes: owner = "chunks"
elif app + ".test" in packages: owner = "external-sender"
elif "com.android.systemui" in packages: owner = "system-ui"
elif any("inputmethod" in item or "latinime" in item for item in packages): owner = "keyboard"
elif any("launcher" in item for item in packages): owner = "launcher"
elif packages: owner = "other"
else: owner = "unknown"
print(state, owner)
PY_UI
)
  fi
  if timeout 10s adb logcat -d -s ChunksIntake:I 2>/dev/null |
      grep -Fq 'outcome=document'; then intake_delivered=true; fi
  # Keep Android's raw task/window dumps in the ephemeral scratch only.
  # A digest of the actual foreground *category* distinguishes launcher/IME
  # occlusion from Chunks being resumed but not rendering its review.
  local focused=unknown focused_app=unknown resumed=unknown wakefulness=unknown
  timeout 12s adb shell dumpsys window displays > "$scratch/focused-window.txt" 2>/dev/null || true
  timeout 12s adb shell dumpsys power > "$scratch/power.txt" 2>/dev/null || true
  timeout 12s adb shell dumpsys activity activities > "$scratch/resumed-activity.txt" 2>/dev/null || true
  focused="$(python3 - "$scratch/focused-window.txt" <<'PY'
import re, sys
from pathlib import Path
raw = Path(sys.argv[1]).read_text(errors="replace")
matches = re.findall(r"mCurrentFocus=([^\n]+)", raw)
item = matches[-1] if matches else ""
if "io.github.gamesbyian.chunks.test" in item: print("external-sender")
elif "io.github.gamesbyian.chunks/" in item: print("chunks")
elif "inputmethod" in item.lower() or "latinime" in item.lower(): print("keyboard")
elif "com.android.systemui" in item: print("system-ui")
elif "launcher" in item.lower(): print("launcher")
elif item.strip() == "null": print("no-focused-window")
elif item: print("other-window")
else: print("unknown")
PY
)"
  focused_app="$(python3 - "$scratch/focused-window.txt" "$pkg" <<'PY'
import re, sys
from pathlib import Path

values = re.findall(r"mFocusedApp=([^\n]+)", Path(sys.argv[1]).read_text(errors="replace"))
value = values[-1].strip() if values else ""
app = sys.argv[2]
if app + ".test/" in value: print("external-sender")
elif app + "/" in value: print("chunks")
elif value == "null": print("no-focused-app")
elif value: print("other-app")
else: print("unknown")
PY
)"
  wakefulness="$(python3 - "$scratch/power.txt" <<'PY'
import re, sys
from pathlib import Path

value = re.search(r"\bmWakefulness=(Awake|Asleep|Dreaming|Dozing)\b",
                  Path(sys.argv[1]).read_text(errors="replace"))
print(value[1].lower() if value else "unknown")
PY
)"
  resumed="$(python3 - "$scratch/resumed-activity.txt" <<'PY'
import re, sys
from pathlib import Path
raw = Path(sys.argv[1]).read_text(errors="replace")
matches = re.findall(r"(?:topResumedActivity|mResumedActivity)=([^\n]+)", raw)
item = matches[-1] if matches else ""
if "io.github.gamesbyian.chunks.test" in item: print("external-sender")
elif "io.github.gamesbyian.chunks/" in item: print("chunks")
elif "launcher" in item.lower(): print("launcher")
elif item: print("other-activity")
else: print("unknown")
PY
)"
  # Diagnostic only: focusing the *existing* Chunks task is not accepted as
  # proof that the foreign sender foregrounded it. Do not submit/Start/reopen
  # a source or let this salvage the failing first-launch assertion.
  local after_focus=not-attempted
  local exact_task
  exact_task="$(task_id || true)"
  if [[ "$exact_task" =~ ^[0-9]+$ ]]; then
    after_focus=unavailable
    if timeout 12s adb shell am task focus "$exact_task" > "$scratch/task-focus.txt" 2>&1; then
      after_focus=no-source
      for attempt in 1 2 3; do
        if screen_contains "$source_name"; then after_focus=source-visible; break; fi
        sleep 0.5
      done
    fi
  fi
  printf 'CHUNKS_DOCUMENT_TASK_DIAG: app_process=%s task=%s ui=%s ui_owner=%s intake=%s foreground=%s focused_app=%s resumed=%s wakefulness=%s after_task_focus=%s\n' \
    "$pid_present" "$task_present" "$xml_state" "$ui_owner" "$intake_delivered" \
    "$focused" "$focused_app" "$resumed" "$wakefulness" "$after_focus" |
    tee "$out/document-preflight-diagnostic.txt"
}
# The disposable Chunks user data was already cleared above. Starting a
# realistic external Open With should NOT then issue FLAG_ACTIVITY_CLEAR_TASK:
# that second task reset can race the initial Compose window's foregrounding.
# The sender's fixture_keep_task flag merely omits that artificial clear; the
# exact foreign-UID content URI, read grant, fresh launch and all later
# task/process assertions remain unchanged. A passing run does not establish
# Pixel behavior or repair a genuinely disappearing app window.
# Failure-only post-process-loss witness. Never print UI XML, the provider
# URI, original intent, content/name, logcat text or exception strings.
# The bounded categories distinguish task focus, missing saved-state notice
# and accidental replay of the original Open With after a process restart.
diagnose_missing_interrupted_notice() {
  local current_pid="" current_task="" xml=missing sender_replayed=unknown focused=unknown
  current_pid="$(app_pid || true)"
  current_task="$(task_id || true)"
  if [[ -s "$scratch/window.xml" ]]; then
    xml="$(python3 - "$scratch/window.xml" "$pkg" "$source_name" "$notice" <<'PY'
import sys
from xml.etree import ElementTree as ET
try:
    nodes = list(ET.parse(sys.argv[1]).getroot().iter())
    app = [n for n in nodes if n.attrib.get("package") == sys.argv[2]]
    has_source = any(sys.argv[3] in n.attrib.get(k, "")
                     for n in app for k in ("text", "content-desc"))
    has_notice = any(sys.argv[4] in n.attrib.get(k, "")
                     for n in app for k in ("text", "content-desc"))
    print("notice" if has_notice else "unexpected-source" if has_source
          else "other-app-content" if app else "no-app-nodes")
except (OSError, ET.ParseError):
    print("invalid-ui")
PY
)"
  fi
  local intake_log=""
  intake_log="$(timeout 10s adb logcat -d -s ChunksIntake:I 2>/dev/null || true)"
  local accepts=0
  accepts="$(printf '%s\n' "$intake_log" | grep -Fc 'outcome=document' || true)"
  if [[ "$accepts" =~ ^[0-9]+$ ]]; then
    if (( accepts == 0 )); then sender_replayed=missing-intake
    elif (( accepts == 1 )); then sender_replayed=initial-only
    else sender_replayed=repeated-admission
    fi
  fi
  timeout 12s adb shell dumpsys window windows > "$scratch/restored-window.txt" 2>/dev/null || true
  focused="$(python3 - "$scratch/restored-window.txt" <<'PY'
import re, sys
from pathlib import Path
text = Path(sys.argv[1]).read_text(errors="replace")
items = re.findall(r"mCurrentFocus=([^\n]+)", text)
item = items[-1] if items else ""
if "io.github.gamesbyian.chunks.test" in item: print("external-sender")
elif "io.github.gamesbyian.chunks/" in item: print("chunks")
elif "inputmethod" in item.lower() or "latinime" in item.lower(): print("keyboard")
elif "launcher" in item.lower(): print("launcher")
elif item: print("other-window")
else: print("unknown")
PY
)"
  # Classify Android's own lifecycle events before the next independent
  # installed journey clears logcat. Retain counts only, never raw entries.
  local events="unavailable"
  if timeout 12s adb logcat -b events -d -t 1000 > "$scratch/restore-events.txt" 2>/dev/null; then
    events="$(python3 - "$scratch/restore-events.txt" "$old_task" "$pkg" <<'PY_EVENTS'
import re, sys
from collections import Counter
from pathlib import Path

events = Counter()
task, package = sys.argv[2:]
target = re.compile(r'(?<![\w.])' + re.escape(package) + r'(?=[/,\s\]])')
for line in Path(sys.argv[1]).read_text(errors="replace").splitlines():
    hit = re.search(r'\b(am_proc_start|am_proc_died|am_kill|am_crash|wm_finish_activity|wm_resume_activity|wm_task_removed)\s*:\s*\[(.*)\]', line)
    if not hit:
        continue
    tag, value = hit.groups()
    if target.search(value) or (tag == "wm_task_removed" and value.split(",", 1)[0].strip() == task):
        events[tag] += 1
        if tag == "wm_finish_activity" and "proc died without state saved" in value:
            events["no_saved_state_reason"] += 1
print(",".join(f"{tag}={events[tag]}" for tag in (
    "am_proc_start", "am_proc_died", "am_kill", "am_crash",
    "wm_finish_activity", "wm_resume_activity", "wm_task_removed", "no_saved_state_reason")))
PY_EVENTS
)"
  fi
  local focus_command=quiet
  if grep -Eqi 'error|exception|unknown|not found' "$scratch/task-focus.txt"; then focus_command=reported-error; fi
  printf 'CHUNKS_DOCUMENT_RESTORE_DIAG: process=%s task_same=%s pid_changed=%s ui=%s foreground=%s document_intake=%s focus_command=%s events=%s\n' \
    "$([[ -n "$current_pid" ]] && echo present || echo absent)" \
    "$([[ -n "$current_task" && "$current_task" == "$old_task" ]] && echo true || echo false)" \
    "$([[ -n "$current_pid" && "$current_pid" != "$old_pid" ]] && echo true || echo false)" \
    "$xml" "$focused" "$sender_replayed" "$focus_command" "$events" | tee "$out/document-restored-diagnostic.txt"
}
open_source "--ez fixture_keep_task true" ||
  { fail "foreign provider VIEW not delivered"; exit 1; }
if ! await_source_review; then
  diagnose_missing_source_review
  fail "first document review not visible after bounded user scroll"; exit 1
fi
old_task="$(task_id)" || { fail "original task identity unavailable"; exit 1; }
old_pid="$(app_pid)" || { fail "original app process identity unavailable"; exit 1; }
echo "CHUNKS_DOCUMENT_TASK: foreign-provider-original-review"

# Home/stop then real SIGKILL from the exact app's debug UID, OUTSIDE target
# instrumentation. Never use am force-stop, which removes saved task state.
adb shell input keyevent KEYCODE_HOME || { fail "could not background app"; exit 1; }
saved=false
for attempt in {1..15}; do
  if has_saved_task_state "$old_task"; then saved=true; break; fi
  sleep 0.5
done
[[ "$saved" == true ]] || { fail "Android did not confirm stopped state for the original task"; exit 1; }
[[ "$(app_pid)" == "$old_pid" ]] || { fail "original app process changed before the controlled kill"; exit 1; }
echo "CHUNKS_DOCUMENT_TASK: android-saved-stopped-state-before-kill"
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

resume_saved_task "$old_task" || { fail "recorded saved task could not be foregrounded"; exit 1; }
# Focusing an attached task schedules its Activity/process resume. Observe the
# actual interruption notice before taking the new stable PID and task sample.
if ! await_text "$notice"; then
  diagnose_missing_interrupted_notice
  fail "restored task lacks interrupted-document notice"; exit 1
fi
new_task="$(task_id)" || { fail "restored task identity unavailable"; exit 1; }
new_pid="$(app_pid)" || { fail "restored app process identity unavailable"; exit 1; }
if [[ "$new_task" != "$old_task" ]]; then fail "task changed during restoration"; exit 1; fi
if [[ "$new_pid" == "$old_pid" ]]; then fail "app process did not change"; exit 1; fi
if await_text "$notice" "$source_name"; then
  echo "CHUNKS_DOCUMENT_TASK: same-task-new-process-no-source-replay"
else
  replay_status=$?
  if [[ "$replay_status" == 2 ]]; then
    fail "old reviewed source replayed without fresh grant"; exit 1
  fi
  diagnose_missing_interrupted_notice
  fail "no fresh app UI proved the notice without the previous review"; exit 1
fi

# The explicit second VIEW is the only route to reacquire a foreign URI grant.
open_source "--ez fixture_keep_task true" ||
  { fail "explicit foreign document reopen failed"; exit 1; }
await_source_review || { fail "regranted source review not visible after bounded user scroll"; exit 1; }
[[ "$(task_id)" == "$old_task" ]] || { fail "fresh VIEW changed task identity"; exit 1; }
echo "CHUNKS_DOCUMENT_TASK: explicit-provider-reopen-same-task"
echo "CHUNKS_INSTALLED_PROTOCOL: foreign document task recovered after independent process death" |
  tee -a "$GITHUB_STEP_SUMMARY"
