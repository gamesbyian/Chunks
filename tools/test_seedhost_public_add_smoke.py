import pathlib
import sys
from types import SimpleNamespace
from unittest.mock import patch
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import seedhost_public_add_smoke as s

HASH = "a1" * 20
DIR = "/home/dev/ChunksFixtures"

def args(apply=False, confirm=HASH):
    return SimpleNamespace(archive_id="fixture", commons_file=None,
        destination=DIR, max_total_mib=512,
        rutorrent_url="https://test.seedhost.eu/rutorrent/",
        apply=apply, confirm_hash=confirm)

def fixture():
    return ({"source": "internet-archive",
             "source_url": "https://archive.org/details/public",
             "torrent": {"infohash_v1": HASH, "total_bytes": 8192}},
            b"not-an-actual-test-torrent-only-a-mocked-RPC-payload")

class Server:
    def __init__(self, url, user, password):
        self.calls = []
        self.present = False
        self.directory = DIR
        self.started = 0
        self.multi = False
        self.name = "Public example"
        self.fail_load = False
    def call(self, method, *args):
        self.calls.append(method)
        if method == "d.multicall2":
            return [[HASH]] if self.present else []
        if method == "load.raw_verbose":
            if self.fail_load:
                raise OSError("network loss after command")
            self.present = True
            return 0
        if method == "d.directory":
            return self.directory
        if method == "d.is_multi_file":
            return int(self.multi)
        if method == "d.name":
            return self.name
        if method == "d.state":
            return self.started
        raise AssertionError("Unexpected RPC method")

class LiveTest(unittest.TestCase):
    def test_preview_is_readonly_and_no_connection(self):
        with patch.object(s, "public_fixture", return_value=fixture()):
            result = s.execute(args(False), factory=lambda *params: self.fail("Connected during preview"))
        self.assertEqual("preview_only", result["result"])

    def test_explicit_exact_hash_needed_before_connecting(self):
        with patch.object(s, "public_fixture", return_value=fixture()):
            with self.assertRaises(ValueError):
                s.execute(args(True, "b2" * 20),
                          factory=lambda *params: self.fail("Connected without confirmation"))

    def test_successful_load_and_verified_stopped_readback(self):
        server = Server(None, None, None)
        with patch.object(s, "public_fixture", return_value=fixture()):
            result = s.execute(args(True), factory=lambda *params: server)
        self.assertEqual("verified_stopped_in_destination", result["result"])
        self.assertEqual(["d.multicall2", "load.raw_verbose", "d.multicall2",
                          "d.directory", "d.is_multi_file", "d.name", "d.state"], server.calls)

    def test_multifile_parent_add_appends_torrent_name(self):
        server = Server(None, None, None)
        server.multi = True
        server.name = "Public example"
        server.directory = DIR + "/" + server.name
        with patch.object(s, "public_fixture", return_value=fixture()):
            result = s.execute(args(True), factory=lambda *params: server)
        self.assertEqual("verified_stopped_in_destination", result["result"])
        self.assertEqual(server.directory, result["observed_directory"])

    def test_multifile_parent_root_cannot_silently_flatten(self):
        server = Server(None, None, None)
        server.multi = True
        server.name = "Public example"
        # d.directory_base.set would flatten the torrent here; must refuse it.
        server.directory = DIR
        with patch.object(s, "public_fixture", return_value=fixture()):
            result = s.execute(args(True), factory=lambda *params: server)
        self.assertEqual("readback_mismatch", result["result"])

    def test_duplicate_refuses_write(self):
        server = Server(None, None, None)
        server.present = True
        with patch.object(s, "public_fixture", return_value=fixture()):
            result = s.execute(args(True), factory=lambda *params: server)
        self.assertEqual("already_present_no_write", result["result"])
        self.assertEqual(["d.multicall2"], server.calls)

    def test_ambiguous_load_never_retries(self):
        server = Server(None, None, None)
        server.fail_load = True
        with patch.object(s, "public_fixture", return_value=fixture()):
            result = s.execute(args(True), factory=lambda *params: server)
        self.assertEqual("outcome_uncertain", result["result"])
        self.assertEqual(1, server.calls.count("load.raw_verbose"))

    def test_directory_mismatch_is_not_verified(self):
        server = Server(None, None, None)
        server.directory = "/somewhere-else"
        with patch.object(s, "public_fixture", return_value=fixture()):
            result = s.execute(args(True), factory=lambda *params: server)
        self.assertEqual("readback_mismatch", result["result"])

    def test_destination_and_https_endpoint_validation(self):
        for path in ("relative", "/", "/tmp/../elsewhere", "/tmp//elsewhere", "/tmp/,d.erase="):
            with self.assertRaises(ValueError):
                s.destination(path)
        self.assertEqual("https://test.seedhost.eu/rutorrent/plugins/httprpc/action.php",
                         s.rpc_endpoint("https://test.seedhost.eu/rutorrent/"))
        for url in ("http://test.seedhost.eu/rutorrent/",
                    "https://evil.example/rutorrent/",
                    "https://user:pass@test.seedhost.eu/rutorrent/",
                    "https://test.seedhost.eu/rutorrent/?token=secret"):
            with self.assertRaises(ValueError):
                s.rpc_endpoint(url)

if __name__ == "__main__":
    unittest.main()
