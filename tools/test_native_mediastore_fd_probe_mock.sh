#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd -P)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin"
cat > "$tmp/bin/adb" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
case "$*" in
  "shell getprop ro.build.version.sdk")
    [[ "${MOCK_GETPROP_FAIL:-0}" != 1 ]] || exit 21
    echo "${MOCK_API:-29}" ;;
  "install -r "*)
    [[ "${MOCK_INSTALL_FAIL:-0}" != 1 ]] || exit 22
    [[ "${MOCK_INSTALL_PRINT_FAILURE:-0}" != 1 ]] ||
      { echo 'Failure [INSTALL_FAILED]'; exit 0; }
    echo Success ;;
  "shell am instrument -w -r "*)
    [[ "${MOCK_INSTRUMENT_FAIL:-0}" != 1 ]] || exit 23
    [[ "$*" == *"OwnedMediaStoreDescriptorInstrumentedTest"* ]] || exit 24
    if [[ "${MOCK_SKIP:-0}" == 1 ]]; then
      echo 'INSTRUMENTATION_STATUS_CODE: -3'
    fi
    if [[ "${MOCK_FAIL_TEST:-0}" == 1 ]]; then
      echo 'FAILURES!!!'
      echo 'Tests run: 2, Failures: 1'
    else
      echo 'OK (2 tests)'
    fi ;;
  *) echo "unexpected adb: $*" >&2; exit 25 ;;
esac
MOCK
chmod +x "$tmp/bin/adb"
export PATH="$tmp/bin:$PATH"
bash -n "$root/native_mediastore_fd_probe.sh"
output="$(MOCK_API=29 bash "$root/native_mediastore_fd_probe.sh" 29)"
[[ "$output" == *"CHUNKS_MEDIASTORE_OWNED_FD_PROBE=PASS api=29"* ]]
output="$(MOCK_API=35 bash "$root/native_mediastore_fd_probe.sh" 35)"
[[ "$output" == *"CHUNKS_MEDIASTORE_OWNED_FD_PROBE=PASS api=35"* ]]
for fixture in \
  'MOCK_GETPROP_FAIL=1' \
  'MOCK_INSTALL_FAIL=1' \
  'MOCK_INSTALL_PRINT_FAILURE=1' \
  'MOCK_INSTRUMENT_FAIL=1' \
  'MOCK_SKIP=1' \
  'MOCK_FAIL_TEST=1' \
  'MOCK_API=35'; do
  if env $fixture bash "$root/native_mediastore_fd_probe.sh" 29 >"$tmp/failure.log" 2>&1; then
    echo "CHUNKS_MEDIASTORE_FD_MOCK: unsafe fixture passed: $fixture" >&2
    exit 1
  fi
done
if bash "$root/native_mediastore_fd_probe.sh" 31 >"$tmp/failure.log" 2>&1; then
  echo 'CHUNKS_MEDIASTORE_FD_MOCK: unsupported API accepted' >&2
  exit 1
fi
echo 'CHUNKS_MEDIASTORE_FD_MOCK=PASS valid29+35 and 8 negative controls'
