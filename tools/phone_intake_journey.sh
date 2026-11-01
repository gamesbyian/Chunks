#!/usr/bin/env bash
# Real MainActivity -> Add -> peer bytes -> UI Stop -> death -> UI restore/seed.
# Destructively resets ONLY a disposable emulator's Chunks test installation.
set -euo pipefail
pkg=io.github.gamesbyian.chunks
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
cls=io.github.gamesbyian.chunks.PhoneIntakeJourneyInstrumentedTest
emulator="$(adb shell getprop ro.kernel.qemu | tr -d '\r')"
[[ "$emulator" == 1 ]] || { echo 'CHUNKS_INTAKE: disposable emulator required; no app data cleared'; exit 1; }

for kind in share magnet; do
  cleared="$(adb shell pm clear "$pkg" | tr -d '\r')"
  [[ "$cleared" == Success ]] || { echo 'CHUNKS_INTAKE: fresh app state was not verified'; exit 1; }
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
  if [[ "$(grep -Fxc 'OK (1 test)' <<< "$out_b" || true)" != 1 ]] ||
     grep -Eq '^INSTRUMENTATION_STATUS_CODE: -[1-9]|^FAILURES|AssumptionViolatedException' <<< "$out_b"; then
    echo 'CHUNKS_INTAKE: cold recovery did not execute exactly one passing test'; exit 1
  fi
  log_b="$(adb logcat -d -v raw -s ChunksIntake:I)"
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
