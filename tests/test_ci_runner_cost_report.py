"""Offline negative controls for real Actions job-time accounting."""
import unittest

from tools.ci_runner_cost_report import cost_report, get_runs, minutes
from unittest.mock import patch


def stamp(s):
    return f"2026-11-03T05:{s:02}:00Z"


def job(id, start=None, end=None, conclusion="success"):
    return {"id": id, "started_at": stamp(start) if start is not None else None,
            "completed_at": stamp(end) if end is not None else None,
            "conclusion": conclusion}


def run(id, outcome, jobs):
    return {"id": id, "name": "API35", "status": "completed", "conclusion": outcome,
            "created_at": stamp(0), "updated_at": stamp(10), "jobs": jobs}


class RunnerCostTests(unittest.TestCase):
    def test_parallel_jobs_sum_compute_but_not_wall_time(self):
        report = cost_report([
            run(10, "success", [job(1, 1, 4), job(2, 1, 5)]),
            run(11, "cancelled", [job(3, 1, 3, "cancelled"), job(4, 2, 4)]),
            run(12, "success", [job(5, conclusion="skipped")]),
        ])
        self.assertEqual(11, report["runner_minutes"])
        self.assertEqual(4, report["cancelled_run_runner_minutes"])
        self.assertEqual(30, report["by_workflow"]["API35"]["wall_minutes"])
        self.assertEqual(7, report["by_workflow"]["API35"]["job_window_minutes"])
        self.assertEqual(2, report["by_workflow"]["API35"]["runs_with_timed_jobs"])
        self.assertEqual(4, report["details"][0]["job_window_minutes"])
        self.assertEqual(3, report["details"][1]["job_window_minutes"])
        self.assertIsNone(report["details"][2]["job_window_minutes"])
        self.assertEqual(3, report["by_workflow"]["API35"]["runs"])
        self.assertEqual(1, report["by_workflow"]["API35"]["cancelled_runs"])

    def test_missing_and_reverse_timestamps_fail(self):
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            minutes(stamp(1), None)
        with self.assertRaisesRegex(ValueError, "Negative"):
            minutes(stamp(3), stamp(2))
        with self.assertRaisesRegex(ValueError, "timezone"):
            minutes("2026-11-03T05:00:00", "2026-11-03T05:01:00")

    def test_incomplete_runs_and_missing_executed_job_times_fail(self):
        incomplete = run(1, "success", [])
        incomplete["status"] = "in_progress"
        with self.assertRaisesRegex(ValueError, "not complete"):
            cost_report([incomplete])
        with self.assertRaisesRegex(ValueError, "Missing timing"):
            cost_report([run(1, "success", [job(3)])])

    def test_duplicate_runs_and_duplicate_jobs_fail(self):
        row = run(1, "success", [job(2, 1, 2)])
        with self.assertRaisesRegex(ValueError, "Duplicate workflow"):
            cost_report([row, row])
        with self.assertRaisesRegex(ValueError, "Duplicate job"):
            cost_report([run(1, "success", [job(2, 1, 2), job(2, 2, 3)])])

    def test_skipped_jobs_are_not_counted_as_runner_time(self):
        # GitHub can cancel a queued job before any runner is allocated.
        # Observed GitHub API: a skipped job can have start later than end,
        # even nine seconds apart, despite allocating no runner.
        r = cost_report([run(1, "skipped", [job(2, 5, 1, conclusion="skipped"),
                                             job(3, conclusion="cancelled")])])
        self.assertEqual(0, r["runner_minutes"])
        self.assertEqual(0, r["cancelled_run_runner_minutes"])
        self.assertIsNone(r["details"][0]["job_window_minutes"])
        self.assertEqual(0, r["by_workflow"]["API35"]["runs_with_timed_jobs"])

    def test_source_sha_aggregates_multiple_events_and_no_runner_cancellations(self):
        same = "a" * 40
        other = "b" * 40
        first = run(30, "cancelled", [job(1, 1, 4, "cancelled")])
        first["head_sha"] = same
        # Same immutable source can be dispatched again on ready-for-review.
        second = run(31, "skipped", [job(2, 8, 3, "skipped")])
        second["head_sha"] = same.upper()
        third = run(32, "success", [job(3, 2, 5), job(4, 2, 6)])
        third["head_sha"] = other
        unattributed = run(33, "cancelled", [])
        report = cost_report([first, second, third, unattributed])
        self.assertEqual(10, report["runner_minutes"])
        self.assertEqual(3, report["cancelled_run_runner_minutes"])
        self.assertEqual(1, report["runs_without_source_sha"])
        self.assertEqual([same, other], sorted(report["by_source_sha"]))
        a = report["by_source_sha"][same]
        self.assertEqual(2, a["runs"])
        self.assertEqual([30, 31], a["run_ids"])
        self.assertEqual(["API35"], a["workflows"])
        self.assertEqual(1, a["cancelled_runs"])
        self.assertEqual(1, a["zero_timed_job_runs"])
        self.assertEqual(3, a["runner_minutes"])
        self.assertEqual(3, a["cancelled_run_runner_minutes"])
        self.assertEqual(7, report["by_source_sha"][other]["runner_minutes"])
        self.assertEqual(0, report["by_source_sha"][other]["zero_timed_job_runs"])

    def test_source_sha_rejects_false_provenance(self):
        bad = run(35, "success", [job(1, 1, 3)])
        bad["head_sha"] = "not-a-sha"
        with self.assertRaisesRegex(ValueError, "Invalid source SHA"):
            cost_report([bad])
        missing = run(36, "cancelled", [])
        self.assertEqual({}, cost_report([missing])["by_source_sha"])

    def test_live_api_is_read_only_and_rejects_incomplete_pages(self):
        with patch("tools.ci_runner_cost_report.gh_json") as fetch:
            fetch.return_value = {"total_count": 101, "workflow_runs": []}
            with self.assertRaisesRegex(ValueError, "More than 100"):
                get_runs("gamesbyian/Chunks", "ui/branch")
            fetch.assert_called_once()
            self.assertIn("branch=ui%2Fbranch", fetch.call_args.args[0])
        with self.assertRaisesRegex(ValueError, "Invalid owner"):
            get_runs("bad/repo/extra", "branch")


if __name__ == "__main__":
    unittest.main()
