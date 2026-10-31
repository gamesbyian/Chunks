#include "owned_payload_file.h"

#include <cassert>
#include <cstdarg>
#include <cstring>
#include <filesystem>
#include <fcntl.h>
#include <iostream>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

// Linker interposition lets the test replace the selected root at the exact
// reservation-read boundary without timing-dependent threads or user files.
namespace {
std::string root_path, exchange_path, moved_path, pending_name;
bool armed = false, root_opened = false, exchanged = false;
}
extern "C" int __real_open(const char*, int, ...);
extern "C" int __real_openat(int, const char*, int, ...);
extern "C" int __wrap_open(const char* path, int flags, ...) {
  mode_t mode = 0;
  if (flags & O_CREAT) {
    va_list args;
    va_start(args, flags);
    mode = static_cast<mode_t>(va_arg(args, int));
    va_end(args);
  }
  int result = __real_open(path, flags, mode);
  if (armed && root_path == path && result >= 0) root_opened = true;
  return result;
}
extern "C" int __wrap_openat(int dir, const char* path, int flags, ...) {
  mode_t mode = 0;
  if (flags & O_CREAT) {
    va_list args;
    va_start(args, flags);
    mode = static_cast<mode_t>(va_arg(args, int));
    va_end(args);
  }
  if (armed && root_opened && pending_name == path && !exchanged) {
    exchanged = true;
    assert(::rename(exchange_path.c_str(), moved_path.c_str()) == 0);
    assert(::mkdir(exchange_path.c_str(), 0700) == 0);
  }
  return __real_openat(dir, path, flags, mode);
}

int main(int argc, char** argv) {
  namespace fs = std::filesystem;
  for (bool replace_parent : {false, true}) {
    if (argc > 1 && (std::string(argv[1]) == "parent") != replace_parent) continue;
    char name[] = "/tmp/chunks-reservation-race-XXXXXX";
    char* directory = ::mkdtemp(name);
    assert(directory);
    auto base = fs::path(directory);
    root_path = (base / "selected").string();
    exchange_path = replace_parent ? (base / "selected" / "parent").string() : root_path;
    moved_path = (base / "original").string();
    auto receipts = base / "private";
    fs::create_directories(fs::path(root_path) / "parent");
    fs::create_directory(receipts);
    fs::permissions(receipts, fs::perms::owner_all, fs::perm_options::replace);
    std::string id(64, 'a'), mapping(64, 'b'), error;
    pending_name = id + ".pending";
    assert(chunks::OwnedPayloadFile::Reserve(root_path, "parent/payload", receipts.string(),
                                             id, mapping, &error));
    root_opened = false;
    exchanged = false;
    armed = true;
    auto file = chunks::OwnedPayloadFile::CreateFromReservation(
        root_path, "parent/payload", receipts.string(), id, mapping, &error);
    armed = false;
    assert(exchanged);
    assert(!file);
    assert(!fs::exists(fs::path(root_path) / "parent/payload"));
    auto orphan = fs::path(moved_path) / (replace_parent ? "payload" : "parent/payload");
    assert(fs::is_regular_file(orphan));
    assert(fs::file_size(orphan) == 0);
    assert(fs::is_regular_file(receipts / id));
    assert(fs::is_regular_file(receipts / pending_name));
    fs::remove_all(base);
  }
  // A replacement already present at admission is rejected before opening a
  // payload or publishing the final claim, for both the root and its parent.
  for (bool replace_parent : {false, true}) {
    char name[] = "/tmp/chunks-reservation-preopen-XXXXXX";
    char* directory = ::mkdtemp(name);
    assert(directory);
    auto base = fs::path(directory);
    auto root = base / "selected";
    auto receipts = base / "private";
    fs::create_directories(root / "parent");
    fs::create_directory(receipts);
    fs::permissions(receipts, fs::perms::owner_all, fs::perm_options::replace);
    std::string id(64, 'a'), mapping(64, 'b'), error;
    assert(chunks::OwnedPayloadFile::Reserve(root.string(), "parent/payload",
                                             receipts.string(), id, mapping, &error));
    auto replaced = replace_parent ? root / "parent" : root;
    fs::rename(replaced, base / "original");
    fs::create_directories(root / "parent");
    auto file = chunks::OwnedPayloadFile::CreateFromReservation(
        root.string(), "parent/payload", receipts.string(), id, mapping, &error);
    assert(!file);
    assert(!fs::exists(root / "parent/payload"));
    assert(!fs::exists(receipts / id));
    assert(fs::is_regular_file(receipts / (id + ".pending")));
    fs::remove_all(base);
  }
  std::cout << "CHUNKS_RESERVATION_ROOT_AND_PARENT_RACES=REFUSED\n";
}
