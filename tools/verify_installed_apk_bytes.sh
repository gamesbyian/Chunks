#!/usr/bin/env bash
# Exact installed APK byte proof after Android reports the expected package/version.
# Safe output only: never echo the installed path, local path or adb diagnostics.
set -euo pipefail

if [[ "$#" -ne 3 ]]; then
  printf '%s\n' 'CHUNKS_INSTALLED_APK: invalid-invocation' >&2
  exit 64
fi

expected="$1"
device_path="$2"
pulled="$3"
if [[ ! -s "$expected" || -z "$device_path" || -z "$pulled" || "$expected" == "$pulled" ]]; then
  printf '%s\n' 'CHUNKS_INSTALLED_APK: missing-or-invalid-input' >&2
  exit 1
fi

# The runner must not retain the pulled APK after either outcome.
trap 'rm -f -- "$pulled"' EXIT
if ! adb pull "$device_path" "$pulled" >/dev/null 2>&1; then
  printf '%s\n' 'CHUNKS_INSTALLED_APK: adb-pull-failed' >&2
  exit 1
fi
if cmp -s -- "$expected" "$pulled"; then
  exit 0
else
  status=$?
  if [[ "$status" -eq 1 ]]; then
    printf '%s\n' 'CHUNKS_INSTALLED_APK: byte-mismatch' >&2
  else
    printf '%s\n' 'CHUNKS_INSTALLED_APK: comparison-unavailable' >&2
  fi
  exit 1
fi
