"""The APK gate must include disposable precompleted payload preservation."""
import hashlib
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from finish_installed_magnet_evidence import precompleted_control_preserved


class PrecompletedControlEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.env = {"CHUNKS_CONTROLLED_PEER": "1", "CHUNKS_DISPOSABLE": "1",
                    "GITHUB_RUN_ID": "12345"}
        self.paths = (self.root / "chunks-real-peer/magnet0/chunks-precompleted-control.bin",
                      self.root / "chunks-disposable/data/chunks-precompleted-control.bin")
        original = hashlib.sha256(b"12345").digest() * 2048
        for path in self.paths:
            path.parent.mkdir(parents=True)
            path.write_bytes(original)

    def test_both_exact_originals_are_required(self):
        self.assertIs(precompleted_control_preserved(self.root, self.env), True)
        self.paths[1].write_bytes(b"changed")
        self.assertIs(precompleted_control_preserved(self.root, self.env), False)
        self.paths[1].unlink()
        self.assertIs(precompleted_control_preserved(self.root, self.env), False)

    def test_matching_but_substituted_copies_do_not_pass(self):
        for path in self.paths:
            path.write_bytes(b"x" * 65536)
        self.assertIs(precompleted_control_preserved(self.root, self.env), False)

    def test_alias_to_original_does_not_count_as_two_preserved_files(self):
        self.paths[1].unlink()
        self.paths[1].symlink_to(self.paths[0])
        self.assertIs(precompleted_control_preserved(self.root, self.env), False)

    def test_other_modes_do_not_fabricate_control_evidence(self):
        for env in ({"CHUNKS_CONTROLLED_PEER": "1", "CHUNKS_DISPOSABLE": "0"},
                    dict(self.env, CHUNKS_PUBLIC_DISCOVERY="1")):
            self.assertIsNone(precompleted_control_preserved(self.root, env))
        self.assertIs(precompleted_control_preserved(self.root, dict(self.env, GITHUB_RUN_ID="bad")), False)


if __name__ == "__main__":
    unittest.main()
