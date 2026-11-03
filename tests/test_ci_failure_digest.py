"""Offline negative controls for CI evidence summaries (never use protected logs)."""
import json
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

    def test_robolectric_runtime_fetch_signature_advisory_not_green(self):
        secret = "SEEDBOX-SECRET-NEVER-ECHO"
        self.path.write_text(
            '<testsuite tests="1" failures="1"><testcase classname="UiTest" name="render">'
            '<failure message="java.lang.AssertionError at MavenArtifactFetcher.java:130">'
            'Caused by java.net.SocketException at NioSocketImpl.java:328 '
            + secret + ' https://private.example/api?token=' + secret +
            '</failure></testcase></testsuite>'
        )
        result = make_digest([str(self.path)], "failure", "android-jvm", self.env)
        self.assertEqual("test_assertion", result["phase"])
        self.assertEqual("possible-robolectric-runtime-dependency-network-fetch",
                         result["diagnostic_hint"])
        self.assertEqual(1, result["junit"]["failed"])
        self.assertTrue(result["evidence_executed"])
        self.assertFalse(rejects_unverified_green(result, "failure", True))
        serialized = json.dumps(result)
        self.assertNotIn(secret, serialized)
        self.assertNotIn("private.example", serialized)
        self.assertNotIn("NioSocketImpl", serialized)
        self.assertIn("Robolectric Android SDK JAR", result["next_action"])

    def test_fetch_hint_requires_exact_conjunction_and_android_jvm_suite(self):
        candidates = (
            ('MavenArtifactFetcher.java:130', "android-jvm"),
            ('Caused by java.net.SocketException', "android-jvm"),
            ('MavenArtifactFetcher and java.net.SocketException', "native-add"),
            ('normal android assertion', "android-jvm"),
        )
        for payload, suite in candidates:
            with self.subTest(payload=payload, suite=suite):
                self.path.write_text(
                    '<testsuite tests="1" failures="1"><testcase name="fails">'
                    '<error>' + payload + '</error></testcase></testsuite>'
                )
                result = make_digest([str(self.path)], "failure", suite, self.env)
                self.assertIsNone(result["diagnostic_hint"])
                self.assertEqual("test_assertion", result["phase"])
                self.assertEqual(1, result["junit"]["failed"])

    def test_malformed_or_green_evidence_cannot_receive_runtime_fetch_hint(self):
        self.path.write_text(
            '<testsuite tests="2" failures="0">'
            '<testcase name="bad"><failure>'
            'MavenArtifactFetcher.java:130 java.net.SocketException'
            '</failure></testcase></testsuite>'
        )
        invalid = make_digest([str(self.path)], "success", "android-jvm", self.env)
        self.assertEqual("harness_report", invalid["phase"])
        self.assertIsNone(invalid["diagnostic_hint"])
        self.assertTrue(rejects_unverified_green(invalid, "success", True))
        self.path.write_text(
            '<testsuite tests="1"><testcase name="passes">'
            '</testcase></testsuite>'
        )
        clean = make_digest([str(self.path)], "success", "android-jvm", self.env)
        self.assertIsNone(clean["diagnostic_hint"])
        self.assertEqual("none", clean["phase"])

    def test_skips_and_missing_tests_never_certify_execution(self):
        self.path.write_text('<testsuite><testcase name="skip"><skipped/></testcase></testsuite>')
        d = make_digest([str(self.path)], "success", "unit", self.env)
        self.assertEqual(d["junit"]["executed"], 0)
        self.assertFalse(d["evidence_executed"] if d["junit"]["executed"] == 0 else True)
        empty = make_digest([], "success", "unit", self.env)
        self.assertFalse(empty["evidence_executed"])
        self.assertEqual(empty["phase"], "unexecuted")

    def test_declared_junit_totals_reject_missing_cases_and_hidden_failures(self):
        # A parser that only counts testcase elements could otherwise certify
        # a report declaring more tests, skips, failures or errors than exist.
        variants = (
            '<testsuite tests="2"><testcase name="passed"/></testsuite>',
            '<testsuite tests="1" failures="1"><testcase name="passed"/></testsuite>',
            '<testsuite tests="1" errors="1"><testcase name="passed"/></testsuite>',
            '<testsuite tests="1" skipped="1"><testcase name="passed"/></testsuite>',
            '<testsuite tests="-1"><testcase name="passed"/></testsuite>',
            '<testsuite tests="bogus"><testcase name="passed"/></testsuite>',
            '<testsuites tests="2"><testsuite tests="1">'
            '<testcase name="passed"/></testsuite></testsuites>',
        )
        for xml in variants:
            with self.subTest(xml=xml):
                self.path.write_text(xml)
                d = make_digest([str(self.path)], "success", "unit", self.env)
                self.assertEqual("harness_report", d["phase"])
                self.assertFalse(d["evidence_executed"])
                self.assertTrue(rejects_unverified_green(d, "success", True))
                self.assertIn("JUnitDeclaredCountMismatch",
                              [v["problem"] for v in d["invalid_reports"]])
                self.assertNotIn(xml, str(d))

    def test_declared_junit_totals_accept_consistent_and_nested_reports(self):
        variants = (
            '<testsuite tests="2" failures="0" errors="0" skipped="1">'
            '<testcase name="passed"/><testcase name="skipped"><skipped/>'
            '</testcase></testsuite>',
            '<testsuites tests="2" failures="0" errors="0" skipped="0">'
            '<testsuite name="A" tests="1"><testcase classname="A" name="one"/>'
            '</testsuite><testsuite name="B" tests="1">'
            '<testcase classname="B" name="two"/></testsuite></testsuites>',
            '<testsuite><testcase name="present"/></testsuite>',
        )
        for xml in variants:
            with self.subTest(xml=xml):
                self.path.write_text(xml)
                d = make_digest([str(self.path)], "success", "unit", self.env)
                self.assertEqual("none", d["phase"])
                self.assertTrue(d["evidence_executed"])
                self.assertFalse(rejects_unverified_green(d, "success", True))

    def test_declared_failure_consistent_with_case_keeps_first_failure(self):
        self.path.write_text(
            '<testsuite tests="1" failures="1" errors="0">'
            '<testcase classname="Sample" name="bad">'
            '<failure message="secret-text">internal stack</failure>'
            '</testcase></testsuite>')
        d = make_digest([str(self.path)], "failure", "unit", self.env)
        self.assertEqual("test_assertion", d["phase"])
        self.assertEqual(1, d["junit"]["failed"])
        self.assertEqual([{"class": "Sample", "test": "bad"}], d["failed_cases"])
        self.assertNotIn("secret-text", str(d))

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
        self.assertEqual("<redacted-identifier>", d["timing"]["slowest_classes"][0]["class"])


    def test_public_failure_digest_redacts_data_bearing_test_names(self):
        # Parameterized names can embed a private tracker URL or secret-bearing
        # user value. Neither the JSON receipt nor the job summary should copy it.
        secret = "LEAKME-TRACKER-PASSKEY"
        self.path.write_text(
            '<testsuite tests="1" failures="1">'
            '<testcase classname="https://private.example/path?passkey=' + secret + '" '
            'name="test[secret=' + secret + ']" time="1.5">'
            '<failure message="hidden-assertion">hidden stack</failure>'
            '</testcase></testsuite>'
        )
        d = make_digest([str(self.path)], "failure", "unit", self.env)
        self.assertEqual(
            [{"class": "<redacted-identifier>", "test": "<redacted-identifier>"}],
            d["failed_cases"],
        )
        self.assertEqual("<redacted-identifier>",
                         d["timing"]["slowest_classes"][0]["class"])
        self.assertEqual("test_assertion", d["phase"])
        self.assertEqual(1, d["junit"]["failed"])
        self.assertEqual(1, d["junit"]["executed"])
        self.assertRegex(d["test_cases"][0]["id_sha256"], r"^[0-9a-f]{64}$")
        receipt = json.dumps(d)
        for private in ("private.example", secret, "hidden-assertion", "hidden stack"):
            self.assertNotIn(private, receipt)

    def test_untrusted_report_filename_is_opaque_on_parse_failure(self):
        path = Path(self.tmp.name) / "TEST-private-passkey-LEAKME.xml"
        path.write_text("<testsuite><testcase>")
        d = make_digest([str(path)], "success", "unit", self.env)
        self.assertEqual("harness_report", d["phase"])
        self.assertFalse(d["evidence_executed"])
        self.assertRegex(d["invalid_reports"][0]["report"],
                         r"^junit-sha256-[0-9a-f]{20}$")
        self.assertNotIn("LEAKME", json.dumps(d))

    def test_conventional_junit_method_names_remain_actionable(self):
        self.path.write_text(
            '<testsuite tests="1" failures="1">'
            '<testcase classname="io.example.FooTest" name="workReturnsError">'
            '<failure/></testcase></testsuite>'
        )
        d = make_digest([str(self.path)], "failure", "unit", self.env)
        self.assertEqual(
            [{"class": "io.example.FooTest", "test": "workReturnsError"}],
            d["failed_cases"],
        )

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
