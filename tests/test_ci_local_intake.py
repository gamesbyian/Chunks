"""The opt-in installed journey reuses existing emulators and stays fail-closed."""
from pathlib import Path
import re
import unittest

from test_ci_trigger_contract import dispatches_for_changed_paths, shared_route_source

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
                    route = shared_route_source("api29") if name == "android10-downloads-instrumentation.yml" else text
                    self.assertTrue(dispatches_for_changed_paths(route, (path,)))
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

    def test_installed_android_invocations_have_independent_deadlines(self):
        # A silent adb bridge must never consume the entire 30–40 minute job.
        # Preserve every class, phase, negative assertion and actual engine test.
        expected = {
            "tools/installed_source_archive.sh": 1,
            "tools/installed_partial_resume_death.sh": 2,
            "tools/android10_downloads_instrumentation.sh": 7,
            "tools/local_intake_journey.sh": 3,
            "tools/android10_downloads_revocation.sh": 2,
        }
        bounded = re.compile(
            r"timeout --signal=TERM --kill-after=15s [0-9]+m "
            r"adb shell am instrument -w -r"
        )
        for path, count in expected.items():
            with self.subTest(script=path):
                source = (ROOT / path).read_text()
                self.assertEqual(count, source.count("adb shell am instrument -w -r"))
                self.assertEqual(count, len(bounded.findall(source)))
                self.assertIn("set -euo pipefail", source)

    def test_ordinary_native_suite_cannot_execute_the_self_kill_phases(self):
        text = (ROOT / FIXTURE).read_text()
        self.assertIn('requirePhase("a")', text)
        self.assertIn('requirePhase("b")', text)
        self.assertIn('assumeTrue("Run with tools/local_intake_journey.sh"', text)
        self.assertIn('getString("chunksIntakePhase") == phase', text)


if __name__ == "__main__":
    unittest.main()
