#!/usr/bin/env bash
set -euo pipefail
apk="${1:?disposable signed probe APK}"
pkg=io.github.gamesbyian.chunks.unrarprobe
tmp="$(mktemp -d)"
trap 'adb uninstall "$pkg" >/dev/null 2>&1 || true; rm -rf "$tmp"' EXIT
adb install "$apk"
installed="$(adb shell pm path "$pkg" | tr -d '\r')"
[[ "$installed" == package:/data/app/*/base.apk && "$installed" != *$'\n'* ]]
adb pull "${installed#package:}" "$tmp/installed.apk"
cmp "$apk" "$tmp/installed.apk"
sha256sum "$tmp/installed.apk"
timeout 120s adb shell am instrument -w -r "$pkg/.WitnessInstrumentation" | tee "$tmp/result.txt"
grep -F 'CHUNKS_UNRAR_JNI: positive bytes=1048 sha256=05abc6df769ec4637bcb82ddc1b5514d910d62502b40c1336aa15436405e2696' "$tmp/result.txt"
for case in missing_last reversed duplicate corrupt truncated budget; do
  grep -F "CHUNKS_UNRAR_JNI: $case=refused" "$tmp/result.txt"
done
grep -F 'CHUNKS_UNRAR_JNI: positive_after_refusals bytes=1048 sha256=05abc6df769ec4637bcb82ddc1b5514d910d62502b40c1336aa15436405e2696' "$tmp/result.txt"
grep -E 'CHUNKS_UNRAR_JNI: PASS app_uid=[1-9][0-9]{4,}' "$tmp/result.txt"
grep -E '^INSTRUMENTATION_CODE: -1[[:space:]]*$' "$tmp/result.txt"
if grep -E 'CHUNKS_UNRAR_JNI: FAIL|INSTRUMENTATION_FAILED|Process crashed' "$tmp/result.txt"; then exit 1; fi
