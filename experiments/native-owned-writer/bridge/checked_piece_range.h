#pragma once

#include <cstdint>
#include <string>

namespace chunks {

// Validate libtorrent peer_request (piece index, within-piece start and
// length) BEFORE mapping to a native file offset. No IO or receipt mutation.
struct CheckedPieceRange {
  std::uint64_t absolute_offset;
  std::uint32_t length;
};

bool DecodePieceRange(std::uint64_t total_bytes, std::uint32_t piece_length,
                      std::int64_t piece_index, std::int64_t start,
                      std::int64_t length, bool is_write,
                      CheckedPieceRange* result, std::string* error);
}  // namespace chunks
