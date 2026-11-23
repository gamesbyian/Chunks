#!/usr/bin/env bash
# Disposable emulator only. The receiver never forwards to a public provider.
set -euo pipefail
test "${CHUNKS_NATIVE_ADD_SHARD_INDEX:-}" = 0
test "$(adb shell getprop ro.kernel.qemu | tr -d '[:space:]')" = 1
test "$(git rev-parse HEAD)" = "${GITHUB_SHA:?}"

pkg=io.github.gamesbyian.chunks
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
case_class="$pkg.InstalledSecondAddMediaConsentInstrumentedTest"
candidate="$RUNNER_TEMP/chunks-prebuilt/chunks-app.apk"
test "$(cat "$RUNNER_TEMP/chunks-prebuilt/source-sha")" = "$GITHUB_SHA"
out="${RUNNER_TEMP:?}/chunks-second-add-media"
mkdir -p "$out"
scratch="$(mktemp -d)"
fixture_pid=
cleanup() {
  adb reverse --remove tcp:18091 >/dev/null 2>&1 || true
  if [[ -n "$fixture_pid" ]]; then kill "$fixture_pid" 2>/dev/null || true; fi
  rm -rf "$scratch"
}
trap cleanup EXIT

adb shell am force-stop "$pkg"
adb shell pm clear "$pkg" | grep -Fx Success
device_path="$(adb shell pm path "$pkg" | tr -d '[:space:]' | grep '^package:' | head -1 | cut -d: -f2-)"
aapt="${ANDROID_HOME:?}/build-tools/36.0.0/aapt"
version="$("$aapt" dump badging "$candidate" | grep -m1 '^package:' | awk -F "versionName='" '{print $2}' | cut -d "'" -f1)"
test -n "$version"
echo "CHUNKS_SECOND_ADD_MEDIA_SOURCE commit=$(git rev-parse HEAD) tree=$(git rev-parse HEAD^{tree})" | tee "$out/source.txt"
echo "CHUNKS_SECOND_ADD_MEDIA_PACKAGE app_id=$pkg version=$version" | tee "$out/package.txt"
bash tools/verify_installed_apk_bytes.sh "$candidate" "$device_path" "$scratch/installed.apk" | tee "$out/installed-sha256.txt"

openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj '/CN=Disposable Chunks HTTPS fixture' \
  -addext 'subjectAltName=DNS:example.com,DNS:api.tvmaze.com,DNS:static.tvmaze.com' \
  -keyout "$scratch/key.pem" -out "$scratch/cert.pem" 2>/dev/null
cert="$(openssl x509 -in "$scratch/cert.pem" -outform DER | base64 -w0)"
python3 tools/installed_media_consent_fixture.py "$scratch/cert.pem" "$scratch/key.pem" "$scratch/port" &
fixture_pid="$!"
for ((attempt=0; attempt<50; attempt++)); do
  if [[ -s "$scratch/port" ]]; then break; fi
  kill -0 "$fixture_pid"
  sleep 0.1
done
host_port="$(cat "$scratch/port")"
adb reverse tcp:18091 "tcp:$host_port"
counts="http://127.0.0.1:$host_port/counts"
curl --fail --silent --max-time 3 "$counts" | grep -Fx '{}'

run_phase() {
  local method="$1" result=success
  if ! timeout 240s adb shell am instrument -w -r -e class "$case_class#$method" \
      -e chunks_media_consent controlled -e chunks_media_cert "$cert" "$runner" \
      > "$scratch/$method.raw" 2>&1; then
    result=failure
  fi
  curl --fail --silent --max-time 3 "$counts" > "$out/$method-counts.json"
  python3 tools/parse_instrumentation_stream.py --raw "$scratch/$method.raw" --junit "$out/$method.xml"
  python3 tools/ci_failure_digest.py --suite "installed-second-add-media-$method" --status "$result" \
    --require-executed --glob "$out/$method.xml" --output "$out/$method-digest.json"
}

run_phase sharedSignedLinkWaitsForReplacementAndExplicitFetch
adb shell am force-stop "$pkg"
run_phase phaseA_submitTwoSourcesAndRefreshInventory
adb shell am force-stop "$pkg"
run_phase phaseB_restoredExclusionAndFreshPositiveControl
adb shell am force-stop "$pkg"
echo 'CHUNKS_SECOND_ADD_MEDIA_ACCEPTANCE controlled_https=true exact_local_exclusion=true fresh_process=true positive_controls=2 signed_share_review=true'
