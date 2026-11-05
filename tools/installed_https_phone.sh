#!/usr/bin/env bash
# One real installed phone case; failures remain red in the assembled publication gate.
set -euo pipefail
candidate_package=${1:?source-identified installed preview package}
[[ "$candidate_package" =~ ^io\.github\.gamesbyian\.chunks\.magnet[0-9a-f]{12}$ ]]
milestone() { printf 'CHUNKS_HTTPS_PHONE_PREFLIGHT: %s\n' "$1"; }
milestone 'running production phone HTTPS and one-time Place journey'
adb logcat -c
adb shell am instrument -w -r \
  -e class io.github.gamesbyian.chunks.HttpsPhoneAddJourneyInstrumentedTest \
  -e chunks_https_add authorized \
  "${candidate_package}.test/androidx.test.runner.AndroidJUnitRunner" \
  > "$RUNNER_TEMP/chunks-https-phone.raw" 2>&1
# Capture the disposable public-fixture process crash before parsing can fail.
# No account has been entered at this point; never retain a protected host log.
if [[ "${CHUNKS_DISPOSABLE:-0}" = 1 ]]; then
  adb logcat -d -b crash > "$RUNNER_TEMP/chunks-https-phone-crash.txt"
fi
adb logcat -d -v raw -s ChunksHttps:I > "$RUNNER_TEMP/chunks-https-phone-witness.txt"
python3 tools/parse_instrumentation_stream.py --raw "$RUNNER_TEMP/chunks-https-phone.raw" \
  --junit "$RUNNER_TEMP/chunks-https-phone.xml"
python3 tools/ci_failure_digest.py --suite https-phone --status success --require-executed \
  --glob "$RUNNER_TEMP/chunks-https-phone.xml" --output "$RUNNER_TEMP/chunks-https-phone-digest.json"
grep -E '^CHUNKS_HTTPS_PHONE verified=true received=[1-9][0-9]* pieces_bytes=[1-9][0-9]* complete=false default_preserved=true piece_sha256=[0-9a-f]{64}$' \
  "$RUNNER_TEMP/chunks-https-phone-witness.txt"
adb shell am force-stop "$candidate_package"
