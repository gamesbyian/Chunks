#!/usr/bin/env bash
# Installed real JNI peer download with bounded *allocated* /data pressure.
# Opt-in disposable API35 emulator ONLY. Never on a local or seeded user data.
set -euo pipefail
pkg=io.github.gamesbyian.chunks

# Device pressure is irreversible while this controller runs. Refuse contradictory
# selectors instead of silently targeting an arbitrary connected AVD.
[[ -z "${ADB_SERIAL:-}" || -z "${ANDROID_SERIAL:-}" ||
   "$ADB_SERIAL" == "$ANDROID_SERIAL" ]] || {
  echo 'CHUNKS_LOW_SPACE: conflicting ADB serial selectors' >&2; exit 2;
}
devices="$(adb devices)" || {
  echo 'CHUNKS_LOW_SPACE: ADB enumeration failed' >&2; exit 2;
}
serial="${ANDROID_SERIAL:-${ADB_SERIAL:-}}"
if [[ -z "$serial" ]]; then
  mapfile -t attached < <(awk 'NR > 1 && $2 == "device" {print $1}' <<< "$devices")
  [[ "${#attached[@]}" == 1 ]] || {
    echo 'CHUNKS_LOW_SPACE: require exactly one disposable emulator' >&2; exit 2;
  }
  serial="${attached[0]}"
fi
[[ "$(awk -v wanted="$serial" '$1 == wanted && $2 == "device" {n++} END {print n+0}' <<< "$devices")" == 1 ]] || {
  echo 'CHUNKS_LOW_SPACE: selected emulator not connected' >&2; exit 2;
}
export ANDROID_SERIAL="$serial"
state="$(adb get-state)" || {
  echo 'CHUNKS_LOW_SPACE: ADB state probe failed' >&2; exit 2;
}
[[ "$(printf '%s' "$state" | tr -d '\r\n')" == device ]] || {
  echo 'CHUNKS_LOW_SPACE: ADB device not ready' >&2; exit 2;
}
qemu="$(adb shell getprop ro.kernel.qemu)" || {
  echo 'CHUNKS_LOW_SPACE: emulator probe failed' >&2; exit 2;
}
sdk="$(adb shell getprop ro.build.version.sdk)" || {
  echo 'CHUNKS_LOW_SPACE: SDK probe failed' >&2; exit 2;
}
[[ "$(printf '%s' "$qemu" | tr -d '\r\n')" == 1 ]] || {
  echo 'CHUNKS_LOW_SPACE: refusing physical-device disk pressure' >&2; exit 2;
}
[[ "$(printf '%s' "$sdk" | tr -d '\r\n')" == 35 ]] || {
  echo 'CHUNKS_LOW_SPACE: requires dedicated API35 AVD' >&2; exit 2;
}
install_verified() {
  local output
  output="$(adb install -r "$1" 2>&1)" || {
    echo 'CHUNKS_LOW_SPACE: adb install failed' >&2; return 1;
  }
  grep -Fxq 'Success' <<< "$(printf '%s\n' "$output" | tr -d '\r')" || {
    echo 'CHUNKS_LOW_SPACE: installation not confirmed by package manager' >&2; return 1;
  }
}
install_verified app/build/outputs/apk/debug/app-debug.apk
install_verified app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c
log="$(mktemp)"
trap 'rm -f "$log"' EXIT
set +e
adb shell am instrument -w -r \
  -e class "$pkg.AndroidNativeLowStorageRecoveryInstrumentedTest" \
  -e chunksLowSpace 1 \
  "$pkg.test/androidx.test.runner.AndroidJUnitRunner" >"$log" 2>&1
instrument_result=$?
set -e
if [[ "$instrument_result" -ne 0 ||
      "$(grep -Fxc 'OK (1 test)' "$log" || true)" != 1 ]] ||
   grep -Eq 'AssumptionViolatedException|^INSTRUMENTATION_STATUS_CODE: -[1-9]|^FAILURES' "$log"; then
  grep -A15 '^INSTRUMENTATION_STATUS: stack=' "$log" | head -60 || true
  echo 'CHUNKS_LOW_SPACE: missing real executed test or native recovery failed' >&2
  exit 1
fi
receipt="$(adb logcat -d -s ChunksLowSpace:I)" || {
  echo 'CHUNKS_LOW_SPACE: logcat proof unavailable' >&2; exit 1;
}
for marker in CHUNKS_LOW_SPACE_PRESSURE CHUNKS_LOW_SPACE_BLOCKED CHUNKS_LOW_SPACE_RECOVERED; do
  [[ "$(grep -Fc "$marker" <<< "$receipt" || true)" == 1 ]] || {
    echo "CHUNKS_LOW_SPACE: missing or duplicated required receipt $marker" >&2; exit 1;
  }
done
grep -Eq 'CHUNKS_LOW_SPACE_RECOVERED bytes=50331648 sha_equal=true stopped=true removed_keep_files=true' <<< "$receipt" || {
  echo 'CHUNKS_LOW_SPACE: no independent SHA and Remove-keep evidence' >&2
  exit 1
}
printf '%s\n' "$receipt" | grep 'CHUNKS_LOW_SPACE_' | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
echo 'CHUNKS_LOW_SPACE: real allocated pressure, native fault, explicit recovery and peer SHA accepted on disposable API35 emulator only'
