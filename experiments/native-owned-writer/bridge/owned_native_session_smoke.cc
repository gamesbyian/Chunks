// Executable host-only smoke for the REAL rebuilt libtorrent session using
// Chunks' session_params.disk_io_constructor. Not a simulated disk adapter.
// No Internet, tracker, live user files, or Android app storage is touched.
#include "owned_libtorrent_disk.h"

#include <libtorrent/add_torrent_params.hpp>
#include <libtorrent/create_torrent.hpp>
#include <libtorrent/hasher.hpp>
#include <libtorrent/load_torrent.hpp>
#include <libtorrent/session.hpp>
#include <libtorrent/session_params.hpp>
#include <libtorrent/settings_pack.hpp>
#include <libtorrent/torrent_flags.hpp>
#include <libtorrent/torrent_handle.hpp>
#include <libtorrent/torrent_status.hpp>

#include <cstdlib>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <string>
#include <sys/stat.h>
#include <thread>
#include <unistd.h>
#include <vector>

// Deliberately remains active under -O3 -DNDEBUG used by CI Release builds.
// Every safety witness MUST use this, never libc CHECK(), whose side effects
// and checks disappear in the actual test configuration.
#define CHECK(condition) do { \
  if (!(condition)) { \
    std::cerr << "CHUNKS_NATIVE_SESSION_CHECK_FAILED " << __FILE__ \
              << ":" << __LINE__ << " " << #condition << "\\n"; \
    std::exit(86); \
  } \
} while (false)

namespace fs = std::filesystem;
namespace lt = libtorrent;

struct Sandbox {
  fs::path root, public_root, receipts;
  Sandbox() {
    char name[] = "/tmp/chunks-owned-native-session-XXXXXX";
    auto* dir = ::mkdtemp(name);
    CHECK(dir);
    root = dir;
    public_root = root / "public";
    receipts = root / "private";
    fs::create_directories(public_root);
    fs::create_directories(receipts);
    CHECK(::chmod(receipts.c_str(), 0700) == 0);
  }
  ~Sandbox() { fs::remove_all(root); }
};

std::vector<char> fixtureTorrent() {
  // Single v1 piece; the declared payload itself is NOT put on disk.
  std::vector<char> data(16 * 1024, 'x');
  lt::hasher h;
  h.update({data.data(), static_cast<std::ptrdiff_t>(data.size())});
  lt::create_torrent t({lt::create_file_entry{"probe.bin", static_cast<std::int64_t>(data.size())}},
                       16 * 1024, lt::create_torrent::v1_only);
  t.set_hash(lt::piece_index_t{0}, h.final());
  return t.generate_buf();
}

void exercise(bool plant_foreign) {
  Sandbox box;
  auto payload_path = box.public_root / "probe.bin";
  const std::string foreign = "DO NOT REPLACE UNRELATED USER DATA";
  if (plant_foreign) {
    std::ofstream out(payload_path, std::ios::binary);
    out << foreign;
    out.close();
  }
  auto bytes = fixtureTorrent();
  auto atp = lt::load_torrent_buffer({bytes.data(), static_cast<std::ptrdiff_t>(bytes.size())});
  atp.save_path = box.public_root.string();
  atp.flags |= lt::torrent_flags::paused;
  atp.flags &= ~lt::torrent_flags::auto_managed;
  lt::settings_pack settings;
  settings.set_str(lt::settings_pack::listen_interfaces, "127.0.0.1:0");
  settings.set_bool(lt::settings_pack::enable_dht, false);
  settings.set_bool(lt::settings_pack::enable_lsd, false);
  settings.set_bool(lt::settings_pack::enable_upnp, false);
  settings.set_bool(lt::settings_pack::enable_natpmp, false);
  lt::session_params sp(settings);
  sp.disk_io_constructor = chunks::MakeOwnedDiskConstructor(box.receipts.string());
  {
    lt::session session(sp);
    lt::error_code ec;
    auto handle = session.add_torrent(std::move(atp), ec);
    CHECK(!ec && handle.is_valid());
    // Allow native check_files to run and report on the network thread.
    bool observed = false;
    for (int i = 0; i < 100; ++i) {
      auto status = handle.status();
      if (plant_foreign) {
        if (status.errc) { observed = true; break; }
      } else {
        if (fs::exists(box.public_root / "probe.bin")) break;
        for (auto const& child : fs::directory_iterator(box.receipts)) {
          if (child.path().extension() == ".pending") observed = true;
        }
        if (observed) break;
      }
      std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }
    if (plant_foreign) {
      CHECK(observed);  // direct native foreign-file intake MUST refuse
      std::ifstream in(payload_path, std::ios::binary);
      std::string after{std::istreambuf_iterator<char>(in), {}};
      CHECK(after == foreign);
    } else {
      CHECK(observed);  // a stopped fresh Add must reserve, not create
      CHECK(!fs::exists(payload_path));
    }
  }
  // Even after native session teardown, foreign bytes remain exactly intact.
  if (plant_foreign) {
    std::ifstream in(payload_path, std::ios::binary);
    std::string after{std::istreambuf_iterator<char>(in), {}};
    CHECK(after == foreign);
  }
}

int main(int argc, char** argv) {
  if (argc == 2 && std::string(argv[1]) == "--self-test-fail") {
    CHECK(false); // the CI release build must terminate with code 86
    return 0;
  }
  CHECK(argc == 1);
  exercise(false);
  exercise(true);
  std::cout << "CHUNKS_REAL_LIBTORRENT_HOST_SESSION=PASS cases=2\n";
}
