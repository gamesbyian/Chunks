#!/usr/bin/env bash
# Local dependency-free checks using a fake adb. Does not need a handset.
set -euo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT
mkdir -p "$temp/bin" "$temp/build"
apk="$temp/build/chunks-debug.apk"
receipt="$temp/build/CHUNKS-BUILD-SHA256.txt"
printf 'chunks synthetic debug apk bytes\n' > "$apk"
commit="0123456789abcdef0123456789abcdef01234567"
(cd "$temp/build" && sha256sum chunks-debug.apk) > "$receipt"
printf '# built_commit=%s\n# workflow_run=test\n' "$commit" >> "$receipt"

cat > "$temp/bin/adb" <<'MOCK_ADB'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "-s" ]]; then
    [[ "${2:-}" == "TEST" ]] || { echo "Wrong ADB serial" >&2; exit 1; }
    shift 2
elif [[ "${1:-}" != "devices" && "${MOCK_REQUIRE_SERIAL:-0}" == 1 ]]; then
    echo "Unscoped ADB call" >&2
    exit 1
fi
case "${1:-}" in
    devices)
        printf 'List of devices attached\nTEST\tdevice\n'
        if [[ "${MOCK_MULTIPLE:-0}" == 1 ]]; then printf 'SECOND\tdevice\n'; fi
        ;;
    get-state) printf 'device\n' ;;
    install)
        if [[ "${MOCK_INSTALL_FAILURE_TEXT:-0}" == 1 ]]; then
            printf 'Failure [INSTALL_FAILED_INVALID_APK]\n'
        else
            : > "${MOCK_INSTALL_FILE:?}"
            printf 'Performing Streamed Install\nSuccess\n'
        fi
        ;;
    shell)
        shift
        case "${1:-}" in
            getprop)
                case "${2:-}" in
                    ro.kernel.qemu|ro.boot.qemu) printf '%s\n' "${MOCK_QEMU:-0}" ;;
                    ro.build.version.sdk) printf '35\n' ;;
                    ro.build.version.release) printf '15\n' ;;
                    ro.product.model) printf 'Physical test fixture\n' ;;
                    *) exit 1 ;;
                esac
                ;;
            dumpsys)
                if [[ "${MOCK_DUMPSYS_MISSING:-0}" == 1 ]]; then
                    printf 'Package [io.github.gamesbyian.chunks]\n'
                else
                    printf 'Package [io.github.gamesbyian.chunks]\nversionName=%s\n' \
                        "${MOCK_VERSION:-0.1.0-dev+0123456789ab}"
                    if [[ "${MOCK_DUMPSYS_LARGE:-0}" == 1 ]]; then
                        # Force a late writer after the early matching line.
                        # grep -m1 abandons this producer with SIGPIPE.
                        sleep 0.02
                        for ((i=0; i<20000; i++)); do
                            printf 'package dump detail %d\n' "$i"
                        done
                    fi
                    if [[ "${MOCK_DUMPSYS_CONFLICT:-0}" == 1 ]]; then
                        printf 'versionName=0.0.0-conflicting\n'
                    fi
                fi
                if [[ "${MOCK_DUMPSYS_FAILURE:-0}" == 1 ]]; then exit 1; fi
                ;;
            am)
                [[ "${2:-}" == "start" ]] || exit 1
                case "${MOCK_LAUNCH_RESULT:-ok}" in
                    ok) printf 'Starting: Intent { cmp=io.github.gamesbyian.chunks/.MainActivity }\nStatus: ok\nActivity: io.github.gamesbyian.chunks/.MainActivity\nComplete\n' ;;
                    error) printf 'Error: Activity class does not exist\n' ;;
                    other) printf 'Status: ok\nActivity: another.package/.MainActivity\nComplete\n' ;;
                    *) exit 1 ;;
                esac
                ;;
            *) exit 1 ;;
        esac
        ;;
    *) exit 1 ;;
esac
MOCK_ADB
chmod +x "$temp/bin/adb"
export PATH="$temp/bin:$PATH" MOCK_INSTALL_FILE="$temp/installed"

bash -n "$repo/tools/physical_android_preflight.sh"
bash -n "$repo/tools/test_physical_android_preflight.sh"

