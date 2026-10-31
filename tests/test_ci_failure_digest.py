"""Offline negative controls for CI evidence summaries (never use protected logs)."""
import tempfile
import unittest
from pathlib import Path
from tools.ci_failure_digest import make_digest


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


if __name__ == "__main__":
    unittest.main()
