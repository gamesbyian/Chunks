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
"$compiler" -std=c++17 -O2 -pthread -Wall -Wextra -Werror -pedantic \
    bridge/checked_piece_range.cc bridge/checked_piece_range_test.cc \
    -o "$tmp/checked-piece-range-test"
"$tmp/checked-piece-range-test"
"$compiler" -std=c++17 -O2 -pthread -Wall -Wextra -Werror -pedantic \
    bridge/receipt_admission.cc bridge/receipt_admission_test.cc \
    -o "$tmp/receipt-admission-test"
"$tmp/receipt-admission-test"
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
  "$compiler" -std=c++17 -O1 -g -pthread -fno-omit-frame-pointer \
      -fsanitize=address,undefined \
      bridge/checked_piece_range.cc bridge/checked_piece_range_test.cc \
      -o "$tmp/checked-piece-range-sanitizers"
  "$tmp/checked-piece-range-sanitizers"
  "$compiler" -std=c++17 -O1 -g -pthread -fno-omit-frame-pointer \
      -fsanitize=address,undefined \
      bridge/receipt_admission.cc bridge/receipt_admission_test.cc \
      -o "$tmp/receipt-admission-sanitizers"
  "$tmp/receipt-admission-sanitizers"
fi
