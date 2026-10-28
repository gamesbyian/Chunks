#!/usr/bin/env bash
# The emulator runner executes this as a single shell command.
set -euo pipefail
gradle --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest
bash tools/assert_debug_stacked_font_assets.sh
gradle --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.gamesbyian.chunks.ExperimentalTypefaceNativeInstrumentedTest \
  -Pandroid.testInstrumentationRunnerArguments.chunks_font_fixture=required
bash tools/export_native_font_screenshots.sh
