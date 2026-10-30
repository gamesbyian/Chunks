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
// Every safety witness MUST use this, never libc assert(), whose side effects
// and checks disappear in the actual test configuration.
#define CHECK(condition) do { \
  if (!(condition)) { \
    std::cerr << "CHUNKS_NATIVE_SESSION_CHECK_FAILED " << __FILE__ \
              << ":" << __LINE__ << " " << #condition << "\n"; \
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



// Process-boundary native receipt proof. First session stops before writing
// any payload but saves pending path ownership; an unrelated external actor
// then occupies the same destination before a second real native session.
// The second session must fail, never adopt or overwrite the occupant.
void exerciseRestartedPendingCollision() {
  Sandbox box;
  auto torrent_bytes = fixtureTorrent();
  auto run = [&](bool expect_refusal) {
    lt::settings_pack settings;
    settings.set_str(lt::settings_pack::listen_interfaces, "127.0.0.1:0");
    settings.set_bool(lt::settings_pack::enable_dht, false);
    settings.set_bool(lt::settings_pack::enable_lsd, false);
    lt::session_params owned(settings);
    owned.disk_io_constructor =
        chunks::MakeOwnedDiskConstructor(box.receipts.string());
    lt::session session(owned);
    auto atp = lt::load_torrent_buffer(
        {torrent_bytes.data(), static_cast<std::ptrdiff_t>(torrent_bytes.size())});
    atp.save_path = box.public_root.string();
    atp.flags |= lt::torrent_flags::paused;
    atp.flags &= ~lt::torrent_flags::auto_managed;
    lt::error_code error;
    auto handle = session.add_torrent(std::move(atp), error);
    CHECK(!error && handle.is_valid());
    bool noticed = false;
    for (int i = 0; i < 150; ++i) {
      if (expect_refusal) {
        if (handle.status().errc) { noticed = true; break; }
      } else {
        for (auto const& entry : fs::directory_iterator(box.receipts)) {
          if (entry.path().extension() == ".pending") noticed = true;
        }
        if (noticed) break;
      }
      std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }
    CHECK(noticed);
  };
  run(false);
  CHECK(!fs::exists(box.public_root / "probe.bin"));
  const std::string foreign(16 * 1024, 'F');
  {
    std::ofstream injected(box.public_root / "probe.bin", std::ios::binary);
    CHECK(static_cast<bool>(injected));
    injected.write(foreign.data(), static_cast<std::streamsize>(foreign.size()));
    CHECK(static_cast<bool>(injected));
  }
  run(true);
  std::ifstream occupant(box.public_root / "probe.bin", std::ios::binary);
  CHECK(static_cast<bool>(occupant));
  const std::string after{std::istreambuf_iterator<char>(occupant), {}};
  CHECK(after == foreign);
  std::cout << "CHUNKS_OWNED_RESTARTED_PENDING_COLLISION=PASS\n";
}

// Positive proof: a real native libtorrent seed sends a piece over loopback
// to a second native libtorrent session using the receipt-owned disk backend.
// Completion must be observed from libtorrent AND compared to exact file bytes.
// The seeder lives in its own disposable directory and uses built-in disk I/O;
// the downloader uses ONLY the custom writer under test.
void exerciseLoopbackTransfer() {
  Sandbox box;
  fs::path const seed_dir = box.root / "seed-only";
  fs::create_directories(seed_dir);
  const std::string expected(16 * 1024, 'x');
  {
    std::ofstream payload(seed_dir / "probe.bin", std::ios::binary);
    CHECK(static_cast<bool>(payload));
    payload.write(expected.data(), static_cast<std::streamsize>(expected.size()));
    CHECK(static_cast<bool>(payload));
  }
  auto const torrent_bytes = fixtureTorrent();
  lt::settings_pack seed_settings;
  seed_settings.set_str(lt::settings_pack::listen_interfaces, "127.0.0.1:0");
  seed_settings.set_bool(lt::settings_pack::enable_dht, false);
  seed_settings.set_bool(lt::settings_pack::enable_lsd, false);
  seed_settings.set_bool(lt::settings_pack::enable_upnp, false);
  seed_settings.set_bool(lt::settings_pack::enable_natpmp, false);
  lt::session seed(seed_settings);
  auto source_params = lt::load_torrent_buffer(
      {torrent_bytes.data(), static_cast<std::ptrdiff_t>(torrent_bytes.size())});
  source_params.save_path = seed_dir.string();
  source_params.flags &= ~lt::torrent_flags::paused;
  source_params.flags &= ~lt::torrent_flags::auto_managed;
  source_params.flags |= lt::torrent_flags::seed_mode;
  lt::error_code ec;
  auto seeded = seed.add_torrent(std::move(source_params), ec);
  CHECK(!ec && seeded.is_valid());

  lt::settings_pack download_settings;
  download_settings.set_str(lt::settings_pack::listen_interfaces, "127.0.0.1:0");
  download_settings.set_bool(lt::settings_pack::enable_dht, false);
  download_settings.set_bool(lt::settings_pack::enable_lsd, false);
  download_settings.set_bool(lt::settings_pack::enable_upnp, false);
  download_settings.set_bool(lt::settings_pack::enable_natpmp, false);
  lt::session_params owned_params(download_settings);
  owned_params.disk_io_constructor =
      chunks::MakeOwnedDiskConstructor(box.receipts.string());
  {
    lt::session owned(owned_params);
    auto target_params = lt::load_torrent_buffer(
        {torrent_bytes.data(), static_cast<std::ptrdiff_t>(torrent_bytes.size())});
    target_params.save_path = box.public_root.string();
    target_params.flags &= ~lt::torrent_flags::paused;
    target_params.flags &= ~lt::torrent_flags::auto_managed;
    auto target = owned.add_torrent(std::move(target_params), ec);
    CHECK(!ec && target.is_valid());

    bool connected = false;
    bool completed = false;
    for (int i = 0; i < 400; ++i) {
      if (!connected && seed.listen_port() != 0) {
        try {
          target.connect_peer(lt::tcp::endpoint(
              lt::address_v4::loopback(), seed.listen_port()));
          connected = true;
        } catch (std::exception const&) {
          // The initial torrent checking state may reject manual peers.
        }
      }
      auto const status = target.status();
      CHECK(!status.errc);
      if (status.is_seeding) {
        completed = true;
        break;
      }
      std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }
    CHECK(connected);
    CHECK(completed);
    CHECK(target.status().total_payload_download >=
          static_cast<std::int64_t>(expected.size()));
  } // own native session must flush all bytes before destruction

  std::ifstream payload(box.public_root / "probe.bin", std::ios::binary);
  CHECK(static_cast<bool>(payload));
  const std::string received{std::istreambuf_iterator<char>(payload), {}};
  CHECK(received == expected);
  payload.close();
  std::cout << "CHUNKS_OWNED_LOOPBACK_TRANSFER=PASS size="
            << received.size() << "\n";

  // Fully received ownership is not transferable to a newly planted file
  // with the same relative pathname. Preserve the original real payload at
  // a new name, replace its old name with unrelated same-size bytes, then
  // start an actual second native session against the durable receipts.
  fs::rename(box.public_root / "probe.bin",
             box.public_root / "original-owned-preserved.bin");
  const std::string foreign(expected.size(), 'F');
  {
    std::ofstream planted(box.public_root / "probe.bin", std::ios::binary);
    CHECK(static_cast<bool>(planted));
    planted.write(foreign.data(), static_cast<std::streamsize>(foreign.size()));
    CHECK(static_cast<bool>(planted));
  }
  {
    lt::session_params restarted(download_settings);
    restarted.disk_io_constructor =
        chunks::MakeOwnedDiskConstructor(box.receipts.string());
    lt::session second(restarted);
    auto revived = lt::load_torrent_buffer(
        {torrent_bytes.data(), static_cast<std::ptrdiff_t>(torrent_bytes.size())});
    revived.save_path = box.public_root.string();
    revived.flags |= lt::torrent_flags::paused;
    revived.flags &= ~lt::torrent_flags::auto_managed;
    auto handle = second.add_torrent(std::move(revived), ec);
    CHECK(!ec && handle.is_valid());
    bool refused = false;
    for (int i = 0; i < 150; ++i) {
      if (handle.status().errc) { refused = true; break; }
      std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }
    CHECK(refused);
  }
  std::ifstream planted(box.public_root / "probe.bin", std::ios::binary);
  CHECK(static_cast<bool>(planted));
  const std::string after{std::istreambuf_iterator<char>(planted), {}};
  CHECK(after == foreign);
  std::ifstream original(box.public_root / "original-owned-preserved.bin",
                         std::ios::binary);
  CHECK(static_cast<bool>(original));
  const std::string preserved{std::istreambuf_iterator<char>(original), {}};
  CHECK(preserved == expected);
  std::cout << "CHUNKS_OWNED_RESTARTED_COMPLETED_COLLISION=PASS\n";
}

int main(int argc, char** argv) {
  if (argc == 2 && std::string(argv[1]) == "--self-test-fail") {
    CHECK(false); // the CI release build must terminate with code 86
    return 0;
  }
  CHECK(argc == 1);
  exercise(false);
  exercise(true);
  exerciseLoopbackTransfer();
  exerciseRestartedPendingCollision();
  std::cout << "CHUNKS_REAL_LIBTORRENT_HOST_SESSION=PASS cases=5\n";
}
