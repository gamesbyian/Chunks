#!/usr/bin/env bash
# Installed launch smoke of the REAL R8 release variant, throwaway-signed for an emulator.
# Verify installed APK bytes, a fresh app-owned UI tree and process-scoped runtime logs.
# The hosted check adds a real Local peer journey when given --engine.
set -uo pipefail
apk="${1:?signed release apk}"
mode="${2:-launch}"
[[ "$mode" == launch || "$mode" == --engine ]] || exit 2
out=app/build/outputs; mkdir -p "$out"
pkg=io.github.gamesbyian.chunks
fail_smoke() { echo "CHUNKS_RELEASE_SMOKE: $*"; exit 1; }
[ -s "$apk" ] || fail_smoke "missing or empty candidate APK"
tmp="$(mktemp -d)" || fail_smoke "cannot create temporary directory"
remote_ui="/sdcard/chunks-release-smoke-$$.xml"
trap 'rm -rf "$tmp"; adb shell rm -f "$remote_ui" >/dev/null 2>&1 || true' EXIT
rm -f "$out/release-ui.xml" "$out/release-installed-identity.txt" "$out/release-logcat.txt" "$out/release-logcat-app.txt" "$out/release-engine.txt" "$out/release-engine-final-ui.xml" "$out/release-engine-transfers-ui.xml"
adb install -r "$apk" || fail_smoke "install failed"
installed="$(adb shell pm path "$pkg")" || fail_smoke "cannot resolve installed APK"
installed="${installed//$'\r'/}"
# This workflow installs a single universal APK. An unexpected split set is not equivalent.
[[ "$installed" =~ ^package:/data/app/[^[:space:]]+/base\.apk$ ]] || fail_smoke "expected exactly one installed base APK"
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)" || fail_smoke "cannot resolve smoke tools"
if ! bash "$script_dir/verify_installed_apk_bytes.sh" "$apk" "${installed#package:}" "$tmp/base.apk" > "$out/release-installed-identity.txt" 2>&1; then
    cat "$out/release-installed-identity.txt"
    fail_smoke "installed APK identity not verified"
fi
cat "$out/release-installed-identity.txt"
adb logcat -c || fail_smoke "cannot clear logcat"
adb shell am start -W -n "$pkg/.MainActivity" || fail_smoke "launch failed"
sleep 20
fail=0
pid="$(adb shell pidof "$pkg")" || pid=""
pid="${pid//$'\r'/}"
[[ "$pid" =~ ^[0-9]+$ ]] || { echo "CHUNKS_RELEASE_SMOKE: process not alive after launch (or ambiguous PID)"; fail=1; }
adb shell rm -f "$remote_ui" >/dev/null 2>&1 &&
    adb shell uiautomator dump "$remote_ui" >/dev/null 2>&1 &&
    adb pull "$remote_ui" "$out/release-ui.xml" >/dev/null 2>&1 || fail_smoke "fresh UI capture failed"
# Count only nonblank text from this package, not permission dialogs, the launcher or Settings.
texts="$(python3 - "$out/release-ui.xml" "$pkg" <<'PY'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
if root.tag != "hierarchy":
    raise ValueError("not a UI hierarchy")
print(sum(1 for node in root.iter("node")
          if node.get("package") == sys.argv[2] and node.get("text", "").strip()))
PY
)" || fail_smoke "invalid UI hierarchy"
echo "CHUNKS_RELEASE_SMOKE: pid=${pid:-none} rendered_text_nodes=$texts"
[ "$texts" -ge 3 ] || { echo "CHUNKS_RELEASE_SMOKE: app-owned Compose UI did not render"; fail=1; }
if [[ "$mode" == --engine ]]; then
    if ! /usr/bin/python3 "$script_dir/release_engine_witness.py" --fixture-root "$tmp/chunks-peer" 2>&1 |
        tee "$out/release-engine.txt"; then
        echo "CHUNKS_RELEASE_SMOKE: installed native peer journey failed"
        fail=1
    fi
fi
adb logcat -d > "$out/release-logcat.txt" || fail_smoke "cannot read global logcat"
# Scan the whole stream (no early-exit pipe under pipefail). A crash may precede a restart.
if awk -v pkg="$pkg" '
    /FATAL EXCEPTION/ { remaining=3; next }
    remaining > 0 {
        if (index($0, "Process: " pkg ", PID:") || index($0, "Process: " pkg ":")) found=1
        remaining--
    }
    END { exit !found }
' "$out/release-logcat.txt"; then
    echo "CHUNKS_RELEASE_SMOKE: Chunks crashed"; fail=1
fi
if [[ "$pid" =~ ^[0-9]+$ ]]; then
    adb logcat -d --pid="$pid" > "$out/release-logcat-app.txt" || fail_smoke "cannot read app logcat"
    if grep -E "UnsatisfiedLinkError|NoSuchMethodError|NoClassDefFoundError|ClassNotFoundException|NoSuchFieldError|AbstractMethodError" "$out/release-logcat-app.txt"; then
        echo "CHUNKS_RELEASE_SMOKE: R8/JNI runtime failure in the app process"; fail=1
    fi
fi
[ "$fail" -eq 0 ] && echo "CHUNKS_RELEASE_SMOKE: PASS"
exit "$fail"
