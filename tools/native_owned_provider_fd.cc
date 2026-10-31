#include "native_owned_provider_fd.h"

#include <cerrno>
#include <climits>
#include <fcntl.h>
#include <limits>
#include <sys/stat.h>
#include <unistd.h>
#include <utility>

namespace chunks::research {
namespace {
bool Refuse(std::string* error, const char* why) {
  if (error) *error = why;
  return false;
}
bool ValidGeneration(const std::string& token) {
  if (token.size() != 64) return false;
  for (char c : token) {
    if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
  }
  return true;
}
}  // namespace

PinnedProviderFd::PinnedProviderFd(int fd, std::uint64_t limit,
                                   std::uint64_t device, std::uint64_t inode,
                                   std::string generation)
    : fd_(fd), declared_bytes_(limit), device_(device),
      inode_(inode), generation_(std::move(generation)) {}

PinnedProviderFd::~PinnedProviderFd() {
  if (fd_ >= 0) ::close(fd_);
}

std::unique_ptr<PinnedProviderFd> PinnedProviderFd::Bind(
    int fd, std::uint64_t declared_bytes, const std::string& generation,
    std::string* error) {
  auto reject = [error](const char* why) -> std::unique_ptr<PinnedProviderFd> {
    Refuse(error, why);
    return nullptr;
  };
  if (fd < 0 || !ValidGeneration(generation))
    return reject("Missing exact 64-hex already-journaled generation");
  if (declared_bytes > static_cast<std::uint64_t>(LLONG_MAX))
    return reject("Declared length exceeds native seekable range");

  // Duplication pins the supplied descriptor's *open file description* even
  // if Android closes/recycles its original integer immediately afterward.
  // No canonical path, realpath, /proc/self/fd target or xattr is trusted.
  const int pinned = ::fcntl(fd, F_DUPFD_CLOEXEC, 0);
  if (pinned < 0) return reject("Could not pin provider file descriptor");

  const int flags = ::fcntl(pinned, F_GETFL);
  struct stat st{};
  if (flags < 0 || (flags & O_ACCMODE) != O_RDWR ||
      (flags & O_APPEND) != 0 || ::fstat(pinned, &st) != 0 ||
      !S_ISREG(st.st_mode) || ::lseek(pinned, 0, SEEK_CUR) < 0) {
    ::close(pinned);
    return reject("Provider descriptor must be seekable read/write regular FD without append");
  }
  // Android provider FD st_dev/st_ino may be FUSE-local. Treat these as an
  // in-process pin check only, NOT durable document or URI identity.
  return std::unique_ptr<PinnedProviderFd>(new PinnedProviderFd(
      pinned, declared_bytes, static_cast<std::uint64_t>(st.st_dev),
      static_cast<std::uint64_t>(st.st_ino), generation));
}

bool PinnedProviderFd::Pinned(std::string* error) const {
  struct stat st{};
  if (::fstat(fd_, &st) != 0 || !S_ISREG(st.st_mode) ||
      static_cast<std::uint64_t>(st.st_dev) != device_ ||
      static_cast<std::uint64_t>(st.st_ino) != inode_)
    return Refuse(error, "Pinned descriptor identity changed");
  const int flags = ::fcntl(fd_, F_GETFL);
  if (flags < 0 || (flags & O_ACCMODE) != O_RDWR || (flags & O_APPEND) != 0)
    return Refuse(error, "Pinned descriptor is no longer read/write and seekable");
  return true;
}

bool PinnedProviderFd::Range(std::uint64_t offset, std::size_t length,
                             std::string* error) const {
  if (offset > declared_bytes_ || length > declared_bytes_ - offset ||
      length > static_cast<std::size_t>(LLONG_MAX) - offset)
    return Refuse(error, "Requested I/O exceeds journaled file length or off_t");
  return true;
}

bool PinnedProviderFd::WriteAt(std::uint64_t offset, const void* input,
                               std::size_t length, std::string* error) {
  if (!Range(offset, length, error) || !Pinned(error)) return false;
  if (length != 0 && input == nullptr) return Refuse(error, "Null write buffer");
  auto* bytes = static_cast<const char*>(input);
  std::size_t done = 0;
  while (done < length) {
    const ssize_t n = ::pwrite(fd_, bytes + done, length - done,
                              static_cast<off_t>(offset + done));
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) return Refuse(error, "Pinned provider pwrite failed");
    done += static_cast<std::size_t>(n);
  }
  return Pinned(error);
}

bool PinnedProviderFd::ReadAt(std::uint64_t offset, void* output,
                              std::size_t length, std::string* error) {
  if (!Range(offset, length, error) || !Pinned(error)) return false;
  if (length != 0 && output == nullptr) return Refuse(error, "Null read buffer");
  auto* bytes = static_cast<char*>(output);
  std::size_t done = 0;
  while (done < length) {
    const ssize_t n = ::pread(fd_, bytes + done, length - done,
                             static_cast<off_t>(offset + done));
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) return Refuse(error, "Pinned provider pread failed or incomplete");
    done += static_cast<std::size_t>(n);
  }
  return Pinned(error);
}

bool PinnedProviderFd::Sync(std::string* error) {
  return Pinned(error) && (::fsync(fd_) == 0 ||
      Refuse(error, "Pinned provider fsync failed"));
}
}  // namespace chunks::research
