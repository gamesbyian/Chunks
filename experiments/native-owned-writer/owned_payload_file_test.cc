#include "owned_payload_file.h"

#include <atomic>
#include <cassert>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <limits>
#include <string>
#include <sys/xattr.h>
#include <thread>
#include <unistd.h>

namespace fs = std::filesystem;

struct Sandbox {
  fs::path base, payload, receipts;
  Sandbox() {
    char name[] = "/tmp/chunks-owned-test-XXXXXX";
    char* p = ::mkdtemp(name);
    assert(p != nullptr);
    base = p;
    payload = base / "public-Downloads";
    receipts = base / "private-receipts";
    fs::create_directories(payload);
    fs::create_directories(receipts);
    fs::permissions(receipts, fs::perms::owner_all, fs::perm_options::replace);
  }
  ~Sandbox() { fs::remove_all(base); }
  std::string read(const fs::path& p) {
    std::ifstream file(p, std::ios::binary);
    return std::string(std::istreambuf_iterator<char>(file), {});
  }
  void write(const fs::path& p, const std::string& bytes) {
    std::ofstream file(p, std::ios::binary | std::ios::trunc);
    file << bytes;
  }
};

constexpr auto kId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
constexpr auto kMapping = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
constexpr auto kOtherMapping = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";

int main() {
  int cases = 0;
  auto passed = [&cases]() { ++cases; };
  {
    Sandbox s;
    std::string error;
    auto f = chunks::OwnedPayloadFile::Create(s.payload.string(), "season/episode.bin",
        s.receipts.string(), kId, kMapping, &error);
    assert(f && error.empty());
    assert(f->WriteAt(0, "torrent-data", 12, &error));
    assert(f->Sync(&error));
    f.reset();
    auto r = chunks::OwnedPayloadFile::Reopen(s.payload.string(), "season/episode.bin",
        s.receipts.string(), kId, kMapping, &error);
    assert(r);
    char bytes[13]{};
    assert(r->ReadAt(0, bytes, 12, &error));
    assert(std::string(bytes) == "torrent-data");
    assert(!chunks::OwnedPayloadFile::Reopen(s.payload.string(), "season/episode.bin",
        s.receipts.string(), kId, kOtherMapping, &error));
    assert(s.read(s.payload / "season/episode.bin") == "torrent-data");
    passed();
  }
  {
    Sandbox s;
    std::string error;
    s.write(s.payload / "episode.bin", "UNRELATED BYTES");
    assert(!chunks::OwnedPayloadFile::Create(s.payload.string(), "episode.bin",
        s.receipts.string(), kId, kMapping, &error));
    assert(s.read(s.payload / "episode.bin") == "UNRELATED BYTES");
    assert(!fs::exists(s.receipts / kId));
    passed();
  }
  {
    Sandbox s;
    std::string error;
    fs::create_directories(s.base / "foreign-parent");
    s.write(s.base / "foreign-parent/episode.bin", "KEEP ME");
    fs::create_directory_symlink(s.base / "foreign-parent", s.payload / "linked");
    assert(!chunks::OwnedPayloadFile::Create(s.payload.string(), "linked/episode.bin",
        s.receipts.string(), kId, kMapping, &error));
    assert(s.read(s.base / "foreign-parent/episode.bin") == "KEEP ME");
    passed();
  }
  {
    Sandbox s;
    std::string error;
    auto f = chunks::OwnedPayloadFile::Create(s.payload.string(), "episode.bin",
        s.receipts.string(), kId, kMapping, &error);
    assert(f);
    assert(f->WriteAt(0, "OWN", 3, &error));
    fs::rename(s.payload / "episode.bin", s.payload / "original-moved.bin");
    s.write(s.payload / "episode.bin", "FOREIGN");
    assert(!f->WriteAt(0, "DESTRUCTIVE", 11, &error));
    assert(s.read(s.payload / "episode.bin") == "FOREIGN");
    assert(!chunks::OwnedPayloadFile::Reopen(s.payload.string(), "episode.bin",
        s.receipts.string(), kId, kMapping, &error));
    assert(s.read(s.payload / "episode.bin") == "FOREIGN");
    passed();
  }
  {
    Sandbox s;
    std::string error;
    auto f = chunks::OwnedPayloadFile::Create(s.payload.string(), "season/episode.bin",
        s.receipts.string(), kId, kMapping, &error);
    assert(f);
    assert(f->WriteAt(0, "OWN", 3, &error));
    fs::rename(s.payload / "season", s.payload / "moved-season");
    fs::create_directories(s.payload / "season");
    s.write(s.payload / "season/episode.bin", "FOREIGN");
    f.reset();
    assert(!chunks::OwnedPayloadFile::Reopen(s.payload.string(), "season/episode.bin",
        s.receipts.string(), kId, kMapping, &error));
    assert(s.read(s.payload / "season/episode.bin") == "FOREIGN");
    passed();
  }
  {
    Sandbox s;
    std::string error;
    auto f = chunks::OwnedPayloadFile::Create(s.payload.string(), "episode.bin",
        s.receipts.string(), kId, kMapping, &error);
    assert(f);
    fs::create_hard_link(s.payload / "episode.bin", s.payload / "alias.bin");
    assert(!f->WriteAt(0, "DENIED", 6, &error));
    f.reset();
    assert(!chunks::OwnedPayloadFile::Reopen(s.payload.string(), "episode.bin",
        s.receipts.string(), kId, kMapping, &error));
    assert(s.read(s.payload / "episode.bin").empty());
    passed();
  }
  {
    Sandbox s;
    std::string error;
    auto f = chunks::OwnedPayloadFile::Create(s.payload.string(), "episode.bin",
        s.receipts.string(), kId, kMapping, &error);
    assert(f);
    f.reset();
    s.write(s.receipts / kId, "INVALID");
    assert(!chunks::OwnedPayloadFile::Reopen(s.payload.string(), "episode.bin",
        s.receipts.string(), kId, kMapping, &error));
    passed();
  }
  {
    Sandbox s;
    std::string error;
    assert(!chunks::OwnedPayloadFile::Create(s.payload.string(), "../bad.bin",
        s.receipts.string(), kId, kMapping, &error));
    assert(!chunks::OwnedPayloadFile::Create(s.payload.string(), "/absolute.bin",
        s.receipts.string(), kId, kMapping, &error));
    assert(!chunks::OwnedPayloadFile::Create(s.payload.string(), "bad//path.bin",
        s.receipts.string(), kId, kMapping, &error));
    assert(!chunks::OwnedPayloadFile::Create(s.payload.string(), "good.bin",
        s.receipts.string(), "../bad-id", kMapping, &error));
    passed();
  }
  {
    Sandbox s;
    std::string error;
    auto f = chunks::OwnedPayloadFile::Create(s.payload.string(), "episode.bin",
        s.receipts.string(), kId, kMapping, &error);
    assert(f);
    assert(!f->WriteAt(std::numeric_limits<std::uint64_t>::max(), "x", 1, &error));
    assert(s.read(s.payload / "episode.bin").empty());
    passed();
  }
  {
    Sandbox s;
    std::string error;
    auto f = chunks::OwnedPayloadFile::Create(s.payload.string(), "episode.bin",
        s.receipts.string(), kId, kMapping, &error);
    assert(f);
    assert(!chunks::OwnedPayloadFile::Create(s.payload.string(), "different.bin",
        s.receipts.string(), kId, kOtherMapping, &error));
    assert(fs::exists(s.receipts / kId));
    assert(s.read(s.receipts / kId).find(kMapping) != std::string::npos);
    assert(!fs::exists(s.payload / "different.bin"));
    passed();
  }
  {
    Sandbox s;
    std::string error;
    auto f = chunks::OwnedPayloadFile::Create(s.payload.string(), "episode.bin",
        s.receipts.string(), kId, kMapping, &error);
    assert(f);
    fs::rename(s.payload, s.base / "old-root");
    fs::create_directories(s.payload);
    s.write(s.payload / "episode.bin", "FOREIGN");
    assert(!f->WriteAt(0, "DENIED", 6, &error));
    assert(s.read(s.payload / "episode.bin") == "FOREIGN");
    passed();
  }
  {
    Sandbox s;
    std::string error;
    auto f = chunks::OwnedPayloadFile::Create(s.payload.string(), "season/episode.bin",
        s.receipts.string(), kId, kMapping, &error);
    assert(f);
    fs::rename(s.payload / "season", s.payload / "old-season");
    fs::create_directories(s.payload / "season");
    s.write(s.payload / "season/episode.bin", "FOREIGN");
    assert(!f->WriteAt(0, "DENIED", 6, &error));
    assert(s.read(s.payload / "season/episode.bin") == "FOREIGN");
    passed();
  }
  {
    Sandbox s;
    std::atomic<bool> go{false};
    std::atomic<int> accepted{0};
    std::thread a([&] {
      while (!go.load()) std::this_thread::yield();
      std::string error;
      auto owned = chunks::OwnedPayloadFile::Create(s.payload.string(), "race.bin",
          s.receipts.string(), kId, kMapping, &error);
      if (owned) ++accepted;
    });
    std::thread b([&] {
      while (!go.load()) std::this_thread::yield();
      std::string error;
      auto owned = chunks::OwnedPayloadFile::Create(s.payload.string(), "race.bin",
          s.receipts.string(), kId, kMapping, &error);
      if (owned) ++accepted;
    });
    go = true;
    a.join(); b.join();
    assert(accepted == 1);
    assert(fs::exists(s.receipts / kId));
    passed();
  }
  {
    Sandbox s;
    std::string error;
    auto owned = chunks::OwnedPayloadFile::Create(s.payload.string(), "episode.bin",
        s.receipts.string(), kId, kMapping, &error);
    assert(owned);
    assert(owned->WriteAt(0, "OWNED", 5, &error));
    // Device and inode identities still match, but loss of the creation
    // marker must revoke native writing without damaging the existing data.
    assert(::removexattr((s.payload / "episode.bin").c_str(),
                         "user.chunks.owned-payload") == 0);
    assert(!owned->WriteAt(0, "OTHER", 5, &error));
    owned.reset();
    assert(!chunks::OwnedPayloadFile::Reopen(s.payload.string(), "episode.bin",
        s.receipts.string(), kId, kMapping, &error));
    assert(s.read(s.payload / "episode.bin") == "OWNED");
    passed();
  }
  {
    Sandbox s;
    std::string error;
    auto owned = chunks::OwnedPayloadFile::Create(s.payload.string(), "episode.bin",
        s.receipts.string(), kId, kMapping, &error);
    assert(owned);
    assert(owned->WriteAt(0, "OWNED", 5, &error));
    owned.reset();
    const std::string wrong(64, 'f');
    assert(::setxattr((s.payload / "episode.bin").c_str(),
                      "user.chunks.owned-payload", wrong.data(), wrong.size(), 0) == 0);
    assert(!chunks::OwnedPayloadFile::Reopen(s.payload.string(), "episode.bin",
        s.receipts.string(), kId, kMapping, &error));
    assert(s.read(s.payload / "episode.bin") == "OWNED");
    passed();
  }
  {
    Sandbox s;
    std::string error;
    auto owned = chunks::OwnedPayloadFile::Create(s.payload.string(), "episode.bin",
        s.receipts.string(), kId, kMapping, &error);
    assert(owned);
    assert(owned->WriteAt(0, "OWNED", 5, &error));
    owned.reset();
    fs::remove(s.payload / "episode.bin");
    // An absent path with a *finalized* receipt cannot be recycled through
    // the pending-zero-progress admission path, even with the same hash.
    assert(!chunks::OwnedPayloadFile::Reserve(s.payload.string(), "episode.bin",
        s.receipts.string(), kId, kMapping, &error));
    assert(!fs::exists(s.payload / "episode.bin"));
    assert(fs::exists(s.receipts / kId));
    passed();
  }
  {
    Sandbox s;
    std::string error;
    assert(chunks::OwnedPayloadFile::Reserve(s.payload.string(), "one.bin",
        s.receipts.string(), kId, kMapping, &error));
    // Same parent/inode and even the same caller-provided receipt identifiers
    // must never authorize another basename after a stopped Add.
    assert(!chunks::OwnedPayloadFile::Reserve(s.payload.string(), "two.bin",
        s.receipts.string(), kId, kMapping, &error));
    assert(!chunks::OwnedPayloadFile::CreateFromReservation(s.payload.string(),
        "two.bin", s.receipts.string(), kId, kMapping, &error));
    assert(!fs::exists(s.payload / "two.bin"));
    auto permitted = chunks::OwnedPayloadFile::CreateFromReservation(s.payload.string(),
        "one.bin", s.receipts.string(), kId, kMapping, &error);
    assert(permitted);
    assert(permitted->WriteAt(0, "OWN", 3, &error));
    assert(s.read(s.payload / "one.bin") == "OWN");
    passed();
  }
  {
    Sandbox s;
    std::string error;
    fs::permissions(s.receipts, fs::perms::group_read | fs::perms::others_write,
                    fs::perm_options::add);
    assert(!chunks::OwnedPayloadFile::Create(s.payload.string(), "episode.bin",
        s.receipts.string(), kId, kMapping, &error));
    assert(!error.empty());
    assert(!fs::exists(s.payload / "episode.bin"));
    passed();
  }
  std::cout << "CHUNKS_PINNED_WRITER_CASES=" << cases << " PASSED\n";
}
