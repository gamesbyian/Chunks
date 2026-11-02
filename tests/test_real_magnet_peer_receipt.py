"""Offline fail-closed magnet acceptance receipt counterexamples, no credentials."""
import tempfile
import unittest
from pathlib import Path

from tools.parse_real_magnet_peer_receipt import parse, STAGES


class RealMagnetReceiptTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.xml = self.root / "result.xml"
        self.sidecar = self.root / "stages.txt"
        self.infohash = "a" * 40
        self.env = {"GITHUB_SHA": "b" * 40, "GITHUB_RUN_ID": "123456"}
        self.xml.write_text(
            '<testsuite tests="3" failures="0" errors="0" skipped="0">'
            '<testcase classname="Safe" name="a"/><testcase classname="Safe" name="b"/>'
            '<testcase classname="Safe" name="c"/>'
            '</testsuite>'
        )
        completed = [s for s in STAGES if s != "manual-reconciliation-required"]
        self.sidecar.write_text("hash:" + self.infohash + "\n" +
                                "".join("stage:" + s + "\n" for s in completed))

    def test_positive_recoverable_complete_proof(self):
        r = parse(self.xml, self.sidecar, 0, self.env)
        self.assertEqual("passed", r["result"])
        self.assertEqual("verified", r["cleanup"])
        self.assertEqual("inventory-restored", r["last_stage"])
        self.assertTrue(r["acceptance"]["metadata"])
        self.assertTrue(r["acceptance"]["peer_bytes_and_pieces"])
        self.assertEqual(3, len(r["junit"]["cases"]))
        self.assertFalse(r["acceptance"]["installed_android"])

    def test_missing_junit_and_sidecar_never_pass(self):
        self.xml.unlink()
        self.sidecar.unlink()
        r = parse(self.xml, self.sidecar, 0, self.env)
        self.assertEqual("failed-or-inconclusive", r["result"])
        self.assertEqual("missing", r["junit"]["report"])
        self.assertEqual("unavailable", r["last_stage"])

    def test_post_add_cleanup_gap_is_actionable(self):
        self.sidecar.write_text("hash:" + self.infohash +
                                "\nstage:added-started\nstage:metadata-verified\n")
        r = parse(self.xml, self.sidecar, 1, self.env)
        self.assertEqual("failed-or-inconclusive", r["result"])
        self.assertEqual("unconfirmed-after-add", r["cleanup"])
        self.assertEqual(self.infohash, r["synthetic_infohash"])

    def test_registration_failure_is_distinct_from_metadata_failure(self):
        self.sidecar.write_text("hash:" + self.infohash + "\n" +
            "".join("stage:" + s + "\n" for s in
                    ("runner-ready", "preflight", "destination", "peer", "seed-ready", "add-submitted")))
        result = parse(self.xml, self.sidecar, 1, self.env)
        self.assertEqual("remote-magnet-registration", result["failure_boundary"])
        self.assertEqual("unconfirmed-after-add", result["cleanup"])
        self.assertFalse(result["acceptance"]["metadata"])
        with self.sidecar.open("a") as out:
            out.write("stage:added-started\nstage:started\nstage:seed-started\nstage:peer-connected\n")
        self.assertEqual("BEP9-metadata", parse(self.xml, self.sidecar, 1, self.env)["failure_boundary"])

    def test_junit_failure_category_never_leaks_exception_message(self):
        self.xml.write_text(
            '<testsuite tests="3" failures="1" errors="0" skipped="0">'
            '<testcase classname="Secret/ShouldNotLog" name="pass"><failure type="java.lang.AssertionError" '
            'message="secret-password">private backend traceback</failure></testcase>'
            '<testcase classname="Safe" name="b"/><testcase classname="Safe" name="c"/>'
            '</testsuite>'
        )
        result = parse(self.xml, self.sidecar, 1, self.env)
        self.assertEqual("failed-or-inconclusive", result["result"])
        self.assertEqual(["AssertionError"], result["junit"]["failed_categories"])
        self.assertNotIn("secret-password", str(result))
        self.assertNotIn("private backend traceback", str(result))
        self.assertNotIn("Secret/ShouldNotLog", str(result))

    def test_falsely_green_junit_counters_never_qualify_failed_testcase(self):
        # Some runners can emit stale or contradictory suite summaries.
        # Real testcase errors must override a claimed green aggregate.
        for tag in ("failure", "error", "skipped"):
            with self.subTest(contradiction=tag):
                self.xml.write_text(
                    '<testsuite tests="3" failures="0" errors="0" skipped="0">'
                    '<testcase classname="Safe" name="a"><' + tag
                    + ' type="java.lang.AssertionError">hidden diagnostic</' + tag
                    + '></testcase>'
                    '<testcase classname="Safe" name="b"/>'
                    '<testcase classname="Safe" name="c"/>'
                    '</testsuite>'
                )
                result = parse(self.xml, self.sidecar, 0, self.env)
                self.assertEqual("invalid", result["junit"]["report"])
                self.assertEqual("failed-or-inconclusive", result["result"])
                self.assertNotIn("hidden diagnostic", str(result))

    def test_falsely_red_junit_counters_are_invalid_not_executed_proof(self):
        self.xml.write_text(
            '<testsuite tests="3" failures="1" errors="0" skipped="0">'
            '<testcase classname="Safe" name="a"/>'
            '<testcase classname="Safe" name="b"/>'
            '<testcase classname="Safe" name="c"/>'
            '</testsuite>'
        )
        result = parse(self.xml, self.sidecar, 0, self.env)
        self.assertEqual("invalid", result["junit"]["report"])
        self.assertEqual("failed-or-inconclusive", result["result"])

    def test_malformed_junit_and_conflicting_hashes_are_unqualified(self):
        self.xml.write_text("<testsuite>")
        self.sidecar.write_text("hash:" + self.infohash + "\nhash:" + "c" * 40 + "\n")
        result = parse(self.xml, self.sidecar, 0, self.env)
        self.assertEqual("invalid", result["junit"]["report"])
        self.assertFalse(result["hash_identity_proven"])
        self.assertEqual("failed-or-inconclusive", result["result"])

    def test_no_fake_acceptance_from_unknown_phases(self):
        self.sidecar.write_text(
            "hash:" + self.infohash + "\nstage:metadata-verified-FAKE\nstage:unknown\n"
        )
        result = parse(self.xml, self.sidecar, 0, self.env)
        self.assertFalse(result["acceptance"]["metadata"])
        self.assertEqual("failed-or-inconclusive", result["result"])

    def test_incomplete_testcase_counts_fail_closed(self):
        self.xml.write_text('<testsuite tests="3" failures="0" errors="0" skipped="0">'
                            '<testcase classname="A" name="only"/></testsuite>')
        result = parse(self.xml, self.sidecar, 0, self.env)
        self.assertEqual("invalid", result["junit"]["report"])
        self.assertNotEqual("passed", result["result"])


if __name__ == "__main__":
    unittest.main()
