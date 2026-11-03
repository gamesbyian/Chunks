"""Rerun-safe native Android artifact reconciliation, without emulator spend."""
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from select_native_add_attempts import select


class NativeAddAttemptSelectionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.sha = "a" * 40

    def fixture(self, shard, attempt, *, status="success", failed=0,
                source_sha=None, include_atlas=True):
        folder = self.root / f"chunks-native-add-receipt-shard-{shard}-attempt-{attempt}"
        folder.mkdir()
        data = {
            "schema_version": 1,
            "source_sha": self.sha if source_sha is None else source_sha,
            "suite": f"api35-native-add-shard-{shard}",
            "run_attempt": str(attempt),
            "reported_job_status": status,
            "evidence_executed": True,
            "junit": {"failed": failed},
        }
        (folder / "digest.json").write_text(json.dumps(data))
        if include_atlas:
            atlas = self.root / f"chunks-native-add-atlas-shard-{shard}-attempt-{attempt}"
            atlas.mkdir()
            (atlas / f"source-{shard}-{attempt}.png").write_bytes(b"fixture only")

    def test_initial_attempt_both_shards_selected(self):
        self.fixture(0, 1)
        self.fixture(1, 1)
        self.assertEqual([(0, 1), (1, 1)], select(self.root, 2, self.sha, "success"))
        self.assertTrue((self.root / "chunks-native-add-receipt-shard-0/digest.json").exists())
        self.assertTrue((self.root / "chunks-native-add-atlas-shard-1/source-1-1.png").exists())

    def test_successful_failed_job_rerun_preserves_sibling_original_evidence(self):
        self.fixture(0, 1, status="failure", failed=1)
        self.fixture(0, 2)
        self.fixture(1, 1)
        self.assertEqual([(0, 2), (1, 1)], select(self.root, 2, self.sha, "success"))
        chosen = json.loads((self.root / "chunks-native-add-receipt-shard-0/digest.json").read_text())
        self.assertEqual("2", chosen["run_attempt"])
        self.assertEqual("success", chosen["reported_job_status"])
        self.assertTrue((self.root / "chunks-native-add-atlas-shard-0/source-0-2.png").exists())
        self.assertFalse((self.root / "chunks-native-add-atlas-shard-0/source-0-1.png").exists())
        self.assertTrue((self.root / "chunks-native-add-receipt-shard-0-attempt-1/digest.json").exists())

    def test_later_failure_must_not_fall_back_to_prior_success(self):
        self.fixture(0, 1)
        self.fixture(0, 2, status="failure", failed=1)
        self.fixture(1, 1)
        with self.assertRaisesRegex(ValueError, "latest attempt did not pass"):
            select(self.root, 2, self.sha, "success")

    def test_failed_matrix_must_not_accept_old_success_without_new_receipt(self):
        self.fixture(0, 1)
        self.fixture(1, 1)
        with self.assertRaisesRegex(ValueError, "did not succeed"):
            select(self.root, 2, self.sha, "failure")

    def test_newer_wrong_source_or_missing_atlas_fails_closed(self):
        self.fixture(0, 1)
        self.fixture(0, 2, source_sha="b" * 40)
        self.fixture(1, 1)
        with self.assertRaisesRegex(ValueError, "identity mismatch"):
            select(self.root, 2, self.sha, "success")

    def test_newest_missing_atlas_never_uses_old_screenshots(self):
        self.fixture(0, 1)
        self.fixture(0, 2, include_atlas=False)
        self.fixture(1, 1)
        with self.assertRaisesRegex(ValueError, "latest atlas"):
            select(self.root, 2, self.sha, "success")

    def test_no_legacy_unversioned_artifact_is_silently_accepted(self):
        (self.root / "chunks-native-add-receipt-shard-0").mkdir()
        self.fixture(1, 1)
        with self.assertRaisesRegex(ValueError, "attempt-scoped"):
            select(self.root, 2, self.sha, "success")


if __name__ == "__main__":
    unittest.main()
