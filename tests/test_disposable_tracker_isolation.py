"""Only disposable real-peer acceptance may strip public discovery metadata."""
import hashlib
import unittest

from tools.disposable_tracker_isolation import (
    LOOPBACK_TRACKER, isolated_public_v1_metainfo,
)
from tools.public_fixture_collector import bstr, parse_bencode


class DisposableTrackerIsolationTests(unittest.TestCase):
    public = b"https://public-tracker.example/announce"

    def torrent(self, info=None):
        if info is None:
            info = (b"d6:lengthi4e4:name4:test12:piece lengthi16384e"
                    b"6:pieces20:" + bytes(range(20)) + b"e")
        return b"d8:announce" + bstr(self.public) + b"4:info" + info + b"e"

    def test_public_publisher_info_bytes_and_hash_remain_identical(self):
        original = self.torrent()
        isolated = isolated_public_v1_metainfo(original)
        previous, original_info = parse_bencode(original)
        current, isolated_info = parse_bencode(isolated)
        self.assertEqual(previous[b"info"], current[b"info"])
        self.assertEqual(original_info, isolated_info)
        self.assertEqual(hashlib.sha1(original_info).hexdigest(),
                         hashlib.sha1(isolated_info).hexdigest())
        self.assertEqual(LOOPBACK_TRACKER, current[b"announce"])
        self.assertNotIn(self.public, isolated)
        self.assertEqual(set(current), {b"info", b"announce"})
        self.assertEqual(isolated, isolated_public_v1_metainfo(isolated))

    def test_private_torrents_and_unknown_identity_formats_refuse_conversion(self):
        private = (b"d6:lengthi4e4:name4:test12:piece lengthi16384e"
                   b"6:pieces20:" + bytes(range(20)) + b"7:privatei1ee")
        hybrid = (b"d6:lengthi4e12:meta versioni2e4:name4:test"
                  b"12:piece lengthi16384e6:pieces20:" + bytes(range(20)) + b"e")
        for raw in (self.torrent(private), self.torrent(hybrid),
                    self.torrent()[:-2], b"d4:infoi0ee"):
            with self.subTest(length=len(raw)):
                with self.assertRaises(ValueError):
                    isolated_public_v1_metainfo(raw)

    def test_never_replaces_publisher_piece_hashes_with_synthetic_values(self):
        info = (b"d6:lengthi4e4:name4:test12:piece lengthi16384e"
                b"6:pieces20:" + b"z" * 20 + b"e")
        isolated = isolated_public_v1_metainfo(self.torrent(info))
        decoded, persisted_info = parse_bencode(isolated)
        self.assertEqual(info, persisted_info)
        self.assertEqual(b"z" * 20, decoded[b"info"][b"pieces"])


if __name__ == "__main__":
    unittest.main()
