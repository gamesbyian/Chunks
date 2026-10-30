// EXPERIMENTAL: not linked to the Android application or published JNI.
// The complete libtorrent disk-interface boundary is owned here, never
// delegated to a built-in POSIX/mmap backend. This synchronous prototype
// needs native/Android validation and worker scheduling before shipping.
#include "../owned_torrent_storage.h"
#include "checked_piece_range.h"

#include <libtorrent/libtorrent.hpp>
#include <libtorrent/hasher.hpp>
#include <boost/asio/post.hpp>
#include <boost/system/errc.hpp>

#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <functional>
#include <memory>
#include <string>
#include <utility>
#include <vector>

namespace lt = libtorrent;

namespace chunks {
namespace {
std::string Hex(const char* p, std::size_t count) {
  constexpr char kHex[] = "0123456789abcdef";
  std::string result;
  result.reserve(2 * count);
  for (std::size_t i = 0; i < count; ++i) {
    unsigned char c = static_cast<unsigned char>(p[i]);
    result.push_back(kHex[c >> 4]);
    result.push_back(kHex[c & 15]);
  }
  return result;
}

std::string Digest(std::string const& value) {
  lt::hasher256 hash;
  hash.update({value.data(), static_cast<std::ptrdiff_t>(value.size())});
  auto const digest = hash.final();
  return Hex(digest.data(), 32);
}

lt::storage_error Denied(lt::operation_t operation) {
  lt::storage_error failure;
  failure.operation = operation;
  failure.ec = lt::error_code(boost::system::errc::permission_denied, lt::system_category());
  return failure;
}

struct Torrent {
  lt::file_storage files;
  std::string save_path;
  std::vector<OwnedTorrentStorage::FileSpec> layout;
  std::unique_ptr<OwnedTorrentStorage> storage;
  std::string refusal;
  bool checked = false;
};

class NativeDisk final : public lt::disk_interface, public lt::buffer_allocator_interface {
 public:
  NativeDisk(lt::io_context& context, std::string receipts)
      : io_(context), receipts_(std::move(receipts)) {}

  lt::storage_holder new_torrent(lt::storage_params const& params,
                                 std::shared_ptr<void> const&) override {
    lt::storage_index_t const index = free_.empty() ? torrents_.end_index() : free_.back();
    if (!free_.empty()) free_.pop_back();

    auto state = std::make_shared<Torrent>();
    state->files = params.files;
    state->save_path = params.path;
    std::string const hash = Hex(params.info_hash.data(), 20);
    if (hash.empty() || params.files.num_files() <= 0 ||
        params.files.num_files() > 100000 || params.files.piece_length() <= 0 ||
        params.files.total_size() <= 0) {
      state->refusal = "Native torrent metadata invalid or missing";
    } else {
      for (lt::file_index_t file{0}; file < params.files.end_file(); ++file) {
        auto const path = params.files.file_path(file);
        auto const size = params.files.file_size(file);
        if (size < 0) {
          state->refusal = "Native torrent file length invalid";
          break;
        }
        auto const file_number = static_cast<int>(file);
        // Path-claim ID excludes infohash to prevent cross-torrent aliases.
        auto const path_id = Digest(params.path + "\n" + path);
        auto const bound = Digest(hash + "\n" + params.path + "\n" +
            path + "\n" + std::to_string(file_number) + "\n" + std::to_string(size));
        state->layout.push_back({path, static_cast<std::uint64_t>(size),
                                 path_id, bound, params.files.pad_file_at(file)});
      }
    }
    if (index == torrents_.end_index()) torrents_.emplace_back(std::move(state));
    else torrents_[index] = std::move(state);
    return lt::storage_holder(index, *this);
  }

  void remove_torrent(lt::storage_index_t index) override {
    torrents_[index].reset();
    free_.push_back(index);
  }

  void async_check_files(lt::storage_index_t index, lt::add_torrent_params const* resume,
      lt::aux::vector<std::string, lt::file_index_t> links,
      std::function<void(lt::status_t, lt::storage_error const&)> callback) override {
    auto& state = *torrents_[index];
    lt::storage_error problem;
    if (!state.refusal.empty()) {
      problem = Denied(lt::operation_t::file_open);
    } else {
      for (auto const& link : links) {
        if (!link.empty()) problem = Denied(lt::operation_t::file_open);
      }
      if (!problem.ec && !state.checked) {
        std::string reason;
        state.storage = OwnedTorrentStorage::Open(state.save_path, receipts_, state.layout,
            resume == nullptr ? OwnedTorrentStorage::Admission::FRESH
                              : OwnedTorrentStorage::Admission::RESTORED, &reason);
        if (!state.storage) {
          state.refusal = reason;
          problem = Denied(lt::operation_t::file_open);
        } else state.checked = true;
      }
    }
    auto status = problem.ec ? lt::disk_status::fatal_disk_error
                : resume == nullptr ? lt::status_t{} : lt::disk_status::need_full_check;
    boost::asio::post(io_, [callback, status, problem] { callback(status, problem); });
  }

