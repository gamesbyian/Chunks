"""R8 exactly-one execution proof: actual XML outcomes, not declared-only totals."""
from pathlib import Path
import tempfile
import unittest

from tools.ci_failure_digest import make_digest
from tools.require_minified_smoke_digest import require_single_smoke


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

    def test_real_one_case_with_valid_declared_totals_passes(self):
        good = self.digest(
            '<testsuite tests="1" failures="0" errors="0" skipped="0">'
            '<testcase classname="MinifiedBuildSmokeInstrumentedTest" '
            'name="executesThroughR8"/></testsuite>')
        self.assertEqual(ENV["GITHUB_SHA"], require_single_smoke(good))

    def test_fabricated_declared_positive_without_actual_case_is_refused(self):
        fake = self.digest('<testsuite tests="1" failures="0" errors="0" skipped="0"/>')
        with self.assertRaises(ValueError):
            require_single_smoke(fake)

    def test_one_hidden_declared_failure_is_refused(self):
        fake = self.digest(
            '<testsuite tests="1" failures="1">'
            '<testcase classname="T" name="ok"/></testsuite>')
        with self.assertRaises(ValueError):
            require_single_smoke(fake)

    def test_failure_skip_double_execution_and_no_report_are_refused(self):
        cases = [
            '<testsuite><testcase name="fail"><failure/></testcase></testsuite>',
            '<testsuite><testcase name="skip"><skipped/></testcase></testsuite>',
            '<testsuite><testcase name="a"/><testcase name="b"/></testsuite>',
            None,
        ]
        for xml in cases:
            with self.subTest(xml=xml):
                with self.assertRaises(ValueError):
                    require_single_smoke(self.digest(xml))

    def test_missing_source_run_or_prior_job_failure_is_refused(self):
        good = self.digest('<testsuite><testcase name="good"/></testsuite>')
        for field, value in (("source_sha", ""), ("run_id", None),
                             ("reported_job_status", "failure"),
                             ("test_identity_coverage_complete", False),
                             ("phase", "unknown_pre_or_post_test")):
            with self.subTest(field=field):
                candidate = dict(good, **{field: value})
                with self.assertRaises(ValueError):
                    require_single_smoke(candidate)


if __name__ == "__main__":
    unittest.main()
