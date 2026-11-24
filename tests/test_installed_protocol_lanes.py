"""Offline controls for the sharded Universal Add acceptance contract."""
import tempfile
import unittest
from pathlib import Path
from tools.verify_installed_protocol_lanes import LANES, verify

ROOT = Path(__file__).resolve().parents[1]
SHA = "a" * 40
TREE = "b" * 40


class LaneTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        for lane in LANES:
            self.receipt(lane)

    def receipt(self, lane, attempt=1):
        path = self.root / f"chunks-protocol-lane-{lane}-attempt-{attempt}"
        path.mkdir()
        for name, value in (("passed-lane", lane), ("source-sha", SHA), ("source-tree", TREE)):
            (path / name).write_text(value + "\n")
        (path / "installed-identity.txt").write_text(
            f"CHUNKS_PROTOCOL_SOURCE: commit={SHA} tree={TREE} package=io.github.gamesbyian.chunks version=0.1.0-dev+{SHA[:12]}\n"
            + "CHUNKS_INSTALLED_APK: matched size_bytes=42 sha256=" + "c" * 64 + "\n")
        return path

    def test_success_and_failed_job_only_rerun(self):
        self.receipt(LANES[0], 2)
        self.assertEqual({LANES[0]: 2, LANES[1]: 1, LANES[2]: 1},
                         verify(self.root, SHA, TREE, "success"))

    def test_newer_failed_attempt_cannot_be_hidden_by_old_success(self):
        path = self.receipt(LANES[0], 2)
        (path / "passed-lane").unlink()
        with self.assertRaises(OSError):
            verify(self.root, SHA, TREE, "success")

    def test_missing_lane_and_non_green_matrix(self):
        with self.assertRaises(ValueError):
            verify(self.root, SHA, TREE, "failure")
        (self.root / f"chunks-protocol-lane-{LANES[0]}-attempt-1").rename(
            self.root / f"chunks-protocol-lane-{LANES[1]}-attempt-2")
        with self.assertRaises(ValueError):
            verify(self.root, SHA, TREE, "success")

    def test_wrong_source_tree_or_installed_bytes_rejected(self):
        path = self.receipt(LANES[0], 2)
        (path / "source-tree").write_text("d" * 40 + "\n")
        with self.assertRaises(ValueError):
            verify(self.root, SHA, TREE, "success")
        (path / "source-tree").write_text(TREE + "\n")
        identity = path / "installed-identity.txt"
        identity.write_text(identity.read_text().replace("c" * 64, "e" * 64))
        with self.assertRaises(ValueError):
            verify(self.root, SHA, TREE, "success")

    def test_workflow_and_script_preserve_all_journeys(self):
        workflow = (ROOT / ".github/workflows/installed-protocol-client.yml").read_text()
        runner = (ROOT / "tools/installed_protocol_client.sh").read_text()
        self.assertEqual(1, workflow.count("gradle --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest"))
        self.assertIn("fail-fast: false", workflow)
        self.assertIn("needs: protocol-lane", workflow)
        self.assertIn("matrix.lane }}-attempt-", workflow)
        for lane in LANES:
            self.assertIn(lane, workflow)
            self.assertIn(lane + ")", runner)
        for kind in ("TRANSMISSION", "QBITTORRENT", "DELUGE"):
            self.assertEqual(1, runner.count("run_pair " + kind + " "))
        for call in ("run_rtorrent_faults", "run_rtorrent_death", "run_local_add_death",
                     "bash tools/installed_external_document_process_loss.sh"):
            self.assertEqual(1, runner.count(call + " || failed=1"))


if __name__ == "__main__":
    unittest.main()
