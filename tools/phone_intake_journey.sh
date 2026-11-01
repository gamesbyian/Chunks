#!/usr/bin/env bash
# Real MainActivity -> Add -> peer bytes -> UI Stop -> death -> UI restore/seed.
# Disposable emulator only. Preserve package data and scoped-storage ownership.
set -euo pipefail
pkg=io.github.gamesbyian.chunks
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
cls=io.github.gamesbyian.chunks.PhoneIntakeJourneyInstrumentedTest
# The standalone intake fixture writes public Downloads and force-stops the
# package. Pin a positively identified emulator *before* any device mutation.
[[ -z "${ADB_SERIAL:-}" || -z "${ANDROID_SERIAL:-}" ||
   "$ADB_SERIAL" == "$ANDROID_SERIAL" ]] || {
  echo 'CHUNKS_INTAKE: conflicting ADB target selectors' >&2; exit 2;
}
devices="$(adb devices)" || {
  echo 'CHUNKS_INTAKE: ADB enumeration failed' >&2; exit 2;
}
serial="${ADB_SERIAL:-${ANDROID_SERIAL:-}}"
if [[ -z "$serial" ]]; then
  mapfile -t connected < <(awk 'NR > 1 && $2 == "device" { print $1 }' <<< "$devices")
  [[ "${#connected[@]}" == 1 ]] || {
    echo 'CHUNKS_INTAKE: require one connected disposable emulator' >&2; exit 2;
  }
  serial="${connected[0]}"
fi
[[ "$(awk -v wanted="$serial" '$1 == wanted && $2 == "device" { n++ } END { print n+0 }' <<< "$devices")" == 1 ]] || {
  echo 'CHUNKS_INTAKE: selected ADB target is missing or not ready' >&2; exit 2;
}
export ANDROID_SERIAL="$serial"
state="$(adb get-state)" || { echo 'CHUNKS_INTAKE: ADB state probe failed' >&2; exit 2; }
[[ "$(printf '%s' "$state" | tr -d '\r\n')" == device ]] || {
  echo 'CHUNKS_INTAKE: ADB target is not ready' >&2; exit 2;
}
emulator="$(adb shell getprop ro.kernel.qemu)" || {
  echo 'CHUNKS_INTAKE: emulator identity probe failed' >&2; exit 2;
}
sdk="$(adb shell getprop ro.build.version.sdk)" || {
  echo 'CHUNKS_INTAKE: Android API probe failed' >&2; exit 2;
}
[[ "$(printf '%s' "$emulator" | tr -d '\r\n')" == 1 ]] || {
  echo 'CHUNKS_INTAKE: disposable emulator required; app untouched' >&2; exit 2;
}
sdk="$(printf '%s' "$sdk" | tr -d '\r\n')"
[[ "$sdk" =~ ^[0-9]+$ && "$sdk" -ge 29 ]] || {
  echo 'CHUNKS_INTAKE: Android API29+ required' >&2; exit 2;
}

for kind in share magnet; do
  # A cold process is the ordinary journey. pm clear orphans MediaStore ownership
  # and belongs to a separate reinstall/data-clear acceptance scenario.
  adb shell am force-stop "$pkg"
  adb logcat -c
  set +e
  out_a="$(adb shell am instrument -w -r -e class "$cls#phaseA_externalReviewDownloadsThroughUiStopsThenProcessDies" \
    -e chunksIntakePhase a -e chunksIntakeKind "$kind" "$runner")"
  set -e
  printf '%s\n' "$out_a"
  log_a="$(adb logcat -d -v raw -s ChunksIntake:I)"
  armed="$(grep -E "^CHUNKS_INTAKE kind=$kind armed reviewed=true bytes=524288 stopped=true sha256=[0-9a-f]{64}$" <<< "$log_a" || true)"
  [[ "$(grep -c '^CHUNKS_INTAKE ' <<< "$armed" || true)" == 1 ]] || {
    echo 'CHUNKS_INTAKE: missing/duplicate download and UI Stop witness'; exit 1;
  }
  if ! grep -Fq 'Process crashed' <<< "$out_a" ||
     grep -Eq '^OK \(|^FAILURES|^INSTRUMENTATION_STATUS_CODE: -[1-9]|AssumptionViolatedException' <<< "$out_a"; then
    echo 'CHUNKS_INTAKE: phase A did not execute and kill its own process'; exit 1
  fi
  dead="$(adb shell "if pidof $pkg >/dev/null; then echo running; else echo stopped; fi" | tr -d '\r')"
  [[ "$dead" == stopped ]] || { echo 'CHUNKS_INTAKE: original process is still running or unobservable'; exit 1; }

  out_b="$(adb shell am instrument -w -r -e class "$cls#phaseB_coldUiRestoreStartsSeedsToFreshPeerAndRemovesKeepingBytes" \
    -e chunksIntakePhase b -e chunksIntakeKind "$kind" "$runner")"
  printf '%s\n' "$out_b"
  log_b="$(adb logcat -d -v raw -s ChunksIntake:I)"
  grep '^CHUNKS_INTAKE_COUNTERS ' <<< "$log_b" || true
  if [[ "$(grep -Fxc 'OK (1 test)' <<< "$out_b" || true)" != 1 ]] ||
     [[ "$(grep -Fxc 'INSTRUMENTATION_CODE: -1' <<< "$out_b" || true)" != 1 ]] ||
     [[ "$(grep -c '^INSTRUMENTATION_CODE:' <<< "$out_b" || true)" != 1 ]] ||
     grep -Eq '^INSTRUMENTATION_STATUS_CODE: -[1-9]|^FAILURES|AssumptionViolatedException' <<< "$out_b"; then
    echo 'CHUNKS_INTAKE: cold recovery did not execute exactly one passing test'; exit 1
  fi
  recovered="$(grep -E "^CHUNKS_INTAKE kind=$kind recovered stopped=true bytes=524288 seeded_bytes=524288 kept_files=true sha256=[0-9a-f]{64}$" <<< "$log_b" || true)"
  [[ "$(grep -c '^CHUNKS_INTAKE ' <<< "$recovered" || true)" == 1 ]] || {
    echo 'CHUNKS_INTAKE: missing/duplicate actual restore, seeding and keep-files witness'; exit 1;
  }
  [[ "${armed##*sha256=}" == "${recovered##*sha256=}" ]] || {
    echo 'CHUNKS_INTAKE: payload digest changed across process death'; exit 1;
  }
  echo "$armed"
  echo "$recovered"
  adb shell am force-stop "$pkg"
done
echo 'CHUNKS_INTAKE: both real Share/magnet UI journeys verified on this disposable emulator only'
