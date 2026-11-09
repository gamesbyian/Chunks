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
  adb shell rm -f /sdcard/chunks-ui.xml
  adb shell uiautomator dump --compressed /sdcard/chunks-ui.xml || true
  adb pull /sdcard/chunks-ui.xml "$RUNNER_TEMP/chunks-native-atlas/$scenario.xml" || true
  # A real Google API35 emulator can surface a Pixel Launcher-only ANR over
  # the already rendered Chunks foreground activity. Never dismiss Chunks'
  # own ANR or an arbitrary dialog. Exact system-owned title, package, close
  # action and bounded coordinates are required; at most ONE recovery.
  atlas_xml="$RUNNER_TEMP/chunks-native-atlas/$scenario.xml"
  if [[ -s "$atlas_xml" ]] && tap=$(python3 tools/native_atlas_launcher_anr.py "$atlas_xml"); then
    read -r tap_x tap_y <<< "$tap"
    cp "$RUNNER_TEMP/chunks-native-atlas/$scenario.png" \
      "$RUNNER_TEMP/chunks-native-atlas/$scenario.launcher-anr-original.png"
    cp "$atlas_xml" "$RUNNER_TEMP/chunks-native-atlas/$scenario.launcher-anr-original.xml"
    printf 'CHUNKS_NATIVE_ATLAS: one external Pixel Launcher ANR dismissal before %s recapture\n' "$scenario"
    adb shell input tap "$tap_x" "$tap_y"
    sleep 3
    adb exec-out screencap -p > "$RUNNER_TEMP/chunks-native-atlas/$scenario.png"
    adb shell rm -f /sdcard/chunks-ui.xml
    adb shell uiautomator dump --compressed /sdcard/chunks-ui.xml || true
    adb pull /sdcard/chunks-ui.xml "$atlas_xml" || true
  fi
done
python3 tools/native_visual_atlas.py "$RUNNER_TEMP/chunks-native-atlas"