output="$(MOCK_REQUIRE_SERIAL=1 bash "$repo/tools/physical_android_preflight.sh" "$apk")"
[[ -e "$MOCK_INSTALL_FILE" && "$output" == *"installation and initial launch verified"* ]]
[[ "$output" == *"NOT VERIFIED:"* ]]
rm "$MOCK_INSTALL_FILE"

# An explicitly selected serial must be honored; an invalid one must fail
# before installation even if another authorized device exists.
output="$(ADB_SERIAL=TEST MOCK_REQUIRE_SERIAL=1 bash "$repo/tools/physical_android_preflight.sh" "$apk")"
[[ -e "$MOCK_INSTALL_FILE" && "$output" == *"installation and initial launch verified"* ]]
rm "$MOCK_INSTALL_FILE"
if ADB_SERIAL=SECOND bash "$repo/tools/physical_android_preflight.sh" "$apk" >/dev/null 2>&1; then
    echo "Unknown explicit ADB serial was accepted" >&2
    exit 1
fi
[[ ! -e "$MOCK_INSTALL_FILE" ]]
if MOCK_QEMU=1 bash "$repo/tools/physical_android_preflight.sh" "$apk" >/dev/null 2>&1; then
    echo "Emulated device was accepted" >&2
    exit 1
fi
[[ ! -e "$MOCK_INSTALL_FILE" ]]

if MOCK_MULTIPLE=1 bash "$repo/tools/physical_android_preflight.sh" "$apk" >/dev/null 2>&1; then
    echo "Ambiguous device selection was accepted" >&2
    exit 1
fi
[[ ! -e "$MOCK_INSTALL_FILE" ]]

# adb may return exit code zero while reporting installation failure.
if MOCK_INSTALL_FAILURE_TEXT=1 bash "$repo/tools/physical_android_preflight.sh" "$apk" >/dev/null 2>&1; then
    echo "False-positive adb install was accepted" >&2
    exit 1
fi
[[ ! -e "$MOCK_INSTALL_FILE" ]]

# Some real package dumps are much larger than one pipe buffer. A reader that
# stops at versionName leaves adb writing into a closed pipe (SIGPIPE 141).
output="$(MOCK_DUMPSYS_LARGE=1 bash "$repo/tools/physical_android_preflight.sh" "$apk")"
[[ -e "$MOCK_INSTALL_FILE" && "$output" == *"installation and initial launch verified"* ]]
rm "$MOCK_INSTALL_FILE"

# Do not mistake a first good line for an authoritative version if a later
# line contradicts it, or if the package manager command itself failed.
for condition in MOCK_DUMPSYS_CONFLICT MOCK_DUMPSYS_FAILURE MOCK_DUMPSYS_MISSING; do
    if env "$condition=1" bash "$repo/tools/physical_android_preflight.sh" "$apk" >/dev/null 2>&1; then
        echo "Unreliable package-version evidence was accepted: $condition" >&2
        exit 1
    fi
    [[ -e "$MOCK_INSTALL_FILE" ]]
    rm "$MOCK_INSTALL_FILE"
done
# An installed package version mismatch must not become launch acceptance.
if MOCK_VERSION=0.1.0-dev+ffffffffffff bash "$repo/tools/physical_android_preflight.sh" "$apk" >/dev/null 2>&1; then
    echo "Mismatched installed APK version was accepted" >&2
    exit 1
fi
[[ -e "$MOCK_INSTALL_FILE" ]]
rm "$MOCK_INSTALL_FILE"

# `am start -W` may report an error with a successful shell exit status.
# An unrelated activity with Status: ok is also insufficient.
for outcome in error other; do
    if MOCK_LAUNCH_RESULT="$outcome" bash "$repo/tools/physical_android_preflight.sh" "$apk" >/dev/null 2>&1; then
        echo "False-positive activity launch was accepted: $outcome" >&2
        exit 1
    fi
    [[ -e "$MOCK_INSTALL_FILE" ]]
    rm "$MOCK_INSTALL_FILE"
done
printf 'tampered APK' >> "$apk"
if bash "$repo/tools/physical_android_preflight.sh" "$apk" >/dev/null 2>&1; then
    echo "Modified APK was installed" >&2
    exit 1
fi
[[ ! -e "$MOCK_INSTALL_FILE" ]]
echo "CHUNKS_PHYSICAL_PREFLIGHT_TEST: verified success and fail-closed serial selection, complete dumpsys output, install and launch evidence, version, emulator, multi-device and tampered APK"
