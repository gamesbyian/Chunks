"""Offline negative controls for real Actions job-time accounting."""
import unittest

from tools.ci_runner_cost_report import cost_report, get_runs, minutes, paginated_items, created_range, main
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

    def test_head_sha_aggregates_multiple_events_and_no_runner_cancellations(self):
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
        self.assertEqual(1, report["runs_without_head_sha"])
        self.assertEqual([same, other], sorted(report["by_head_sha"]))
        a = report["by_head_sha"][same]
        self.assertEqual(2, a["runs"])
        self.assertEqual([30, 31], a["run_ids"])
        self.assertEqual(["API35"], a["workflows"])
        self.assertEqual(1, a["cancelled_runs"])
        self.assertEqual(1, a["zero_timed_job_runs"])
        self.assertEqual(3, a["runner_minutes"])
        self.assertEqual(3, a["cancelled_run_runner_minutes"])
        self.assertEqual(7, report["by_head_sha"][other]["runner_minutes"])
        self.assertEqual(0, report["by_head_sha"][other]["zero_timed_job_runs"])

    def test_queue_and_earliest_failing_job_are_timestamp_proxies(self):
        sample = cost_report([
            run(50, "failure", [job(1, 2, 4, "failure"), job(2, 1, 5)]),
            run(51, "cancelled", []),
            run(52, "success", [job(3, conclusion="skipped")]),
        ])
        self.assertEqual(1, sample["details"][0]["queue_minutes"])
        self.assertEqual(4, sample["details"][0]["first_failed_job_completion_minutes"])
        self.assertEqual(1, sample["by_workflow"]["API35"]["queue_minutes"])
        self.assertIsNone(sample["details"][1]["queue_minutes"])
        self.assertIsNone(sample["details"][1]["first_failed_job_completion_minutes"])
        self.assertIsNone(sample["details"][2]["queue_minutes"])

    def test_queue_rejects_impossible_pre_dispatch_job_start(self):
        sample = run(60, "success", [job(1, 1, 4)])
        sample["created_at"] = stamp(2)
        with self.assertRaisesRegex(ValueError, "before workflow created_at"):
            cost_report([sample])

    def test_head_sha_rejects_false_provenance(self):
        bad = run(35, "success", [job(1, 1, 3)])
        bad["head_sha"] = "not-a-sha"
        with self.assertRaisesRegex(ValueError, "Invalid source SHA"):
            cost_report([bad])
        missing = run(36, "cancelled", [])
        self.assertEqual({}, cost_report([missing])["by_head_sha"])

    def test_step_hotspots_are_from_existing_job_payload_and_hide_labels(self):
        import json

        one = job(101, 1, 7)
        one["steps"] = [
            {"number": 1, "name": "Private server credential in step name",
             "conclusion": "success", "started_at": stamp(1), "completed_at": stamp(3)},
            {"number": 2, "name": "Native Android test from installed APK",
             "conclusion": "success", "started_at": stamp(3), "completed_at": stamp(6)},
            {"number": 3, "conclusion": "skipped",
             "started_at": stamp(9), "completed_at": stamp(2)},
            {"number": 4, "conclusion": "cancelled",
             "started_at": stamp(6), "completed_at": None},
        ]
        two = job(102, 1, 6)  # Old/offline job payload missing step detail.
        result = cost_report([run(70, "success", [one, two])])
        self.assertEqual(11, result["runner_minutes"])
        steps = result["by_workflow_step_activity"]["API35"]
        self.assertEqual(1, steps["jobs_with_steps"])
        self.assertEqual(1, steps["jobs_without_steps"])
        self.assertEqual(2, steps["timed_steps"])
        self.assertEqual(1, steps["skipped_steps"])
        self.assertEqual(1, steps["incomplete_steps"])
        self.assertEqual(5, steps["timed_step_minutes"])
        self.assertEqual([
            {"run_id": 70, "job_id": 101, "step_number": 2, "minutes": 3},
            {"run_id": 70, "job_id": 101, "step_number": 1, "minutes": 2},
        ], steps["hotspots"])
        self.assertNotIn("Private server credential", json.dumps(result))
        self.assertNotIn("Native Android test from installed APK", json.dumps(result))

    def test_step_negative_controls_and_unknown_coverage(self):
        missing = job(101, 1, 4)
        self.assertEqual(1, cost_report([run(75, "success", [missing])])[
            "by_workflow_step_activity"]["API35"]["jobs_without_steps"])
        skipped = job(110, 7, 1, conclusion="skipped")
        self.assertEqual({}, cost_report([run(76, "skipped", [skipped])])[
            "by_workflow_step_activity"])
        bad_time = job(109, 1, 5)
        bad_time["steps"] = [{"number": 1, "conclusion": "cancelled",
                              "started_at": stamp(4), "completed_at": stamp(2)}]
        inspected = cost_report([run(74, "cancelled", [bad_time])])
        timed = inspected["by_workflow_step_activity"]["API35"]
        self.assertEqual(1, timed["invalid_step_timestamps"])
        self.assertEqual(0, timed["timed_steps"])
        self.assertEqual(4, inspected["runner_minutes"])

        for bad_steps, error in [
            ("malformed", "Invalid steps"),
            ([None], "Invalid step"),
            ([{"number": "1", "conclusion": "success",
               "started_at": stamp(1), "completed_at": stamp(2)}], "Missing step number"),
            ([{"number": 1, "conclusion": "success",
               "started_at": stamp(1), "completed_at": stamp(2)},
              {"number": 1, "conclusion": "success",
               "started_at": stamp(2), "completed_at": stamp(3)}], "Duplicate step number"),
        ]:
            checked = job(111, 1, 5)
            checked["steps"] = bad_steps
            with self.subTest(error=error), self.assertRaisesRegex(ValueError, error):
                cost_report([run(77, "success", [checked])])

    def test_rerun_attempts_count_all_actual_job_minutes(self):
        first = job(210, 1, 5, "failure")
        first["run_attempt"] = 1
        # GitHub allocates a NEW job ID on retry, even with the same name.
        retried = job(211, 6, 9, "success")
        retried["run_attempt"] = 2
        source = run(80, "success", [first, retried])
        source["run_attempt"] = 2
        source["head_sha"] = "f" * 40
        report = cost_report([source])
        self.assertEqual(7, report["runner_minutes"])
        self.assertEqual(4, report["prior_attempt_runner_minutes"])
        self.assertEqual(1, report["runs_with_reruns"])
        self.assertEqual(0, report["runs_with_unobserved_job_attempts"])
        self.assertEqual([1, 2], report["details"][0]["job_attempts_observed"])
        self.assertEqual(1, report["details"][0]["failed_jobs_across_attempts"])
        self.assertEqual([], report["details"][0]["attempts_without_jobs"])
        self.assertEqual(4, report["by_workflow"]["API35"]["prior_attempt_runner_minutes"])
        self.assertEqual(1, report["by_workflow"]["API35"]["failed_jobs_across_attempts"])
        self.assertEqual(4, report["by_head_sha"]["f" * 40]["prior_attempt_runner_minutes"])

    def test_missing_run_attempt_job_metadata_is_explicitly_uncertain(self):
        only_second = job(212, 1, 3)
        only_second["run_attempt"] = 2
        case = run(81, "success", [only_second])
        case["run_attempt"] = 2
        report = cost_report([case])
        self.assertEqual(1, report["runs_with_unobserved_job_attempts"])
        self.assertEqual([1], report["details"][0]["attempts_without_jobs"])
        self.assertEqual(2, report["runner_minutes"])  # observed lower bound
        invalid = run(82, "success", [only_second])
        invalid["run_attempt"] = 1
        with self.assertRaisesRegex(ValueError, "Invalid job attempt"):
            cost_report([invalid])
        invalid["run_attempt"] = "2"
        with self.assertRaisesRegex(ValueError, "Invalid run attempt"):
            cost_report([invalid])

    def test_live_api_retrieves_all_attempts_without_new_endpoint_fanout(self):
        source = run(83, "success", [])
        source["run_attempt"] = 2
        first = job(301, 1, 3, "failure")
        first["run_attempt"] = 1
        second = job(302, 4, 6, "success")
        second["run_attempt"] = 2
        with patch("tools.ci_runner_cost_report.gh_json") as fetch:
            fetch.side_effect = [
                {"total_count": 1, "workflow_runs": [source]},
                {"total_count": 2, "jobs": [first, second]},
            ]
            fetched = get_runs("gamesbyian/Chunks", "ux/branch")
        self.assertEqual(2, len(fetched[0]["jobs"]))
        self.assertEqual(2, fetch.call_count)
        self.assertIn("filter=all", fetch.call_args.args[0])
        self.assertEqual(4, cost_report(fetched)["runner_minutes"])

    def test_live_api_is_read_only_and_rejects_incomplete_pages(self):
        with patch("tools.ci_runner_cost_report.gh_json") as fetch:
            fetch.return_value = {"total_count": 101, "workflow_runs": []}
            with self.assertRaisesRegex(ValueError, "Incomplete workflow runs page"):
                get_runs("gamesbyian/Chunks", "ui/branch")
            fetch.assert_called_once()
            self.assertIn("branch=ui%2Fbranch", fetch.call_args.args[0])
        with self.assertRaisesRegex(ValueError, "Invalid owner"):
            get_runs("bad/repo/extra", "branch")


    def test_created_window_accepts_only_ordered_real_dates(self):
        self.assertEqual("2026-11-01..2026-11-03",
                         created_range("2026-11-01..2026-11-03"))
        self.assertIsNone(created_range(None))
        for invalid in (
            "2026-11-03", "2026-11-03..", "2026-11-03..2026-11-01",
            "2026-02-30..2026-03-01", "2026-11-03&status=success..2026-11-04",
            "2026-01-01%0A..2026-01-02",
        ):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                created_range(invalid)

    def test_complete_multi_page_workflow_run_query_and_date_scope(self):
        first = [dict(run(i, "success", [])) for i in range(100, 200)]
        second = [dict(run(200, "success", []))]
        for x in first + second:
            x.pop("jobs")
        def fake_fetch(path):
            if "/actions/runs?" in path:
                self.assertIn("created=2026-11-01..2026-11-03", path)
                if path.endswith("page=1"):
                    return {"total_count": 101, "workflow_runs": first}
                if path.endswith("page=2"):
                    return {"total_count": 101, "workflow_runs": second}
                self.fail("Unexpected run page: " + path)
            self.assertIn("filter=all", path)
            self.assertIn("page=1", path)
            run_id = int(path.split("/runs/")[1].split("/jobs?")[0])
            return {"total_count": 1, "jobs": [
                job(run_id * 10, 1, 2),
            ]}
        with patch("tools.ci_runner_cost_report.gh_json", side_effect=fake_fetch) as fetch:
            collected = get_runs("gamesbyian/Chunks", "ux/example",
                                 "2026-11-01..2026-11-03")
        self.assertEqual(101, len(collected))
        self.assertEqual(101, cost_report(collected)["runner_minutes"])
        self.assertEqual(103, fetch.call_count)

    def test_all_attempt_job_pagination_over_one_hundred_jobs(self):
        r = run(47, "success", [])
        r["run_attempt"] = 2
        r.pop("jobs")
        jobs = []
        for index in range(101):
            item = job(index + 1000, 1, 2)
            item["run_attempt"] = 1 if index < 100 else 2
            jobs.append(item)
        def fake_fetch(path):
            if "/actions/runs?" in path:
                return {"total_count": 1, "workflow_runs": [r]}
            self.assertIn("filter=all", path)
            if path.endswith("page=1"):
                return {"total_count": 101, "jobs": jobs[:100]}
            if path.endswith("page=2"):
                return {"total_count": 101, "jobs": jobs[100:]}
            self.fail("Unexpected job page")
        with patch("tools.ci_runner_cost_report.gh_json", side_effect=fake_fetch) as fetch:
            collected = get_runs("gamesbyian/Chunks", "ux/long")
        reported = cost_report(collected)
        self.assertEqual(101, reported["runner_minutes"])
        self.assertEqual(100, reported["prior_attempt_runner_minutes"])
        self.assertEqual(0, reported["runs_with_unobserved_job_attempts"])
        self.assertEqual(3, fetch.call_count)

    def test_refuse_incomplete_changing_and_duplicate_pages(self):
        first = [{"id": i} for i in range(100)]
        for second_page, message in (
            ({"total_count": 100, "workflow_runs": [{"id": 100}]}, "changed"),
            ({"total_count": 101, "workflow_runs": []}, "Incomplete"),
            ({"total_count": 101, "workflow_runs": [{"id": 1}]}, "Duplicate"),
        ):
            with self.subTest(message=message):
                with patch("tools.ci_runner_cost_report.gh_json") as fetch:
                    fetch.side_effect = [
                        {"total_count": 101, "workflow_runs": first},
                        second_page,
                    ]
                    with self.assertRaisesRegex(ValueError, message):
                        paginated_items("repos/gamesbyian/Chunks/actions/runs?branch=example",
                                        "workflow_runs", "workflow runs")
        with patch("tools.ci_runner_cost_report.gh_json") as fetch:
            fetch.return_value = {"total_count": 1001,
                                  "workflow_runs": first}
            with self.assertRaisesRegex(ValueError, "More than 1000"):
                paginated_items("repos/gamesbyian/Chunks/actions/runs?branch=main",
                                "workflow_runs", "workflow runs")
            fetch.assert_called_once()

    def test_unfinished_branch_exits_before_job_requests(self):
        active = run(66, "success", [])
        active["status"] = "in_progress"
        active.pop("jobs")
        with patch("tools.ci_runner_cost_report.gh_json") as fetch:
            fetch.return_value = {"total_count": 1, "workflow_runs": [active]}
            with self.assertRaisesRegex(ValueError, "not complete"):
                get_runs("gamesbyian/Chunks", "ux/active")
            fetch.assert_called_once()


    def test_exact_1000_workflow_results_cannot_certify_search_completeness(self):
        # Filtered GitHub REST searches can stop at this exact boundary.
        hundred = [{"id": i} for i in range(100)]
        with patch("tools.ci_runner_cost_report.gh_json") as fetch:
            fetch.return_value = {"total_count": 1000, "workflow_runs": hundred}
            with self.assertRaisesRegex(ValueError, "search ceiling"):
                paginated_items("repos/gamesbyian/Chunks/actions/runs?branch=main",
                                "workflow_runs", "workflow runs")
            fetch.assert_called_once()

    def test_exported_live_json_includes_date_scope_and_all_attempt_filter(self):
        import json
        import tempfile
        from pathlib import Path

        with tempfile.TemporaryDirectory() as temporary:
            out = Path(temporary) / "report.json"
            argv = ["ci_runner_cost_report.py", "--repo", "gamesbyian/Chunks",
                    "--branch", "ux/evidence", "--created",
                    "2026-11-01..2026-11-03", "--json-out", str(out)]
            with patch("sys.argv", argv), patch(
                "tools.ci_runner_cost_report.get_runs",
                return_value=[run(90, "success", [job(90, 1, 3)])],
            ) as fetched, patch("builtins.print"):
                main()
            fetched.assert_called_once_with(
                "gamesbyian/Chunks", "ux/evidence", "2026-11-01..2026-11-03"
            )
            report = json.loads(out.read_text(encoding="utf-8"))
            self.assertEqual({
                "mode": "github-rest",
                "repository": "gamesbyian/Chunks",
                "branch": "ux/evidence",
                "created_range": "2026-11-01..2026-11-03",
                "job_attempt_filter": "all",
            }, report["selection"])
            self.assertEqual(2, report["runner_minutes"])

    def test_offline_json_scope_stays_distinct_from_live_branch_evidence(self):
        import json
        import tempfile
        from pathlib import Path

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            jobs_dir = root / "jobs"
            jobs_dir.mkdir()
            sample = run(91, "success", [])
            sample.pop("jobs")
            (root / "runs.json").write_text(json.dumps({
                "total_count": 1, "workflow_runs": [sample],
            }), encoding="utf-8")
            (jobs_dir / "91.json").write_text(json.dumps({
                "total_count": 1, "jobs": [job(910, 1, 2)],
            }), encoding="utf-8")
            out = root / "report.json"
            argv = ["ci_runner_cost_report.py", "--runs-json",
                    str(root / "runs.json"), "--jobs-dir", str(jobs_dir),
                    "--json-out", str(out)]
            with patch("sys.argv", argv), patch("builtins.print"):
                main()
            data = json.loads(out.read_text(encoding="utf-8"))
            self.assertEqual("offline-snapshot", data["selection"]["mode"])
            self.assertNotIn("branch", data["selection"])
            self.assertEqual(1, data["runner_minutes"])


if __name__ == "__main__":
    unittest.main()
