#!/usr/bin/env bash
# Explicit physical-handset install preflight for a verified Chunks debug APK.
# No server login, screenshots, application data, logcat or torrent mutations.
set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "Usage: bash tools/physical_android_preflight.sh /path/to/chunks-debug.apk" >&2
    exit 2
fi

for tool in adb sha256sum; do
    command -v "$tool" >/dev/null 2>&1 || {
        echo "Required program unavailable: $tool" >&2
        exit 2
    }
done

apk="$1"
[[ -s "$apk" && "$(basename "$apk")" == "chunks-debug.apk" ]] || {
    echo "Expected the extracted nonempty chunks-debug.apk" >&2
    exit 2
}
folder="$(cd "$(dirname "$apk")" && pwd -P)"
apk="$folder/chunks-debug.apk"
receipt="$folder/CHUNKS-BUILD-SHA256.txt"
[[ -s "$receipt" ]] || {
    echo "Missing the matching CHUNKS-BUILD-SHA256.txt from the Actions artifact" >&2
    exit 2
}

# Restrict the checksum to the one expected filename. Do not execute any
# arbitrary path entries from an untrusted checksum manifest.
first_line="$(head -n 1 "$receipt")"
if [[ ! "$first_line" =~ ^([[:xdigit:]]{64})[[:space:]][[:space:]]chunks-debug\.apk$ ]]; then
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
    # Count and select in the same snapshot, then pin every subsequent command
    # to that serial. An unscoped adb install could target another handset if
    # attachments change after enumeration.
    # Process substitutions do not propagate their producer's exit status.
    # A failed adb device inventory can still print a plausible serial.
    devices_output="$(adb devices)" || {
        echo "adb devices failed: refusing device selection" >&2
        exit 2
    }
    attached_serials=()
    while read -r serial state _; do
        if [[ "$state" == "device" && -n "$serial" ]]; then
            attached_serials+=("$serial")
        fi
    done <<< "$devices_output"
    [[ "${#attached_serials[@]}" -eq 1 ]] || {
        echo "Connect exactly one authorized physical Android device, or set ADB_SERIAL" >&2
        exit 2
    }
    adb_command+=(-s "${attached_serials[0]}")
fi
# Comparing text inside [[ ... ]] discards the command-substitution exit
# status. Require a successful adb response as well as the exact state.
device_state="$("${adb_command[@]}" get-state | tr -d '\r\n')" || {
    echo "adb get-state failed: refusing device selection" >&2
    exit 2
}
[[ "$device_state" == "device" ]] || {
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

install_output="$("${adb_command[@]}" install -r "$apk" 2>&1)" || {
    echo "adb install failed; installation was not verified" >&2
    exit 1
}
# Older adb variants can return zero when the package manager reports failure.
# Require a genuine Success line, not merely command exit status.
if ! printf '%s\n' "$install_output" | tr -d '\r' | grep -qx 'Success'; then
    echo "adb did not report successful installation: refusing acceptance" >&2
    exit 1
fi
# Consume all of dumpsys output: grep -m 1 can close the pipe early, causing
# a healthy adb producer to receive SIGPIPE under pipefail. Multiple distinct
# package versions are ambiguous and must not certify the installed APK.
installed_version="$("${adb_command[@]}" shell dumpsys package io.github.gamesbyian.chunks |
    grep -o 'versionName=[^[:space:]]*' | cut -d = -f 2 | sort -u)"
expected_version="0.1.0-dev+${built_commit:0:12}"
[[ "$installed_version" == "$expected_version" ]] || {
    echo "Installed Chunks version does not match the built checkout: refusing to record success" >&2
    exit 1
}
launch_output="$("${adb_command[@]}" shell am start -W -n io.github.gamesbyian.chunks/.MainActivity 2>&1)" || {
    echo "Android activity launch command failed" >&2
    exit 1
}
# An activity-start Error may be printed even if the shell returns zero.
# Require both positive status and an exact Chunks MainActivity component.
launch_output="$(printf '%s' "$launch_output" | tr -d '\r')"
if ! grep -qx 'Status: ok' <<< "$launch_output" ||
   ! grep -Eq '^Activity: io\.github\.gamesbyian\.chunks/(\.MainActivity|io\.github\.gamesbyian\.chunks\.MainActivity)$' <<< "$launch_output"; then
    echo "Android did not confirm Chunks MainActivity launch: refusing acceptance" >&2
    exit 1
fi

printf 'CHUNKS_PHYSICAL_PREFLIGHT device_model=%s android=%s api=%s\n' "$model" "$release" "$sdk"
printf 'CHUNKS_PHYSICAL_PREFLIGHT source_checkout=%s apk_sha256=%s version=%s\n' \
    "$built_commit" "$actual_hash" "$installed_version"
echo "CHUNKS_PHYSICAL_PREFLIGHT installation and initial launch verified."
echo "NOT VERIFIED: protected Seedhost login, torrent data, DHT, seeding, Doze, FGS, SAF, accessibility or sustained lifecycle."
