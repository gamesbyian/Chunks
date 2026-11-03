"""Dispatch topology negative controls. Verify event routing, not job names alone."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent.parent / ".github" / "workflows"


def pr_block(name):
    text = (ROOT / name).read_text(encoding="utf-8")
    if "  pull_request:" not in text:
        return ""
    return text.split("  pull_request:", 1)[1].split("  workflow_dispatch:", 1)[0]


class DispatchTests(unittest.TestCase):
    def test_one_pr_runner_owns_overlapping_radio_interrupt_and_doze(self):
        main = pr_block("android-radio-harness-qa.yml")
        for path in ("tools/android_public_swarm_interrupt.sh",
                     "tools/test_android_public_swarm_interrupt_mock.sh",
                     "tools/android_public_swarm_doze.sh",
                     "tools/test_android_public_swarm_doze_mock.sh"):
            self.assertIn(path, main)
        self.assertEqual("", pr_block("android-doze-harness-qa.yml"))
        self.assertEqual("", pr_block("android-interrupt-harness-qa.yml"))

    def test_harness_coverage_is_executed_in_combined_job(self):
        text = (ROOT / "android-radio-harness-qa.yml").read_text(encoding="utf-8")
        for cmd in ("bash tools/test_android_public_swarm_doze_mock.sh",
                    "bash tools/test_android_public_swarm_interrupt_mock.sh",
                    "bash tools/test_android_public_swarm_netflap_mock.sh"):
            self.assertIn(cmd, text)
        for name in ("android-doze-harness-qa.yml",
                     "android-interrupt-harness-qa.yml"):
            self.assertIn("  workflow_dispatch:", (ROOT / name).read_text())

    def test_summary_script_runs_even_when_build_fails(self):
        for name in ("android-check.yml", "native-add-instrumentation.yml"):
            text = (ROOT / name).read_text(encoding="utf-8")
            self.assertIn("python3 tools/ci_failure_digest.py", text)
            self.assertIn("if: always()", text)
            self.assertIn("CHUNKS_JOB_STATUS: ${{ job.status }}", text)
            self.assertIn("--require-executed", text)
            self.assertNotIn("if [ -f tools/ci_failure_digest.py ]; then", text)

    def test_legacy_shared_build_comparison_is_manual_only(self):
        experiment = (ROOT / "native-add-shared-build-experiment.yml").read_text()
        production = (ROOT / "native-add-instrumentation.yml").read_text()
        self.assertIn("  workflow_dispatch:", experiment)
        self.assertNotIn("  pull_request:", experiment)
        self.assertIn("script: bash tools/native_add_prebuilt.sh", experiment)
        self.assertIn("verify_native_add_shards.py", experiment)
        self.assertIn("  pull_request:", production)
        self.assertIn("  workflow_dispatch:", production)

    def test_orchestration_tests_trigger_docs_validation(self):
        text = (ROOT / "documentation-validation.yml").read_text()
        for filename in ("tools/ci_failure_digest.py", "tests/test_ci_failure_digest.py",
                         "tests/test_ci_orchestration.py"):
            self.assertEqual(text.count("      - '" + filename + "'"), 2)
        self.assertIn("test_ci_orchestration.py", text)
        self.assertIn("test_ci_failure_digest.py", text)


if __name__ == "__main__":
    unittest.main()
