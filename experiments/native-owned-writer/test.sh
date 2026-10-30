#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
c++ -std=c++17 -O2 -Wall -Wextra -Werror -pedantic \
    owned_payload_file.cc owned_payload_file_test.cc -o "$tmp/owned-payload-file-test"
"$tmp/owned-payload-file-test"
if [[ "$#" -gt 0 && "$1" == "--sanitizers" ]]; then
  c++ -std=c++17 -O1 -g -fno-omit-frame-pointer \
      -fsanitize=address,undefined \
      owned_payload_file.cc owned_payload_file_test.cc -o "$tmp/owned-payload-file-sanitizers"
  "$tmp/owned-payload-file-sanitizers"
fi
