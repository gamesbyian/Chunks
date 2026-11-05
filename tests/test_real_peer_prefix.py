"""Native offline source-proof regression. CI runs with its installed python3-libtorrent."""
from pathlib import Path
import sys
import tempfile
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
                               "payload_uploaded_bytes": 0, "stages": []}}
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


if __name__ == "__main__":
    unittest.main()
