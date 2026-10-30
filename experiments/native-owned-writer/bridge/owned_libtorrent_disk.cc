// EXPERIMENTAL: not linked to the Android application or published JNI.
// The complete libtorrent disk-interface boundary is owned here, never
// delegated to a built-in POSIX/mmap backend. This synchronous prototype
// needs native/Android validation and worker scheduling before shipping.
#include "../owned_torrent_storage.h"
#include "checked_piece_range.h"
#include "owned_libtorrent_disk.h"
#include "receipt_admission.h"
#include "serial_native_io_queue.h"

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
    state->save_path.assign(params.path.data(), params.path.size());
    std::string const hash = Hex(params.info_hash.data(), 20);
    // storage_params carries a 20-byte v1 identity. Do not give v2-only
    // torrents an all-zero/ambiguous identity: enable them only when the
    // complete v2 identity can be bound from authenticated metadata.
    if (!params.v1 || hash.empty() || params.files.num_files() <= 0 ||
        params.files.num_files() > 100000 || params.files.piece_length() <= 0 ||
        params.files.total_size() <= 0) {
      state->refusal = "Native torrent metadata invalid or missing";
    } else {
      // Use libtorrent's immutable layout plus the explicit renamed-files
      // overlay. The original file_storage path alone is not authoritative.
      lt::filenames const mapped(params.files, params.renamed_files);
      for (lt::file_index_t file : mapped.file_range()) {
        auto const path = mapped.file_path(file);
        auto const size = mapped.file_size(file);
        if (size < 0 || mapped.file_absolute_path(file) || !mapped.symlink(file).empty()) {
          state->refusal = "Native torrent path is invalid, absolute or a symlink";
          break;
        }
        auto const file_number = static_cast<int>(file);
        // Path-claim ID excludes infohash to prevent cross-torrent aliases.
        auto const path_id = Digest(state->save_path + "\n" + path);
        auto const bound = Digest(hash + "\n" + state->save_path + "\n" +
            path + "\n" + std::to_string(file_number) + "\n" + std::to_string(size));
        state->layout.push_back({path, static_cast<std::uint64_t>(size),
                                 path_id, bound, mapped.pad_file_at(file)});
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
    auto state = torrents_[index];
    const bool had_resume = resume != nullptr;
    auto failure = [this, callback] {
      auto denied = Denied(lt::operation_t::file_open);
      boost::asio::post(io_, [callback, denied] {
        callback(lt::disk_status::fatal_disk_error, denied);
      });
    };
    // Never run receipt discovery, directory traversal or reservation on
    // libtorrent's network thread. The queue serializes admission and writes.
    if (!queue_.Submit([this, state, links=std::move(links), had_resume, callback] {
      lt::storage_error problem;
      if (!state || !state->refusal.empty()) {
        problem = Denied(lt::operation_t::file_open);
      } else {
        for (auto const& link : links)
          if (!link.empty()) problem = Denied(lt::operation_t::file_open);
        if (!problem.ec && !state->checked) {
          std::string reason;
          auto const admission = ClassifyNativeAdmission(receipts_, state->layout, &reason);
          if (admission == NativeAdmissionMode::REFUSED) {
            state->refusal = reason;
            problem = Denied(lt::operation_t::file_open);
          } else {
            auto const mode = admission == NativeAdmissionMode::FRESH
                ? OwnedTorrentStorage::Admission::FRESH
                : OwnedTorrentStorage::Admission::RESTORED;
            state->storage = OwnedTorrentStorage::Open(state->save_path, receipts_,
                                                      state->layout, mode, &reason);
          }
          if (!problem.ec && !state->storage) {
            state->refusal = reason;
            problem = Denied(lt::operation_t::file_open);
          }
          if (!problem.ec && state->storage) state->checked = true;
        }
      }
      auto const status = problem.ec ? lt::disk_status::fatal_disk_error
          : had_resume ? lt::disk_status::need_full_check : lt::status_t{};
      boost::asio::post(io_, [callback, status, problem] {
        callback(status, problem);
      });
    }, failure)) failure();
  }

  void async_read(lt::storage_index_t index, lt::peer_request const& request,
      std::function<void(lt::disk_buffer_holder, lt::storage_error const&)> callback,
      lt::disk_job_flags_t) override {
    auto state = torrents_[index];
    auto failure = [this, callback] {
      auto denied = Denied(lt::operation_t::file_read);
      boost::asio::post(io_, [this, callback, denied] {
        callback(lt::disk_buffer_holder(*this, nullptr, 0), denied);
      });
    };
    if (!queue_.Submit([this, state, request, callback] {
      lt::storage_error problem;
      CheckedPieceRange bounds{};
      std::string reason;
      char* data = nullptr;
      if (!state || !state->checked || !state->refusal.empty() ||
          !DecodePieceRange(static_cast<std::uint64_t>(state->files.total_size()),
                            static_cast<std::uint32_t>(state->files.piece_length()),
                            static_cast<int>(request.piece), request.start, request.length,
                            false, &bounds, &reason)) {
        problem = Denied(lt::operation_t::file_read);
      } else {
        data = static_cast<char*>(std::malloc(bounds.length));
        if (!data || !state->storage->ReadRange(bounds.absolute_offset,
                                                 data, bounds.length, &reason)) {
          std::free(data);
          data = nullptr;
          problem = Denied(lt::operation_t::file_read);
        }
      }
      boost::asio::post(io_, [this, callback, problem, data, length=request.length] {
        callback(lt::disk_buffer_holder(*this, data, data ? length : 0), problem);
      });
    }, failure)) failure();
  }

  bool async_write(lt::storage_index_t index, lt::peer_request const& request,
      char const* buffer, std::shared_ptr<lt::disk_observer>,
      std::function<void(lt::storage_error const&)> callback,
      lt::disk_job_flags_t) override {
    auto state = torrents_[index];
    auto failure = [this, callback] {
      auto denied = Denied(lt::operation_t::file_write);
      boost::asio::post(io_, [callback, denied] { callback(denied); });
    };
    CheckedPieceRange bounds{};
    std::string reason;
    // A peer's raw buffer is not valid once this method returns. Validate
    // the entire geometry before allocating/copying any network-owned bytes.
    if (!state || !buffer ||
        !DecodePieceRange(static_cast<std::uint64_t>(state->files.total_size()),
                          static_cast<std::uint32_t>(state->files.piece_length()),
                          static_cast<int>(request.piece), request.start, request.length,
                          true, &bounds, &reason)) {
      failure();
      return false;
    }
    try {
      std::vector<char> owned(buffer, buffer + bounds.length);
      if (!queue_.Submit([this, state, bounds, owned=std::move(owned), callback] {
        std::string error;
        lt::storage_error result;
        if (!state->checked || !state->refusal.empty() ||
            !state->storage->WriteRange(bounds.absolute_offset, owned.data(),
                                         owned.size(), &error)) {
          result = Denied(lt::operation_t::file_write);
        }
        boost::asio::post(io_, [callback, result] { callback(result); });
      }, failure)) failure();
    } catch (...) {
      failure();
    }
    return false; // successful enqueue is bounded; overflow receives an error
  }

  void async_hash(lt::storage_index_t index, lt::piece_index_t piece,
      lt::span<lt::sha256_hash> v2, lt::disk_job_flags_t,
      std::function<void(lt::piece_index_t, lt::sha1_hash const&,
                         lt::storage_error const&)> callback) override {
    auto state = torrents_[index];
    auto failure = [this, piece, callback] {
      auto denied = Denied(lt::operation_t::file_read);
      boost::asio::post(io_, [piece, callback, denied] {
        callback(piece, lt::sha1_hash{}, denied);
      });
    };
    if (!queue_.Submit([this, state, piece, v2, callback] {
      lt::sha1_hash result{};
      lt::storage_error problem;
      std::string reason;
      auto const piece_length = state ? state->files.piece_length() : 0;
      auto const total = state ? state->files.total_size() : 0;
      auto const start = std::int64_t(static_cast<int>(piece)) * piece_length;
      if (!state || !state->checked || !state->refusal.empty() ||
          start < 0 || start >= total || piece_length <= 0) {
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
            if (!state->storage->ReadRange(static_cast<std::uint64_t>(start + offset),
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
    }, failure)) failure();
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
  SerialNativeIoQueue queue_;
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
