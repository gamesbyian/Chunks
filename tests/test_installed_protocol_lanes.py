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
        self.assertIn("if: inputs.artifact_name == ''", workflow)
        self.assertIn("name: ${{ inputs.artifact_name || 'chunks-protocol-apks' }}", workflow)
        self.assertIn("sha256sum --strict --check SHA256SUMS", workflow)
        caller = (ROOT / ".github/workflows/native-add-instrumentation.yml").read_text()
        self.assertIn("artifact_name: ${{ needs.build-once.outputs.artifact-name }}", caller)
        self.assertIn("needs: [select, build-once]", caller)
        self.assertIn("needs: protocol-lane", workflow)
        self.assertIn("matrix.lane }}-attempt-", workflow)
        for lane in LANES:
            self.assertIn(lane, workflow)
            self.assertIn(lane + ")", runner)
        for kind in ("TRANSMISSION", "QBITTORRENT", "DELUGE"):
            self.assertEqual(1, runner.count("run_pair " + kind + " "))
        for call in ("run_rtorrent_faults", "run_rtorrent_death", "run_transmission_stop_death", "run_transmission_remove_death",
                     "run_local_add_death",
                     "bash tools/installed_external_document_process_loss.sh"):
            self.assertEqual(1, runner.count(call + " || failed=1"))


if __name__ == "__main__":
    unittest.main()


class AssumptionSkipTests(unittest.TestCase):
    """A skipped or ignored installed test still prints OK (N tests); it is never a pass."""
    HELPER = ROOT / "tools" / "instrumentation_result.sh"
    USERS = ("installed_protocol_client.sh", "installed_combined_two_servers.sh",
             "installed_remote_delete_data.sh", "installed_partial_resume_death.sh")

    def run_helper(self, codes, summary="OK (1 test)", count=1):
        import subprocess
        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as raw:
            raw.write("".join(f"INSTRUMENTATION_STATUS_CODE: {c}\r\n" for c in codes) + f"\r\n{summary}\r\n")
        self.addCleanup(Path(raw.name).unlink)
        return subprocess.run(["bash", "-c", f'source "{self.HELPER}"; instrumentation_passed "{raw.name}" {count}']).returncode

    def test_only_the_exact_executed_count_passes(self):
        self.assertEqual(0, self.run_helper([1, 0]))
        self.assertEqual(0, self.run_helper([1, 0, 1, 0], "OK (2 tests)", 2))
        self.assertNotEqual(0, self.run_helper([1, -4]), "assumption skip")
        self.assertNotEqual(0, self.run_helper([1, -3]), "ignored")
        self.assertNotEqual(0, self.run_helper([1, 0], "OK (2 tests)", 1), "wrong count")
        self.assertNotEqual(0, self.run_helper([1, 0], "OK (12 tests)", 1), "prefix count")
        self.assertNotEqual(0, self.run_helper([1, -2], "FAILURES!!!"))

    def test_installed_scripts_never_accept_a_bare_ok_summary(self):
        import re
        for name in self.USERS:
            text = (ROOT / "tools" / name).read_text(encoding="utf-8")
            self.assertIn('source "$(dirname "$0")/instrumentation_result.sh"', text, name)
            self.assertIn("instrumentation_passed ", text, name)
            # Only phase-1 kill checks may look for OK, and only as a failure signal.
            for line in text.splitlines():
                if re.search(r"grep -q '\^OK \(", line):
                    self.assertNotIn("!", line.split("grep")[0], (name, line))
