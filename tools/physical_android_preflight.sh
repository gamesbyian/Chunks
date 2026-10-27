#!/usr/bin/env bash
# Explicit physical-handset install preflight for a verified Chunks debug APK.
# No server login, screenshots, application data, logcat or torrent mutations.
set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "Usage: bash tools/physical_android_preflight.sh /path/to/app-debug.apk" >&2
    exit 2
fi

for tool in adb sha256sum; do
    command -v "$tool" >/dev/null 2>&1 || {
        echo "Required program unavailable: $tool" >&2
        exit 2
    }
done

apk="$1"
[[ -s "$apk" && "$(basename "$apk")" == "app-debug.apk" ]] || {
    echo "Expected the extracted nonempty app-debug.apk" >&2
    exit 2
}
folder="$(cd "$(dirname "$apk")" && pwd -P)"
apk="$folder/app-debug.apk"
receipt="$folder/CHUNKS-BUILD-SHA256.txt"
[[ -s "$receipt" ]] || {
    echo "Missing the matching CHUNKS-BUILD-SHA256.txt from the Actions artifact" >&2
    exit 2
}

# Restrict the checksum to the one expected filename. Do not execute any
# arbitrary path entries from an untrusted checksum manifest.
first_line="$(head -n 1 "$receipt")"
if [[ ! "$first_line" =~ ^([[:xdigit:]]{64})[[:space:]][[:space:]]app-debug\.apk$ ]]; then
    echo "Unexpected APK checksum receipt format" >&2
    exit 2
fi
expected_hash="${BASH_REMATCH[1],,}"
actual_hash="$(sha256sum "$apk" | cut -d ' ' -f 1)"
[[ "$actual_hash" == "$expected_hash" ]] || {
    echo "APK SHA-256 differs from its build receipt: refusing installation" >&2
    exit 1
}

built_commit="$(sed -n 's/^# built_commit=//p' "$receipt" | head -n 1)"
[[ "$built_commit" =~ ^[[:xdigit:]]{40}$ ]] || {
    echo "Missing or invalid checked-out build commit in receipt" >&2
    exit 2
}

adb_command=(adb)
if [[ -n "${ADB_SERIAL:-}" ]]; then
    adb_command+=(-s "$ADB_SERIAL")
else
    attached_count="$(adb devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count+0 }')"
    [[ "$attached_count" == 1 ]] || {
        echo "Connect exactly one authorized physical Android device, or set ADB_SERIAL" >&2
        exit 2
    }
fi
[[ "$("${adb_command[@]}" get-state | tr -d '\r\n')" == "device" ]] || {
    echo "The selected device is not authorized for adb" >&2
    exit 2
}

# This guard prevents an API35 emulator run from being mislabeled as physical
# acceptance. It is a preflight check, not tamper-resistant attestation.
qemu="$("${adb_command[@]}" shell getprop ro.kernel.qemu | tr -d '\r\n')"
boot_qemu="$("${adb_command[@]}" shell getprop ro.boot.qemu | tr -d '\r\n')"
if [[ "$qemu" == "1" || "$boot_qemu" == "1" ]]; then
    echo "Emulator detected: this command is reserved for a physical handset" >&2
    exit 1
fi

sdk="$("${adb_command[@]}" shell getprop ro.build.version.sdk | tr -d '\r\n')"
release="$("${adb_command[@]}" shell getprop ro.build.version.release | tr -d '\r\n')"
model="$("${adb_command[@]}" shell getprop ro.product.model | tr -d '\r\n')"
[[ "$sdk" =~ ^[0-9]+$ ]] || {
    echo "Could not read Android API level" >&2
    exit 1
}

"${adb_command[@]}" install -r "$apk" >/dev/null
installed_version="$("${adb_command[@]}" shell dumpsys package io.github.gamesbyian.chunks |
    grep -m 1 -o 'versionName=[^[:space:]]*' | cut -d = -f 2)"
expected_version="0.1.0-dev+${built_commit:0:12}"
[[ "$installed_version" == "$expected_version" ]] || {
    echo "Installed Chunks version does not match the built checkout: refusing to record success" >&2
    exit 1
}
"${adb_command[@]}" shell am start -W -n io.github.gamesbyian.chunks/.MainActivity >/dev/null

printf 'CHUNKS_PHYSICAL_PREFLIGHT device_model=%s android=%s api=%s\n' "$model" "$release" "$sdk"
printf 'CHUNKS_PHYSICAL_PREFLIGHT source_checkout=%s apk_sha256=%s version=%s\n' \
    "$built_commit" "$actual_hash" "$installed_version"
echo "CHUNKS_PHYSICAL_PREFLIGHT installation and initial launch verified."
echo "NOT VERIFIED: protected Seedhost login, torrent data, DHT, seeding, Doze, FGS, SAF, accessibility or sustained lifecycle."
