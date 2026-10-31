// Native owned-writer process restart regressions for G0 #568.
// Host Linux research only; fork/_exit simulates process death, not power cut,
// FUSE/SD/SAF, Android process management or production JNI integration.
// All files are minted beneath this fixture's own mkdtemp sandbox.
#include "../experiments/native-owned-writer/owned_payload_file.h"

#include <cassert>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>
#include <sys/wait.h>
#include <unistd.h>

namespace fs = std::filesystem;
using chunks::OwnedPayloadFile;
static const std::string kHash(64, 'a');
static const std::string kMapping(64, 'c');
static const std::string kBytes = "OWNED-EXACT-DURABLE-PIECE-BYTES";
static const std::string kForeign = "FOREIGN-SURVIVES-UNCHANGED-AFTER-RESTORE";

struct Sandbox {
  fs::path base, payload, receipts;
  Sandbox() {
    char name[] = "/tmp/chunks-owned-process-XXXXXX";
    auto* root = ::mkdtemp(name);
    assert(root != nullptr);
    base = root;
    payload = base / "public";
    receipts = base / "private";
    assert(fs::create_directory(payload));
    assert(fs::create_directory(receipts));
    fs::permissions(receipts, fs::perms::owner_all, fs::perm_options::replace);
  }
  ~Sandbox() { fs::remove_all(base); }
};

static std::string Read(const fs::path& p) {
  std::ifstream s(p, std::ios::binary);
  assert(s.good());
  return std::string(std::istreambuf_iterator<char>(s), {});
}
static void WriteExclusive(const fs::path& p, const std::string& contents) {
  assert(!fs::exists(p));
  std::ofstream f(p, std::ios::binary | std::ios::out);
  assert(f.good());
  f << contents;
  f.flush();
  assert(f.good());
}

template <typename F>
static void DieAfter(F action) {
  pid_t pid = ::fork();
  assert(pid >= 0);
  if (pid == 0) {
    // No C++ destructors, flushes or normal process shutdown.
    bool succeeded = action();
    ::_exit(succeeded ? 0 : 91);
  }
  int status = 0;
  assert(::waitpid(pid, &status, 0) == pid);
  assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
}

