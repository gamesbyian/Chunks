"""Offline negative controls for CI evidence summaries (never use protected logs)."""
import tempfile
import unittest
import subprocess
import sys
from pathlib import Path
from tools.ci_failure_digest import make_digest, rejects_unverified_green


class DigestTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "TEST-one.xml"
        self.env = {"GITHUB_SHA": "a" * 40, "GITHUB_REPOSITORY": "gamesbyian/Chunks",
                    "GITHUB_RUN_ID": "123", "GITHUB_WORKFLOW": "CI",
                    "SECRET_PASSWORD": "must-never-appear"}

    def test_positive_executed_not_skipped(self):
        self.path.write_text('<testsuite><testcase classname="C" name="works"/></testsuite>')
        d = make_digest([str(self.path)], "success", "unit", self.env)
        self.assertTrue(d["evidence_executed"])
        self.assertEqual(d["phase"], "none")
        self.assertEqual(d["junit"]["executed"], 1)

    def test_failure_retains_first_test_without_exception_or_secret(self):
        self.path.write_text('<testsuite><testcase classname="C" name="fails">'
                             '<failure message="must-never-appear">sensitive traceback</failure>'
                             '</testcase></testsuite>')
        d = make_digest([str(self.path)], "failure", "unit", self.env)
        self.assertEqual(d["phase"], "test_assertion")
        self.assertEqual(d["failed_cases"], [{"class": "C", "test": "fails"}])
        self.assertNotIn("must-never-appear", str(d))
        self.assertNotIn("sensitive traceback", str(d))

    def test_skips_and_missing_tests_never_certify_execution(self):
        self.path.write_text('<testsuite><testcase name="skip"><skipped/></testcase></testsuite>')
        d = make_digest([str(self.path)], "success", "unit", self.env)
        self.assertEqual(d["junit"]["executed"], 0)
        self.assertFalse(d["evidence_executed"] if d["junit"]["executed"] == 0 else True)
        empty = make_digest([], "success", "unit", self.env)
        self.assertFalse(empty["evidence_executed"])
        self.assertEqual(empty["phase"], "unexecuted")

    def test_infrastructure_failure_without_junit_is_unknown(self):
        d = make_digest([], "failure", "instrumentation", self.env)
        self.assertEqual(d["phase"], "unknown_pre_or_post_test")
        self.assertFalse(d["evidence_executed"])

    def test_malformed_xml_is_visible_not_green(self):
        self.path.write_text("<testsuite><testcase>")
        d = make_digest([str(self.path)], "success", "unit", self.env)
        self.assertEqual(d["phase"], "harness_report")
        self.assertFalse(d["evidence_executed"])
        self.assertEqual(d["invalid_reports"][0]["problem"], "ParseError")

    def test_independent_failures_and_cancellation(self):
        self.path.write_text('<testsuite><testcase name="one"><failure/></testcase>'
                             '<testcase name="two"><error/></testcase></testsuite>')
        d = make_digest([str(self.path)], "failure", "unit", self.env)
        self.assertEqual(d["junit"]["failed"], 2)
        self.assertEqual(len(d["failed_cases"]), 2)
        self.assertEqual(make_digest([], "cancelled", "unit", self.env)["phase"],
                         "cancellation")

    def test_required_evidence_refuses_success_without_executed_tests(self):
        for xml in (None,
                    "<testsuite></testsuite>",
                    '<testsuite><testcase name="skip"><skipped/></testcase></testsuite>',
                    '<testsuite><testcase>'):
            if xml is None:
                paths = []
            else:
                self.path.write_text(xml)
                paths = [str(self.path)]
            with self.subTest(xml=xml):
                digest = make_digest(paths, "success", "unit", self.env)
                self.assertTrue(rejects_unverified_green(digest, "success", True))
                # Diagnostic step cannot replace the first root cause of a red job.
                self.assertFalse(rejects_unverified_green(digest, "failure", True))
                # Non-acceptance summary invocations remain read-only by default.
                self.assertFalse(rejects_unverified_green(digest, "success", False))

    def test_required_evidence_refuses_failed_junit_when_job_claims_success(self):
        self.path.write_text('<testsuite><testcase name="bad"><failure/></testcase></testsuite>')
        digest = make_digest([str(self.path)], "success", "unit", self.env)
        self.assertTrue(digest["evidence_executed"])
        self.assertTrue(rejects_unverified_green(digest, "success", True))

    def test_required_evidence_passes_only_executed_clean_suite(self):
        self.path.write_text('<testsuite><testcase name="good"/></testsuite>')
        digest = make_digest([str(self.path)], "success", "unit", self.env)
        self.assertFalse(rejects_unverified_green(digest, "success", True))

    def test_required_cli_fails_closed_and_preserves_original_red(self):
        script = Path(__file__).resolve().parents[1] / "tools/ci_failure_digest.py"
        output = Path(self.tmp.name) / "digest.json"
        base = [sys.executable, str(script), "--suite", "unit",
                "--output", str(output), "--require-executed"]
        no_tests = subprocess.run(base + ["--status", "success"], check=False,
                                  capture_output=True, text=True)
        self.assertEqual(1, no_tests.returncode)
        self.assertIn("Required JUnit evidence", no_tests.stdout)
        self.assertTrue(output.is_file())
        # A prior setup/test failure should remain the original red step.
        already_red = subprocess.run(base + ["--status", "failure"], check=False,
                                     capture_output=True, text=True)
        self.assertEqual(0, already_red.returncode)
        self.path.write_text('<testsuite><testcase name="real"/></testsuite>')
        actual_green = subprocess.run(base + ["--status", "success",
                                             "--glob", str(self.path)],
                                      check=False, capture_output=True, text=True)
        self.assertEqual(0, actual_green.returncode, actual_green.stdout)

    def test_slowest_class_timings_are_bounded_and_never_used_as_pass_proof(self):
        self.path.write_text(
            '<testsuite>'
            '<testcase classname="Fast" name="one" time="1.0"/>'
            '<testcase classname="Slow" name="two" time="4.2"/>'
            '<testcase classname="Slow" name="three" time="3.5"/>'
            '<testcase classname="Sensitive/unsafe" name="four" time="nan"/>'
            '<testcase classname="Neg" name="five" time="-1"/>'
            '<testcase classname="NoTimer" name="six"/>'
            '</testsuite>'
        )
        d = make_digest([str(self.path)], "success", "unit", self.env)
        tm = d["timing"]
        self.assertEqual(3, tm["valid_case_durations"])
        self.assertEqual(2, tm["invalid_case_durations"])
        self.assertEqual(1, tm["missing_case_durations"])
        self.assertEqual(8.7, tm["total_case_seconds"])
        self.assertEqual("Slow", tm["slowest_classes"][0]["class"])
        self.assertEqual(7.7, tm["slowest_classes"][0]["summed_case_seconds"])
        self.assertTrue(d["evidence_executed"])
        self.assertEqual("none", d["phase"])

    def test_duration_is_advisory_not_an_evidence_replacement(self):
        self.path.write_text(
            '<testsuite><testcase classname="Slow" name="only" time="9999">'
            '<skipped/></testcase></testsuite>'
        )
        d = make_digest([str(self.path)], "success", "unit", self.env)
        self.assertEqual("unexecuted", d["phase"])
        self.assertFalse(d["evidence_executed"])
        self.assertEqual(9999, d["timing"]["total_case_seconds"])
        self.assertTrue(rejects_unverified_green(d, "success", True))

    def test_class_timing_identifiers_are_sanitized(self):
        self.path.write_text(
            '<testsuite><testcase classname="A/B*Secret" name="foo" time="0.25"/>'
            '</testsuite>'
        )
        d = make_digest([str(self.path)], "success", "unit", self.env)
        self.assertEqual("A?B?Secret", d["timing"]["slowest_classes"][0]["class"])


    def test_complete_case_receipt_is_opaque_and_identifies_all_cases(self):
        self.path.write_text(
            '<testsuite>'
            '<testcase classname="A" name="one" time="1"/>'
            '<testcase classname="A" name="two" time="2"><skipped/></testcase>'
            '<testcase classname="B" name="three" time="3"><failure message="must-never-appear"/></testcase>'
            '</testsuite>')
        result = make_digest([str(self.path)], "failure", "unit", self.env)
        self.assertTrue(result["test_identity_coverage_complete"])
        self.assertEqual(3, result["test_identity_count"])
        self.assertEqual(["passed", "skipped", "failed"],
                         [x["outcome"] for x in result["test_cases"]])
        for case in result["test_cases"]:
            self.assertRegex(case["id_sha256"], r"^[a-f0-9]{64}$")
        self.assertNotIn("must-never-appear", str(result["test_cases"]))

    def test_duplicate_junit_identity_fails_closed(self):
        self.path.write_text(
            '<testsuite><testcase classname="A" name="same"/>'
            '<testcase classname="A" name="same"/></testsuite>')
        result = make_digest([str(self.path)], "success", "unit", self.env)
        self.assertEqual("harness_report", result["phase"])
        self.assertEqual("DuplicateTestIdentity", result["invalid_reports"][0]["problem"])
        self.assertFalse(result["evidence_executed"])
        self.assertTrue(rejects_unverified_green(result, "success", True))


if __name__ == "__main__":
    unittest.main()
