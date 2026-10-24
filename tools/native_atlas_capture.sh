#!/usr/bin/env bash
# Single command in reactivecircus android-emulator-runner, not fragmented lines.
set -euo pipefail
adb install -r app/build/outputs/apk/debug/app-debug.apk
mkdir -p "$RUNNER_TEMP/chunks-native-atlas"
for scenario in small standard larger_text wide; do
  case "$scenario" in
    small) pixels="1080x2400"; density=480; font=1.0 ;;
    standard) pixels="1080x2400"; density=420; font=1.0 ;;
    larger_text) pixels="1080x2400"; density=420; font=1.4 ;;
    wide) pixels="1080x2250"; density=360; font=1.0 ;;
  esac
  adb shell wm size "$pixels"
  adb shell wm density "$density"
  adb shell settings put system font_scale "$font"
  adb shell am force-stop io.github.gamesbyian.chunks || true
  adb shell am start -W -n io.github.gamesbyian.chunks/.MainActivity
  sleep 3
  adb exec-out screencap -p > "$RUNNER_TEMP/chunks-native-atlas/$scenario.png"
  adb shell uiautomator dump --compressed /sdcard/chunks-ui.xml || true
  adb pull /sdcard/chunks-ui.xml "$RUNNER_TEMP/chunks-native-atlas/$scenario.xml" || true
done
python3 tools/native_visual_atlas.py "$RUNNER_TEMP/chunks-native-atlas"
