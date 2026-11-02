"""Offline guards for the real-rTorrent pre-existing fixture control."""
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import preload_disposable_magnet as preload
from public_fixture_collector import bstr


class PreloadDisposableMagnetTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name) / "chunks-real-peer"
        root.mkdir()
        (root / "magnet0").mkdir()
        (root / "magnet0" / "chunks-precompleted-control.bin").write_bytes(b"x" * 65536)
        (Path(self.temp.name) / "chunks-disposable" / "data").mkdir(parents=True)
        name = b"test.bin"
        info = b"d6:lengthi1e4:name" + bstr(name) + b"12:piece lengthi1e6:pieces" + bstr(hashlib.sha1(b"x").digest()) + b"e"
        (root / "magnet.torrent").write_bytes(b"d4:info" + info + b"e")
        self.hash = hashlib.sha1(info).hexdigest()
        self.receipt = root / "PEER-RECEIPT.json"
        self.receipt.write_text(json.dumps({"fixtures": {"magnet": {"hash": self.hash,
            "native_verified_bytes": 1, "prefix_bytes": 1}}}))

    def test_refuses_live_before_connecting(self):
        with mock.patch.dict(os.environ, {"CHUNKS_DISPOSABLE": "0", "RUNNER_TEMP": self.temp.name}), \
             mock.patch.object(preload.xmlrpc.client, "ServerProxy") as server:
            with self.assertRaises(SystemExit):
                preload.main()
            server.assert_not_called()

    def test_refuses_unverified_source_before_connecting(self):
        self.receipt.write_text(json.dumps({"fixtures": {"magnet": {"hash": self.hash,
            "native_verified_bytes": 0, "prefix_bytes": 1}}}))
        with mock.patch.dict(os.environ, {"CHUNKS_DISPOSABLE": "1", "RUNNER_TEMP": self.temp.name}), \
             mock.patch.object(preload.xmlrpc.client, "ServerProxy") as server:
            with self.assertRaises(SystemExit):
                preload.main()
            server.assert_not_called()

    def test_loads_only_fixed_loopback_and_checks_exact_hash(self):
        rpc = mock.Mock()
        getattr(rpc, "load.raw").return_value = 0
        getattr(rpc, "d.name").return_value = "test.bin"
        with mock.patch.dict(os.environ, {"CHUNKS_DISPOSABLE": "1", "RUNNER_TEMP": self.temp.name}), \
             mock.patch.object(preload.xmlrpc.client, "ServerProxy", return_value=rpc) as server:
            preload.main()
        server.assert_called_once_with("http://127.0.0.1:18080/RPC2", allow_none=False)
        getattr(rpc, "d.name").assert_called_once_with(self.hash)
        self.assertEqual(1, getattr(rpc, "load.raw").call_count)
        self.assertEqual(b"x" * 65536, (Path(self.temp.name) / "chunks-disposable" /
                         "data" / "chunks-precompleted-control.bin").read_bytes())

    def test_existing_disposable_control_is_never_overwritten(self):
        target = Path(self.temp.name) / "chunks-disposable" / "data" / "chunks-precompleted-control.bin"
        target.write_bytes(b"foreign")
        with mock.patch.dict(os.environ, {"CHUNKS_DISPOSABLE": "1", "RUNNER_TEMP": self.temp.name}), \
             mock.patch.object(preload.xmlrpc.client, "ServerProxy") as server:
            with self.assertRaises(FileExistsError):
                preload.main()
            server.assert_not_called()
        self.assertEqual(b"foreign", target.read_bytes())


if __name__ == "__main__":
    unittest.main()
