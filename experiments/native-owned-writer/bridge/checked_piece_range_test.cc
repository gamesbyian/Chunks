#include "checked_piece_range.h"

#include <cassert>
#include <cstdint>
#include <iostream>
#include <limits>
#include <string>

using chunks::CheckedPieceRange;
using chunks::DecodePieceRange;

int main() {
  int passed = 0;
  auto allow = [&](std::uint64_t total, std::uint32_t piece,
                   std::int64_t index, std::int64_t start,
                   std::int64_t length, bool write, std::uint64_t offset) {
    CheckedPieceRange output{};
    std::string error;
    assert(DecodePieceRange(total, piece, index, start, length, write, &output, &error));
    assert(output.absolute_offset == offset);
    assert(output.length == static_cast<std::uint64_t>(length));
    assert(error.empty());
    ++passed;
  };
  auto deny = [&](std::uint64_t total, std::uint32_t piece,
                  std::int64_t index, std::int64_t start,
                  std::int64_t length, bool write) {
    CheckedPieceRange output{123, 45};
    std::string error;
    assert(!DecodePieceRange(total, piece, index, start, length, write, &output, &error));
    assert(!error.empty());
    assert(output.absolute_offset == 123 && output.length == 45);
    ++passed;
  };
  allow(50000, 16384, 0, 0, 16384, true, 0);
  allow(50000, 16384, 3, 0, 848, true, 49152);
  allow(50000, 16384, 2, 1, 25, false, 32769);
  allow(50000, 16384, 2, 16383, 1, false, 49151);
  deny(50000, 16384, -1, 0, 1, false);
  deny(50000, 16384, 0, -1, 1, false);
  deny(50000, 16384, 0, 0, 0, false);
  deny(0, 16384, 0, 0, 1, false);
  deny(50000, 0, 0, 0, 1, false);
  deny(50000, 16384, 3, 0, 849, true);
  deny(50000, 16384, 0, 16383, 2, false);
  deny(50000, 16384, 0, 1, 1, true);
  deny(50000, 32768, 0, 0, 32768, true);
  deny(50000, 16384, 100000, 0, 1, true);
  deny(50000, 16384, std::numeric_limits<std::int64_t>::max(), 0, 1, false);
  deny(50000, 16384, 0, 16384, 1, false);
  std::cout << "CHUNKS_CHECKED_PIECE_RANGES=" << passed << " PASSED\n";
}
