#include "owned_payload_file.h"

#include <cerrno>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>
#include <unistd.h>

namespace fs = std::filesystem;
namespace {
int blocked_hardlinks = 0;
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
extern "C" int __wrap_linkat(int, const char*, int, const char*, int) {
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

  Check(chunks::OwnedPayloadFile::Reserve(payload.string(), "folder/part.bin",
          receipts.string(), id, mapping, &error),
        "durable prospective reservation must work without hardlinks");
  Check(fs::is_regular_file(receipts / (id + ".pending")),
        "pending receipt must exist after checked fsync");
  auto owned = chunks::OwnedPayloadFile::CreateFromReservation(
      payload.string(), "folder/part.bin", receipts.string(), id, mapping, &error);
  Check(owned != nullptr, "exclusive final claim must work without hardlinks");
  Check(blocked_hardlinks >= 2, "test did not actually deny linkat");
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
            << blocked_hardlinks << '\n';
  return 0;
}
