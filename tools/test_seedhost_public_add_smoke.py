import pathlib
import sys
from types import SimpleNamespace
from unittest.mock import patch
import unittest
import xmlrpc.client

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import seedhost_public_add_smoke as s

HASH = "a1" * 20
DIR = "/home/dev/ChunksFixtures"

def args(apply=False, confirm=HASH):
    return SimpleNamespace(archive_id="fixture", commons_file=None,
        destination=DIR, max_total_mib=512,
        rutorrent_url="https://ianseeds.wood.seedhost.eu/rutorrent/",
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
            if not self.present:
                raise xmlrpc.client.Fault(-501, "Could not find info-hash.")
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
        self.assertEqual(["d.multicall2", "d.name", "load.raw_verbose", "d.multicall2",
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

    def test_session_hidden_duplicate_refuses_load_even_when_main_view_is_empty(self):
        class HiddenServer(Server):
            def call(self, method, *params):
                if method == "d.name":
                    self.calls.append(method)
                    return "Already registered in a different session view"
                return super().call(method, *params)
        server = HiddenServer(None, None, None)
        with patch.object(s, "public_fixture", return_value=fixture()):
            result = s.execute(args(True), factory=lambda *params: server)
        self.assertEqual("already_present_no_write", result["result"])
        self.assertEqual(["d.multicall2", "d.name"], server.calls)

    def test_only_documented_unknown_hash_faults_permit_one_add(self):
        for code, message in (
            (-501, "Could not find info-hash."),
            (-500, "invalid parameters: info-hash not found"),
        ):
            class AbsentServer(Server):
                def call(self, method, *params):
                    if method == "d.name" and not self.present:
                        self.calls.append(method)
                        raise xmlrpc.client.Fault(code, message)
                    return super().call(method, *params)
            server = AbsentServer(None, None, None)
            with self.subTest(code=code), patch.object(s, "public_fixture", return_value=fixture()):
                result = s.execute(args(True), factory=lambda *params: server)
            self.assertEqual("verified_stopped_in_destination", result["result"])
            self.assertEqual(1, server.calls.count("load.raw_verbose"))

    def test_unexpected_or_malformed_exact_lookup_never_writes(self):
        for reply in (xmlrpc.client.Fault(-32601, "Unknown RPC method"), None, 123):
            class FaultingServer(Server):
                def call(self, method, *params):
                    if method == "d.name" and not self.present:
                        self.calls.append(method)
                        if isinstance(reply, Exception):
                            raise reply
                        return reply
                    return super().call(method, *params)
            server = FaultingServer(None, None, None)
            with self.subTest(reply=type(reply).__name__), patch.object(
                    s, "public_fixture", return_value=fixture()):
                with self.assertRaises((xmlrpc.client.Fault, ValueError)):
                    s.execute(args(True), factory=lambda *params: server)
            self.assertEqual(["d.multicall2", "d.name"], server.calls)
            self.assertNotIn("load.raw_verbose", server.calls)

    def test_ambiguous_load_never_retries(self):
        server = Server(None, None, None)
        server.fail_load = True
        with patch.object(s, "public_fixture", return_value=fixture()):
            result = s.execute(args(True), factory=lambda *params: server)
        self.assertEqual("outcome_uncertain", result["result"])
        self.assertEqual(1, server.calls.count("load.raw_verbose"))

    def test_malformed_post_add_flags_never_verify_stopped_or_trigger_replay(self):
        # XML-RPC may legitimately deliver a float/string/bool; none is
        # a confirmed rTorrent int 0/1 even if Python int() coerces it.
        for method in ("d.is_multi_file", "d.state"):
            for malformed in (0.9, "0", False, 2, -1):
                class MalformedFlagServer(Server):
                    def call(self, name, *params):
                        if name == method:
                            self.calls.append(name)
                            return malformed
                        return super().call(name, *params)
                server = MalformedFlagServer(None, None, None)
                with self.subTest(method=method, malformed=repr(malformed)), patch.object(
                        s, "public_fixture", return_value=fixture()):
                    result = s.execute(args(True), factory=lambda *params: server)
                self.assertEqual("acknowledged_pending_readback", result["result"])
                self.assertEqual("ValueError", result["error_class"])
                self.assertEqual(1, server.calls.count("load.raw_verbose"))
                self.assertNotIn("f.priority.set", server.calls)
                self.assertNotIn("d.erase", server.calls)

    def test_directory_mismatch_is_not_verified(self):
        server = Server(None, None, None)
        server.directory = "/somewhere-else"
        with patch.object(s, "public_fixture", return_value=fixture()):
            result = s.execute(args(True), factory=lambda *params: server)
        self.assertEqual("readback_mismatch", result["result"])

    def test_primary_secret_transport_refuses_sibling_tenants_before_opening_network(self):
        # Even direct calls bypassing GitHub's protected workflow constant
        # cannot pass repository credentials to another account or mount.
        refused = [
            "https://other.wood.seedhost.eu/rutorrent/",
            "https://ianseeds.wood.seedhost.eu:8443/rutorrent/",
            "https://ianseeds.wood.seedhost.eu/rutorrent2/",
            "https://ianseeds.wood.seedhost.eu/ianseeds/rutorrent/",
            "https://ianseeds.wood.seedhost.eu/rutorrent//",
            "https://ianseeds.wood.seedhost.eu/rutorrent/../rutorrent2/",
            "https://ianseeds.wood.seedhost.eu/rutorrent/%2e%2e/rutorrent2/",
            "https://ianseeds.wood.seedhost.eu/rutorrent%2fplugins/httprpc/action.php",
            "https://ianseeds.wood.seedhost.eu/rutorrent/plugins/httprpc/action.php/",
            "https://ianseeds.wood.seedhost.eu/RPC1",
            "https://ianseeds.wood.seedhost.eu/RPC2",
            "https://ianseeds.wood.seedhost.eu/rutorrent/?token=not-a-secret",
            "https://@ianseeds.wood.seedhost.eu/rutorrent/",
            "http://ianseeds.wood.seedhost.eu/rutorrent/",
        ]
        with patch.object(s.urllib.request, "build_opener") as opener:
            for url in refused:
                with self.subTest(url=url):
                    with self.assertRaises(ValueError):
                        s.ProtectedRpc(url, "user", "test-not-a-password")
                    with self.assertRaises(ValueError):
                        s.rpc_endpoint(url)
            opener.assert_not_called()

    def test_primary_web_alias_and_exact_rpc_mount_remain_available(self):
        expected = "https://ianseeds.wood.seedhost.eu/rutorrent/plugins/httprpc/action.php"
        for url in (
            "https://ianseeds.wood.seedhost.eu/rutorrent",
            "https://ianseeds.wood.seedhost.eu/rutorrent/",
            expected,
        ):
            with self.subTest(url=url):
                self.assertEqual(expected, s.rpc_endpoint(url))
        self.assertEqual(
            "https://ianseeds.wood.seedhost.eu:443/rutorrent/plugins/httprpc/action.php",
            s.rpc_endpoint("https://ianseeds.wood.seedhost.eu:443/rutorrent/"))

    def test_destination_and_https_endpoint_validation(self):
        for path in ("relative", "/", "/tmp/../elsewhere", "/tmp//elsewhere", "/tmp/,d.erase="):
            with self.assertRaises(ValueError):
                s.destination(path)
        self.assertEqual("https://ianseeds.wood.seedhost.eu/rutorrent/plugins/httprpc/action.php",
                         s.rpc_endpoint("https://ianseeds.wood.seedhost.eu/rutorrent/"))
        for url in ("http://ianseeds.wood.seedhost.eu/rutorrent/",
                    "https://evil.example/rutorrent/",
                    "https://user:pass@ianseeds.wood.seedhost.eu/rutorrent/",
                    "https://ianseeds.wood.seedhost.eu/rutorrent/?token=secret"):
            with self.assertRaises(ValueError):
                s.rpc_endpoint(url)

if __name__ == "__main__":
    unittest.main()
