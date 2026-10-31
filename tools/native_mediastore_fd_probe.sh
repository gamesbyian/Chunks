#!/usr/bin/env bash
# #568 opt-in, disposable emulator MediaStore owned-file descriptor evidence.
# The emulator runner's "script" input expects ONE executable command, not
# a multiline YAML shell program (which it splits into independent sh calls).
set -euo pipefail
api="${1:?expected 29 or 35}"
[[ "$api" == 29 || "$api" == 35 ]] || {
  echo "CHUNKS_MEDIASTORE_FD: unsupported requested API $api" >&2
  exit 1
}
observed="$(adb shell getprop ro.build.version.sdk | tr -d '\r\n')"
[[ "$observed" == "$api" ]] || {
  echo "CHUNKS_MEDIASTORE_FD: expected API $api got $observed" >&2
  exit 1
}
install_checked() {
  local output
  output="$(adb install -r "$1" 2>&1)" || {
    echo "CHUNKS_MEDIASTORE_FD: adb install command failed" >&2
    return 1
  }
  if ! grep -qx 'Success' <<< "$output" || grep -qi 'Failure' <<< "$output"; then
    echo "CHUNKS_MEDIASTORE_FD: package manager did not confirm install: $output" >&2
    return 1
  fi
}
install_checked app/build/outputs/apk/debug/app-debug.apk
install_checked app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
log="$(mktemp)"
trap 'rm -f "$log"' EXIT
set +e
adb shell am instrument -w -r \
  -e class io.github.gamesbyian.chunks.engine.OwnedMediaStoreDescriptorInstrumentedTest \
  io.github.gamesbyian.chunks.test/androidx.test.runner.AndroidJUnitRunner >"$log" 2>&1
status=$?
set -e
cat "$log"
[[ "$status" -eq 0 ]] || {
  echo "CHUNKS_MEDIASTORE_FD: instrumentation command failed" >&2
  exit 1
}
grep -q '^OK (2 tests)' "$log" || {
  echo "CHUNKS_MEDIASTORE_FD: exact two executed tests not confirmed" >&2
  exit 1
}
if grep -qE '^INSTRUMENTATION_STATUS_CODE: -(1|2|3|4)|^FAILURES|^Tests run: .*Failures: [1-9]' "$log"; then
  echo "CHUNKS_MEDIASTORE_FD: failing/skipped tests cannot be accepted" >&2
  exit 1
fi
echo "CHUNKS_MEDIASTORE_OWNED_FD_PROBE=PASS api=$api seekable-fsync-reopen-publish"