  void async_read(lt::storage_index_t index, lt::peer_request const& req,
      std::function<void(lt::disk_buffer_holder, lt::storage_error const&)> callback,
      lt::disk_job_flags_t) override {
    auto& state = *torrents_[index];
    lt::storage_error problem;
    CheckedPieceRange bounds{};
    std::string reason;
    char* bytes = nullptr;
    if (!state.checked || !state.refusal.empty() ||
        !DecodePieceRange(static_cast<std::uint64_t>(state.files.total_size()),
                          static_cast<std::uint32_t>(state.files.piece_length()),
                          static_cast<int>(req.piece), req.start, req.length, false, &bounds, &reason)) {
      problem = Denied(lt::operation_t::file_read);
    } else {
      bytes = static_cast<char*>(std::malloc(bounds.length));
      if (bytes == nullptr || !state.storage->ReadRange(bounds.absolute_offset,
                                bytes, bounds.length, &reason)) {
        std::free(bytes);
        bytes = nullptr;
        problem = Denied(lt::operation_t::file_read);
      }
    }
    boost::asio::post(io_, [this, callback, problem, bytes, length=req.length] {
      callback(lt::disk_buffer_holder(*this, bytes, bytes ? length : 0), problem);
    });
  }

  bool async_write(lt::storage_index_t index, lt::peer_request const& req,
      char const* buffer, std::shared_ptr<lt::disk_observer>,
      std::function<void(lt::storage_error const&)> callback,
      lt::disk_job_flags_t) override {
    auto& state = *torrents_[index];
    lt::storage_error problem;
    CheckedPieceRange bounds{};
    std::string reason;
    if (buffer == nullptr || !state.checked || !state.refusal.empty() ||
        !DecodePieceRange(static_cast<std::uint64_t>(state.files.total_size()),
                          static_cast<std::uint32_t>(state.files.piece_length()),
                          static_cast<int>(req.piece), req.start, req.length, true, &bounds, &reason) ||
        !state.storage->WriteRange(bounds.absolute_offset, buffer, bounds.length, &reason)) {
      problem = Denied(lt::operation_t::file_write);
    }
    boost::asio::post(io_, [callback, problem] { callback(problem); });
    return false; // no deferred write queue in this synchronous experiment
  }

  void async_hash(lt::storage_index_t index, lt::piece_index_t piece,
      lt::span<lt::sha256_hash> v2, lt::disk_job_flags_t,
      std::function<void(lt::piece_index_t, lt::sha1_hash const&,
                         lt::storage_error const&)> callback) override {
    auto& state = *torrents_[index];
    lt::sha1_hash result{};
    lt::storage_error problem;
    std::string reason;
    auto const piece_length = state.files.piece_length();
    auto const total = state.files.total_size();
    auto const start = std::int64_t(static_cast<int>(piece)) * piece_length;
    if (!state.checked || start < 0 || start >= total) {
      problem = Denied(lt::operation_t::file_read);
    } else {
      int const length = static_cast<int>(std::min<std::int64_t>(piece_length, total - start));
      auto const blocks = (length + lt::default_block_size - 1) / lt::default_block_size;
      if (!v2.empty() && static_cast<int>(v2.size()) < blocks) {
        problem = Denied(lt::operation_t::file_read);
      } else {
        lt::hasher sha;
        std::vector<char> block(lt::default_block_size);
        for (int offset=0, part=0; offset < length; offset += lt::default_block_size, ++part) {
          int const count = std::min(lt::default_block_size, length - offset);
          if (!state.storage->ReadRange(static_cast<std::uint64_t>(start + offset),
                                        block.data(), static_cast<std::size_t>(count), &reason)) {
            problem = Denied(lt::operation_t::file_read);
            break;
          }
          sha.update({block.data(), count});
          if (!v2.empty()) {
            lt::hasher256 h;
            h.update({block.data(), count});
            v2[part] = h.final();
          }
        }
        if (!problem.ec) result = sha.final();
      }
    }
    boost::asio::post(io_, [callback, piece, result, problem] {
      callback(piece, result, problem);
    });
  }

