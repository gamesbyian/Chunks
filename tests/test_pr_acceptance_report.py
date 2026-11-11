"""Offline tests: skipped, cancelled or absent gates are never passes."""
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import pr_acceptance_report as r

CONFIG = r.load_config()


def check(name, conclusion="success", status="completed", steps=None):
    return {"name": name, "status": status, "conclusion": conclusion, "steps": steps or []}


def all_green(profile):
    return [check(g) for g in r.profile_gates(CONFIG, profile)]


class AcceptanceReportTest(unittest.TestCase):
    def test_all_required_gates_passed_is_acceptable(self):
        report = r.evaluate(all_green("integration"), r.profile_gates(CONFIG, "integration"), CONFIG, {})
        self.assertTrue(report["acceptable"])

    def test_skipped_cancelled_and_absent_gates_are_never_passes(self):
        gates = r.profile_gates(CONFIG, "integration")
        for conclusion in ("skipped", "cancelled"):
            checks = all_green("integration")
            checks[2] = check(gates[2], conclusion)
            with self.subTest(conclusion=conclusion):
                report = r.evaluate(checks, gates, CONFIG, {})
                self.assertFalse(report["acceptable"])
                self.assertEqual(r.NOT_EXECUTED, report["rows"][2]["state"])
        report = r.evaluate(all_green("integration")[:-1], gates, CONFIG, {})
        self.assertFalse(report["acceptable"])
        self.assertEqual(r.NOT_REPORTED, report["rows"][-1]["state"])

    def test_a_draft_is_never_acceptable_even_if_the_cheap_gates_pass(self):
        report = r.evaluate(all_green("foundation"), r.profile_gates(CONFIG, "foundation"), CONFIG, {}, draft=True)
        self.assertFalse(report["acceptable"])

    def test_skipped_matrix_job_without_shard_suffix_is_reported_as_not_executed(self):
        gates = ["add-review-device-test (0)", "add-review-device-test (1)"]
        report = r.evaluate([check("add-review-device-test", "skipped")], gates, CONFIG, {})
        self.assertEqual([r.NOT_EXECUTED, r.NOT_EXECUTED], [row["state"] for row in report["rows"]])
        self.assertFalse(report["acceptable"])

    def test_newest_run_of_a_name_wins_over_a_stale_draft_skip(self):
        stale = dict(check("build-once", "skipped"), id=10)
        fresh = dict(check("build-once", "success"), id=20)
        for order in ([stale, fresh], [fresh, stale]):
            with self.subTest(order=[c["id"] for c in order]):
                self.assertEqual(r.PASSED, r.evaluate(order, ["build-once"], CONFIG, {})["rows"][0]["state"])
        newer_failure = dict(check("build-once", "failure"), id=30)
        self.assertEqual(r.FAILED, r.evaluate([fresh, newer_failure], ["build-once"], CONFIG, {})["rows"][0]["state"])

    def test_pending_is_not_a_pass(self):
        gates = r.profile_gates(CONFIG, "foundation")
        checks = [check(gates[0]), check(gates[1], None, "in_progress")]
        self.assertEqual(r.PENDING, r.evaluate(checks, gates, CONFIG, {})["rows"][1]["state"])

    def test_a_waiver_is_explicit_and_echoed(self):
        gates = r.profile_gates(CONFIG, "integration")
        checks = [c for c in all_green("integration") if c["name"] != "minified-smoke"]
        report = r.evaluate(checks, gates, CONFIG, {"minified-smoke": "docs-only change"})
        self.assertTrue(report["acceptable"])
        self.assertIn("waived", r.render(report, "abc", "integration").lower())
        self.assertIn("docs-only change", r.render(report, "abc", "integration"))

    def test_failure_is_labelled_environment_only_for_setup_steps(self):
        gates = ["build"]
        env = check("build", "failure", steps=[{"name": "Set up Java", "conclusion": "failure"}])
        test = check("build", "failure", steps=[{"name": "Run JVM tests", "conclusion": "failure"}])
        self.assertIn("environment/dependency", r.evaluate([env], gates, CONFIG, {})["rows"][0]["detail"])
        detail = r.evaluate([test], gates, CONFIG, {})["rows"][0]["detail"]
        self.assertIn("needs-diagnosis", detail)
        self.assertNotIn("environment/dependency", detail)

    def test_a_journey_step_that_merely_says_installed_is_never_environment(self):
        journey = check("installed-magnet-lifecycle", "failure", steps=[
            {"name": "Installed production phone and rTorrent source journeys with real peer pieces",
             "conclusion": "failure"}])
        detail = r.evaluate([journey], ["installed-magnet-lifecycle"], CONFIG, {})["rows"][0]["detail"]
        self.assertIn("needs-diagnosis", detail)
        self.assertNotIn("environment/dependency", detail)
        emulator = check("x", "failure", steps=[{"name": "Run tests on emulator", "conclusion": "failure"}])
        self.assertIn("needs-diagnosis", r.evaluate([emulator], ["x"], CONFIG, {})["rows"][0]["detail"])

    def test_real_peer_profile_extends_integration(self):
        base = r.profile_gates(CONFIG, "integration")
        extended = r.profile_gates(CONFIG, "integration-real-peer")
        self.assertEqual(base + ["installed-magnet-lifecycle"], extended)

    def test_unknown_profile_is_rejected(self):
        with self.assertRaises(ValueError):
            r.profile_gates(CONFIG, "nope")


if __name__ == "__main__":
    unittest.main()
