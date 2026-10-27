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
if [[ "${1:-}" == "-s" ]]; then shift 2; fi
case "${1:-}" in
    devices)
        printf 'List of devices attached\nTEST\tdevice\n'
        if [[ "${MOCK_MULTIPLE:-0}" == 1 ]]; then printf 'SECOND\tdevice\n'; fi
        ;;
    get-state) printf 'device\n' ;;
    install) : > "${MOCK_INSTALL_FILE:?}" ;;
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
                printf 'Package [io.github.gamesbyian.chunks]\nversionName=%s\n' \
                    "${MOCK_VERSION:-0.1.0-dev+0123456789ab}"
                ;;
            am) [[ "${2:-}" == "start" ]] ;;
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

output="$(bash "$repo/tools/physical_android_preflight.sh" "$apk")"
[[ -e "$MOCK_INSTALL_FILE" && "$output" == *"installation and initial launch verified"* ]]
[[ "$output" == *"NOT VERIFIED:"* ]]
rm "$MOCK_INSTALL_FILE"

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

printf 'tampered APK' >> "$apk"
if bash "$repo/tools/physical_android_preflight.sh" "$apk" >/dev/null 2>&1; then
    echo "Modified APK was installed" >&2
    exit 1
fi
[[ ! -e "$MOCK_INSTALL_FILE" ]]
echo "CHUNKS_PHYSICAL_PREFLIGHT_TEST: verified success and fail-closed emulator, multi-device and tampered APK"
