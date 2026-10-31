// Isolated host acceptance for a future Android ContentResolver PFD-backed
// libtorrent disk adapter. THESE TESTS DO NOT AUTHORIZE ANY FILE'S OWNERSHIP.
#include "native_owned_provider_fd.h"

#include <cassert>
#include <climits>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>
#include <fcntl.h>
#include <unistd.h>

namespace fs = std::filesystem;
using chunks::research::PinnedProviderFd;
static const std::string kGeneration(64, 'a');
static const std::string kForeign = "FOREIGN-BYTES-MUST-REMAIN-UNCHANGED";

struct Lab {
  fs::path base, owner, foreign;
  Lab() {
    char name[] = "/tmp/chunks-provider-fd-XXXXXX";
    char* root = ::mkdtemp(name);
    assert(root);
    base = root;
    owner = base / "document.bin";
    foreign = base / "foreign.bin";
    std::ofstream f(foreign, std::ios::binary);
    f << kForeign;
    f.flush();
    assert(f.good());
  }
  ~Lab() { fs::remove_all(base); }
  int CreateOwner() const {
    int fd = ::open(owner.c_str(), O_RDWR | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    assert(fd >= 0);
    return fd;
  }
};

static std::string Read(const fs::path& path) {
  std::ifstream stream(path, std::ios::binary);
  assert(stream.good());
  return {std::istreambuf_iterator<char>(stream), {}};
}

int main() {
  int cases = 0;
  {
    // Distant piece ranges, explicit fsync and independent descriptor
    // readback; unlinking caller's FD integer does not invalidate pinned I/O.
    Lab s;
    int selected = s.CreateOwner();
    std::string error;
    auto owned = PinnedProviderFd::Bind(selected, 65552, kGeneration, &error);
    assert(owned && error.empty() && owned->DeclaredBytes() == 65552);
    assert(::close(selected) == 0);
    const std::string first(4096, 'a'), second(16, 'z');
    assert(owned->WriteAt(0, first.data(), first.size(), &error));
    assert(owned->WriteAt(65536, second.data(), second.size(), &error));
    assert(owned->Sync(&error));
    std::string actual(16, '\0');
    assert(owned->ReadAt(65536, actual.data(), actual.size(), &error));
    assert(actual == second);
    owned.reset();
    auto independent = ::open(s.owner.c_str(), O_RDONLY | O_NOFOLLOW);
    assert(independent >= 0);
    char check[16]{};
    assert(::pread(independent, check, sizeof(check), 65536) == 16);
    assert(std::string(check, 16) == second);
    assert(::close(independent) == 0);
    assert(Read(s.foreign) == kForeign);
    ++cases;
  }
  {
    // Boundaries must not silently extend the provider document or accept
    // integer overflows after an otherwise legitimate descriptor admission.
    Lab s;
    int source = s.CreateOwner();
    std::string error;
    auto owned = PinnedProviderFd::Bind(source, 8, kGeneration, &error);
    assert(owned);
    assert(owned->WriteAt(0, "abcdefgh", 8, &error));
    assert(!owned->WriteAt(7, "xyz", 3, &error));
    assert(!owned->WriteAt(UINT64_MAX, "a", 1, &error));
    assert(!owned->WriteAt(0, nullptr, 1, &error));
    assert(!owned->ReadAt(7, nullptr, 2, &error));
    assert(owned->Sync(&error));
    assert(::close(source) == 0);
    assert(Read(s.owner) == "abcdefgh");
    ++cases;
  }
  {
    // A recycled caller FD number might now refer to a foreign document:
    // only the original duplicated open-file description can receive bytes.
    Lab s;
    int source = s.CreateOwner();
    std::string error;
    auto owned = PinnedProviderFd::Bind(source, 4, kGeneration, &error);
    assert(owned);
    assert(::close(source) == 0);
    int foreign = ::open(s.foreign.c_str(), O_RDWR | O_CLOEXEC);
    assert(foreign >= 0);
    // Explicitly recycle the original integer if open() did not use it.
    if (foreign != source) {
      assert(::dup2(foreign, source) == source);
      assert(::close(foreign) == 0);
      foreign = source;
    }
    assert(owned->WriteAt(0, "GOOD", 4, &error));
    assert(owned->Sync(&error));
    assert(Read(s.owner) == "GOOD");
    assert(Read(s.foreign) == kForeign);
    assert(::close(foreign) == 0);
    ++cases;
  }
  {
    Lab s;
    int source = s.CreateOwner();
    assert(::close(source) == 0);
    int readonly = ::open(s.owner.c_str(), O_RDONLY | O_CLOEXEC);
    assert(readonly >= 0);
    std::string error;
    assert(!PinnedProviderFd::Bind(readonly, 10, kGeneration, &error));
    assert(::close(readonly) == 0);
    assert(fs::file_size(s.owner) == 0);
    ++cases;
  }
  {
    // Linux pwrite() on O_APPEND FDs appends regardless of the offset.
    // Therefore O_APPEND must be rejected before any payload write.
    Lab s;
    int source = s.CreateOwner();
    assert(::close(source) == 0);
    int append = ::open(s.owner.c_str(), O_RDWR | O_APPEND | O_CLOEXEC);
    assert(append >= 0);
    std::string error;
    assert(!PinnedProviderFd::Bind(append, 10, kGeneration, &error));
    assert(::close(append) == 0);
    ++cases;
  }
  {
    // A surviving alias can mutate status flags shared by dup(). Detect an
    // O_APPEND change before an attempted offset write and refuse outright.
    // This deterministic check is not a substitute for exclusive transfer:
    // concurrent F_SETFL in the last instruction window is still a threat.
    Lab s;
    int source = s.CreateOwner();
    std::string error;
    auto pinned = PinnedProviderFd::Bind(source, 4, kGeneration, &error);
    assert(pinned);
    int flags = ::fcntl(source, F_GETFL);
    assert(flags >= 0 && ::fcntl(source, F_SETFL, flags | O_APPEND) == 0);
    assert(!pinned->WriteAt(0, "test", 4, &error));
    assert(::close(source) == 0);
    assert(fs::file_size(s.owner) == 0);
    ++cases;
  }
  {
    Lab s;
    int pipefd[2]{};
    assert(::pipe(pipefd) == 0);
    std::string error;
    assert(!PinnedProviderFd::Bind(pipefd[0], 10, kGeneration, &error));
    assert(::close(pipefd[0]) == 0);
    assert(::close(pipefd[1]) == 0);
    ++cases;
  }
  {
    Lab s;
    int source = s.CreateOwner();
    assert(::close(source) == 0);
    int writeonly = ::open(s.owner.c_str(), O_WRONLY | O_CLOEXEC);
    assert(writeonly >= 0);
    std::string error;
    assert(!PinnedProviderFd::Bind(writeonly, 10, kGeneration, &error));
    assert(::close(writeonly) == 0);
    ++cases;
  }
  {
    Lab s;
    int source = s.CreateOwner();
    std::string error;
    assert(!PinnedProviderFd::Bind(source, 8, "not-journaled", &error));
    assert(!PinnedProviderFd::Bind(source,
        static_cast<std::uint64_t>(LLONG_MAX) + 1, kGeneration, &error));
    assert(::close(source) == 0);
    ++cases;
  }
  {
    // No data is written, but an exact empty-file declaration is legal.
    Lab s;
    int source = s.CreateOwner();
    std::string error;
    auto owned = PinnedProviderFd::Bind(source, 0, kGeneration, &error);
    assert(owned);
    assert(owned->WriteAt(0, nullptr, 0, &error));
    assert(owned->ReadAt(0, nullptr, 0, &error));
    assert(owned->Sync(&error));
    assert(::close(source) == 0);
    assert(fs::file_size(s.owner) == 0);
    ++cases;
  }
  std::cout << "CHUNKS_PINNED_PROVIDER_FD=PASS cases=" << cases
            << " tier=host-linux-no-android-provider" << std::endl;
}
