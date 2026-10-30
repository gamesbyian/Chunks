#include "owned_torrent_storage.h"

#include <cassert>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <string>
#include <unistd.h>

namespace fs = std::filesystem;
constexpr auto A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
constexpr auto B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
constexpr auto C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
constexpr auto D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
struct Sandbox {
  fs::path base, payload, receipts;
  Sandbox() {
    char pattern[] = "/tmp/chunks-multi-writer-XXXXXX";
    char* p = mkdtemp(pattern);
    assert(p);
    base = p;
    payload = base / "public";
    receipts = base / "private";
    fs::create_directories(payload);
    fs::create_directories(receipts);
    fs::permissions(receipts, fs::perms::owner_all, fs::perm_options::replace);
  }
  ~Sandbox() { fs::remove_all(base); }
  std::string read(const fs::path& p) {
    std::ifstream file(p, std::ios::binary);
    return std::string(std::istreambuf_iterator<char>(file), {});
  }
};
using Storage = chunks::OwnedTorrentStorage;
std::vector<Storage::FileSpec> files() {
  return {{"show/partA.bin", 5, A, C}, {"show/partB.bin", 7, B, D}};
}
int main() {
  int count = 0;
  {
    Sandbox s;
    std::string error;
    auto t = Storage::Open(s.payload.string(), s.receipts.string(), files(),
                           Storage::Admission::FRESH, &error);
    assert(t && t->size() == 12);
    assert(t->WriteRange(3, "ABCDEFGHI", 9, &error));
    assert(t->Flush(&error));
    assert(s.read(s.payload / "show/partA.bin").size() == 5);
    assert(s.read(s.payload / "show/partB.bin") == "CDEFGHI");
    t.reset();
    auto resumed = Storage::Open(s.payload.string(), s.receipts.string(), files(),
                                 Storage::Admission::RESTORED, &error);
    assert(resumed);
    char received[10]{};
    assert(resumed->ReadRange(3, received, 9, &error));
    assert(std::string(received) == "ABCDEFGHI");
    ++count;
  }
  {
    Sandbox s;
    fs::create_directories(s.payload / "show");
    { std::ofstream out(s.payload / "show/partB.bin"); out << "FOREIGN"; }
    std::string error;
    auto t = Storage::Open(s.payload.string(), s.receipts.string(), files(),
                           Storage::Admission::FRESH, &error);
    assert(!t); // prospective admission refused before creating any payload
    assert(s.read(s.payload / "show/partB.bin") == "FOREIGN");
    assert(!fs::exists(s.payload / "show/partA.bin"));
    ++count;
  }
  {
    Sandbox s;
    std::string error;
    auto t = Storage::Open(s.payload.string(), s.receipts.string(), files(),
                           Storage::Admission::FRESH, &error);
    assert(t);
    assert(!t->WriteRange(11, "XX", 2, &error));
    assert(!fs::exists(s.payload / "show/partA.bin"));
    assert(!fs::exists(s.payload / "show/partB.bin"));
    ++count;
  }
  {
    Sandbox s;
    std::string error;
    auto duplicate = files(); duplicate[1].receipt_id = A;
    assert(!Storage::Open(s.payload.string(), s.receipts.string(), duplicate,
                          Storage::Admission::FRESH, &error));
    ++count;
  }
  {
    Sandbox s;
    std::string error;
    auto t = Storage::Open(s.payload.string(), s.receipts.string(), files(),
                           Storage::Admission::RESTORED, &error);
    assert(t);
    assert(!t->WriteRange(0, "ABCDE", 5, &error));
    assert(!fs::exists(s.payload / "show"));
    ++count;
  }
  {
    Sandbox s;
    std::string error;
    auto layout = files();
    layout.insert(layout.begin() + 1, {".pad/3", 3, "", "", true});
    auto t = Storage::Open(s.payload.string(), s.receipts.string(), layout,
                           Storage::Admission::FRESH, &error);
    assert(t && t->size() == 15);
    assert(t->WriteRange(4, "ABCDEF", 6, &error));
    assert(t->Flush(&error));
    assert(!fs::exists(s.payload / ".pad"));
    assert(s.read(s.payload / "show/partB.bin") == "EF");
    char received[7]{};
    assert(t->ReadRange(4, received, 6, &error));
    assert(std::string(received, 6) == std::string("A\0\0\0EF", 6));
    ++count;
  }
  {
    Sandbox s;
    std::string error;
    auto fresh = Storage::Open(s.payload.string(), s.receipts.string(), files(),
                               Storage::Admission::FRESH, &error);
    assert(fresh);
    assert(!fs::exists(s.payload / "show/partA.bin"));
    assert(fs::exists(s.receipts / (std::string(A) + ".pending")));
    fresh.reset(); // stopped Add, process exits before native payload creation
    auto restored = Storage::Open(s.payload.string(), s.receipts.string(), files(),
                                  Storage::Admission::RESTORED, &error);
    assert(restored);
    assert(restored->WriteRange(0, "ABCDE", 5, &error));
    assert(restored->Flush(&error));
    assert(s.read(s.payload / "show/partA.bin") == "ABCDE");
    ++count;
  }
  {
    Sandbox s;
    std::string error;
    auto fresh = Storage::Open(s.payload.string(), s.receipts.string(), files(),
                               Storage::Admission::FRESH, &error);
    assert(fresh);
    fresh.reset();
    { std::ofstream planted(s.payload / "show/partA.bin"); planted << "FOREIGN"; }
    auto restored = Storage::Open(s.payload.string(), s.receipts.string(), files(),
                                  Storage::Admission::RESTORED, &error);
    assert(restored);
    assert(!restored->WriteRange(0, "ABCDE", 5, &error));
    assert(s.read(s.payload / "show/partA.bin") == "FOREIGN");
    ++count;
  }
  {
    Sandbox s;
    std::string error;
    auto conflicting = files();
    conflicting[0].relative_path = "episode.bin";
    conflicting[1].relative_path = "episode.bin/extras.txt";
    auto invalid = Storage::Open(s.payload.string(), s.receipts.string(),
          conflicting, Storage::Admission::FRESH, &error);
    assert(!invalid);
    assert(!fs::exists(s.payload / "episode.bin"));
    ++count;
  }
  {
    Sandbox s;
    std::string error;
    auto fresh = Storage::Open(s.payload.string(), s.receipts.string(), files(),
                                Storage::Admission::FRESH, &error);
    assert(fresh);
    char bytes[6]{};
    // A seeder read of an uncreated but durably reserved payload must
    // refuse without fabricating empty bytes, metadata or backing paths.
    assert(!fresh->ReadRange(0, bytes, 5, &error));
    assert(!fs::exists(s.payload / "show/partA.bin"));
    assert(!fs::exists(s.receipts / A));
    assert(fs::exists(s.receipts / (std::string(A) + ".pending")));
    // A later real write may still exclusively acquire this exact file.
    assert(fresh->WriteRange(0, "ABCDE", 5, &error));
    assert(s.read(s.payload / "show/partA.bin") == "ABCDE");
    ++count;
  }
  {
    Sandbox s;
    std::string error;
    auto fresh = Storage::Open(s.payload.string(), s.receipts.string(), files(),
                                Storage::Admission::FRESH, &error);
    assert(fresh);
    fresh.reset();
    auto resumed = Storage::Open(s.payload.string(), s.receipts.string(), files(),
                                  Storage::Admission::RESTORED, &error);
    assert(resumed);
    char bytes[4]{};
    assert(!resumed->ReadRange(0, bytes, 3, &error));
    assert(!fs::exists(s.payload / "show/partA.bin"));
    assert(!fs::exists(s.receipts / A));
    ++count;
  }
  {
    Sandbox s;
    std::string error;
    auto first = Storage::Open(s.payload.string(), s.receipts.string(), files(),
                                Storage::Admission::FRESH, &error);
    assert(first);
    // Receipt ID is a physical-Place + path claim, independent of torrent.
    // A different torrent's mapping must not reserve the same empty path.
    auto competing = files();
    competing[0].mapping_commitment = D;
    auto second = Storage::Open(s.payload.string(), s.receipts.string(), competing,
                                 Storage::Admission::FRESH, &error);
    assert(!second);
    assert(!fs::exists(s.payload / "show/partA.bin"));
    assert(fs::exists(s.receipts / (std::string(A) + ".pending")));
    ++count;
  }
  std::cout << "CHUNKS_MULTIFILE_WRITER_CASES=" << count << " PASSED\n";
}
