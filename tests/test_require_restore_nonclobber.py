"""Offline negative controls for real checked-restore non-clobber JUnit receipts."""

import importlib.util
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

FILE = Path(__file__).resolve().parents[1] / "tools/require_restore_nonclobber.py"
spec = importlib.util.spec_from_file_location("require_restore_nonclobber", FILE)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
F = module.FAMILY
A = "a" * 64
B = "b" * 64


def witness(*, restored=A, final=A, expected=B, stopped="true",
            outcome="REFUSED", changed="false", replaced="false",
            present="false", omit=None, duplicate=None, skip=False, fail=False,
            case=module.CASE, declared="4"):
    suite = ET.Element("testsuite", {"name": module.CLASS, "tests": declared,
                                   "failures": "1" if fail else "0",
                                   "errors": "0", "skipped": "1" if skip else "0"})
    for name in (case, "postStop", "ordinaryAdd", "magnetAdmission"):
        elem = ET.SubElement(suite, "testcase", {"classname": "engine." + module.CLASS,
                                               "name": name})
        if name == case and skip:
            ET.SubElement(elem, "skipped")
        if name == case and fail:
            ET.SubElement(elem, "failure")
    evidence = {
        "state": f"{F} observed_stopped={stopped} resumed_outcome={outcome}",
        "hashes": f"{F} before_sha256={A} expected_sha256={expected} "
                  f"after_restore_sha256={restored} after_sha256={final}",
        "restore_changed_foreign": f"{F} restore_changed_foreign={changed}",
        "foreign_file_replaced": f"{F} foreign_file_replaced={replaced}",
        "expected_torrent_data_present": f"{F} expected_torrent_data_present={present}",
    }
    lines = [v for k, v in evidence.items() if k != omit]
    if duplicate:
        lines.append(evidence[duplicate])
    ET.SubElement(suite, "system-out").text = "\n".join(lines)
    return suite


class RestoreReceiptTest(unittest.TestCase):
    def expect_block(self, root, text):
        good, reasons = module.verify(root)
        self.assertFalse(good)
        self.assertIn(text, "\n".join(reasons))

    def test_refused_start_with_all_foreign_bytes_unmodified_is_safe(self):
        self.assertTrue(module.verify(witness())[0])

    def test_absent_restored_handle_with_unchanged_bytes_is_safe(self):
        self.assertTrue(module.verify(witness(stopped="false", outcome="NOT_FOUND"))[0])

    def test_actual_red_restore_witness_is_rejected(self):
        self.expect_block(witness(final=B, outcome="VERIFIED",
                                  replaced="true", present="true"),
                          "FOREIGN BYTES CHANGED AFTER RESTORED START")

    def test_overwrite_during_restore_is_rejected(self):
        self.expect_block(witness(restored=B, changed="true"),
                          "FOREIGN BYTES CHANGED DURING ENGINE RESTORATION")

    def test_uncertain_start_does_not_prove_safety(self):
        self.expect_block(witness(outcome="UNCERTAIN"), "not safely blocked")

    def test_successful_start_does_not_prove_safety_even_before_write(self):
        self.expect_block(witness(outcome="VERIFIED"), "not safely blocked")

    def test_refused_but_unstopped_is_rejected(self):
        self.expect_block(witness(stopped="false"), "must leave the native handle stopped")

    def test_missing_witness_is_rejected(self):
        self.expect_block(witness(omit="hashes"), "five restoration witness lines")

    def test_duplicate_witness_is_rejected(self):
        self.expect_block(witness(duplicate="hashes"), "five restoration witness lines")

    def test_contradictory_hash_flag_is_rejected(self):
        self.expect_block(witness(replaced="true"), "contradicts")

    def test_torrent_fixture_must_differ_from_foreign_bytes(self):
        self.expect_block(witness(expected=A, present="true"), "Foreign fixture bytes equal")

    def test_skipped_junit_case_is_rejected(self):
        self.expect_block(witness(skip=True), "JUnit execution not clean")

    def test_failed_junit_case_is_rejected(self):
        self.expect_block(witness(fail=True), "Target case failed or was skipped")

    def test_missing_target_case_is_rejected(self):
        self.expect_block(witness(case="wrongCase"), "Expected exactly one executed")

    def test_lied_about_junit_case_count_is_rejected(self):
        self.expect_block(witness(declared="5"), "testcase count disagrees")

    def test_missing_suites_is_rejected(self):
        self.expect_block(ET.Element("testsuites"), "no test suites")


    def test_prefixed_counterfeit_classname_cannot_satisfy_native_case(self):
        root = witness()
        root.find("testcase").set("classname", "engine.Counterfeit" + module.CLASS)
        self.expect_block(root, "Expected exactly one executed")

    def test_unrelated_junit_suite_cannot_lend_its_green_witness(self):
        native = witness()
        stdout = native.find("system-out")
        native.remove(stdout)
        unrelated = ET.Element("testsuite", {"name": "UnrelatedFixture", "tests": "1",
                                             "failures": "0", "errors": "0", "skipped": "0"})
        ET.SubElement(unrelated, "testcase", {"classname": "other.UnrelatedFixture",
                                             "name": "unrelated"})
        ET.SubElement(unrelated, "system-out").text = stdout.text
        root = ET.Element("testsuites")
        root.extend((native, unrelated))
        self.expect_block(root, "different JUnit test suite")

    def test_unrelated_suite_contamination_blocks_even_clean_native_witness(self):
        native = witness()
        unrelated = ET.Element("testsuite", {"name": "UnrelatedFixture", "tests": "1",
                                             "failures": "0", "errors": "0", "skipped": "0"})
        ET.SubElement(unrelated, "testcase", {"classname": "other.UnrelatedFixture",
                                             "name": "unrelated"})
        ET.SubElement(unrelated, "system-out").text = (
            f"{F} foreign_file_replaced=false")
        root = ET.Element("testsuites")
        root.extend((native, unrelated))
        self.expect_block(root, "different JUnit test suite")



if __name__ == "__main__":
    unittest.main()
