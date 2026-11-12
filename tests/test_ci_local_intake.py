"""The opt-in installed journey reuses existing emulators and stays fail-closed."""
from pathlib import Path
import re
import unittest

from test_ci_trigger_contract import dispatches_for_changed_paths

ROOT = Path(__file__).resolve().parents[1]
HARNESS = "tools/local_intake_journey.sh"
MOCK = "tools/test_local_intake_journey.sh"
FIXTURE = "app/src/androidTest/java/io/github/gamesbyian/chunks/LocalIntakeJourneyInstrumentedTest.kt"


class LocalIntakeDispatchTest(unittest.TestCase):
    def test_fixture_and_harness_qualify_both_existing_runtime_tiers(self):
        for name in ("android10-downloads-instrumentation.yml", "android-fgs-timeout-repro.yml"):
            text = (ROOT / ".github/workflows" / name).read_text()
            for path in (HARNESS, MOCK, FIXTURE):
                with self.subTest(workflow=name, path=path):
                    self.assertTrue(dispatches_for_changed_paths(text, (path,)))
            self.assertIn(f"bash {HARNESS}", text)
            self.assertIn(f"bash {MOCK}", text)
            jobs = text.split("\njobs:\n", 1)[1]
            self.assertEqual(1, len(re.findall(r"(?m)^  [a-z][a-z0-9-]*:\s*$", jobs)))

    def test_offline_harness_edits_also_run_cheap_negative_controls(self):
        text = (ROOT / ".github/workflows/documentation-validation.yml").read_text()
        for path in (HARNESS, MOCK, "tests/test_ci_local_intake.py"):
            self.assertEqual(2, text.count("      - '" + path + "'"))
        self.assertIn(f"bash {MOCK}", text)
        self.assertIn("python3 -m unittest discover -s tests -p test_ci_local_intake.py", text)

    def test_ordinary_native_suite_cannot_execute_the_self_kill_phases(self):
        text = (ROOT / FIXTURE).read_text()
        self.assertIn('requirePhase("a")', text)
        self.assertIn('requirePhase("b")', text)
        self.assertIn('assumeTrue("Run with tools/local_intake_journey.sh"', text)
        self.assertIn('getString("chunksIntakePhase") == phase', text)


if __name__ == "__main__":
    unittest.main()
