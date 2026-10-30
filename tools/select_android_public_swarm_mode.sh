#!/usr/bin/env bash
# Pure selector for the manual public-swarm acceptance run. Never silently
# discard simultaneous requested experiments or interpolate arbitrary paths.
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo 'CHUNKS_SWARM_MODE: expected interrupt, doze and netflap booleans' >&2
  exit 2
fi

count=0
for value in "$@"; do
  case "$value" in
    true) ((count += 1)) ;;
    false) ;;
    *) echo 'CHUNKS_SWARM_MODE: expected literal true or false' >&2; exit 2 ;;
  esac
done

if (( count > 1 )); then
  echo 'CHUNKS_SWARM_MODE: interrupt, doze and netflap are mutually exclusive' >&2
  exit 2
fi

case "$1:$2:$3" in
  false:false:false) script=android_public_swarm.sh ;;
  true:false:false) script=android_public_swarm_interrupt.sh ;;
  false:true:false) script=android_public_swarm_doze.sh ;;
  false:false:true) script=android_public_swarm_netflap.sh ;;
  *) echo 'CHUNKS_SWARM_MODE: no valid exclusive selection' >&2; exit 2 ;;
esac
printf '%s\n' "$script"
