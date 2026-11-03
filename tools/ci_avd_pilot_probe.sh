#!/usr/bin/env bash
# One atomic script for reactivecircus/android-emulator-runner@v2.
# The action executes each YAML script line in a separate /bin/sh -c process.
# Keep assertions here so failed ADB reads and identity mismatches fail closed.
set -euo pipefail

if (( $# != 1 )); then
  echo 'CHUNKS_AVD_PILOT: invalid probe arguments' >&2
  exit 2
fi
case "$1" in
  pristine|measure) phase="$1" ;;
  *)
    echo 'CHUNKS_AVD_PILOT: invalid probe phase' >&2
    exit 2
    ;;
esac

sdk="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
abi="$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
ready="$(adb shell getprop sys.boot_completed | tr -d '\r')"
packages="$(adb shell pm list packages)"

if [[ "$sdk" != 35 || "$abi" != x86_64 || "$ready" != 1 ]]; then
  echo 'CHUNKS_AVD_PILOT: wrong SDK, ABI or incomplete boot' >&2
  exit 1
fi
if [[ -z "$packages" || "$packages" != *"package:"* ]]; then
  echo 'CHUNKS_AVD_PILOT: package inventory missing or invalid' >&2
  exit 1
fi
# Compare entire package records, stripping only CR from Android shell output.
# Avoid a grep -q pipeline whose early close can mask producer errors.
while IFS= read -r item; do
  item="${item%
printf 'CHUNKS_AVD_PILOT: phase=%s api=35 abi=x86_64 clean=verified tests=none\n' "$phase"
\r'}"
  if [[ "$item" == 'package:io.github.gamesbyian.chunks' ]]; then
    echo 'CHUNKS_AVD_PILOT: app unexpectedly installed on clean virtual device' >&2
    exit 1
  fi
done <<< "$packages"
printf 'CHUNKS_AVD_PILOT: phase=%s api=35 abi=x86_64 clean=verified tests=none\n' "$phase"
