"""R8 exact-test and exact-run proof, checked on real structured JUnit reports."""
from pathlib import Path
import tempfile
import unittest

from tools.ci_failure_digest import make_digest
from tools.require_minified_smoke_digest import (
    EXPECTED_CLASS, EXPECTED_TEST, require_single_smoke,
)


ENV = {
    "GITHUB_SHA": "a" * 40,
    "GITHUB_RUN_ID": "41500000000",
    "GITHUB_JOB": "minified-smoke",
    "GITHUB_WORKFLOW": "Chunks R8 minified smoke",
}


class MinifiedSmokeDigestTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.xml = Path(self.temp.name) / "TEST-Smoke.xml"

    def digest(self, xml, status="success"):
        if xml is None:
            paths = []
        else:
            self.xml.write_text(xml, encoding="utf-8")
            paths = [str(self.xml)]
        return make_digest(paths, status, "r8-qa-minified", ENV)

    def required(self, digest, sha=ENV["GITHUB_SHA"],
                 run_id=ENV["GITHUB_RUN_ID"]):
        return require_single_smoke(digest, sha, run_id)

    def exact_case(self, classname=EXPECTED_CLASS, name=EXPECTED_TEST):
        return ('<testsuite tests="1" failures="0" errors="0" skipped="0">'
                f'<testcase classname="{classname}" name="{name}"/>'
                '</testsuite>')

    def test_expected_qualified_class_and_method_are_accepted(self):
        good = self.digest(self.exact_case())
        self.assertEqual(ENV["GITHUB_SHA"], self.required(good))

    def test_other_successful_test_does_not_stand_in_for_r8(self):
        for klass, name in (
            ("com.example.DifferentTest", EXPECTED_TEST),
            (EXPECTED_CLASS, "someOtherPassingTest"),
            ("MinifiedBuildSmokeInstrumentedTest", EXPECTED_TEST),
        ):
            with self.subTest(klass=klass, name=name):
                fake = self.digest(self.exact_case(klass, name))
                self.assertEqual(1, fake["junit"]["executed"])
                with self.assertRaises(ValueError):
                    self.required(fake)

    def test_stale_or_wrong_run_provenance_is_rejected(self):
        good = self.digest(self.exact_case())
        for sha, rid in (
            ("b" * 40, ENV["GITHUB_RUN_ID"]),
            (ENV["GITHUB_SHA"], "41500000001"),
            (None, ENV["GITHUB_RUN_ID"]),
            (ENV["GITHUB_SHA"], None),
        ):
            with self.subTest(sha=sha, rid=rid):
                with self.assertRaises(ValueError):
                    self.required(good, sha, rid)

    def test_claimed_live_sha_or_run_id_in_replayed_digest_rejected(self):
        good = self.digest(self.exact_case())
        for field, value in (("source_sha", "b" * 40),
                             ("run_id", "41500000001")):
            with self.subTest(field=field):
                copied = dict(good, **{field: value})
                with self.assertRaises(ValueError):
                    self.required(copied)

    def test_phantom_summary_without_case_fails(self):
        fake = self.digest('<testsuite tests="1" failures="0" errors="0" skipped="0"/>')
        with self.assertRaises(ValueError):
            self.required(fake)

    def test_hidden_declared_failure_on_correct_case_fails(self):
        fake = self.digest(self.exact_case().replace('failures="0"', 'failures="1"'))
        with self.assertRaises(ValueError):
            self.required(fake)

    def test_fail_skip_double_execution_and_no_report_are_refused(self):
        cases = [
            f'<testsuite><testcase classname="{EXPECTED_CLASS}" name="{EXPECTED_TEST}">'
            '<failure/></testcase></testsuite>',
            f'<testsuite><testcase classname="{EXPECTED_CLASS}" name="{EXPECTED_TEST}">'
            '<skipped/></testcase></testsuite>',
            '<testsuite><testcase name="a"/><testcase name="b"/></testsuite>',
            None,
        ]
        for xml in cases:
            with self.subTest(xml=xml):
                with self.assertRaises(ValueError):
                    self.required(self.digest(xml))

    def test_provenance_and_prior_job_status_fail_closed(self):
        good = self.digest(self.exact_case())
        for field, value in (("source_sha", ""), ("run_id", None),
                             ("reported_job_status", "failure"),
                             ("test_identity_coverage_complete", False),
                             ("phase", "unknown_pre_or_post_test")):
            with self.subTest(field=field):
                candidate = dict(good, **{field: value})
                with self.assertRaises(ValueError):
                    self.required(candidate)


if __name__ == "__main__":
    unittest.main()
