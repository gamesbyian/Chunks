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
for kind in legacy4 modern4 modern5; do
  for label in positive positive_after_refusals; do
    grep -F "CHUNKS_UNRAR_JNI_CORPUS: $kind/$label bytes=207050 member_sha256=57613b4a0d18b31472c9abe90780dcaf834f4edf48e79008f027a99710cf3632,6805973b24128edda3148235c77a7f7b5a7a9be6272bc882a6e030871b2b18d1" "$tmp/result.txt"
  done
  for label in missing_middle missing_last corrupt_middle reversed_middle_last aggregate_budget; do
    grep -F "CHUNKS_UNRAR_JNI_CORPUS: $kind/$label=refused" "$tmp/result.txt"
  done
done
grep -F 'CHUNKS_UNRAR_JNI_CORPUS: PASS' "$tmp/result.txt"
grep -E 'CHUNKS_UNRAR_ISOLATED: private_path=(eacces|eperm|enoent|enotdir) parent_inode_and_bytes=verified accessible_control=verified negative_control=enotdir' "$tmp/result.txt"
grep -E 'CHUNKS_UNRAR_ISOLATED: readonly_input bytes=[1-9][0-9]* sha256=4cc78dae7289d222afae0c28a1e186c59dba7298cf49e7cfa25ad57230a054f7 distinct_uid=true private_path_denied=true sealed=true' "$tmp/result.txt"
grep -E 'CHUNKS_UNRAR_ISOLATED: tmpfile=(verified|(stat|flags|read):access_denied)' "$tmp/result.txt"
grep -F 'CHUNKS_UNRAR_ISOLATED: wrong_length=refused' "$tmp/result.txt"
grep -F 'CHUNKS_UNRAR_ISOLATED: named_inode=refused' "$tmp/result.txt"
grep -F 'CHUNKS_UNRAR_ISOLATED: PASS' "$tmp/result.txt"
grep -E 'CHUNKS_UNRAR_JNI: PASS app_uid=[1-9][0-9]{4,}' "$tmp/result.txt"
grep -E '^INSTRUMENTATION_CODE: -1[[:space:]]*$' "$tmp/result.txt"
if grep -E 'CHUNKS_UNRAR_JNI: FAIL|INSTRUMENTATION_FAILED|Process crashed' "$tmp/result.txt"; then exit 1; fi
