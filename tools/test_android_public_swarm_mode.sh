#!/usr/bin/env bash
# Offline negative controls for manual peer/Doze/interruption test selection.
# No device, peer, Android SDK, remote host or GitHub Actions runner required.
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd -P)"
selector="$root/select_android_public_swarm_mode.sh"
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT
bash -n "$selector"
bash -n "$root/test_android_public_swarm_mode.sh"

accept() {
  local expected="$1" result
  shift
  result="$(bash "$selector" "$@")" || {
    echo "Expected an accepted selection: $*" >&2; exit 1;
  }
  [[ "$result" == "$expected" ]] || {
    echo "Expected $expected, got $result for: $*" >&2; exit 1;
  }
}
refuse() {
  if bash "$selector" "$@" > "$temp/stdout" 2> "$temp/stderr"; then
    echo "Invalid mode selection was accepted: $*" >&2; exit 1
  fi
  [[ ! -s "$temp/stdout" ]] || {
    echo "Refused selection leaked an executable script path: $*" >&2; exit 1;
  }
  grep -q '^CHUNKS_SWARM_MODE:' "$temp/stderr" || {
    echo "Refused selection lacked a diagnostic: $*" >&2; exit 1;
  }
}

accept android_public_swarm.sh false false false
accept android_public_swarm_interrupt.sh true false false
accept android_public_swarm_doze.sh false true false
accept android_public_swarm_netflap.sh false false true
refuse true true false
refuse true false true
refuse false true true
refuse true true true
refuse false false
refuse false false false false
refuse TRUE false false
refuse '' false false
refuse '../evil.sh' false false

echo 'CHUNKS_SWARM_MODE_TEST: default and three exclusive modes accepted; all competing, malformed and missing inputs refused'
