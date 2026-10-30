#include "owned_torrent_storage.h"

#include <cstring>
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
  return std::unique_ptr<OwnedTorrentStorage>(new OwnedTorrentStorage(
      std::move(root), std::move(receipts), mode, std::move(slots), total));
}

bool OwnedTorrentStorage::Preopen(std::uint64_t offset, size_t bytes,
                                  std::string* error) {
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
    slot.owned = (mode_ == Admission::FRESH)
        ? OwnedPayloadFile::Create(root_, s.relative_path, receipts_,
                                   s.receipt_id, s.mapping_commitment, error)
        : OwnedPayloadFile::Reopen(root_, s.relative_path, receipts_,
                                   s.receipt_id, s.mapping_commitment, error);
    if (!slot.owned) return false;
  }
  return true;
}

bool OwnedTorrentStorage::WriteRange(std::uint64_t offset, const void* input,
                                     size_t bytes, std::string* error) {
  if (bytes && input == nullptr) return Fail(error, "Missing native write buffer");
  if (!Preopen(offset, bytes, error)) return false;
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
  if (!Preopen(offset, bytes, error)) return false;
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

bool OwnedTorrentStorage::Flush(std::string* error) {
  for (auto& slot : slots_) if (slot.owned && !slot.owned->Sync(error)) return false;
  return true;
}
}  // namespace chunks
