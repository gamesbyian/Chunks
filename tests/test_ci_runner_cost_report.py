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
        r = cost_report([run(1, "skipped", [job(2, conclusion="skipped"),
                                             job(3, conclusion="cancelled")])])
        self.assertEqual(0, r["runner_minutes"])
        self.assertEqual(0, r["cancelled_run_runner_minutes"])

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
