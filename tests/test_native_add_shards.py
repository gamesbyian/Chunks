"""Offline fail-closed checks for full two-emulator native Add acceptance."""
import json
import struct
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from verify_native_add_shards import verify, BASELINE_EXECUTED_TESTS


class ShardedNativeAddTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.sha = "a" * 40
        self.names = ("one-real-compose.png", "two-real-compose.png")
        for shard in (0, 1):
            folder = self.root / f"chunks-native-add-receipt-shard-{shard}"
            folder.mkdir()
            cases = [
                {"id_sha256": f"{shard * 181 + index:064x}", "outcome": "passed"}
                for index in range(181)
            ]
            data = {
                "schema_version": 1,
                "source_sha": self.sha,
                "suite": f"api35-native-add-shard-{shard}",
                "reported_job_status": "success",
                "evidence_executed": True,
                "invalid_reports": [],
                "junit": {"reports": 12, "tests": 181, "executed": 181,
                          "skipped": 0, "failed": 0},
                "test_cases": cases,
                "test_identity_coverage_complete": True,
                "timing": {"slowest_classes": []},
            }
            (folder / "digest.json").write_text(json.dumps(data))
            (self.root / f"chunks-native-add-atlas-shard-{shard}").mkdir()
        for shard, filename in enumerate(self.names):
            file = self.root / f"chunks-native-add-atlas-shard-{shard}" / filename
            file.write_bytes(b"\x89PNG\r\n\x1a\n" + b"\x00\x00\x00\rIHDR" +
                             struct.pack(">II", 1080, 2400))

    def receipt(self, index):
        path = self.root / f"chunks-native-add-receipt-shard-{index}" / "digest.json"
        return path, json.loads(path.read_text())

    def change(self, index, edit):
        path, data = self.receipt(index)
        edit(data)
        path.write_text(json.dumps(data))

    def check(self):
        return verify(self.root, 2, self.sha, self.names)

    def test_all_genuine_shards_and_distributed_screenshots_are_required(self):
        result = self.check()
        self.assertEqual(BASELINE_EXECUTED_TESTS, result["junit"]["executed"])
        self.assertEqual(2, result["mandatory_real_png_count"])
        self.assertEqual({"one-real-compose.png": [0], "two-real-compose.png": [1]},
                         result["screenshot_shards"])

    def test_reject_missing_shard(self):
        (self.root / "chunks-native-add-receipt-shard-1" / "digest.json").unlink()
        with self.assertRaises(FileNotFoundError):
            self.check()

    def test_reject_incomplete_per_case_receipt(self):
        self.change(0, lambda d: d["test_cases"].pop())
        with self.assertRaises(ValueError):
            self.check()

    def test_reject_duplicate_test_across_shards(self):
        first = self.receipt(0)[1]["test_cases"][0]["id_sha256"]
        self.change(1, lambda d: d["test_cases"][0].update(id_sha256=first))
        with self.assertRaises(ValueError):
            self.check()

    def test_reject_under_baseline_even_if_shards_claim_green(self):
        for index in (0, 1):
            def trim(d):
                d["test_cases"] = d["test_cases"][:100]
                d["junit"]["tests"] = 100
                d["junit"]["executed"] = 100
            self.change(index, trim)
        with self.assertRaisesRegex(ValueError, "baseline"):
            self.check()

    def test_reject_shard_without_executed_evidence(self):
        self.change(0, lambda d: d.update(evidence_executed=False))
        with self.assertRaises(ValueError):
            self.check()

    def test_reject_missing_png_or_invalid_signature(self):
        path = self.root / "chunks-native-add-atlas-shard-1" / self.names[1]
        path.unlink()
        with self.assertRaisesRegex(ValueError, "screenshot"):
            self.check()
        path.write_bytes(b"not a PNG")
        with self.assertRaisesRegex(ValueError, "PNG"):
            self.check()

    def test_reject_wrong_source(self):
        self.change(0, lambda d: d.update(source_sha="b" * 40))
        with self.assertRaisesRegex(ValueError, "source"):
            self.check()

    def test_workflow_has_real_two_shards_and_always_uploads_receipts(self):
        workflow = (ROOT / ".github/workflows/native-add-instrumentation.yml").read_text()
        shell = (ROOT / "tools/native_add_review.sh").read_text()
        self.assertIn("shard: [0, 1]", workflow)
        self.assertIn('CHUNKS_NATIVE_ADD_SHARD_COUNT: "2"', workflow)
        self.assertIn('CHUNKS_NATIVE_ADD_SHARD_INDEX: ${ matrix.shard }}', workflow)
        self.assertIn('if: always()', workflow)
        self.assertIn('if-no-files-found: error', workflow)
        self.assertIn('--shards 2 --sha "$GITHUB_SHA"', workflow)
        self.assertIn('testInstrumentationRunnerArguments.numShards', shell)
        self.assertIn('testInstrumentationRunnerArguments.shardIndex', shell)
        self.assertIn('testInstrumentationRunnerArguments.notClass', shell)
        self.assertIn('screenshot_validation=delegated_to_coordinator', shell)
        self.assertNotIn('timeout-minutes: 35', workflow)


if __name__ == "__main__":
    unittest.main()
