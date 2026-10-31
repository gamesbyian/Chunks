#include "owned_torrent_storage.h"

#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <limits>
#include <set>
#include <utility>

namespace chunks {
namespace {
bool Fail(std::string* error, const char* reason) {
  if (error) *error = reason;
  return false;
}
}

OwnedTorrentStorage::OwnedTorrentStorage(std::string root, std::string receipts,
    Admission mode, std::vector<Slot> slots, std::uint64_t total)
    : root_(std::move(root)), receipts_(std::move(receipts)), mode_(mode),
      slots_(std::move(slots)), total_bytes_(total) {}

std::unique_ptr<OwnedTorrentStorage> OwnedTorrentStorage::Open(
    std::string root, std::string receipts, std::vector<FileSpec> layout,
    Admission mode, std::string* error) {
  if (layout.empty() || layout.size() > 100000) {
    Fail(error, "Empty or oversized native file layout");
    return nullptr;
  }
  std::vector<Slot> slots;
  std::set<std::string> paths, ids;
  std::uint64_t total = 0;
  for (auto& file : layout) {
    if (std::numeric_limits<std::uint64_t>::max() - total < file.size ||
        (!file.pad_file &&
           (!paths.insert(file.relative_path).second ||
            !ids.insert(file.receipt_id).second))) {
      Fail(error, "Ambiguous duplicate, zero-size, or overflowing native layout");
      return nullptr;
    }
    slots.push_back(Slot{std::move(file), total, nullptr});
    total += slots.back().spec.size;
  }
  // A declared file cannot also be another file's parent; reject before
  // creating any private reservation or payload object.
  for (const auto& path : paths) {
    size_t slash = path.find('/');
    while (slash != std::string::npos) {
      if (paths.count(path.substr(0, slash)) != 0) {
        Fail(error, "Torrent declares a backing file as another file's parent");
        return nullptr;
      }
      slash = path.find('/', slash + 1);
    }
  }
  // Persist prospective file claims before a stopped Add crosses a process
  // boundary. Skipped/unwritten files have receipts but no payload bytes.
  if (mode == Admission::FRESH) {
    for (const auto& slot : slots) {
      if (slot.spec.pad_file || slot.spec.size == 0) continue;
      if (!OwnedPayloadFile::Reserve(root, slot.spec.relative_path, receipts,
              slot.spec.receipt_id, slot.spec.mapping_commitment, error)) return nullptr;
    }
  }
  return std::unique_ptr<OwnedTorrentStorage>(new OwnedTorrentStorage(
      std::move(root), std::move(receipts), mode, std::move(slots), total));
}

bool OwnedTorrentStorage::Preopen(std::uint64_t offset, size_t bytes,
                                  bool for_write, std::string* error) {
  if (offset > total_bytes_ || bytes > total_bytes_ - offset)
    return Fail(error, "Piece request exceeds declared torrent length");
  if (bytes == 0) return true;
  const auto last = offset + bytes;
  // Open all involved payloads before making any piece write: a collision
  // in file B must not leave file A with half a submitted native block.
  for (auto& slot : slots_) {
    if (slot.start >= last || slot.start + slot.spec.size <= offset) continue;
    if (slot.spec.pad_file || slot.owned) continue;
    const auto& s = slot.spec;
    // Seeding and verification reads never create payload or alter receipts.
    if (!for_write) {
      slot.owned = OwnedPayloadFile::Reopen(root_, s.relative_path, receipts_,
                        s.receipt_id, s.mapping_commitment, error);
    } else if (mode_ == Admission::FRESH) {
      slot.owned = OwnedPayloadFile::CreateFromReservation(root_, s.relative_path,
                        receipts_, s.receipt_id, s.mapping_commitment, error);
    } else {
      slot.owned = OwnedPayloadFile::Reopen(root_, s.relative_path, receipts_,
                        s.receipt_id, s.mapping_commitment, error);
      if (!slot.owned) slot.owned = OwnedPayloadFile::CreateFromReservation(
          root_, s.relative_path, receipts_, s.receipt_id, s.mapping_commitment, error);
    }
    if (!slot.owned) return false;
    // A successful restored CreateFromReservation can follow a failed
    // Reopen while the physical file was still absent. The earlier error
    // must never be reported as the result of the successful operation.
    if (error) error->clear();
  }
  return true;
}

bool OwnedTorrentStorage::WriteRange(std::uint64_t offset, const void* input,
                                     size_t bytes, std::string* error) {
  if (bytes && input == nullptr) return Fail(error, "Missing native write buffer");
  if (!Preopen(offset, bytes, true, error)) return false;
  const auto last = offset + bytes;
  auto* data = static_cast<const char*>(input);
  size_t done = 0;
  for (auto& slot : slots_) {
    if (slot.start >= last || slot.start + slot.spec.size <= offset) continue;
    const auto begin = offset > slot.start ? offset : slot.start;
    const auto end = last < slot.start + slot.spec.size ? last : slot.start + slot.spec.size;
    const auto chunk = static_cast<size_t>(end - begin);
    if (!slot.spec.pad_file &&
        !slot.owned->WriteAt(begin - slot.start, data + done, chunk, error)) return false;
    done += chunk;
  }
  return done == bytes || Fail(error, "Incomplete piece-to-file mapping");
}

bool OwnedTorrentStorage::ReadRange(std::uint64_t offset, void* output,
                                    size_t bytes, std::string* error) {
  if (bytes && output == nullptr) return Fail(error, "Missing native read buffer");
  if (!Preopen(offset, bytes, false, error)) return false;
  const auto last = offset + bytes;
  auto* data = static_cast<char*>(output);
  size_t done = 0;
  for (auto& slot : slots_) {
    if (slot.start >= last || slot.start + slot.spec.size <= offset) continue;
    const auto begin = offset > slot.start ? offset : slot.start;
    const auto end = last < slot.start + slot.spec.size ? last : slot.start + slot.spec.size;
    const auto chunk = static_cast<size_t>(end - begin);
    if (slot.spec.pad_file) std::memset(data + done, 0, chunk);
    else if (!slot.owned->ReadAt(begin - slot.start, data + done, chunk, error)) return false;
    done += chunk;
  }
  return done == bytes || Fail(error, "Incomplete piece read mapping");
}

bool OwnedTorrentStorage::VerifyRestoredOwnership(std::string* error) {
  if (mode_ != Admission::RESTORED)
    return Fail(error, "Restored ownership verification on fresh storage");
  // Do not trust resume's claimed pieces until finalized file receipts and
  // pending-only absences agree with current physical filesystem identities.
  int const root = ::open(receipts_.c_str(),
                          O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
  if (root < 0) return Fail(error, "Native private receipts cannot be read");
  for (auto& slot : slots_) {
    if (slot.spec.pad_file || slot.spec.size == 0) continue;
    auto const& file = slot.spec;
    struct stat st{};
    if (::fstatat(root, file.receipt_id.c_str(), &st, AT_SYMLINK_NOFOLLOW) == 0) {
      // Probe one descriptor at a time, rather than pinning every file in
      // a large torrent at Add time (which could exhaust Android's FD cap).
      // Actual ReadRange/WriteRange later reopens and rechecks the inode.
      auto verified = OwnedPayloadFile::Reopen(
          root_, file.relative_path, receipts_, file.receipt_id,
          file.mapping_commitment, error);
      if (!verified) {
        ::close(root);
        return false;
      }
    } else if (errno == ENOENT) {
      if (!OwnedPayloadFile::VerifyReservation(
              root_, file.relative_path, receipts_, file.receipt_id,
              file.mapping_commitment, error)) {
        ::close(root);
        return false;
      }
    } else {
      ::close(root);
      return Fail(error, "Native receipt inventory changed during restore");
    }
  }
  ::close(root);
  if (error) error->clear();
  return true;
}

bool OwnedTorrentStorage::Flush(std::string* error) {
  for (auto& slot : slots_) if (slot.owned && !slot.owned->Sync(error)) return false;
  return true;
}
}  // namespace chunks
