"""Adversarial validation of paired study receipts and anti-overclaim safeguards."""
import copy
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("trial", ROOT / "scripts" / "compare_trials.py")
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


def run(arm, **kwargs):
    d = {
        "run_id": "run-" + arm, "pair_id": "pair-1", "task_id": "small-label-fix",
        "arm": arm, "model": "same-model", "scaffold": "same-scaffold",
        "tool_access": "isolated", "time_budget_seconds": 300,
        "task_digest": "task-sha256", "oracle_digest": "oracle-sha256",
        "skill_loaded": arm == "skill", "exposure": "isolated",
        "executed_tests": 2, "tests_passed": True, "review": "accepted",
        "elapsed_seconds": 15, "unnecessary_work": False}
    d.update(kwargs)
    return d


def dataset(*records):
    return {"schema": "engineering-judgment/trial-receipts-v1", "runs": list(records)}


class TrialTests(unittest.TestCase):
    def test_equal_pairs_are_not_automatically_a_gain(self):
        result = mod.validate_and_summarize(dataset(run("control"), run("skill")))
        self.assertEqual(result["eligible_pairs"], 1)
        self.assertEqual((result["skill_only_successes"], result["control_only_successes"]), (0, 0))
        self.assertEqual(result["both_successes"], 1)

    def test_actual_skill_win_and_overengineering_reported_separately(self):
        result = mod.validate_and_summarize(dataset(
            run("control", review="rejected", tests_passed=False),
            run("skill", unnecessary_work=True)))
        self.assertEqual(result["skill_only_successes"], 1)
        self.assertEqual(result["skill_unnecessary_work"], 1)

    def test_mismatched_model_or_tool_budget_excluded(self):
        for change in ({"model": "different"}, {"time_budget_seconds": 999},
                       {"task_digest": "different"}, {"tool_access": "github-connected"}):
            with self.subTest(change=change):
                result = mod.validate_and_summarize(dataset(run("control"), run("skill", **change)))
                self.assertEqual(result["eligible_pairs"], 0)
                self.assertEqual(result["comparisons"][0]["reason"], "unmatched_conditions")

    def test_possible_contamination_excluded(self):
        result = mod.validate_and_summarize(dataset(run("control"), run("skill", exposure="potentially_contaminated")))
        self.assertEqual(result["eligible_pairs"], 0)

    def test_pending_review_is_not_accepted(self):
        result = mod.validate_and_summarize(dataset(run("control"), run("skill", review="pending")))
        self.assertEqual(result["eligible_pairs"], 0)

    def test_skipped_ci_cannot_be_a_pass(self):
        with self.assertRaisesRegex(ValueError, "Skipped"):
            mod.validate_and_summarize(dataset(run("control"), run("skill", executed_tests=0)))

    def test_duplicate_arm_rejected(self):
        with self.assertRaisesRegex(ValueError, "Duplicate arm"):
            mod.validate_and_summarize(dataset(run("control"), run("control", run_id="another")))

    def test_fake_skill_activation_rejected(self):
        with self.assertRaisesRegex(ValueError, "Skill loading"):
            mod.validate_and_summarize(dataset(run("control"), run("skill", skill_loaded=False)))

    def test_missing_paired_arm_is_explicit(self):
        report = mod.validate_and_summarize(dataset(run("control")))
        self.assertEqual(report["eligible_pairs"], 0)
        self.assertEqual(report["comparisons"][0]["reason"], "incomplete_pair")


if __name__ == "__main__":
    unittest.main()
