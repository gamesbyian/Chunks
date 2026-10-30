"""Offline negative controls for strict raw-Add and deferred magnet native receipts."""
import unittest
import xml.etree.ElementTree as ET

import importlib.util
from pathlib import Path

PATH = Path(__file__).resolve().parents[1] / "tools/require_add_magnet_nonclobber.py"
spec = importlib.util.spec_from_file_location("require_add_magnet_nonclobber", PATH)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

A, B = "a" * 64, "b" * 64


def fixture(**changes):
    suite = ET.Element("testsuite", {"name": gate.CLASS, "tests": "4",
                                    "failures": "0", "errors": "0", "skipped": "0"})
    for name in (gate.CASES["CHUNKS_POST_ADD_COLLISION"],
                 gate.CASES["CHUNKS_MAGNET_COLLISION"], "postStop", "restore"):
        ET.SubElement(suite, "testcase", {"classname": gate.CLASS, "name": name})
    data = {}
    for family in gate.CASES:
        data[family] = [
            f"{family} before_sha256={A} expected_sha256={B} after_sha256={A}",
            f"{family} foreign_file_replaced=false",
            f"{family} expected_torrent_data_present=false",
            f"{family} " + ("native_start_outcome=REFUSED"
                           if family == "CHUNKS_POST_ADD_COLLISION"
                           else "observed_metadata=true stopped=true upload_only=true"),
        ]
    for key, value in changes.items():
        family, index = key.split("_", 1)
        family = ("CHUNKS_POST_ADD_COLLISION" if family == "add"
                  else "CHUNKS_MAGNET_COLLISION")
        if index == "extra":
            data[family].append(value)
        elif index == "omit":
            data[family].pop(int(value))
        else:
            data[family][int(index)] = value
    ET.SubElement(suite, "system-out").text = "\n".join(
        line for family in gate.CASES for line in data[family])
    return suite


class NativeAddMagnetSafetyGateTest(unittest.TestCase):
    def blocked(self, root, reason):
        accepted, errors = gate.verify(root)
        self.assertFalse(accepted)
        self.assertIn(reason, "\n".join(errors))

    def test_safe_refused_raw_start_and_stopped_magnet(self):
        self.assertTrue(gate.verify(fixture())[0])

    def test_raw_verified_even_with_unchanged_bytes_is_not_safe(self):
        self.blocked(fixture(add_3="CHUNKS_POST_ADD_COLLISION native_start_outcome=VERIFIED"),
                     "not explicitly refused")

    def test_raw_uncertain_does_not_prove_refusal(self):
        self.blocked(fixture(add_3="CHUNKS_POST_ADD_COLLISION native_start_outcome=UNCERTAIN"),
                     "not explicitly refused")

    def test_raw_foreign_overwrite_fails_even_if_flags_lie(self):
        self.blocked(fixture(add_0=f"CHUNKS_POST_ADD_COLLISION before_sha256={A} "
                                   f"expected_sha256={B} after_sha256={B}"),
                     "FOREIGN BYTES OVERWRITTEN")

    def test_magnet_foreign_overwrite_fails_even_if_flags_lie(self):
        self.blocked(fixture(mag_0=f"CHUNKS_MAGNET_COLLISION before_sha256={A} "
                                   f"expected_sha256={B} after_sha256={B}"),
                     "FOREIGN BYTES OVERWRITTEN")

    def test_magnet_auto_start_after_metadata_is_unsafe(self):
        self.blocked(fixture(mag_3="CHUNKS_MAGNET_COLLISION observed_metadata=true "
                                   "stopped=false upload_only=false"), "unsafe magnet")

    def test_magnet_not_upload_only_is_unsafe(self):
        self.blocked(fixture(mag_3="CHUNKS_MAGNET_COLLISION observed_metadata=true "
                                   "stopped=true upload_only=false"), "unsafe magnet")

    def test_false_both_outcome_flags_are_required(self):
        self.blocked(fixture(add_1="CHUNKS_POST_ADD_COLLISION foreign_file_replaced=true"),
                     "native outcome flags are unsafe")
        self.blocked(fixture(mag_2="CHUNKS_MAGNET_COLLISION expected_torrent_data_present=true"),
                     "native outcome flags are unsafe")

    def test_missing_and_duplicate_witness_fail(self):
        self.blocked(fixture(add_omit="2"), "four native witness lines")
        self.blocked(fixture(mag_extra="CHUNKS_MAGNET_COLLISION foreign_file_replaced=false"),
                     "four native witness lines")

    def test_foreign_and_torrent_fixtures_must_be_distinct(self):
        self.blocked(fixture(add_0=f"CHUNKS_POST_ADD_COLLISION before_sha256={A} "
                                   f"expected_sha256={A} after_sha256={A}"),
                     "lacks distinct foreign bytes")

    def test_failed_and_skipped_junit_cases_are_rejected(self):
        skipped = fixture()
        skipped.set("skipped", "1")
        self.blocked(skipped, "Unclean JUnit")
        failed = fixture()
        failed.set("failures", "1")
        self.blocked(failed, "Unclean JUnit")

    def test_counterfeit_native_classname_is_not_accepted(self):
        root = fixture()
        root.findall("testcase")[0].set("classname", "fake.Counterfeit" + gate.CLASS)
        self.blocked(root, "Expected exactly one executed")

    def test_unrelated_suite_cannot_supply_native_witness(self):
        target = fixture()
        stdout = target.find("system-out")
        target.remove(stdout)
        unrelated = ET.Element("testsuite", {"name": "unrelated", "tests": "1",
                                              "failures": "0", "errors": "0", "skipped": "0"})
        ET.SubElement(unrelated, "testcase",
                      {"classname": "other.Test", "name": "irrelevant"})
        ET.SubElement(unrelated, "system-out").text = stdout.text
        root = ET.Element("testsuites")
        root.extend((target, unrelated))
        self.blocked(root, "unrelated JUnit suite")

    def test_lied_about_junit_test_count_is_rejected(self):
        root = fixture()
        root.set("tests", "5")
        self.blocked(root, "JUnit case count mismatch")


if __name__ == "__main__":
    unittest.main()
