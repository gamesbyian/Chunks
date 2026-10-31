#!/usr/bin/env bash
# Focused host-only G0 #568 nonclobber process restart test.
set -euo pipefail
cd "$(dirname "$0")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
compiler="${CXX:-c++}"
"$compiler" -std=c++17 -O2 -pthread -Wall -Wextra -Werror -pedantic \
  experiments/native-owned-writer/owned_payload_file.cc \
  tools/native_owned_process_restart_test.cc \
  -o "$out/native-owned-process-restart"
log="$out/process-restore.log"
"$out/native-owned-process-restart" | tee "$log"
grep -Fxq 'CHUNKS_OWNED_PROCESS_RESTART=PASS cases=7 tier=linux-fork-no-powerloss' "$log"
if [[ "${1:-}" == "--sanitizers" ]]; then
  "$compiler" -std=c++17 -O1 -g -fno-omit-frame-pointer \
    -pthread -Wall -Wextra -Werror \
    -fsanitize=address,undefined \
    experiments/native-owned-writer/owned_payload_file.cc \
    tools/native_owned_process_restart_test.cc \
    -o "$out/native-owned-process-restart-asan"
  "$out/native-owned-process-restart-asan" | tee "$out/sanitizer.log"
  grep -Fxq 'CHUNKS_OWNED_PROCESS_RESTART=PASS cases=7 tier=linux-fork-no-powerloss' "$out/sanitizer.log"
fi
