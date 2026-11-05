"""Native offline source-proof regression. CI runs with its installed python3-libtorrent."""
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest

try:
    import libtorrent as lt
except ImportError:
    lt = None

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))


@unittest.skipUnless(lt is not None, "native libtorrent source recheck gate only")
class RealPeerPrefixTest(unittest.TestCase):
    def fixture(self, root, corrupt=False):
        from real_magnet_peer import Peer
        source = root / "magnet"
        source.mkdir()
        data = bytes(range(256)) * 256 + bytes(192 * 1024)
        payload = source / "source.bin"
        payload.write_bytes(data)
        files = lt.file_storage()
        files.add_file("source.bin", len(data))
        create = lt.create_torrent(files, 16 * 1024)
        lt.set_piece_hashes(create, str(source))
        (root / "magnet.torrent").write_bytes(lt.bencode(create.generate()))
        if corrupt:
            with payload.open("r+b") as out:
                out.write(b"\xff")
        fixtures = {"magnet": {"prefix_bytes": 64 * 1024, "native_verified_bytes": 0,
                               "payload_uploaded_bytes": 0, "stages": [], "max_active_peers": 0,
                               "peer_connections": 0, "peer_disconnects": 0, "peer_error_alerts": 0}}
        return Peer(root, "127.0.0.1", fixtures)

    def test_missing_original_prefix_piece_cannot_be_replaced_by_verified_zero_tail(self):
        with tempfile.TemporaryDirectory() as directory:
            peer = self.fixture(Path(directory), corrupt=True)
            try:
                with self.assertRaisesRegex(ValueError, "Original source pieces are unavailable"):
                    peer.new_seed("magnet", "preflight")
            finally:
                peer.close()

    def test_original_prefix_is_accepted_with_other_verified_pieces(self):
        with tempfile.TemporaryDirectory() as directory:
            peer = self.fixture(Path(directory))
            try:
                peer.new_seed("magnet", "preflight")
                handle = peer.active["magnet"][1]
                self.assertTrue(all(handle.status(lt.status_flags_t.query_pieces).pieces[:4]))
                self.assertGreaterEqual(peer.fixtures["magnet"]["native_verified_bytes"], 64 * 1024)
            finally:
                peer.close()

    def test_connected_native_peer_monitor_retains_requests_and_real_upload_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            peer = self.fixture(root)
            target = root / "download"
            target.mkdir()
            session = lt.session({"listen_interfaces": "127.0.0.1:0", "enable_dht": False,
                                  "enable_lsd": False, "enable_upnp": False, "enable_natpmp": False})
            session.add_torrent({"ti": lt.torrent_info(str(root / "magnet.torrent")), "save_path": str(target)})
            errors = []
            def monitor():
                try:
                    peer.monitor()
                except Exception as error:
                    errors.append(error)
            peer.new_seed("magnet", "payload", session.listen_port())
            thread = threading.Thread(target=monitor)
            thread.start()
            try:
                deadline = time.monotonic() + 10
                while time.monotonic() < deadline and not errors:
                    if (peer.events["magnet"].get("in_request", 0) > 0 and
                            peer.events["magnet"].get("blocks_uploaded", 0) > 0):
                        break
                    time.sleep(.1)
            finally:
                peer.close()
                thread.join()
                session.pause()
            self.assertEqual([], errors, "Native alert handling must not kill evidence collection")
            self.assertGreater(peer.fixtures["magnet"].get("peer_connections", 0), 0)
            self.assertGreater(peer.fixtures["magnet"]["payload_uploaded_bytes"], 0)
            self.assertGreater(peer.events["magnet"].get("in_request", 0), 0)
            self.assertGreater(peer.events["magnet"].get("blocks_uploaded", 0), 0)


if __name__ == "__main__":
    unittest.main()