int main() {
  int cases = 0;
  {
    // Fully forced payload and private receipt survives immediate process
    // death. A restarted owner can reopen only its exact previous inode.
    Sandbox s;
    DieAfter([&]() {
      std::string error;
      auto out = OwnedPayloadFile::Create(s.payload.string(), "season/a.bin",
           s.receipts.string(), kHash, kMapping, &error);
      return out && out->WriteAt(0, kBytes.data(), kBytes.size(), &error)
          && out->Sync(&error);
    });
    std::string error;
    auto reopened = OwnedPayloadFile::Reopen(s.payload.string(), "season/a.bin",
        s.receipts.string(), kHash, kMapping, &error);
    assert(reopened);
    std::string data(kBytes.size(), '\0');
    assert(reopened->ReadAt(0, data.data(), data.size(), &error));
    assert(data == kBytes && Read(s.payload / "season/a.bin") == kBytes);
    ++cases;
  }
  {
    // A process dies with only a durable prospective reservation. An outside
    // writer then occupies the prospective path. Restoration MUST refuse
    // actual payload creation, and may not replace identical-length bytes.
    Sandbox s;
    DieAfter([&]() {
      std::string error;
      return OwnedPayloadFile::Reserve(s.payload.string(), "season/b.bin",
          s.receipts.string(), kHash, kMapping, &error);
    });
    const auto foreign = s.payload / "season/b.bin";
    WriteExclusive(foreign, kForeign);
    const auto before = Read(foreign);
    std::string error;
    assert(!OwnedPayloadFile::VerifyReservation(s.payload.string(), "season/b.bin",
        s.receipts.string(), kHash, kMapping, &error));
    error.clear();
    assert(!OwnedPayloadFile::CreateFromReservation(s.payload.string(), "season/b.bin",
        s.receipts.string(), kHash, kMapping, &error));
    assert(Read(foreign) == before);
    assert(fs::is_regular_file(s.receipts / (kHash + ".pending")));
    assert(!fs::exists(s.receipts / kHash));
    ++cases;
  }
  {
    // A real owned file was forced and the child process died. Replacing its
    // public pathname with foreign bytes MUST reject Reopen; the old owner's
    // xattr and receipt must not authorize writes to the foreign new inode.
    Sandbox s;
    DieAfter([&]() {
      std::string error;
      auto out = OwnedPayloadFile::Create(s.payload.string(), "season/c.bin",
          s.receipts.string(), kHash, kMapping, &error);
      return out && out->WriteAt(0, kBytes.data(), kBytes.size(), &error)
          && out->Sync(&error);
    });
    const auto name = s.payload / "season/c.bin";
    const auto former = s.payload / "season/old-owned-displaced.bin";
    fs::rename(name, former);
    WriteExclusive(name, kForeign);
    std::string error;
    auto reopened = OwnedPayloadFile::Reopen(s.payload.string(), "season/c.bin",
        s.receipts.string(), kHash, kMapping, &error);
    assert(!reopened);
    assert(Read(name) == kForeign);
    assert(Read(former) == kBytes);
    assert(fs::exists(s.receipts / kHash));
    ++cases;
  }
  {
    // Completed owner died and a symlink now redirects its old filename to
    // unrelated bytes. Native reopen must not follow the link or mutate
    // either the planted target or the displaced owned file.
    Sandbox s;
    DieAfter([&]() {
      std::string error;
      auto out = OwnedPayloadFile::Create(s.payload.string(), "season/f.bin",
          s.receipts.string(), kHash, kMapping, &error);
      return out && out->WriteAt(0, kBytes.data(), kBytes.size(), &error)
          && out->Sync(&error);
    });
    const auto selected = s.payload / "season/f.bin";
    const auto displaced = s.payload / "season/original-f.bin";
    const auto canary = s.base / "foreign-symlink-target";
    WriteExclusive(canary, kForeign);
    fs::rename(selected, displaced);
    fs::create_symlink(canary, selected);
    std::string error;
    assert(!OwnedPayloadFile::Reopen(s.payload.string(), "season/f.bin",
        s.receipts.string(), kHash, kMapping, &error));
    assert(Read(canary) == kForeign && Read(displaced) == kBytes);
    assert(fs::is_symlink(selected));
    ++cases;
  }
  {
    // The pending reservation's target becomes a symlink during shutdown;
    // neither Verify nor CreateFromReservation may follow it into bytes
    // owned by a completely separate actor.
    Sandbox s;
    DieAfter([&]() {
      std::string error;
      return OwnedPayloadFile::Reserve(s.payload.string(), "season/g.bin",
          s.receipts.string(), kHash, kMapping, &error);
    });
    const auto canary = s.base / "pending-foreign-target";
    WriteExclusive(canary, kForeign);
    const auto selected = s.payload / "season/g.bin";
    fs::create_symlink(canary, selected);
    std::string error;
    assert(!OwnedPayloadFile::VerifyReservation(s.payload.string(), "season/g.bin",
        s.receipts.string(), kHash, kMapping, &error));
    error.clear();
    assert(!OwnedPayloadFile::CreateFromReservation(s.payload.string(), "season/g.bin",
        s.receipts.string(), kHash, kMapping, &error));
    assert(Read(canary) == kForeign && fs::is_symlink(selected));
    ++cases;
  }
  {
    // Positive continuation after reservation-only process death: the exact
    // private receipt and original free destination still match, so a new
    // process can complete creation and persist bytes without abandoning the
    // user's selected destination.
    Sandbox s;
    DieAfter([&]() {
      std::string error;
      return OwnedPayloadFile::Reserve(s.payload.string(), "season/d.bin",
          s.receipts.string(), kHash, kMapping, &error);
    });
    std::string error;
    assert(OwnedPayloadFile::VerifyReservation(s.payload.string(), "season/d.bin",
        s.receipts.string(), kHash, kMapping, &error));
    auto out = OwnedPayloadFile::CreateFromReservation(s.payload.string(),
        "season/d.bin", s.receipts.string(), kHash, kMapping, &error);
    assert(out && out->WriteAt(0, kBytes.data(), kBytes.size(), &error));
    assert(out->Sync(&error));
    out.reset();
    auto reopened = OwnedPayloadFile::Reopen(s.payload.string(), "season/d.bin",
        s.receipts.string(), kHash, kMapping, &error);
    assert(reopened && Read(s.payload / "season/d.bin") == kBytes);
    ++cases;
  }
  {
    // A published ownership receipt is later corrupted while the application
    // is offline; exact file bytes remain, but restart must refuse adoption.
    Sandbox s;
    DieAfter([&]() {
      std::string error;
      auto out = OwnedPayloadFile::Create(s.payload.string(), "season/e.bin",
          s.receipts.string(), kHash, kMapping, &error);
      return out && out->WriteAt(0, kBytes.data(), kBytes.size(), &error)
          && out->Sync(&error);
    });
    fs::resize_file(s.receipts / kHash, 1);
    std::string error;
    assert(!OwnedPayloadFile::Reopen(s.payload.string(), "season/e.bin",
        s.receipts.string(), kHash, kMapping, &error));
    assert(Read(s.payload / "season/e.bin") == kBytes);
    ++cases;
  }
  std::cout << "CHUNKS_OWNED_PROCESS_RESTART=PASS cases=" << cases
            << " tier=linux-fork-no-powerloss" << std::endl;
}
