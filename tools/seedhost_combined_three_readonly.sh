#!/usr/bin/env bash
# Manual, trusted-main, READ-ONLY: installed app Combined across the primary
# and numbered Seedhost instances 2 and 3. Never print raw instrumentation
# output, logcat or semantics trees: they could include real transfer names
# or authentication material. Only enumerated stage labels are reported.
set -euo pipefail
: "${CHUNKS_P1_USER:?}" "${CHUNKS_P1_PASS:?}" "${CHUNKS_P2_PASS:?}" "${CHUNKS_P3_PASS:?}"
# Optional: an exact staged same-hash cohort fixture. When blank, the test
# uses any infohash instances 2 and 3 already both hold (read-only).
CHUNKS_SHARED_HASH="${CHUNKS_SHARED_HASH:-}"
if [ -n "$CHUNKS_SHARED_HASH" ]; then
  [[ "$CHUNKS_SHARED_HASH" =~ ^[0-9a-fA-F]{40}$ ]] || { echo "::error::invalid hash"; exit 1; }
fi
adb shell wm size 1080x2400
adb shell wm density 420
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
b64() { printf '%s' "$1" | base64 -w 0; }
log=$(mktemp)
trap 'rm -f "$log"' EXIT
set +e
adb shell am instrument -w -r \
  -e class io.github.gamesbyian.chunks.CombinedThreeSeedhostReadOnlyInstrumentedTest \
  -e chunks_protected_combined authorized \
  -e chunks_user_b64 "$(b64 "$CHUNKS_P1_USER")" \
  -e chunks_pass1_b64 "$(b64 "$CHUNKS_P1_PASS")" \
  -e chunks_pass2_b64 "$(b64 "$CHUNKS_P2_PASS")" \
  -e chunks_pass3_b64 "$(b64 "$CHUNKS_P3_PASS")" \
  ${CHUNKS_SHARED_HASH:+-e chunks_shared_hash_b64 "$(b64 "$CHUNKS_SHARED_HASH")"} \
  io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner >"$log" 2>&1
status=$?
set -e
allowed='^(ground-truth|truth-(primary|second|third)-failed-[A-Za-z]{1,60}|no-shared-hash|shared-hash-present|launch-failed|form-input-failed|form-(primary|second|third)-missing|connect-(primary|second|third)(-failed|-control-missing|-name-missing)?|connected-(primary|second|third)|connections-(primary|second|third)-missing|disconnect-(primary|second)-failed|disconnected-(primary|second)|combined-open-failed|all-chip-missing|all-scope-not-applied|combined-union(-mismatch)?|combined-chips(-mismatch)?|combined-same-hash(-collapsed)?|unchanged)$'
# No stage at all (e.g. a skip or a pre-stage crash) must still reach the error report below.
{ grep -E '^INSTRUMENTATION_STATUS: chunks_stage=' "$log" || true; } | sed 's/^INSTRUMENTATION_STATUS: chunks_stage=//; s/[[:cntrl:]]//g' |
  while read -r stage; do
    if [[ "$stage" =~ $allowed ]]; then echo "CHUNKS_STAGE: $stage"; else echo "CHUNKS_STAGE: unlisted"; fi
  done | tee -a "$GITHUB_STEP_SUMMARY"
if [ "$status" -ne 0 ] || ! grep -q '^OK (1 test)' "$log"; then
  # Exception CLASS names only (never messages): enough to tell a skip, auth,
  # transport or assertion failure apart without exposing private data.
  grep -oE '^(java|kotlin|org\.junit|androidx\.compose\.ui\.test|io\.github\.gamesbyian)[A-Za-z0-9_.$]*(Exception|Error)\b' "$log" \
    | sort -u | head -5 | sed 's/^/CHUNKS_FAILURE_CLASS: /' || true
  grep -oE '^(OK \(1 test\)|OK \(0 tests\)|Tests run: [0-9]+,  Failures: [0-9]+)' "$log" | head -2 || true
  echo "::error::Protected three-server Combined read-only check failed or skipped. Raw output withheld."
  exit 1
fi
echo "CHUNKS_PROTECTED_COMBINED: passed read-only" | tee -a "$GITHUB_STEP_SUMMARY"
