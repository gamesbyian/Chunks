#include "owned_payload_file.h"

#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>
#include <unistd.h>

namespace fs = std::filesystem;
namespace {
int blocked_hardlinks = 0;
int forbidden_temporary_deletions = 0;
bool checking_publication = false;
std::string foreign_source, injected_temporary;
void Check(bool ok, const char* reason) {
  if (!ok) {
    std::cerr << "CHUNKS_RECEIPT_NOHARDLINK_FAILED: " << reason << '\n';
    std::exit(83);
  }
}
std::string Read(const fs::path& path) {
  std::ifstream stream(path, std::ios::binary);
  return std::string(std::istreambuf_iterator<char>(stream), {});
}
void Write(const fs::path& path, const std::string& bytes) {
  std::ofstream stream(path, std::ios::binary | std::ios::trunc);
  stream << bytes;
}
} // namespace

// A deterministic Android-like private-filesystem restriction. The actual
// Android API29/35 runtime must still be tested independently on-device.
extern "C" int __real_unlinkat(int, const char*, int);
extern "C" int __wrap_unlinkat(int dir, const char* name, int flags) {
  if (checking_publication && std::strncmp(name, ".tmp-", 5) == 0) {
    ++forbidden_temporary_deletions;
    errno = EPERM;
    return -1;
  }
  return __real_unlinkat(dir, name, flags);
}
extern "C" int __wrap_linkat(int, const char* source, int, const char*, int) {
  if (checking_publication && source[0] == '\0') {
    // At the exact publication boundary, plant a foreign hardlink using
    // the old named temporary convention. The pinned unnamed FD is immune.
    if (!injected_temporary.empty()) {
      Check(::link(foreign_source.c_str(), injected_temporary.c_str()) == 0,
            "could not interpose foreign private temporary");
      injected_temporary.clear();
    }
  }
  ++blocked_hardlinks;
  errno = EPERM;
  return -1;
}

int main() {
  char name[] = "/tmp/chunks-receipt-nohardlink-XXXXXX";
  char* made = ::mkdtemp(name);
  Check(made != nullptr, "could not prepare private host fixture");
  const fs::path base(made), payload = base / "Downloads",
                 receipts = base / "private";
  fs::create_directories(payload);
  fs::create_directory(receipts);
  fs::permissions(receipts, fs::perms::owner_all, fs::perm_options::replace);
  const std::string id(64, 'a'), mapping(64, 'b'), other(64, 'c');
  std::string error;
  const fs::path foreign = base / "foreign-private-name-source";
  Write(foreign, "FOREIGN NAME NEVER DELETED OR LINKED AS CLAIM");
  foreign_source = foreign.string();
  injected_temporary = (receipts / ".tmp-interposed").string();
  checking_publication = true;

  Check(chunks::OwnedPayloadFile::Reserve(payload.string(), "folder/part.bin",
          receipts.string(), id, mapping, &error),
        "durable prospective reservation must work without hardlinks");
  Check(fs::is_regular_file(receipts / (id + ".pending")),
        "pending receipt must exist after checked fsync");
  auto owned = chunks::OwnedPayloadFile::CreateFromReservation(
      payload.string(), "folder/part.bin", receipts.string(), id, mapping, &error);
  Check(owned != nullptr, "exclusive final claim must work without hardlinks");
  checking_publication = false;
  // The old named-temp implementation could link or unlink a substituted
  // hardlink between checks. No named temp is ever consumed or deleted.
  Check(forbidden_temporary_deletions == 0,
        "receipt publication attempted a pathname-only temporary deletion");
  Check(Read(foreign) == "FOREIGN NAME NEVER DELETED OR LINKED AS CLAIM",
        "foreign source bytes changed");
  if (!injected_temporary.empty()) {
    // O_TMPFILE may be unsupported; direct final O_EXCL still must not
    // touch an attacker-owned legacy temporary pathname.
    Check(::link(foreign_source.c_str(), injected_temporary.c_str()) == 0,
          "could not plant foreign temporary on no-O_TMPFILE filesystem");
    injected_temporary.clear();
  }
  Check(Read(receipts / ".tmp-interposed") ==
            "FOREIGN NAME NEVER DELETED OR LINKED AS CLAIM",
        "unrelated temporary hardlink changed");
  Check(blocked_hardlinks >= 0, "impossible linkat counter");
  Check(owned->WriteAt(0, "byte-exact", 10, &error),
        "owned write after no-hardlink receipt must succeed");
  Check(owned->Sync(&error), "owned payload sync failed");
  owned.reset();
  auto restored = chunks::OwnedPayloadFile::Reopen(payload.string(),
      "folder/part.bin", receipts.string(), id, mapping, &error);
  Check(restored != nullptr, "durably claimed file must reopen");
  char actual[11]{};
  Check(restored->ReadAt(0, actual, 10, &error),
        "reopened file cannot be read");
  Check(std::string(actual, 10) == "byte-exact",
        "reopened owned data was corrupted");
  restored.reset();

  // Neither an occupied payload nor a foreign final receipt may be adopted
  // or modified to make this fallback superficially green.
  Write(payload / "foreign.bin", "FOREIGN PAYLOAD REMAINS");
  Check(!chunks::OwnedPayloadFile::Reserve(payload.string(), "foreign.bin",
          receipts.string(), other, mapping, &error),
        "foreign payload was incorrectly reserved");
  Check(Read(payload / "foreign.bin") == "FOREIGN PAYLOAD REMAINS",
        "foreign payload was altered");
  const std::string foreign_receipt_id(64, 'd');
  Write(receipts / (foreign_receipt_id + ".pending"), "FOREIGN RECEIPT REMAINS");
  Check(!chunks::OwnedPayloadFile::Reserve(payload.string(), "another.bin",
          receipts.string(), foreign_receipt_id, mapping, &error),
        "foreign preexisting receipt was incorrectly replaced");
  Check(Read(receipts / (foreign_receipt_id + ".pending")) ==
            "FOREIGN RECEIPT REMAINS", "foreign receipt bytes changed");

  fs::remove_all(base);
  std::cout << "CHUNKS_NOHARDLINK_RECEIPT_FALLBACK=PASS cases=4 linkat_denied="
            << blocked_hardlinks << " temporary_unlinks=" <<
            forbidden_temporary_deletions << '\n';
  return 0;
}
