"""Catalog measurements never disclose arbitrary output or certify missing samples."""
import json
import sys
import tempfile
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from ci_catalog_measurement import (
    CASES, CATALOG_CLASS, CATALOG_TEST, POLICY_CLASS, POLICY_TEST, SIZES, receipt,
)


class CatalogMeasurementTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.directory = Path(self.tmp.name)

    def report(self, klass, test, lines, outcome=None):
        root = ET.Element("testsuite", name=klass)
        case = ET.SubElement(root, "testcase", classname=klass, name=test)
        if outcome:
            ET.SubElement(case, outcome).text = "private failure secret"
        ET.SubElement(root, "system-out").text = "\n".join(lines)
        ET.ElementTree(root).write(self.directory / ("TEST-" + klass + ".xml"))

    @staticmethod
    def catalog_lines():
        return [f"CHUNKS_UNIFIED_CATALOG: jobs={size} case={case} "
                "median_us=12 range_us=10-15" for size in SIZES for case in CASES]

    @staticmethod
    def policy_lines():
        return [f"CHUNKS_POLICY_CATALOG: jobs={size} disabled_us=1 activation_us=20 "
                "steady_median_us=5 steady_range_us=4-6 "
                "inventory_per_enabled_tick=1 facts_per_tick=0" for size in SIZES]

    def test_complete_known_success_only_numeric_receipts(self):
        self.report(CATALOG_CLASS, CATALOG_TEST, self.catalog_lines() + ["secret tracker URL"])
        self.report(POLICY_CLASS, POLICY_TEST, self.policy_lines() + ["private password"])
        result = receipt(self.directory)
        self.assertEqual(16, len(result["unified_catalog"]))
        self.assertEqual(4, len(result["policy"]))
        self.assertEqual(12, result["unified_catalog"][0]["median_us"])
        self.assertEqual(0, result["policy"][0]["extra_facts_per_tick"])
        self.assertNotIn("secret", json.dumps(result))
        self.assertNotIn("password", json.dumps(result))

    def test_incomplete_duplicate_or_malformed_samples_are_unavailable(self):
        good = self.catalog_lines()
        variants = (good[:-1], good + good[:1],
                    good[:-1] + [good[-1] + " private token"],
                    good[:-1] + [good[-1].replace("12", "99")],
                    good[:-1] + [good[-1].replace("12", "1234567890")])
        for lines in variants:
            with self.subTest(count=len(lines)):
                self.report(CATALOG_CLASS, CATALOG_TEST, lines)
                self.assertIsNone(receipt(self.directory)["unified_catalog"])

    def test_wrong_case_failed_skipped_and_missing_reports_never_measure(self):
        self.assertIsNone(receipt(self.directory)["policy"])
        for outcome in ("failure", "error", "skipped"):
            self.report(POLICY_CLASS, POLICY_TEST, self.policy_lines(), outcome)
            self.assertIsNone(receipt(self.directory)["policy"])
        self.report(POLICY_CLASS, "unrelatedTest", self.policy_lines())
        self.assertIsNone(receipt(self.directory)["policy"])
