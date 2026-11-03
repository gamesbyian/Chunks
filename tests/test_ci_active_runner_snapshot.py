"""Offline controls for a nonbinding read-only CI pre-push snapshot."""
from datetime import datetime, timezone
import json
import unittest

from tools.ci_active_runner_snapshot import (
    get_snapshot_data, snapshot, timestamp,
)

NOW = datetime(2026, 11, 3, 10, 5, tzinfo=timezone.utc)


def run(id, status="in_progress", sha="a" * 40):
    return {"id": id, "status": status, "head_sha": sha}


def job(id, state, start=None, end=None, conclusion=None):
    def iso(n):
        return f"2026-11-03T10:{n:02}:00Z" if n is not None else None
    return {
        "id": id, "status": state, "conclusion": conclusion,
        "started_at": iso(start), "completed_at": iso(end),
    }


class ActiveRunnerSnapshotTests(unittest.TestCase):
    def test_completed_plus_current_running_runner_time_is_separate(self):
        rows = snapshot(
            [run(10), run(11, "queued", "b" * 40)],
            {10: [job(20, "completed", 0, 2, "success"),
                  job(21, "in_progress", 3, None)],
             11: []},
            NOW,
        )
        self.assertEqual(1, rows["active_workflows"])
        self.assertEqual(1, rows["queued_or_waiting_workflows"])
        self.assertEqual(1, rows["running_jobs"])
        self.assertEqual(1, rows["completed_jobs"])
        self.assertEqual(2, rows["known_completed_runner_minutes"])
        self.assertEqual(2, rows["estimated_running_runner_minutes"])
        self.assertEqual(0, rows["unknown_timing_jobs"])
        self.assertEqual(4, rows["runs"][0]["known_elapsed_runner_minutes"])
        self.assertIn("not future or avoidable savings", rows["scope"])

    def test_unknown_and_skipped_are_never_treated_as_evidence(self):
        result = snapshot([run(12)], {12: [
            job(30, "completed", 0, None, "failure"),
            job(31, "completed", 4, 2, "skipped"),  # inverted but no runner
            job(32, "in_progress", None, None),
            job(33, "in_progress", 7, None),  # clock inversion
        ]}, NOW)
        self.assertEqual(3, result["unknown_timing_jobs"])
        self.assertEqual(1, result["skipped_jobs"])
        self.assertEqual(0, result["running_jobs"])
        self.assertEqual(0, result["known_completed_runner_minutes"])
        self.assertEqual(0, result["estimated_running_runner_minutes"])

    def test_secret_bearing_job_labels_are_never_output(self):
        source = job(40, "in_progress", 1)
        source["name"] = "secret=NEVER_ECHO endpoint=https://private.example"
        report = snapshot([run(13)], {13: [source]}, NOW)
        dump = json.dumps(report)
        self.assertNotIn("NEVER_ECHO", dump)
        self.assertNotIn("private.example", dump)

    def test_invalid_identity_and_duplicate_records_refuse_snapshot(self):
        for item, jobs, message in [
            ([run(14), run(14)], {14: []}, "Repeated run"),
            ([run(15, sha="INVALID")], {15: []}, "Invalid GitHub head SHA"),
            ([run(16, status="completed")], {16: []}, "not in a snapshot-eligible state"),
            ([run(17)], {}, "Missing jobs"),
            ([run(18)], {18: [job(41, "completed", 1, 2),
                               job(41, "completed", 1, 2)]}, "Repeated job"),
        ]:
            with self.subTest(message=message), self.assertRaisesRegex(ValueError, message):
                snapshot(item, jobs, NOW)
        with self.assertRaisesRegex(ValueError, "timezone"):
            snapshot([], {}, datetime(2026, 11, 3, 10, 5))
        with self.assertRaisesRegex(ValueError, "Unzoned"):
            timestamp("2026-11-03T10:00:00")

    def test_live_reader_uses_only_GET_and_all_attempts(self):
        calls = []
        responses = [
            {"total_count": 1, "workflow_runs": [run(50)]},
            {"total_count": 1, "workflow_runs": [run(51, "queued", "b" * 40)]},
            {"total_count": 0, "workflow_runs": []},
            {"total_count": 1, "jobs": [job(60, "in_progress", 1)]},
            {"total_count": 0, "jobs": []},
        ]

        def fetch(path):
            calls.append(path)
            return responses[len(calls) - 1]

        runs, jobs = get_snapshot_data("gamesbyian/Chunks", "ux/wave", fetch)
        self.assertEqual(2, len(runs))
        self.assertEqual([50, 51], sorted(jobs))
        self.assertEqual(5, len(calls))
        self.assertTrue(all("?" in p for p in calls))
        self.assertTrue(all("run" in p for p in calls))
        self.assertIn("branch=ux%2Fwave", calls[0])
        self.assertIn("filter=all", calls[3])
        self.assertIn("filter=all", calls[4])

    def test_incomplete_pages_and_bad_paths_are_not_silent(self):
        def incomplete(path):
            return {"total_count": 101, "workflow_runs": []}
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            get_snapshot_data("gamesbyian/Chunks", "ux/a", incomplete)
        for repo, branch, reason in [
            ("bad/extra/repo", "ux/a", "Invalid repository"),
            ("gamesbyian/Chunks", "ux/a\nmalicious", "Invalid branch"),
        ]:
            with self.assertRaisesRegex(ValueError, reason):
                get_snapshot_data(repo, branch, lambda path: {})

    def test_duplicate_runs_across_status_pages_are_refused(self):
        outputs = [
            {"total_count": 1, "workflow_runs": [run(70)]},
            {"total_count": 1, "workflow_runs": [run(70, "queued")]},
        ]
        cursor = iter(outputs)
        with self.assertRaisesRegex(ValueError, "Repeated active"):
            get_snapshot_data("gamesbyian/Chunks", "ux/branch", lambda _: next(cursor))


if __name__ == "__main__":
    unittest.main()
