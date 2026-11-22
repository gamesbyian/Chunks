"""Fail-closed, no-secret diagnostics for slow disposable real-peer preparation."""
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from peer_preparation_progress import NAME, read_progress, write_progress


class PeerPreparationProgressTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def test_only_fixed_milestones_are_reported(self):
        self.assertTrue(write_progress(self.root, "publisher-prefix", "magnet3"))
        self.assertEqual(("publisher-prefix", "magnet3"), read_progress(self.root))
        self.assertTrue(write_progress(self.root, "native-piece-check", "magnet0"))
        self.assertEqual(("native-piece-check", "magnet0"), read_progress(self.root))
        self.assertTrue(write_progress(self.root, "source-verified", "all"))
        self.assertEqual(("source-verified", "all"), read_progress(self.root))

    def test_never_persists_untrusted_uri_or_path(self):
        self.assertRaises(ValueError, write_progress, self.root,
                          "https://private.example/secret", "magnet")
        self.assertRaises(ValueError, write_progress, self.root,
                          "publisher-prefix", "../../secret")
        self.assertFalse((self.root / NAME).exists())

    def test_corrupt_or_untrusted_readback_is_not_printable(self):
        path = self.root / NAME
        for raw in ('{"phase":"publisher-prefix","fixture":"../../secret"}',
                    '{"phase":"unknown","fixture":"magnet"}',
                    '{"phase":"publisher-prefix","fixture":"magnet","url":"private"}',
                    '{"phase":"publisher-prefix","fixture":"magnet"}' + "x" * 300,
                    "not json"):
            with self.subTest(raw=raw[:20]):
                path.write_text(raw)
                self.assertIsNone(read_progress(self.root))

    def test_missing_marker_is_safe_and_no_publisher_work_is_required(self):
        self.assertIsNone(read_progress(self.root))


if __name__ == "__main__":
    unittest.main()
