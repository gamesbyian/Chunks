#pragma once

// G0 #568 EXPERIMENT ONLY, no libtorrent or Android JNI integration.
// An already-authorized, opened, read/write provider FD can be pinned and
// used without ever reopening a caller-supplied filename.
//
// CRITICAL: a file descriptor does NOT PROVE ownership. Before calling Bind,
// the Android adapter must exclusively mint the document, verify platform
// collection/volume, exact owner and URI identity, journal the mapping with
// crash durability, then keep that access authorization valid. A forged or
// substituted document passed by the caller cannot be detected by this class.
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>

namespace chunks::research {

class PinnedProviderFd final {
 public:
  static std::unique_ptr<PinnedProviderFd> Bind(
      int already_authorized_fd, std::uint64_t declared_bytes,
      const std::string& journal_generation_token, std::string* error);
  PinnedProviderFd(const PinnedProviderFd&) = delete;
  PinnedProviderFd& operator=(const PinnedProviderFd&) = delete;
  ~PinnedProviderFd();

  bool WriteAt(std::uint64_t offset, const void* input, std::size_t count,
               std::string* error);
  bool ReadAt(std::uint64_t offset, void* output, std::size_t count,
              std::string* error);
  bool Sync(std::string* error);
  std::uint64_t DeclaredBytes() const { return declared_bytes_; }

 private:
  PinnedProviderFd(int fd, std::uint64_t limit, std::uint64_t device,
                   std::uint64_t inode, std::string generation);
  bool Pinned(std::string* error) const;
  bool Range(std::uint64_t offset, std::size_t length, std::string* error) const;

  int fd_;
  std::uint64_t declared_bytes_;
  std::uint64_t device_, inode_;
  const std::string generation_;
};

} // namespace chunks::research
