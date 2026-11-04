#!/usr/bin/env bash
# Manual, trusted-main, READ-ONLY: installed app Combined across the primary
# and numbered Seedhost instances 2 and 3. Never print raw instrumentation
# output, logcat or semantics trees: they could include real transfer names
# or authentication material. Only enumerated stage labels are reported.
set -euo pipefail
: "${CHUNKS_P1_USER:?}" "${CHUNKS_P1_PASS:?}" "${CHUNKS_P2_PASS:?}" "${CHUNKS_P3_PASS:?}"
: "${CHUNKS_SHARED_HASH:?Exact staged cohort same-hash fixture required}"
[[ "$CHUNKS_SHARED_HASH" =~ ^[0-9a-fA-F]{40}$ ]] || { echo "::error::invalid hash"; exit 1; }
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
  -e chunks_shared_hash_b64 "$(b64 "$CHUNKS_SHARED_HASH")" \
  io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner >"$log" 2>&1
status=$?
set -e
allowed='^(ground-truth|launch-failed|form-input-failed|connect-(primary|second|third)(-failed)?|connected-(primary|second|third)|options-(primary|second)-missing|server-screen-(primary|second)-missing|disconnect-(primary|second)-failed|disconnected-(primary|second)|combined-open-failed|combined-union(-mismatch)?|combined-origins(-mismatch)?|unchanged)$'
grep -E '^INSTRUMENTATION_STATUS: chunks_stage=' "$log" | sed 's/^INSTRUMENTATION_STATUS: chunks_stage=//; s/[[:cntrl:]]//g' |
  while read -r stage; do
    if [[ "$stage" =~ $allowed ]]; then echo "CHUNKS_STAGE: $stage"; else echo "CHUNKS_STAGE: unlisted"; fi
  done | tee -a "$GITHUB_STEP_SUMMARY"
if [ "$status" -ne 0 ] || ! grep -q '^OK (1 test)' "$log"; then
  echo "::error::Protected three-server Combined read-only check failed or skipped. Raw output withheld."
  exit 1
fi
echo "CHUNKS_PROTECTED_COMBINED: passed read-only" | tee -a "$GITHUB_STEP_SUMMARY"
