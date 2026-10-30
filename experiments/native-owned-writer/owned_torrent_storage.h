#pragma once

#include "owned_payload_file.h"

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace chunks {

/**
 * Experimental multi-file piece-offset router, not a libtorrent disk_interface.
 * Fail closed and pre-open every destination before writing any part of a piece.
 * The caller's metadata authentication supplies path, length and commitments.
 */
class OwnedTorrentStorage final {
 public:
  struct FileSpec {
    std::string relative_path;
    std::uint64_t size;
    std::string receipt_id;
    std::string mapping_commitment;
  };
  enum class Admission { FRESH, RESTORED };
  static std::unique_ptr<OwnedTorrentStorage> Open(
      std::string payload_root, std::string private_receipts,
      std::vector<FileSpec> layout, Admission mode, std::string* error);

  bool WriteRange(std::uint64_t torrent_offset, const void* data,
                  size_t bytes, std::string* error);
  bool ReadRange(std::uint64_t torrent_offset, void* output,
                 size_t bytes, std::string* error);
  bool Flush(std::string* error);
  std::uint64_t size() const { return total_bytes_; }

 private:
  struct Slot {
    FileSpec spec;
    std::uint64_t start;
    std::unique_ptr<OwnedPayloadFile> owned;
  };
  OwnedTorrentStorage(std::string root, std::string receipts,
                      Admission mode, std::vector<Slot> slots,
                      std::uint64_t total_bytes);
  bool Preopen(std::uint64_t offset, size_t bytes, std::string* error);
  std::string root_;
  std::string receipts_;
  Admission mode_;
  std::vector<Slot> slots_;
  std::uint64_t total_bytes_;
};
}  // namespace chunks
