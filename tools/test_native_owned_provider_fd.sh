#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
build="$(mktemp -d)"
trap 'rm -rf "$build"' EXIT
compiler="${CXX:-c++}"
"$compiler" -std=c++17 -O2 -Wall -Wextra -Werror -pedantic \
  tools/native_owned_provider_fd.cc tools/native_owned_provider_fd_test.cc \
  -o "$build/provider-fd"
"$build/provider-fd" | tee "$build/result.log"
grep -Fxq 'CHUNKS_PINNED_PROVIDER_FD=PASS cases=9 tier=host-linux-no-android-provider' "$build/result.log"
if [[ "${1:-}" == "--sanitizers" ]]; then
  "$compiler" -std=c++17 -O1 -g -fno-omit-frame-pointer \
    -Wall -Wextra -Werror -fsanitize=address,undefined \
    tools/native_owned_provider_fd.cc tools/native_owned_provider_fd_test.cc \
    -o "$build/provider-fd-sanitized"
  "$build/provider-fd-sanitized" | tee "$build/sanitized.log"
  grep -Fxq 'CHUNKS_PINNED_PROVIDER_FD=PASS cases=9 tier=host-linux-no-android-provider' "$build/sanitized.log"
fi
