"""Structural audit tests: findings are prompts, never auto-diagnosed gaps."""
import copy
import importlib.util
import json
import sys
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
spec = importlib.util.spec_from_file_location("coverageaudit", ROOT / "scripts" / "audit_coverage.py")
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)
example = json.loads((ROOT / "examples" / "cross-system-journey.json").read_text(encoding="utf-8"))


class CoverageAuditTests(unittest.TestCase):
    def kinds(self, model):
        return {f["kind"] for f in mod.audit(model)["findings"]}

    def test_example_flags_known_unknown_without_claiming_absence(self):
        result = mod.audit(example)
        self.assertEqual("review_candidates_only", result["status"])
        self.assertIn("question_needs_evidence", {f["kind"] for f in result["findings"]})
        self.assertIn("No feature absence", result["limits"])

    def test_isolated_concept_is_review_only(self):
        self.assertIn("isolated_concept_review", self.kinds(example))
        finding = next(x for x in mod.audit(example)["findings"] if x["kind"]=="isolated_concept_review")
        self.assertIn("do not infer", finding["operation"])

    def test_unused_source_is_detected(self):
        item = copy.deepcopy(example)
        item["source_records"].append({"id":"S_unused","url":"https://example.net/fictional","role":"hypothetical","as_of":"2026-01-01"})
        self.assertIn("source_coverage_review", self.kinds(item))

    def test_weak_answer_is_flagged(self):
        item = copy.deepcopy(example)
        item["claims"][0]["truth_kind"] = "hypothesized"
        item["claims"][1]["truth_kind"] = "unknown"
        self.assertIn("answer_evidence_review", self.kinds(item))

    def test_supported_answer_not_auto_flagged_as_weak(self):
        self.assertNotIn("answer_evidence_review", self.kinds(example))

    def test_invalid_references_fail_before_audit(self):
        item = copy.deepcopy(example)
        item["claims"][0]["object"] = "MISSING"
        with self.assertRaisesRegex(ValueError, "Invalid model"):
            mod.audit(item)

    def test_frontier_not_proclaimed_by_audit(self):
        result = mod.audit(example)
        self.assertNotIn("qualified_frontier", result["status"])
        self.assertTrue(all(f["kind"] != "new_feature" for f in result["findings"]))

    def test_gap_followup_has_class_specific_operation(self):
        x = next(f for f in mod.audit(example)["findings"] if f["kind"] == "gap_followup")
        self.assertEqual("unknown_fact", x["basis"])
        self.assertIn("source", x["operation"])


if __name__ == "__main__":
    unittest.main()
