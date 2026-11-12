import tempfile
import unittest
from pathlib import Path

from tools.p0_delete_payload_witness import Refused, capture, verify


class DeletePayloadWitnessTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "owned").mkdir()
        (self.root / "owned/payload.bin").write_bytes(b"exact torrent payload")
        (self.root / "foreign.txt").write_bytes(b"unrelated user bytes")
        self.manifest = {
            "environment": "disposable-local",
            "info_hash": "a" * 40,
            "targets": ["owned/payload.bin"],
            "canaries": ["foreign.txt"],
        }

    def before(self):
        return capture(self.manifest, self.root)

    def test_actual_absence_and_independently_unchanged_canary(self):
        before = self.before()
        (self.root / "owned/payload.bin").unlink()
        result = verify(before, self.root)
        self.assertEqual(1, result["targets_absent"])
        self.assertEqual(1, result["canaries_unchanged"])
        self.assertIn("no source ownership", result["evidence_tier"])

    def test_rpc_ack_without_file_deletion_cannot_qualify(self):
        before = self.before()
        with self.assertRaises(Refused):
            verify(before, self.root)

    def test_canary_change_or_replacement_refuses(self):
        before = self.before()
        (self.root / "owned/payload.bin").unlink()
        (self.root / "foreign.txt").write_bytes(b"modified")
        with self.assertRaises(Refused):
            verify(before, self.root)
        (self.root / "foreign.txt").unlink()
        (self.root / "foreign.txt").write_bytes(b"unrelated user bytes")
        with self.assertRaises(Refused):
            verify(before, self.root)

    def test_symlinked_target_and_parent_refused(self):
        before = self.before()
        (self.root / "owned/payload.bin").unlink()
        (self.root / "owned/payload.bin").symlink_to(self.root / "foreign.txt")
        with self.assertRaises(Refused):
            verify(before, self.root)
        (self.root / "owned/payload.bin").unlink()
        (self.root / "owned").rmdir()
        (self.root / "owned").symlink_to(self.root)
        with self.assertRaises(Refused):
            verify(before, self.root)

    def test_hardlink_cannot_be_claimed_as_independent_payload(self):
        other = self.root / "another-link.bin"
        other.hardlink_to(self.root / "owned/payload.bin")
        with self.assertRaises(Refused):
            self.before()

    def test_traversal_alias_controls_and_missing_canary_refused(self):
        for bad in ("../escape", "/absolute", "owned/./payload.bin", "owned/../foreign.txt",
                    "owned//payload.bin", "owned\\payload.bin", "owned/line\nbreak"):
            self.manifest["targets"] = [bad]
            with self.subTest(bad=bad), self.assertRaises(Refused):
                self.before()
        self.manifest["targets"] = ["owned/payload.bin"]
        self.manifest["canaries"] = ["OWNED/PAYLOAD.BIN"]
        with self.assertRaises(Refused):
            self.before()
        self.manifest["canaries"] = ["foreign.txt"]
        before = self.before()
        (self.root / "owned/payload.bin").unlink()
        (self.root / "foreign.txt").unlink()
        with self.assertRaises(Refused):
            verify(before, self.root)

    def test_other_environment_root_does_not_inherit_witness(self):
        before = self.before()
        other = self.root / "other"
        other.mkdir()
        with self.assertRaises(Refused):
            verify(before, other)


if __name__ == "__main__":
    unittest.main()
