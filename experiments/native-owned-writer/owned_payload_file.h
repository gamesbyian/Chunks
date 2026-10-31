#pragma once

#include <cstdint>
#include <memory>
#include <string>

namespace chunks {

// Experimental native disk-writer boundary. Does not plug into libtorrent yet.
// All paths are relative to caller-owned, pinned directory descriptors.
class OwnedPayloadFile final {
 public:
  static std::unique_ptr<OwnedPayloadFile> Create(
      const std::string& payload_root, const std::string& relative_path,
      const std::string& private_receipt_root, const std::string& receipt_id,
      const std::string& commitment, std::string* error);
  // Durable prospective claim without an actual payload for skipped files.
  static bool Reserve(const std::string& payload_root,
                      const std::string& relative_path,
                      const std::string& private_receipt_root,
                      const std::string& receipt_id,
                      const std::string& commitment, std::string* error);
  // Validate an existing pending claim without creating directories,
  // modifying receipts, adopting physical bytes, or writing payload.
  static bool VerifyReservation(const std::string& payload_root,
                                const std::string& relative_path,
                                const std::string& private_receipt_root,
                                const std::string& receipt_id,
                                const std::string& commitment, std::string* error);
  static std::unique_ptr<OwnedPayloadFile> CreateFromReservation(
      const std::string& payload_root, const std::string& relative_path,
      const std::string& private_receipt_root, const std::string& receipt_id,
      const std::string& commitment, std::string* error);
  static std::unique_ptr<OwnedPayloadFile> Reopen(
      const std::string& payload_root, const std::string& relative_path,
      const std::string& private_receipt_root, const std::string& receipt_id,
      const std::string& commitment, std::string* error);

  OwnedPayloadFile(const OwnedPayloadFile&) = delete;
  OwnedPayloadFile& operator=(const OwnedPayloadFile&) = delete;
  ~OwnedPayloadFile();

  bool WriteAt(std::uint64_t offset, const void* bytes, size_t length,
               std::string* error);
  bool ReadAt(std::uint64_t offset, void* bytes, size_t length,
              std::string* error);
  bool Sync(std::string* error);

 private:
  static std::unique_ptr<OwnedPayloadFile> OpenImpl(
      const std::string& payload_root, const std::string& relative_path,
      const std::string& private_receipt_root, const std::string& receipt_id,
      const std::string& commitment, bool create, std::string* error);
  OwnedPayloadFile(int root_fd, int parent_fd, int file_fd,
                   std::string root_path, std::string relative_path, std::string leaf,
                   std::string owner_token, std::uint64_t dev, std::uint64_t ino,
                   std::uint64_t root_dev, std::uint64_t root_ino,
                   std::uint64_t parent_dev, std::uint64_t parent_ino);
  bool StillOwned(std::string* error) const;
  int root_fd_;
  int parent_fd_;
  int file_fd_;
  std::string root_path_;
  std::string relative_path_;
  std::string leaf_;
  std::string owner_token_;
  std::uint64_t dev_;
  std::uint64_t ino_;
  std::uint64_t root_dev_;
  std::uint64_t root_ino_;
  std::uint64_t parent_dev_;
  std::uint64_t parent_ino_;
};
}  // namespace chunks
