"""Offline fail-closed checks for full two-emulator native Add acceptance."""
import json
import struct
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from verify_native_add_shards import verify, BASELINE_JUNIT_TESTS, BASELINE_EXECUTED_TESTS


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
        self.assertGreaterEqual(result["junit"]["executed"], BASELINE_EXECUTED_TESTS)
        self.assertGreaterEqual(result["junit"]["tests"], BASELINE_JUNIT_TESTS)
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
        with self.assertRaisesRegex(ValueError, "coverage"):
            self.check()

    def test_accept_one_additional_opt_in_native_research_skip(self):
        # The dedicated native-delete probe is intentionally skipped by the
        # normal installed Add suite. Keep this one-slot allowance explicit.
        def skip_twenty_nine(d):
            for case in d["test_cases"][:29]:
                case["outcome"] = "skipped"
            d["junit"]["executed"] = 152
            d["junit"]["skipped"] = 29
        self.change(0, skip_twenty_nine)
        self.assertEqual(29, self.check()["junit"]["skipped"])

    def test_accepts_reviewed_two_server_combined_skip(self):
        # CombinedTwoRealServersInstrumentedTest runs only in its own
        # two-disposable-server workflow and is skipped in native Add.
        def skip_thirty(d):
            for case in d["test_cases"][:30]:
                case["outcome"] = "skipped"
            d["junit"]["executed"] = 151
            d["junit"]["skipped"] = 30
        self.change(0, skip_thirty)
        self.assertEqual(30, self.check()["junit"]["skipped"])

    def test_reject_unreviewed_increase_in_skipped_cases(self):
        def skip_thirty_one(d):
            for case in d["test_cases"][:31]:
                case["outcome"] = "skipped"
            d["junit"]["executed"] = 150
            d["junit"]["skipped"] = 31
        self.change(0, skip_thirty_one)
        with self.assertRaisesRegex(ValueError, "excessive skips"):
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

    def test_expensive_device_suite_waits_for_ready_pr_without_skipping_required_coverage(self):
        source = (ROOT / ".github/workflows/native-add-instrumentation.yml").read_text()
        self.assertIn("types: [opened, reopened, synchronize, ready_for_review, converted_to_draft]", source)
        self.assertIn("workflow_dispatch:", source)
        self.assertIn(
            "if: github.event_name != 'pull_request' || !github.event.pull_request.draft",
            source,
        )
        self.assertIn(
            "if: always() && (github.event_name != 'pull_request' || !github.event.pull_request.draft)",
            source,
        )
        # Keep the same two emulators, JUnit receipts, screenshot evidence and
        # failed-shard handling once a PR is actually ready for review.
        self.assertIn("shard: [0, 1]", source)
        self.assertIn("verify_native_add_shards.py", source)
        self.assertIn("if-no-files-found: error", source)
        self.assertNotIn("continue-on-error: true", source)

    def test_build_once_preserves_full_installed_android_shard_coverage(self):
        workflow = (ROOT / ".github/workflows/native-add-instrumentation.yml").read_text()
        runner = (ROOT / "tools/native_add_prebuilt.sh").read_text()
        self.assertIn("  build-once:", workflow)
        self.assertIn("  add-review-device-test:\n    needs: build-once", workflow)
        self.assertIn("  verify-complete-native-add:\n    needs: add-review-device-test", workflow)
        self.assertEqual(workflow.count(":app:assembleDebug :app:assembleDebugAndroidTest"), 1)
        self.assertNotIn("connectedDebugAndroidTest", workflow)
        for expected in (
            "chunks-prebuilt-native-add-apk-pair",
            "sha256sum -c SHA256SUMS",
            "script: bash tools/native_add_prebuilt.sh",
            "check_native_atlas_contract.py",
            "test_instrumentation_stream.py",
            "--require-executed",
            "verify_native_add_shards.py",
            "chunks-native-add-sharded-acceptance",
            "Native Add installed Android test failure",
            "if: failure()",
        ):
            with self.subTest(expected=expected):
                self.assertIn(expected, workflow)
        for expected in (
            "adb shell am instrument -w -r",
            '-e numShards "$count" -e shardIndex "$index"',
            "parse_instrumentation_stream.py",
        ):
            with self.subTest(expected=expected):
                self.assertIn(expected, runner)

    def test_workflow_has_real_two_shards_and_always_uploads_receipts(self):
        workflow = (ROOT / ".github/workflows/native-add-instrumentation.yml").read_text()
        shell = (ROOT / "tools/native_add_review.sh").read_text()
        self.assertIn("shard: [0, 1]", workflow)
        self.assertIn('CHUNKS_NATIVE_ADD_SHARD_COUNT: "2"', workflow)
        self.assertIn('CHUNKS_NATIVE_ADD_SHARD_INDEX: ${{ matrix.shard }}', workflow)
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
