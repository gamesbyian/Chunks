#!/usr/bin/env bash
# Opt-in two-process sudden-death repro (#569) on a DISPOSABLE emulator.
# Phase A arms a seeding transfer under the service and SIGKILLs itself;
# phase B (fresh process) checks honest restore. APKs must be installed.
set -euo pipefail
pkg=io.github.gamesbyian.chunks
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
cls=io.github.gamesbyian.chunks.LocalEngineProcessDeathInstrumentedTest
adb logcat -c || true
set +e
outA=$(adb shell am instrument -w -r -e class "$cls#phaseA_seedUnderServiceThenDieWithoutWarning" -e chunksDeathPhase a "$runner")
set -e
echo "$outA" | tail -20
adb logcat -d -s ChunksDeath:I | grep "CHUNKS_DEATH armed" || { echo "CHUNKS_DEATH: phase A never armed"; exit 1; }
# Phase A must have died by its own SIGKILL, not passed or been skipped.
echo "$outA" | grep -q "Process crashed" || { echo "CHUNKS_DEATH: phase A did not die as intended"; exit 1; }
sleep 3
if adb shell pidof "$pkg" >/dev/null 2>&1; then echo "CHUNKS_DEATH: process still alive"; exit 1; fi
outB=$(adb shell am instrument -w -r -e class "$cls#phaseB_restoredStoppedWithBytesIntactAndRechecks" -e chunksDeathPhase b "$runner")
echo "$outB" | tail -20
adb logcat -d -s ChunksDeath:I | tail -5
echo "$outB" | grep -q "OK (1 test)" || { echo "CHUNKS_DEATH: phase B did not pass"; exit 1; }
if echo "$outB" | grep -q "INSTRUMENTATION_STATUS_CODE: -3"; then echo "CHUNKS_DEATH: phase B skipped"; exit 1; fi