  void async_hash2(lt::storage_index_t index, lt::piece_index_t piece,
      int offset, lt::disk_job_flags_t,
      std::function<void(lt::piece_index_t, lt::sha256_hash const&,
                         lt::storage_error const&)> callback) override {
    auto& state = *torrents_[index];
    lt::sha256_hash result{};
    lt::storage_error problem;
    std::string reason;
    CheckedPieceRange bounds{};
    auto const total = static_cast<std::uint64_t>(state.files.total_size());
    auto const piece_len = static_cast<std::uint32_t>(state.files.piece_length());
    auto const requested = offset < 0 || offset >= static_cast<int>(piece_len)
        ? 0 : std::min(lt::default_block_size, static_cast<int>(piece_len) - offset);
    if (!state.checked || !DecodePieceRange(total, piece_len,
            static_cast<int>(piece), offset, 1, false, &bounds, &reason)) {
      problem = Denied(lt::operation_t::file_read);
    } else {
      // A v2 block at the final partial piece may be smaller than 16KiB.
      auto const available = total - bounds.absolute_offset;
      auto const length = std::min<std::uint64_t>(requested, available);
      if (!DecodePieceRange(total, piece_len, static_cast<int>(piece),
                            offset, static_cast<std::int64_t>(length),
                            false, &bounds, &reason)) {
        problem = Denied(lt::operation_t::file_read);
      }
    }
    if (!problem.ec) {
      std::vector<char> block(bounds.length);
      if (!state.storage->ReadRange(bounds.absolute_offset, block.data(),
                                    block.size(), &reason)) {
        problem = Denied(lt::operation_t::file_read);
      } else {
        lt::hasher256 h;
        h.update({block.data(), static_cast<std::ptrdiff_t>(block.size())});
        result = h.final();
      }
    }
    boost::asio::post(io_, [callback, piece, result, problem] { callback(piece, result, problem); });
  }

  void async_move_storage(lt::storage_index_t, std::string place, lt::move_flags_t,
      std::function<void(lt::status_t, std::string const&, lt::storage_error const&)> cb) override {
    auto error = Denied(lt::operation_t::file_rename);
    boost::asio::post(io_, [cb, place, error] { cb(lt::disk_status::fatal_disk_error, place, error); });
  }

  void async_release_files(lt::storage_index_t index, std::function<void()> cb) override {
    std::string error;
    if (torrents_[index]->storage && !torrents_[index]->storage->Flush(&error))
      torrents_[index]->refusal = error;
    if (cb) boost::asio::post(io_, cb);
  }

  void async_stop_torrent(lt::storage_index_t index, std::function<void()> cb) override {
    async_release_files(index, std::move(cb));
  }

  void async_rename_file(lt::storage_index_t, lt::file_index_t file,
      std::string path,
      std::function<void(std::string const&, lt::file_index_t, lt::storage_error const&)> cb) override {
    auto error = Denied(lt::operation_t::file_rename);
    boost::asio::post(io_, [cb, path, file, error] { cb(path, file, error); });
  }

  void async_delete_files(lt::storage_index_t, lt::remove_flags_t,
      std::function<void(lt::storage_error const&)> cb) override {
    auto error = Denied(lt::operation_t::file_remove);
    boost::asio::post(io_, [cb, error] { cb(error); });
  }

  void async_set_file_priority(lt::storage_index_t,
      lt::aux::vector<lt::download_priority_t, lt::file_index_t> priority,
      std::function<void(lt::storage_error const&,
          lt::aux::vector<lt::download_priority_t, lt::file_index_t>)> cb) override {
    auto error = Denied(lt::operation_t::file_write);
    boost::asio::post(io_, [cb, priority=std::move(priority), error]() mutable {
      cb(error, std::move(priority));
    });
  }

  void async_clear_piece(lt::storage_index_t, lt::piece_index_t piece,
      std::function<void(lt::piece_index_t)> cb) override {
    boost::asio::post(io_, [cb, piece] { cb(piece); });
  }

  void update_stats_counters(lt::counters&) const override {}
  std::vector<lt::open_file_state> get_status(lt::storage_index_t) const override { return {}; }
  void abort(bool) override {
    for (auto const& state : torrents_) {
      if (!state || !state->storage) continue;
      std::string error;
      if (!state->storage->Flush(&error)) state->refusal = error;
    }
  }
  void submit_jobs() override {}
  void settings_updated() override {}
  void free_disk_buffer(char* p) override { std::free(p); }
#if TORRENT_DEBUG_BUFFER_POOL
  void rename_buffer(char*, char const*) override {}
#endif

 private:
  lt::io_context& io_;
  std::string receipts_;
  lt::aux::vector<std::shared_ptr<Torrent>, lt::storage_index_t> torrents_;
  std::vector<lt::storage_index_t> free_;
};
} // namespace

// The maintained SWIG/NDK libtorrent4j fork must call this factory through
// session_params.disk_io_constructor BEFORE its native Session starts.
// No independent .so or Java-only preflight can install it safely.
lt::disk_io_constructor_type MakeOwnedDiskConstructor(std::string private_receipts) {
  return [private_receipts=std::move(private_receipts)](
      lt::io_context& io, lt::settings_interface const&, lt::counters&) {
    return std::unique_ptr<lt::disk_interface>(new NativeDisk(io, private_receipts));
  };
}

} // namespace chunks
