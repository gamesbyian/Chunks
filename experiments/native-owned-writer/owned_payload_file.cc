#include "owned_payload_file.h"

#include <array>
#include <cerrno>
#include <climits>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <memory>
#include <sstream>
#include <string>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/xattr.h>
#include <unistd.h>
#include <vector>

namespace chunks {
namespace {
constexpr char kMagic[] = "CHUNKS-OWNED-PAYLOAD-V3";
constexpr char kOwnerXattr[] = "user.chunks.owned-payload";
constexpr char kUncreatedToken[] =
    "0000000000000000000000000000000000000000000000000000000000000000";

struct Fd {
  explicit Fd(int x = -1) : v(x) {}
  ~Fd() { if (v >= 0) ::close(v); }
  Fd(const Fd&) = delete;
  Fd& operator=(const Fd&) = delete;
  int release() { int old = v; v = -1; return old; }
  int v;
};

bool Fail(std::string* err, const char* reason) {
  if (err) *err = reason;
  return false;
}

bool IsSafeName(const std::string& s) {
  if (s.empty() || s == "." || s == ".." || s.size() > 255) return false;
  for (char c : s) if (c == '/' || c == '\\' || c == '\0' || static_cast<unsigned char>(c) < 32) return false;
  return true;
}

bool ValidRelative(const std::string& s, std::vector<std::string>* parts) {
  if (s.empty() || s.size() > 4096 || s[0] == '/') return false;
  size_t from = 0;
  for (;;) {
    size_t end = s.find('/', from);
    std::string part = s.substr(from, end == std::string::npos ? end : end - from);
    if (!IsSafeName(part)) return false;
    parts->push_back(part);
    if (end == std::string::npos) return true;
    from = end + 1;
  }
}

bool GetDirectory(int fd, struct stat* st) {
  return ::fstat(fd, st) == 0 && S_ISDIR(st->st_mode);
}

bool GetFile(int fd, struct stat* st) {
  return ::fstat(fd, st) == 0 && S_ISREG(st->st_mode) && st->st_nlink == 1;
}

bool PrivateReceiptRoot(int fd, std::string* error) {
  struct stat st{};
  if (::fstat(fd, &st) != 0 || !S_ISDIR(st.st_mode) ||
      st.st_uid != ::geteuid() || (st.st_mode & 0077) != 0)
    return Fail(error, "Native receipt root must be private to the app UID");
  return true;
}

bool ParentPath(int root, const std::vector<std::string>& parts, bool create,
                Fd* parent, std::string* err) {
  Fd current(::dup(root));
  if (current.v < 0) return Fail(err, "Could not pin payload root");
  for (size_t i = 0; i + 1 < parts.size(); ++i) {
    int next = ::openat(current.v, parts[i].c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (next < 0 && create && errno == ENOENT) {
      const int made = ::mkdirat(current.v, parts[i].c_str(), 0700);
      if (made != 0 && errno != EEXIST)
        return Fail(err, "Cannot create payload parent");
      next = ::openat(current.v, parts[i].c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
      // Ensure every newly made ancestor exists durably before the private
      // receipt claims its path. Never silently downgrade unsupported fsync.
      if (made == 0 && ::fsync(current.v) != 0)
        return Fail(err, "Payload parent directory sync unsupported");
    }
    if (next < 0) return Fail(err, "Payload parent unavailable or indirect");
    ::close(current.release());
    current.v = next;
  }
  parent->v = current.release();
  return true;
}

struct Receipt {
  std::string commitment;
  std::string relative_path;
  std::uint64_t root_dev, root_ino, parent_dev, parent_ino, dev, ino;
  std::string owner_token;
};

std::string Encode(const Receipt& v) {
  std::ostringstream os;
  os << kMagic << '\n' << v.commitment << '\n' << v.relative_path << '\n' << v.root_dev << '\n' << v.root_ino << '\n'
     << v.parent_dev << '\n' << v.parent_ino << '\n' << v.dev << '\n' << v.ino << '\n'
     << v.owner_token << '\n';
  return os.str();
}

bool Parse(const std::string& s, Receipt* out) {
  std::istringstream is(s);
  std::string magic, commitment;
  Receipt result{};
  if (!std::getline(is, magic) || magic != kMagic ||
      !std::getline(is, commitment) || commitment.size() != 64 ||
      !std::getline(is, result.relative_path)) return false;
  std::vector<std::string> segments;
  if (!ValidRelative(result.relative_path, &segments)) return false;
  for (char c : commitment) if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
  if (!(is >> result.root_dev >> result.root_ino >> result.parent_dev >> result.parent_ino
           >> result.dev >> result.ino >> result.owner_token)) return false;
  if (!(is >> std::ws).eof()) return false;
  if (result.owner_token.size() != 64) return false;
  for (char c : result.owner_token)
    if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
  result.commitment = commitment;
  *out = result;
  return true;
}

bool ValidId(const std::string& id) {
  if (id.size() != 64) return false;
  for (char c : id) if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
  return true;
}

// Fresh identity survives inode recycling. Public/SD filesystems that do not
// support persistent user xattrs are *not* eligible for direct native writes;
// they require separately verified non-replacing publication from staging.
std::string RandomOwnerToken() {
  Fd random(::open("/dev/urandom", O_RDONLY | O_CLOEXEC));
  if (random.v < 0) return {};
  std::array<unsigned char, 32> bytes{};
  size_t done = 0;
  while (done < bytes.size()) {
    ssize_t count = ::read(random.v, bytes.data() + done, bytes.size() - done);
    if (count < 0 && errno == EINTR) continue;
    if (count <= 0) return {};
    done += static_cast<size_t>(count);
  }
  const char* alphabet = "0123456789abcdef";
  std::string token;
  token.reserve(64);
  for (auto byte : bytes) {
    token.push_back(alphabet[byte >> 4]);
    token.push_back(alphabet[byte & 15]);
  }
  return token;
}

bool ReadOwnerToken(int fd, std::string* token) {
  std::array<char, 64> bytes{};
  ssize_t count = ::fgetxattr(fd, kOwnerXattr, bytes.data(), bytes.size());
  if (count != static_cast<ssize_t>(bytes.size())) return false;
  token->assign(bytes.begin(), bytes.end());
  return token->size() == 64;
}

bool FullyWrite(int fd, const std::string& content) {
  size_t off = 0;
  while (off < content.size()) {
    ssize_t count = ::write(fd, content.data() + off, content.size() - off);
    if (count <= 0) return false;
    off += static_cast<size_t>(count);
  }
  return ::fsync(fd) == 0;
}

bool PublishReceipt(int dir, const std::string& id, const Receipt& receipt, std::string* err) {
  // A named temporary file is not an unforgeable owner: another same-UID
  // actor may replace its pathname after O_EXCL and before linkat/unlinkat.
  // Prefer an anonymous O_TMPFILE FD. Publication links THAT descriptor,
  // never a mutable source name; closing it discards failed staging without
  // unlinking an uncertain path. On filesystems without this capability,
  // exclusively create the FINAL receipt itself and leave incomplete bytes
  // for explicit fail-closed recovery after an interrupted write.
  const std::string encoded = Encode(receipt);
#if defined(O_TMPFILE) && defined(AT_EMPTY_PATH)
  Fd stage(::openat(dir, ".", O_TMPFILE | O_WRONLY | O_CLOEXEC, 0600));
  if (stage.v >= 0) {
    if (!FullyWrite(stage.v, encoded))
      return Fail(err, "Anonymous receipt stage write or sync failed");
    if (::linkat(stage.v, "", dir, id.c_str(), AT_EMPTY_PATH) == 0) {
      // The original pinned inode must be the one named by the published
      // claim. A concurrent same-UID replacement can only force refusal.
      struct stat pinned{}, named{};
      const bool same_owner = ::fstat(stage.v, &pinned) == 0 &&
          ::fstatat(dir, id.c_str(), &named, AT_SYMLINK_NOFOLLOW) == 0 &&
          S_ISREG(pinned.st_mode) && S_ISREG(named.st_mode) &&
          pinned.st_nlink == 1 && named.st_nlink == 1 &&
          pinned.st_dev == named.st_dev && pinned.st_ino == named.st_ino;
      if (!same_owner || ::fsync(dir) != 0)
        return Fail(err, "Anonymous receipt publication uncertain; manual recovery required");
      return true;
    }
    // ENOENT is seen on kernels that refuse linking an anonymous inode;
    // EPERM/EACCES often reflect private-filesystem hardlink restrictions.
    // EEXIST is NEVER a fallback invitation: a different owner won.
    const int link_error = errno;
    if (link_error != EPERM && link_error != EACCES &&
        link_error != EOPNOTSUPP && link_error != ENOSYS &&
        link_error != EINVAL && link_error != ENOENT)
      return Fail(err, "Anonymous receipt publication refused or already claimed");
  } else {
    const int stage_error = errno;
    if (stage_error != EPERM && stage_error != EACCES &&
        stage_error != EOPNOTSUPP && stage_error != ENOSYS &&
        stage_error != EINVAL && stage_error != EISDIR &&
        stage_error != ENOENT)
      return Fail(err, "Anonymous receipt staging failed unexpectedly");
  }
#endif
  // Always no-replace and no-follow. Never reopen the final name for
  // mutation and never delete it on uncertain write/sync/publication.
  Fd finalized(::openat(dir, id.c_str(),
      O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600));
  if (finalized.v < 0)
    return Fail(err, "Exclusive final receipt already exists or cannot be created");
  if (!FullyWrite(finalized.v, encoded))
    return Fail(err, "Exclusive receipt write or sync uncertain; manual recovery required");
  struct stat pinned{}, named{};
  const bool same_owner = ::fstat(finalized.v, &pinned) == 0 &&
      ::fstatat(dir, id.c_str(), &named, AT_SYMLINK_NOFOLLOW) == 0 &&
      S_ISREG(pinned.st_mode) && S_ISREG(named.st_mode) &&
      pinned.st_nlink == 1 && named.st_nlink == 1 &&
      pinned.st_dev == named.st_dev && pinned.st_ino == named.st_ino;
  if (!same_owner || ::fsync(dir) != 0)
    return Fail(err, "Exclusive receipt publication uncertain; manual recovery required");
  return true;
}

bool LoadReceipt(int dir, const std::string& id, Receipt* receipt, std::string* err) {
  Fd fd(::openat(dir, id.c_str(), O_RDONLY | O_NOFOLLOW | O_CLOEXEC));
  if (fd.v < 0) return Fail(err, "No durable owned-file receipt");
  struct stat st{};
  if (!S_ISREG((::fstat(fd.v, &st) == 0) ? st.st_mode : 0) || st.st_size <= 0 || st.st_size > 8192)
    return Fail(err, "Owned-file receipt type or size invalid");
  std::string content(static_cast<size_t>(st.st_size), '\0');
  if (::read(fd.v, content.data(), content.size()) != static_cast<ssize_t>(content.size()) ||
      !Parse(content, receipt)) return Fail(err, "Owned-file receipt corrupt");
  return true;
}

}  // anonymous namespace

std::unique_ptr<OwnedPayloadFile> OwnedPayloadFile::OpenImpl(
    const std::string& root, const std::string& relative,
    const std::string& receipts, const std::string& id,
    const std::string& commitment, bool create, bool require_reservation,
    std::string* error) {
  auto reject = [error](const char* why) -> std::unique_ptr<OwnedPayloadFile> {
    Fail(error, why);
    return nullptr;
  };
  if (!ValidId(id) || !ValidId(commitment)) return reject("Invalid receipt identity or transfer mapping");
  std::vector<std::string> parts;
  if (!ValidRelative(relative, &parts)) return reject("Unsafe payload relative path");
  Fd root_fd(::open(root.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
  Fd receipt_fd(::open(receipts.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
  if (root_fd.v < 0 || receipt_fd.v < 0) return reject("Payload or private receipt root unavailable");
  if (!PrivateReceiptRoot(receipt_fd.v, error)) return nullptr;
  // Existing or unreadable app-private ownership claims must be resolved
  // before touching a new public payload path. Publication is still the
  // authoritative no-replace check after O_EXCL creation.
  if (create) {
    struct stat claimed{};
    if (::fstatat(receipt_fd.v, id.c_str(), &claimed, AT_SYMLINK_NOFOLLOW) == 0 ||
        errno != ENOENT) return reject("Native ownership receipt already exists or is uncertain");
  }
  struct stat rs{}, ps{}, fs{};
  if (!GetDirectory(root_fd.v, &rs)) return reject("Invalid payload root");
  Fd parent;
  if (!ParentPath(root_fd.v, parts, create && !require_reservation, &parent, error) ||
      !GetDirectory(parent.v, &ps))
    return nullptr;
  if (require_reservation) {
    // Bind the saved reservation to the very directory descriptors used by
    // openat below. A prior path survey cannot authorize a later re-open: the
    // selected root or its parent may have been replaced between them.
    Receipt pending{};
    if (!LoadReceipt(receipt_fd.v, id + ".pending", &pending, error)) return nullptr;
    if (pending.commitment != commitment || pending.relative_path != relative ||
        pending.root_dev != static_cast<std::uint64_t>(rs.st_dev) ||
        pending.root_ino != static_cast<std::uint64_t>(rs.st_ino) ||
        pending.parent_dev != static_cast<std::uint64_t>(ps.st_dev) ||
        pending.parent_ino != static_cast<std::uint64_t>(ps.st_ino) ||
        pending.dev != 0 || pending.ino != 0 || pending.owner_token != kUncreatedToken)
      return reject("Pending ownership identity does not match pinned destination");
  }
  Fd file(::openat(parent.v, parts.back().c_str(),
                   (create ? (O_RDWR | O_CREAT | O_EXCL) : O_RDWR) | O_NOFOLLOW | O_CLOEXEC, 0600));
  if (file.v < 0 || !GetFile(file.v, &fs))
    return reject("Payload file not exclusively owned or unavailable");
  std::string owner_token;
  if (create) {
    owner_token = RandomOwnerToken();
    if (owner_token.empty()) return reject("Cannot generate native file identity");
    if (::fsetxattr(file.v, kOwnerXattr, owner_token.data(),
                    owner_token.size(), XATTR_CREATE) != 0)
      return reject("Native file ownership xattrs unsupported; direct write refused");
  } else {
    if (!ReadOwnerToken(file.v, &owner_token))
      return reject("Native file owner token unavailable; reopen refused");
  }
  Receipt record{commitment, relative, static_cast<uint64_t>(rs.st_dev), static_cast<uint64_t>(rs.st_ino),
                 static_cast<uint64_t>(ps.st_dev), static_cast<uint64_t>(ps.st_ino),
                 static_cast<uint64_t>(fs.st_dev), static_cast<uint64_t>(fs.st_ino),
                 owner_token};
  if (create) {
    if (::fsync(file.v) != 0 || ::fsync(parent.v) != 0 ||
        !PublishReceipt(receipt_fd.v, id, record, error))
      return reject("Unconfirmed owned-file creation; orphan left intact");
  } else {
    Receipt saved{};
    if (!LoadReceipt(receipt_fd.v, id, &saved, error)) return nullptr;
    if (saved.commitment != record.commitment ||
        saved.relative_path != record.relative_path || saved.root_dev != record.root_dev ||
        saved.root_ino != record.root_ino || saved.parent_dev != record.parent_dev ||
        saved.parent_ino != record.parent_ino || saved.dev != record.dev ||
        saved.ino != record.ino || saved.owner_token != record.owner_token)
      return reject("Payload identity differs from durable ownership receipt");
  }
  std::unique_ptr<OwnedPayloadFile> opened(new OwnedPayloadFile(root_fd.release(),
      parent.release(), file.release(), root, relative, parts.back(),
      record.owner_token, record.dev, record.ino,
      record.root_dev, record.root_ino, record.parent_dev, record.parent_ino));
  if (!opened->StillOwned(error)) return nullptr;
  return opened;
}

bool OwnedPayloadFile::VerifyReservation(
    const std::string& root, const std::string& relative,
    const std::string& receipts, const std::string& id,
    const std::string& commitment, std::string* error) {
  if (!ValidId(id) || !ValidId(commitment))
    return Fail(error, "Invalid pending ownership identity");
  std::vector<std::string> parts;
  if (!ValidRelative(relative, &parts))
    return Fail(error, "Unsafe pending payload path");
  Fd root_fd(::open(root.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
  Fd receipt_fd(::open(receipts.c_str(),
                       O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
  if (root_fd.v < 0 || receipt_fd.v < 0 ||
      !PrivateReceiptRoot(receipt_fd.v, error))
    return Fail(error, "Pending receipt root missing or untrusted");
  struct stat published{};
  if (::fstatat(receipt_fd.v, id.c_str(), &published, AT_SYMLINK_NOFOLLOW) == 0 ||
      errno != ENOENT)
    return Fail(error, "Pending owner also has a finalized physical receipt");
  Receipt pending{};
  if (!LoadReceipt(receipt_fd.v, id + ".pending", &pending, error))
    return false;
  struct stat rs{}, ps{}, occupied{};
  Fd parent;
  if (!GetDirectory(root_fd.v, &rs) ||
      !ParentPath(root_fd.v, parts, false, &parent, error) ||
      !GetDirectory(parent.v, &ps))
    return Fail(error, "Pending root or parent path no longer exists");
  if (::fstatat(parent.v, parts.back().c_str(), &occupied,
                AT_SYMLINK_NOFOLLOW) == 0 || errno != ENOENT)
    return Fail(error, "Foreign file now occupies pending payload path");
  return (pending.commitment == commitment &&
      pending.relative_path == relative &&
      pending.root_dev == static_cast<std::uint64_t>(rs.st_dev) &&
      pending.root_ino == static_cast<std::uint64_t>(rs.st_ino) &&
      pending.parent_dev == static_cast<std::uint64_t>(ps.st_dev) &&
      pending.parent_ino == static_cast<std::uint64_t>(ps.st_ino) &&
      pending.dev == 0 && pending.ino == 0 &&
      pending.owner_token == kUncreatedToken) ||
      Fail(error, "Pending ownership identity does not match destination");
}

bool OwnedPayloadFile::Reserve(
    const std::string& root, const std::string& relative,
    const std::string& receipts, const std::string& id,
    const std::string& commitment, std::string* error) {
  if (!ValidId(id) || !ValidId(commitment))
    return Fail(error, "Invalid prospective ownership identity");
  std::vector<std::string> parts;
  if (!ValidRelative(relative, &parts))
    return Fail(error, "Unsafe prospective payload path");
  Fd root_fd(::open(root.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
  Fd receipt_fd(::open(receipts.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
  if (root_fd.v < 0 || receipt_fd.v < 0)
    return Fail(error, "Prospective payload or receipt root inaccessible");
  if (!PrivateReceiptRoot(receipt_fd.v, error)) return false;
  // A previously completed physical claim must never be downgraded to a
  // fresh zero-progress reservation after the file disappeared or was moved.
  // Existing data recovery is a separate explicit verify/adopt operation.
  struct stat committed{};
  if (::fstatat(receipt_fd.v, id.c_str(), &committed, AT_SYMLINK_NOFOLLOW) == 0 ||
      errno != ENOENT)
    return Fail(error, "Previously published ownership may not be re-reserved");
  struct stat rs{}, ps{}, foreign{};
  if (!GetDirectory(root_fd.v, &rs)) return Fail(error, "Invalid prospective root");
  Fd parent;
  if (!ParentPath(root_fd.v, parts, true, &parent, error) ||
      !GetDirectory(parent.v, &ps)) return false;
  // Never reserve already-existing physical bytes, even identical-sized files.
  if (::fstatat(parent.v, parts.back().c_str(), &foreign, AT_SYMLINK_NOFOLLOW) == 0 ||
      errno != ENOENT) return Fail(error, "Prospective payload path already occupied or uncertain");
  Receipt target{commitment, relative, static_cast<uint64_t>(rs.st_dev),
      static_cast<uint64_t>(rs.st_ino), static_cast<uint64_t>(ps.st_dev),
      static_cast<uint64_t>(ps.st_ino), 0, 0, kUncreatedToken};
  const std::string pending = id + ".pending";
  Receipt existing{};
  std::string ignored;
  if (LoadReceipt(receipt_fd.v, pending, &existing, &ignored)) {
    return (existing.commitment == target.commitment &&
        existing.relative_path == target.relative_path &&
        existing.root_dev == target.root_dev && existing.root_ino == target.root_ino &&
        existing.parent_dev == target.parent_dev && existing.parent_ino == target.parent_ino &&
        existing.dev == 0 && existing.ino == 0 &&
        existing.owner_token == kUncreatedToken) ||
        Fail(error, "Prospective ownership changed since reservation");
  }
  // If a pending receipt exists but cannot be read, it is untrusted, not free.
  struct stat marker{};
  if (::fstatat(receipt_fd.v, pending.c_str(), &marker, AT_SYMLINK_NOFOLLOW) == 0 ||
      errno != ENOENT) return Fail(error, "Prospective receipt is corrupt or inaccessible");
  return PublishReceipt(receipt_fd.v, pending, target, error);
}

std::unique_ptr<OwnedPayloadFile> OwnedPayloadFile::CreateFromReservation(
    const std::string& root, const std::string& relative,
    const std::string& receipts, const std::string& id,
    const std::string& commitment, std::string* error) {
  return OpenImpl(root, relative, receipts, id, commitment, true, true, error);
}

std::unique_ptr<OwnedPayloadFile> OwnedPayloadFile::Create(
    const std::string& root, const std::string& rel, const std::string& receipts,
    const std::string& id, const std::string& mapping, std::string* error) {
  return OpenImpl(root, rel, receipts, id, mapping, true, false, error);
}

std::unique_ptr<OwnedPayloadFile> OwnedPayloadFile::Reopen(
    const std::string& root, const std::string& rel, const std::string& receipts,
    const std::string& id, const std::string& mapping, std::string* error) {
  return OpenImpl(root, rel, receipts, id, mapping, false, false, error);
}

OwnedPayloadFile::OwnedPayloadFile(int root, int parent, int file,
    std::string root_path, std::string relative_path, std::string leaf,
    std::string owner_token, std::uint64_t dev, std::uint64_t ino, std::uint64_t root_dev,
    std::uint64_t root_ino, std::uint64_t parent_dev, std::uint64_t parent_ino)
    : root_fd_(root), parent_fd_(parent), file_fd_(file), root_path_(std::move(root_path)),
      relative_path_(std::move(relative_path)), leaf_(std::move(leaf)),
      owner_token_(std::move(owner_token)), dev_(dev), ino_(ino), root_dev_(root_dev), root_ino_(root_ino),
      parent_dev_(parent_dev), parent_ino_(parent_ino) {}

OwnedPayloadFile::~OwnedPayloadFile() {
  ::close(file_fd_);
  ::close(parent_fd_);
  ::close(root_fd_);
}

bool OwnedPayloadFile::StillOwned(std::string* err) const {
  // Re-evaluate the named path as well as pinned descriptors: a renamed root
  // or intermediate directory cannot count as writes to the selected Place.
  Fd named_root(::open(root_path_.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
  std::vector<std::string> parts;
  Fd named_parent;
  struct stat actual_root{}, actual_parent{};
  if (named_root.v < 0 || !ValidRelative(relative_path_, &parts) ||
      !GetDirectory(named_root.v, &actual_root) ||
      !ParentPath(named_root.v, parts, false, &named_parent, err) ||
      !GetDirectory(named_parent.v, &actual_parent) ||
      static_cast<std::uint64_t>(actual_root.st_dev) != root_dev_ ||
      static_cast<std::uint64_t>(actual_root.st_ino) != root_ino_ ||
      static_cast<std::uint64_t>(actual_parent.st_dev) != parent_dev_ ||
      static_cast<std::uint64_t>(actual_parent.st_ino) != parent_ino_)
    return Fail(err, "Native destination root or parent mapping changed");
  struct stat root{}, parent{}, pinned{}, path{};
  if (::fstat(root_fd_, &root) != 0 || ::fstat(parent_fd_, &parent) != 0 ||
      !GetFile(file_fd_, &pinned) ||
      ::fstatat(parent_fd_, leaf_.c_str(), &path, AT_SYMLINK_NOFOLLOW) != 0 ||
      !S_ISREG(path.st_mode) || path.st_nlink != 1 ||
      static_cast<std::uint64_t>(root.st_dev) != root_dev_ ||
      static_cast<std::uint64_t>(root.st_ino) != root_ino_ ||
      static_cast<std::uint64_t>(parent.st_dev) != parent_dev_ ||
      static_cast<std::uint64_t>(parent.st_ino) != parent_ino_ ||
      static_cast<std::uint64_t>(pinned.st_dev) != dev_ ||
      static_cast<std::uint64_t>(pinned.st_ino) != ino_ ||
      static_cast<std::uint64_t>(path.st_dev) != dev_ ||
      static_cast<std::uint64_t>(path.st_ino) != ino_)
    return Fail(err, "Pinned native file no longer matches its owned path");
  // A recycled inode with the same dev+ino is not durable ownership.
  std::string actual_token;
  if (!ReadOwnerToken(file_fd_, &actual_token) || actual_token != owner_token_)
    return Fail(err, "Native filesystem creation identity changed");
  return true;
}

bool OwnedPayloadFile::WriteAt(std::uint64_t offset, const void* input,
                                size_t length, std::string* error) {
  if (offset > LLONG_MAX || length > static_cast<std::uint64_t>(LLONG_MAX) - offset)
    return Fail(error, "Native write offset out of bounds");
  if (!StillOwned(error)) return false;
  const char* bytes = static_cast<const char*>(input);
  size_t complete = 0;
  while (complete < length) {
    ssize_t n = ::pwrite(file_fd_, bytes + complete, length - complete,
                         static_cast<off_t>(offset + complete));
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) return Fail(error, "Pinned native write failed");
    complete += static_cast<size_t>(n);
  }
  return StillOwned(error);
}

bool OwnedPayloadFile::ReadAt(std::uint64_t offset, void* output,
                               size_t length, std::string* error) {
  if (offset > LLONG_MAX || length > static_cast<std::uint64_t>(LLONG_MAX) - offset)
    return Fail(error, "Native read offset out of bounds");
  if (!StillOwned(error)) return false;
  char* bytes = static_cast<char*>(output);
  size_t complete = 0;
  while (complete < length) {
    ssize_t n = ::pread(file_fd_, bytes + complete, length - complete,
                        static_cast<off_t>(offset + complete));
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) return Fail(error, "Pinned native read failed or incomplete");
    complete += static_cast<size_t>(n);
  }
  return StillOwned(error);
}

bool OwnedPayloadFile::Sync(std::string* error) {
  return StillOwned(error) &&
         (::fsync(file_fd_) == 0 || Fail(error, "Owned native file sync failed"));
}
}  // namespace chunks
