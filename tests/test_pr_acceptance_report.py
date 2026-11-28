"""Offline tests: skipped, cancelled or absent gates are never passes."""
import sys
import contextlib
import io
import json
import tempfile
from unittest.mock import patch
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

    def test_actions_workflow_order_beats_late_draft_job_id(self):
        url = "https://github.com/gamesbyian/Chunks/actions/runs/"
        draft = dict(check("build", "skipped"), id=50, details_url=url + "100/job/50")
        ready = dict(check("build", "success"), id=40, details_url=url + "110/job/40")
        self.assertTrue(r.evaluate([draft, ready], ["build"], CONFIG, {})["acceptable"])

        for newer in (
            dict(check("build", "failure"), id=30, details_url=url + "120/job/30"),
            dict(check("build", None, "in_progress"), id=41, details_url=url + "110/job/41"),
        ):
            with self.subTest(newer=newer):
                self.assertFalse(r.evaluate([ready, draft, newer], ["build"], CONFIG, {})["acceptable"])

    def test_reusable_installed_check_names_preserve_exact_gate_identity(self):
        url = "https://github.com/gamesbyian/Chunks/actions/runs/"
        for gate, alias in (("two-server-combined", "combined / two-server-combined"),
                            ("remote-delete-data", "remove / remote-delete-data"),
                            ("installed-protocol-client", "protocol / installed-protocol-client"),
                            ("api29-downloads", "api29 / api29-downloads"),
                            ("installed-resume-death (29)", "resume / installed-resume-death (29)"),
                            ("installed-resume-death (35)", "resume / installed-resume-death (35)")):
            with self.subTest(gate=gate):
                old = dict(check(gate, "success"), id=10, details_url=url + "100/job/10")
                good = dict(check(alias, "success"), id=20, details_url=url + "110/job/20")
                bad = dict(check(alias, "failure"), id=21, details_url=url + "110/job/21")
                self.assertTrue(r.evaluate([good], [gate], CONFIG, {})["acceptable"])
                self.assertFalse(r.evaluate([old, bad], [gate], CONFIG, {})["acceptable"])
                self.assertFalse(r.evaluate([check("unrelated / " + gate)], [gate], CONFIG, {})["acceptable"])
                self.assertFalse(r.evaluate([check(alias, "skipped")], [gate], CONFIG, {})["acceptable"])

    def test_newer_unexpanded_caller_or_matrix_blocks_old_exact_child(self):
        url = "https://github.com/gamesbyian/Chunks/actions/runs/"
        for gate, alias, parent in (
            ("two-server-combined", "combined / two-server-combined", "combined"),
            ("remote-delete-data", "remove / remote-delete-data", "remove"),
            ("installed-protocol-client", "protocol / installed-protocol-client", "protocol"),
            ("api29-downloads", "api29 / api29-downloads", "api29"),
            ("installed-resume-death (29)", "resume / installed-resume-death (29)", "resume"),
            ("installed-resume-death (35)", "resume / installed-resume-death (35)", "resume"),
            ("add-review-device-test (0)", "add-review-device-test (0)", "add-review-device-test"),
        ):
            with self.subTest(gate=gate):
                old = dict(check(alias), id=50, details_url=url + "100/job/50")
                # Later workflow ID wins even if check IDs arrive out of order.
                for conclusion, status, expected in (
                    ("skipped", "completed", r.NOT_EXECUTED),
                    ("cancelled", "completed", r.NOT_EXECUTED),
                    ("failure", "completed", r.FAILED),
                    (None, "in_progress", r.PENDING),
                    ("success", "completed", r.NOT_REPORTED),
                ):
                    parent_check = dict(check(parent, conclusion, status), id=40,
                                        details_url=url + "110/job/40")
                    self.assertEqual(expected, r.evaluate([old, parent_check], [gate], CONFIG, {})["rows"][0]["state"])
                    self.assertFalse(r.evaluate([parent_check], [gate], CONFIG, {})["acceptable"])
                fresh = dict(check(alias), id=30, details_url=url + "120/job/30")
                self.assertTrue(r.evaluate([old, parent_check, fresh], [gate], CONFIG, {})["acceptable"])
                # Same-run later attempt with an unexpanded skipped caller.
                retry = dict(check(parent, "skipped"), id=31, details_url=url + "120/job/31")
                self.assertFalse(r.evaluate([fresh, retry], [gate], CONFIG, {})["acceptable"])

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
            {"name": "Installed production local and rTorrent source journeys with real peer pieces",
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

    def test_live_inventory_requires_full_sha_before_request(self):
        for ref in ("main", "abc123", "refs/pull/1/merge", "g" * 40):
            with self.subTest(ref=ref), patch.object(r, "_get") as get:
                with self.assertRaises(ValueError):
                    r.fetch_checks("gamesbyian/Chunks", ref)
                get.assert_not_called()

    def test_live_inventory_fetches_all_attempts_and_exact_source(self):
        sha = "a" * 40
        first = dict(check("build", "success"), head_sha=sha, id=1)
        second = dict(check("build", "failure"), head_sha=sha, id=2)
        with patch.object(r, "_get", side_effect=[
            {"total_count": 2, "check_runs": [first]},
            {"total_count": 2, "check_runs": [second]},
        ]) as get:
            checks = r.fetch_checks("gamesbyian/Chunks", sha)
        self.assertEqual([first, second], checks)
        self.assertEqual(2, get.call_count)
        self.assertIn("filter=all", get.call_args_list[0].args[0])
        self.assertIn("page=2", get.call_args_list[1].args[0])
        self.assertFalse(r.evaluate(checks, ["build"], CONFIG, {})["acceptable"])

    def test_live_inventory_rejects_incomplete_or_inconsistent_pages(self):
        sha = "a" * 40
        first = dict(check("build"), head_sha=sha, id=1)
        second = dict(check("check"), head_sha=sha, id=2)
        for pages in (
            [{"total_count": 2, "check_runs": [first]},
             {"total_count": 2, "check_runs": []}],
            [{"total_count": 2, "check_runs": [first]},
             {"total_count": 3, "check_runs": [second]}],
            [{"total_count": 2, "check_runs": [first, first]}],
            [{"total_count": 1, "check_runs": [first, second]}],
            [{"total_count": 1, "check_runs": [dict(first, head_sha="b" * 40)]}],
            [{"total_count": 1, "check_runs": [check("build")]}],
            [{"total_count": 1, "check_runs": [dict(first, id=None)]}],
        ):
            with self.subTest(pages=pages), patch.object(r, "_get", side_effect=pages):
                with self.assertRaises(ValueError):
                    r.fetch_checks("gamesbyian/Chunks", sha)

    def test_empty_inventory_is_not_a_pass(self):
        with patch.object(r, "_get", return_value={"total_count": 0, "check_runs": []}):
            checks = r.fetch_checks("gamesbyian/Chunks", "a" * 40)
        self.assertEqual([], checks)
        self.assertFalse(r.evaluate(checks, ["build"], CONFIG, {})["acceptable"])

    def test_offline_fixture_is_explicitly_not_live_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory) / "checks.json"
            fixture.write_text(json.dumps({"check_runs": all_green("foundation")}))
            output = io.StringIO()
            with contextlib.redirect_stdout(output), patch.object(r, "_get") as get:
                code = r.main(["--fixture", str(fixture), "--sha", "a" * 40,
                               "--profile", "foundation"])
            self.assertEqual(0, code)
            get.assert_not_called()
            self.assertIn("OFFLINE FIXTURE: synthetic input, not live commit acceptance evidence.", output.getvalue())

    def test_waiver_verdict_never_claims_every_gate_executed(self):
        report = r.evaluate([], ["build"], CONFIG, {"build": "explicit scope waiver"})
        rendered = r.render(report, "a" * 40, "foundation")
        self.assertIn("ACCEPTABLE with explicit waivers", rendered)
        self.assertNotIn("every required gate executed", rendered)

    def test_unknown_profile_is_rejected(self):
        with self.assertRaises(ValueError):
            r.profile_gates(CONFIG, "nope")


if __name__ == "__main__":
    unittest.main()
