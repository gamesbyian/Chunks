#include "checked_piece_range.h"

#include <limits>

namespace chunks {
namespace {
bool Refuse(std::string* error, const char* detail) {
  if (error) *error = detail;
  return false;
}
}

bool DecodePieceRange(std::uint64_t total_bytes, std::uint32_t piece_length,
                      std::int64_t piece_index, std::int64_t start,
                      std::int64_t length, bool is_write,
                      CheckedPieceRange* result, std::string* error) {
  if (result == nullptr || total_bytes == 0 || piece_length == 0 ||
      piece_index < 0 || start < 0 || length <= 0)
    return Refuse(error, "Invalid native piece or transfer geometry");
  const std::uint64_t piece = static_cast<std::uint64_t>(piece_index);
  const std::uint64_t in_piece = static_cast<std::uint64_t>(start);
  const std::uint64_t bytes = static_cast<std::uint64_t>(length);
  if (in_piece >= piece_length || bytes > piece_length - in_piece ||
      bytes > std::numeric_limits<std::uint32_t>::max())
    return Refuse(error, "Native request crosses its piece boundary");
  if (piece > (std::numeric_limits<std::uint64_t>::max() - in_piece) / piece_length)
    return Refuse(error, "Native piece address overflows");
  const std::uint64_t absolute = piece * piece_length + in_piece;
  if (absolute >= total_bytes || bytes > total_bytes - absolute)
    return Refuse(error, "Native request exceeds actual payload length");
  if (is_write && (in_piece % 16384u != 0 || bytes > 16384u))
    return Refuse(error, "Unaligned or oversized native peer write block");
  *result = {absolute, static_cast<std::uint32_t>(bytes)};
  if (error) error->clear();
  return true;
}
}  // namespace chunks
