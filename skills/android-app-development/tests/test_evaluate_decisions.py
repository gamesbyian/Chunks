"""Fast, offline checks for the decision corpus and grader."""
import importlib.util
import json
from pathlib import Path
import unittest

root = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("eval", root / "scripts" / "evaluate_decisions.py")
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class DecisionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cases = mod.load_cases()

    def test_cases_are_unique_complete_and_source_linked(self):
        self.assertGreaterEqual(len(self.cases), 16)
        self.assertEqual(len({c["case_id"] for c in self.cases}), len(self.cases))
        for c in self.cases:
            self.assertTrue(c["source"].startswith("https://github.com/"))
            self.assertGreater(len(c["scenario"]), 60)

    def test_prompts_do_not_expose_keys_or_sources(self):
        out = mod.prompts(self.cases)
        self.assertEqual(len(out), len(self.cases))
        for p in out:
            self.assertEqual(set(p), {"id", "scenario", "choices"})
        self.assertNotIn('"answer"', json.dumps(out))
        self.assertNotIn('"reason"', json.dumps(out))
        self.assertNotIn("github.com", json.dumps(out))

    def test_all_correct_and_all_wrong(self):
        perfect = [{"id": c["id"], "choice": c["answer"], "rationale": "checked"} for c in self.cases]
        good = mod.grade(self.cases, perfect)
        self.assertEqual(good["correct"], len(self.cases))
        wrong = [{"id": c["id"], "choice": next(x for x in "ABC" if x != c["answer"]), "rationale": "bad"}
                 for c in self.cases]
        self.assertEqual(mod.grade(self.cases, wrong)["correct"], 0)

    def test_missing_and_duplicates_are_not_false_green(self):
        result = mod.grade(self.cases, [])
        self.assertEqual(result["answered"], 0)
        self.assertEqual(result["correct"], 0)
        item = {"id": self.cases[0]["id"], "choice": "A", "rationale": "test"}
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            mod.grade(self.cases, [item, item])
        with self.assertRaisesRegex(ValueError, "Unknown"):
            mod.grade(self.cases, [{"id": "X999", "choice": "A", "rationale": "bad"}])


if __name__ == "__main__":
    unittest.main()
