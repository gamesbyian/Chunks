#!/usr/bin/env bash
# Installed smoke of the REAL release variant built with R8 (-Pchunks.releaseShrink=true),
# debug-signed with a throwaway key by the workflow. Black-box only: install, launch, let the
# app run, require a live process, a rendered Compose UI and no R8/JNI failure in logcat.
# This does not exercise the native engine; the engine witness for this APK is the static
# tools/release_jni_witness.py, and an installed engine journey remains a release-candidate step.
set -uo pipefail
apk="${1:?signed release apk}"
out=app/build/outputs; mkdir -p "$out"
pkg=io.github.gamesbyian.chunks
adb install -r "$apk" || { echo "CHUNKS_RELEASE_SMOKE: install failed"; exit 1; }
adb logcat -c
adb shell am start -W -n "$pkg/.MainActivity" || { echo "CHUNKS_RELEASE_SMOKE: launch failed"; exit 1; }
sleep 20
fail=0
pid="$(adb shell pidof "$pkg" | tr -d '\r')"
[ -n "$pid" ] || { echo "CHUNKS_RELEASE_SMOKE: process not alive after launch"; fail=1; }
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb pull /sdcard/ui.xml "$out/release-ui.xml" >/dev/null 2>&1
texts="$(grep -o 'text="[^"]\+"' "$out/release-ui.xml" 2>/dev/null | wc -l)"
echo "CHUNKS_RELEASE_SMOKE: pid=${pid:-none} rendered_text_nodes=$texts"
[ "$texts" -ge 3 ] || { echo "CHUNKS_RELEASE_SMOKE: Compose UI did not render"; fail=1; }
adb logcat -d > "$out/release-logcat.txt"
# Only Chunks' own failures count: other system processes (e.g. Settings) log unrelated
# ClassNotFoundExceptions. A crash record names the process; a live process is scoped by pid.
if grep -A3 "FATAL EXCEPTION" "$out/release-logcat.txt" | grep -q "Process: $pkg"; then
  echo "CHUNKS_RELEASE_SMOKE: Chunks crashed"; grep -A20 "FATAL EXCEPTION" "$out/release-logcat.txt" | head -40; fail=1
fi
if [ -n "$pid" ]; then
  adb logcat -d --pid="$pid" > "$out/release-logcat-app.txt"
  if grep -E "UnsatisfiedLinkError|NoSuchMethodError|NoClassDefFoundError|ClassNotFoundException|NoSuchFieldError|AbstractMethodError" "$out/release-logcat-app.txt"; then
    echo "CHUNKS_RELEASE_SMOKE: R8/JNI runtime failure in the app process"; fail=1
  fi
fi
[ "$fail" -eq 0 ] && echo "CHUNKS_RELEASE_SMOKE: PASS"
exit "$fail"
