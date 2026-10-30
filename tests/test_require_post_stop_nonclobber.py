"""Fail-closed offline negative controls for the native post-Stop safety gate."""

import importlib.util
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

path = Path(__file__).resolve().parents[1] / "tools/require_post_stop_nonclobber.py"
spec = importlib.util.spec_from_file_location("require_post_stop_nonclobber", path)
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)
FAMILY = validator.FAMILY
SHA_A = "a" * 64
SHA_B = "b" * 64


def sample(priority=SHA_A, final=SHA_A, replaced="false", present="false",
           restarted="REFUSED", includes_case=True, skipped=False, duplicate=False):
    root = ET.Element("testsuite", {
        "name": "io.github.gamesbyian.chunks.engine.LocalNativePostAddCollisionProbeTest",
        "tests": "3", "failures": "0", "errors": "0",
        "skipped": "1" if skipped else "0"
    })
    for name in (
        validator.TEST_METHOD if includes_case else "notTheNativeWitness",
        "firstNativeCollisionDiagnostic",
        "secondNativeCollisionDiagnostic",
    ):
        tc = ET.SubElement(root, "testcase", {
            "classname": "io.github.gamesbyian.chunks.engine.LocalNativePostAddCollisionProbeTest",
            "name": name,
        })
        if skipped and name == validator.TEST_METHOD:
            ET.SubElement(tc, "skipped")
    logs = [
        f"{FAMILY} before_sha256={SHA_A} expected_sha256={SHA_B} "
        f"after_priority_sha256={priority} after_sha256={final}",
        f"{FAMILY} foreign_file_replaced={replaced}",
        f"{FAMILY} expected_torrent_data_present={present}",
        f"{FAMILY} first_start_outcome=VERIFIED stop_outcome=VERIFIED "
        f"selection_outcome=VERIFIED restart_outcome={restarted}",
    ]
    if duplicate:
        logs.append(logs[0])
    ET.SubElement(root, "system-out").text = "\n".join(logs) + "\n"
    return root


class PostStopReceiptSafetyTest(unittest.TestCase):
    def blocked(self, root, substring):
        ok, errors = validator.verify(root)
        self.assertFalse(ok)
        self.assertIn(substring, "\n".join(errors))

    def test_safe_refused_restart(self):
        self.assertTrue(validator.verify(sample())[0])

    def test_safe_verified_restart_if_foreign_bytes_remain_unchanged(self):
        self.assertTrue(validator.verify(sample(restarted="VERIFIED"))[0])

    def test_priority_mutation_overwrite_is_a_failure_even_if_repaired_later(self):
        self.blocked(sample(priority=SHA_B), "DURING FILE PRIORITY")

    def test_restart_overwrite_is_a_failure_even_if_characterization_passes(self):
        self.blocked(sample(final=SHA_B), "BY END OF STOP/RESTART")

    def test_foreign_replaced_boolean_refuses_even_with_fake_equal_hashes(self):
        self.blocked(sample(replaced="true"), "foreign_file_replaced=false")

    def test_torrent_data_present_boolean_refuses_even_with_fake_equal_hashes(self):
        self.blocked(sample(present="true"), "expected_torrent_data_present=false")

    def test_missing_exact_witness_test_name(self):
        self.blocked(sample(includes_case=False), "Expected exactly one executed")

    def test_skipped_test_is_never_accepted(self):
        self.blocked(sample(skipped=True), "skipped=1")

    def test_ambiguous_duplicate_witness_is_never_accepted(self):
        self.blocked(sample(duplicate=True), "Ambiguous duplicate")

    def test_fake_junit_counters_are_rejected(self):
        root = sample()
        root.set("tests", "9")
        self.blocked(root, "case count mismatch")

    def test_unverified_lifecycle_state_refuses(self):
        root = sample()
        root.find("system-out").text = root.find("system-out").text.replace(
            "stop_outcome=VERIFIED", "stop_outcome=UNCERTAIN")
        self.blocked(root, "verified Start/Stop")

    def test_same_foreign_and_expected_sha_refuses(self):
        root = sample()
        out = root.find("system-out")
        out.text = out.text.replace("expected_sha256=" + SHA_B,
                                    "expected_sha256=" + SHA_A)
        self.blocked(root, "not distinct")

    def test_missing_malformed_report_refuses(self):
        self.blocked(ET.Element("testsuites"), "no test suite")


    def test_counterfeit_prefixed_classname_cannot_qualify(self):
        root = sample()
        root.find("testcase").set("classname", "engine.Counterfeit" + validator.TEST_CLASS)
        self.blocked(root, "Expected exactly one executed")

    def test_unrelated_suite_cannot_supply_native_post_stop_timeline(self):
        native = sample()
        stdout = native.find("system-out")
        native.remove(stdout)
        unrelated = ET.Element("testsuite", {
            "name": "UnrelatedFixture", "tests": "1", "failures": "0",
            "errors": "0", "skipped": "0"
        })
        ET.SubElement(unrelated, "testcase", {
            "classname": "other.UnrelatedFixture", "name": "unrelated"
        })
        ET.SubElement(unrelated, "system-out").text = stdout.text
        root = ET.Element("testsuites")
        root.extend((native, unrelated))
        self.blocked(root, "different JUnit test suite")

    def test_unrelated_suite_native_witness_contamination_refuses_acceptance(self):
        native = sample()
        unrelated = ET.Element("testsuite", {
            "name": "UnrelatedFixture", "tests": "1", "failures": "0",
            "errors": "0", "skipped": "0"
        })
        ET.SubElement(unrelated, "testcase", {
            "classname": "other.UnrelatedFixture", "name": "unrelated"
        })
        ET.SubElement(unrelated, "system-out").text = (
            f"{FAMILY} foreign_file_replaced=false")
        root = ET.Element("testsuites")
        root.extend((native, unrelated))
        self.blocked(root, "different JUnit test suite")



if __name__ == "__main__":
    unittest.main()
