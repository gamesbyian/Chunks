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
"$compiler" -std=c++17 -O2 -pthread -Wall -Wextra -Werror -pedantic \
    bridge/serial_native_io_queue.cc bridge/serial_native_io_queue_test.cc \
    -o "$tmp/serial-native-io-queue-test"
"$tmp/serial-native-io-queue-test"
"$tmp/owned-payload-file-test"
"$compiler" -std=c++17 -O2 -pthread -Wall -Wextra -Werror -pedantic \
    owned_payload_file.cc owned_reservation_race_test.cc \
    -Wl,--wrap=open -Wl,--wrap=openat -o "$tmp/owned-reservation-race-test"
"$tmp/owned-reservation-race-test"
# A real native writer must still claim and reopen its private receipts when
# Android forbids hardlinks; never treat compile-only NDK evidence as a pass.
"$compiler" -std=c++17 -O2 -pthread -Wall -Wextra -Werror -pedantic \
    owned_payload_file.cc owned_receipt_nohardlink_test.cc \
    -Wl,--wrap=linkat -o "$tmp/owned-receipt-nohardlink-test"
"$tmp/owned-receipt-nohardlink-test"
if [[ "$#" -gt 0 && "$1" == "--sanitizers" ]]; then
  "$compiler" -std=c++17 -O1 -g -pthread -fno-omit-frame-pointer \
      -fsanitize=address,undefined \
      owned_payload_file.cc owned_payload_file_test.cc -o "$tmp/owned-payload-file-sanitizers"
  "$tmp/owned-payload-file-sanitizers"
  "$compiler" -std=c++17 -O1 -g -pthread -fno-omit-frame-pointer \
      -fsanitize=address,undefined \
      owned_payload_file.cc owned_receipt_nohardlink_test.cc \
      -Wl,--wrap=linkat -o "$tmp/owned-receipt-nohardlink-sanitizers"
  "$tmp/owned-receipt-nohardlink-sanitizers"
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
  "$compiler" -std=c++17 -O1 -g -pthread -fno-omit-frame-pointer \
      -fsanitize=address,undefined \
      bridge/serial_native_io_queue.cc bridge/serial_native_io_queue_test.cc \
      -o "$tmp/serial-native-io-queue-sanitizers"
  "$tmp/serial-native-io-queue-sanitizers"
fi
