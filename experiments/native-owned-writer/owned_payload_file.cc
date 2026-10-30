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
constexpr char kMagic[] = "CHUNKS-OWNED-PAYLOAD-V2";
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

bool ParentPath(int root, const std::vector<std::string>& parts, bool create,
                Fd* parent, std::string* err) {
  Fd current(::dup(root));
  if (current.v < 0) return Fail(err, "Could not pin payload root");
  for (size_t i = 0; i + 1 < parts.size(); ++i) {
    int next = ::openat(current.v, parts[i].c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (next < 0 && create && errno == ENOENT) {
      if (::mkdirat(current.v, parts[i].c_str(), 0700) != 0 && errno != EEXIST)
        return Fail(err, "Cannot create payload parent");
      next = ::openat(current.v, parts[i].c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
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
  std::uint64_t root_dev, root_ino, parent_dev, parent_ino, dev, ino;
  std::string owner_token;
};

std::string Encode(const Receipt& v) {
  std::ostringstream os;
  os << kMagic << '\n' << v.commitment << '\n' << v.root_dev << '\n' << v.root_ino << '\n'
     << v.parent_dev << '\n' << v.parent_ino << '\n' << v.dev << '\n' << v.ino << '\n'
     << v.owner_token << '\n';
  return os.str();
}

bool Parse(const std::string& s, Receipt* out) {
  std::istringstream is(s);
  std::string magic, commitment;
  Receipt result{};
  if (!std::getline(is, magic) || magic != kMagic || !std::getline(is, commitment) ||
      commitment.size() != 64) return false;
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
  // Receipt staging uses an exclusive random O_EXCL file under a pinned dirfd.
  // /dev/urandom random bytes avoid temp collisions; not an ownership secret.
  Fd random(::open("/dev/urandom", O_RDONLY | O_CLOEXEC));
  if (random.v < 0) return Fail(err, "Cannot generate private receipt staging identity");
  const char* alphabet = "0123456789abcdef";
  for (int attempt = 0; attempt < 8; ++attempt) {
    unsigned char bytes[16];
    if (::read(random.v, bytes, sizeof(bytes)) != sizeof(bytes))
      return Fail(err, "Cannot generate private receipt staging identity");
    std::string temp = ".tmp-";
    for (unsigned char byte : bytes) { temp += alphabet[byte >> 4]; temp += alphabet[byte & 15]; }
    Fd stage(::openat(dir, temp.c_str(), O_CREAT | O_EXCL | O_WRONLY | O_NOFOLLOW | O_CLOEXEC, 0600));
    if (stage.v < 0) { if (errno == EEXIST) continue; return Fail(err, "Receipt stage creation failed"); }
    const bool wrote = FullyWrite(stage.v, Encode(receipt));
    if (!wrote) { ::unlinkat(dir, temp.c_str(), 0); return Fail(err, "Receipt stage sync failed"); }
    // Link-to-claim refuses to replace an existing receipt, unlike rename().
    const bool linked = ::linkat(dir, temp.c_str(), dir, id.c_str(), 0) == 0;
    // Never remove a published claim on uncertain parent fsync; manual review.
    if (linked && ::fsync(dir) != 0) {
      ::unlinkat(dir, temp.c_str(), 0);
      return Fail(err, "Receipt parent sync failed; claim may exist and must be reviewed");
    }
    ::unlinkat(dir, temp.c_str(), 0); // only this exclusive staging file
    if (!linked) return Fail(err, "Receipt already claimed or publication unsupported");
    return true;
  }
  return Fail(err, "Receipt staging collisions exhausted");
}

bool LoadReceipt(int dir, const std::string& id, Receipt* receipt, std::string* err) {
  Fd fd(::openat(dir, id.c_str(), O_RDONLY | O_NOFOLLOW | O_CLOEXEC));
  if (fd.v < 0) return Fail(err, "No durable owned-file receipt");
  struct stat st{};
  if (!S_ISREG((::fstat(fd.v, &st) == 0) ? st.st_mode : 0) || st.st_size <= 0 || st.st_size > 512)
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
    const std::string& commitment, bool create, std::string* error) {
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
  struct stat rs{}, ps{}, fs{};
  if (!GetDirectory(root_fd.v, &rs)) return reject("Invalid payload root");
  Fd parent;
  if (!ParentPath(root_fd.v, parts, create, &parent, error) || !GetDirectory(parent.v, &ps))
    return nullptr;
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
  Receipt record{commitment, static_cast<uint64_t>(rs.st_dev), static_cast<uint64_t>(rs.st_ino),
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
    if (saved.commitment != record.commitment || saved.root_dev != record.root_dev ||
        saved.root_ino != record.root_ino || saved.parent_dev != record.parent_dev ||
        saved.parent_ino != record.parent_ino || saved.dev != record.dev || saved.ino != record.ino)
      return reject("Payload identity differs from durable ownership receipt");
  }
  std::unique_ptr<OwnedPayloadFile> opened(new OwnedPayloadFile(root_fd.release(),
      parent.release(), file.release(), root, relative, parts.back(),
      record.owner_token, record.dev, record.ino,
      record.root_dev, record.root_ino, record.parent_dev, record.parent_ino));
  if (!opened->StillOwned(error)) return nullptr;
  return opened;
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
  struct stat rs{}, ps{}, foreign{};
  if (!GetDirectory(root_fd.v, &rs)) return Fail(error, "Invalid prospective root");
  Fd parent;
  if (!ParentPath(root_fd.v, parts, true, &parent, error) ||
      !GetDirectory(parent.v, &ps)) return false;
  // Never reserve already-existing physical bytes, even identical-sized files.
  if (::fstatat(parent.v, parts.back().c_str(), &foreign, AT_SYMLINK_NOFOLLOW) == 0 ||
      errno != ENOENT) return Fail(error, "Prospective payload path already occupied or uncertain");
  Receipt target{commitment, static_cast<uint64_t>(rs.st_dev),
      static_cast<uint64_t>(rs.st_ino), static_cast<uint64_t>(ps.st_dev),
      static_cast<uint64_t>(ps.st_ino), 0, 0, kUncreatedToken};
  const std::string pending = id + ".pending";
  Receipt existing{};
  std::string ignored;
  if (LoadReceipt(receipt_fd.v, pending, &existing, &ignored)) {
    return (existing.commitment == target.commitment &&
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
  if (!ValidId(id) || !ValidId(commitment)) {
    Fail(error, "Invalid saved reservation identity");
    return nullptr;
  }
  Fd receipt_fd(::open(receipts.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
  Receipt pending{};
  if (receipt_fd.v < 0 || !LoadReceipt(receipt_fd.v, id + ".pending", &pending, error))
    return nullptr;
  if (pending.commitment != commitment || pending.dev != 0 || pending.ino != 0 ||
      pending.owner_token != kUncreatedToken ||
      !Reserve(root, relative, receipts, id, commitment, error)) return nullptr;
  // Reserve validated the original identity and absence. O_EXCL rechecks
  // physical absence at the actual writer open, never trusting observation.
  return Create(root, relative, receipts, id, commitment, error);
}

std::unique_ptr<OwnedPayloadFile> OwnedPayloadFile::Create(
    const std::string& root, const std::string& rel, const std::string& receipts,
    const std::string& id, const std::string& mapping, std::string* error) {
  return OpenImpl(root, rel, receipts, id, mapping, true, error);
}

std::unique_ptr<OwnedPayloadFile> OwnedPayloadFile::Reopen(
    const std::string& root, const std::string& rel, const std::string& receipts,
    const std::string& id, const std::string& mapping, std::string* error) {
  return OpenImpl(root, rel, receipts, id, mapping, false, error);
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
