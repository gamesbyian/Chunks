#include "receipt_admission.h"

#include <cerrno>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace chunks {
namespace {
struct ScopedFd {
  explicit ScopedFd(int fd) : value(fd) {}
  ~ScopedFd() { if (value >= 0) ::close(value); }
  int value;
};
bool ValidId(std::string const& id) {
  if (id.size() != 64) return false;
  for (char c : id)
    if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
  return true;
}
enum class Presence { NO, YES, UNKNOWN };
Presence Exists(int root, std::string const& path) {
  struct stat st{};
  if (::fstatat(root, path.c_str(), &st, AT_SYMLINK_NOFOLLOW) == 0)
    return Presence::YES; // parser later refuses a corrupt file or symlink
  return errno == ENOENT ? Presence::NO : Presence::UNKNOWN;
}
} // namespace

NativeAdmissionMode ClassifyNativeAdmission(
    const std::string& private_receipts,
    const std::vector<OwnedTorrentStorage::FileSpec>& layout,
    std::string* error) {
  ScopedFd root(::open(private_receipts.c_str(),
                       O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
  struct stat privacy{};
  if (root.value < 0 || ::fstat(root.value, &privacy) != 0 ||
      !S_ISDIR(privacy.st_mode) || privacy.st_uid != ::geteuid() ||
      (privacy.st_mode & 0077) != 0) {
    if (error) *error = "Private receipt directory missing or accessible by another UID";
    return NativeAdmissionMode::REFUSED;
  }
  std::size_t claimed = 0;
  std::size_t finalized = 0;
  std::size_t eligible = 0;
  for (auto const& file : layout) {
    if (file.pad_file || file.size == 0) continue;
    ++eligible;
    if (!ValidId(file.receipt_id)) {
      if (error) *error = "Unsafe private native receipt name";
      return NativeAdmissionMode::REFUSED;
    }
    auto const final = Exists(root.value, file.receipt_id);
    auto const pending = Exists(root.value, file.receipt_id + ".pending");
    if (final == Presence::UNKNOWN || pending == Presence::UNKNOWN) {
      if (error) *error = "Cannot inspect native receipt inventory";
      return NativeAdmissionMode::REFUSED;
    }
    if (final == Presence::YES || pending == Presence::YES) ++claimed;
    if (final == Presence::YES) ++finalized;
  }
  if (eligible == 0) {
    if (error) *error = "Torrent has no physical backing files";
    return NativeAdmissionMode::REFUSED;
  }
  if (claimed == eligible) {
    if (error) error->clear();
    return NativeAdmissionMode::RESTORED;
  }
  if (claimed == 0 || finalized == 0) {
    // An interrupted zero-progress Add can leave *some* pending path claims.
    // It is safe to retry a FRESH reservation pass: Reserve checks every
    // existing pending receipt against the exact path, parent and mapping,
    // and O_EXCL still refuses any subsequently planted physical file.
    // Never use this path once even one finalized physical receipt exists.
    if (error) error->clear();
    return NativeAdmissionMode::FRESH;
  }
  if (error) *error = "Incomplete native receipt inventory includes finalized payload; manual recovery required";
  return NativeAdmissionMode::REFUSED;
}
} // namespace chunks
