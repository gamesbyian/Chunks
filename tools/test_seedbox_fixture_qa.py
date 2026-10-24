"""Deterministic offline proof of read-only fixture QA selection."""
import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).parent))
from seedbox_fixture_qa import analyze, markdown, FIELDS

H = lambda v: f"{v:040x}"

def document():
    return {"source":"owner-approved-seedhost-dev","view":"main",
            "completeness":"view-scoped-unverified","fields":FIELDS,
            "transfers":[
                [H(1), "Ordinary.iso", 128, 128, 1, "/srv/downloads"],
                [H(2), "مسلسل الموسم ١ " + "ع" * 80 + " [final]!", 1000, 100, 0,
                 "/srv/downloads/series/franchise/season/very/long/path/" + "x" * 100],
                [H(3), "Video & Audio (2026).webm", 2_000_000_000, 0, 1,
                 "/srv/downloads/film"],
                [H(4), "Empty placeholder", 0, 0, 0, "/srv/downloads"],
            ]}

class QATest(unittest.TestCase):
    def test_deterministic_case_selection_and_text_output(self):
        fixture = document()
        actual = analyze(fixture)
        self.assertEqual(4, actual["total"])
        self.assertEqual(1, actual["counts"]["rtl"])
        self.assertEqual(1, actual["counts"]["zero_bytes"])
        self.assertEqual(2, actual["counts"]["started"])
        self.assertEqual("view-scoped-unverified", actual["completeness"])
        cases = [case["case"] for case in actual["cases"]]
        self.assertIn("rtl", cases)
        self.assertIn("long_path", cases)
        self.assertIn("large_transfer", cases)
        self.assertIn("punctuation_name", cases)
        self.assertEqual(actual, analyze(copy.deepcopy(fixture)))
        report = markdown(actual)
        self.assertIn("**not** a screenshot atlas", report)
        self.assertIn("مسلسل", report)

    def test_no_fabricated_case_when_missing(self):
        item = document()
        item["transfers"] = [item["transfers"][0]]
        actual = analyze(item)
        self.assertIn("rtl", actual["coverage"]["missing_cases"])
        self.assertNotIn("rtl", [case["case"] for case in actual["cases"]])

    def test_bad_input_fails_closed(self):
        source = document()
        mutants = []
        for key, value in (("source", "personal-server"), ("view", "default"),
                           ("fields", ["d.hash="]), ("transfers", "bad")):
            bad = copy.deepcopy(source)
            bad[key] = value
            mutants.append(bad)
        for column, bad_value in ((0, "../other"), (1, "bad\nfile"),
                                  (2, -1), (3, 5000), (4, 5), (5, "bad\npath")):
            bad = copy.deepcopy(source)
            bad["transfers"][0][column] = bad_value
            mutants.append(bad)
        duplicated = copy.deepcopy(source)
        duplicated["transfers"][1][0] = duplicated["transfers"][0][0]
        mutants.append(duplicated)
        for value in mutants:
            with self.subTest(value=value["transfers"] if isinstance(value["transfers"], str) else "structure"):
                with self.assertRaises(ValueError):
                    analyze(value)

    def test_empty_fixture_is_explicitly_empty(self):
        doc = document()
        doc["transfers"] = []
        result = analyze(doc)
        self.assertEqual(0, result["total"])
        self.assertEqual([], result["cases"])
        self.assertEqual(0, result["coverage"]["selected_unique"])

if __name__ == "__main__":
    unittest.main()
