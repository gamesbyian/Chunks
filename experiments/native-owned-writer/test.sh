#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
compiler="${CXX:-c++}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
"$compiler" -std=c++17 -O2 -pthread -Wall -Wextra -Werror -pedantic \
    owned_payload_file.cc owned_payload_file_test.cc -o "$tmp/owned-payload-file-test"
"$compiler" -std=c++17 -O2 -pthread -Wall -Wextra -Werror -pedantic \
    owned_payload_file.cc owned_torrent_storage.cc owned_torrent_storage_test.cc \
    -o "$tmp/owned-torrent-storage-test"
"$tmp/owned-torrent-storage-test"
"$tmp/owned-payload-file-test"
if [[ "$#" -gt 0 && "$1" == "--sanitizers" ]]; then
  "$compiler" -std=c++17 -O1 -g -pthread -fno-omit-frame-pointer \
      -fsanitize=address,undefined \
      owned_payload_file.cc owned_payload_file_test.cc -o "$tmp/owned-payload-file-sanitizers"
  "$tmp/owned-payload-file-sanitizers"
  "$compiler" -std=c++17 -O1 -g -pthread -fno-omit-frame-pointer \
      -fsanitize=address,undefined \
      owned_payload_file.cc owned_torrent_storage.cc owned_torrent_storage_test.cc \
      -o "$tmp/owned-torrent-storage-sanitizers"
  "$tmp/owned-torrent-storage-sanitizers"
fi
