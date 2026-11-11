"""Test staged source-ingestion and identity review, never semantic truth."""
import copy
import importlib.util
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
spec = importlib.util.spec_from_file_location("ingestion", ROOT / "scripts" / "audit_ingestion.py")
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)
fixture = json.loads((ROOT / "examples" / "multilingual-ingestion.json").read_text(encoding="utf-8"))


class IngestionAuditTests(unittest.TestCase):
    def scan(self, item):
        return mod.audit(item)

    def kinds(self, item):
        return {f["kind"] for f in self.scan(item)["findings"]}

    def test_fixture_is_review_only(self):
        out = self.scan(fixture)
        self.assertEqual(out["status"], "review_candidates_only")
        self.assertEqual(6, out["counts"]["sources"])
        self.assertIn("never merged", out["limits"])

    def test_reused_lineage_is_not_counted_twice(self):
        q = self.scan(fixture)["coverage"][0]
        self.assertEqual("Q1", q["question_id"])
        self.assertEqual(4, len(q["inspected_source_ids"]))
        self.assertEqual(3, q["distinct_declared_lineages"])
        self.assertIn("shared_lineage_review", self.kinds(fixture))

    def test_missing_test_is_a_source_gap_not_feature_absence(self):
        q = next(x for x in self.scan(fixture)["coverage"] if x["question_id"] == "Q2")
        self.assertEqual(["test"], q["uninspected_required_kinds"])
        self.assertIn("source_class_gap_review", self.kinds(fixture))
        self.assertNotIn("feature_absence", str(self.scan(fixture)))

    def test_unsearched_and_inaccessible_source_classes_are_distinct(self):
        report = self.scan(fixture)
        q = next(x for x in report["coverage"] if x["question_id"] == "Q2")
        self.assertEqual(["not_searched"], q["uninspected_required_statuses"]["test"])
        inaccessible = copy.deepcopy(fixture)
        inaccessible["sources"][4]["status"] = "inaccessible"
        q2 = next(x for x in self.scan(inaccessible)["coverage"] if x["question_id"] == "Q2")
        self.assertEqual(["inaccessible"], q2["uninspected_required_statuses"]["test"])
        missing = copy.deepcopy(fixture)
        missing["sources"] = [s for s in missing["sources"] if s["id"] != "S5"]
        q3 = next(x for x in self.scan(missing)["coverage"] if x["question_id"] == "Q2")
        self.assertEqual(["not_in_inventory"], q3["uninspected_required_statuses"]["test"])

    def test_cross_source_join_is_reviewed_even_when_both_endpoints_cited(self):
        self.assertIn("cross_source_bridge_review", self.kinds(fixture))
        finding = next(f for f in self.scan(fixture)["findings"]
                       if f["kind"] == "cross_source_bridge_review")
        self.assertEqual(["R2"], finding["subject_ids"])
        self.assertIn("insufficient", finding["operation"])

    def test_unicode_nfkc_detects_review_match(self):
        self.assertEqual(mod.normalize_label("Ｔｒａｎｓｆｅｒ"), mod.normalize_label("Transfer"))
        self.assertIn("possible_identity_match_review", self.kinds(fixture))

    def test_diacritics_are_preserved(self):
        self.assertNotEqual(mod.normalize_label("café"), mod.normalize_label("cafe"))

    def test_punctuation_is_preserved(self):
        self.assertNotEqual(mod.normalize_label("C++"), mod.normalize_label("C"))

    def test_same_word_different_role_is_homonym(self):
        self.assertIn("homonym_or_scope_collision_review", self.kinds(fixture))

    def test_different_labels_with_same_id_are_reviewed(self):
        self.assertIn("alias_evidence_review", self.kinds(fixture))

    def test_unassigned_mention_is_not_auto_merged(self):
        self.assertIn("unresolved_mention_review", self.kinds(fixture))

    def test_opposed_evidence_kept_separately(self):
        self.assertIn("opposed_claims_review", self.kinds(fixture))

    def test_wrong_source_reference_rejected(self):
        item = copy.deepcopy(fixture)
        item["mentions"][0]["source_id"] = "DOES_NOT_EXIST"
        with self.assertRaisesRegex(ValueError, "inspected source"):
            self.scan(item)

    def test_uninspected_source_cannot_support_mention(self):
        item = copy.deepcopy(fixture)
        item["mentions"][0]["source_id"] = "S5"
        with self.assertRaisesRegex(ValueError, "inspected source"):
            self.scan(item)

    def test_unknown_coverage_question_rejected(self):
        item = copy.deepcopy(fixture)
        item["sources"][0]["covers"] = ["MISSING"]
        with self.assertRaisesRegex(ValueError, "source-question"):
            self.scan(item)

    def test_duplicate_ids_rejected(self):
        item = copy.deepcopy(fixture)
        item["sources"].append(copy.deepcopy(item["sources"][0]))
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            self.scan(item)

    def test_unknown_lineage_is_not_assumed_independent(self):
        q = next(x for x in self.scan(fixture)["coverage"] if x["question_id"] == "Q2")
        self.assertEqual(["S6"], q["unknown_lineage_source_ids"])

    def test_invalid_relation_endpoint_rejected(self):
        item = copy.deepcopy(fixture)
        item["relations"][0]["object_mention_id"] = "MISSING"
        with self.assertRaisesRegex(ValueError, "unknown mention"):
            self.scan(item)

    def test_reconstruction_requires_original_source_citations(self):
        item = copy.deepcopy(fixture)
        item["relations"][1]["source_ids"] = ["S2"]
        self.assertIn("cross_source_relation_review", self.kinds(item))

    def test_id_reused_across_scopes_flagged_not_merged(self):
        item = copy.deepcopy(fixture)
        item["mentions"][4]["scope"] = "different_version"
        self.assertIn("claimed_identity_boundary_review", self.kinds(item))

    def test_structural_output_is_deterministic(self):
        self.assertEqual(self.scan(fixture), self.scan(copy.deepcopy(fixture)))


if __name__ == "__main__":
    unittest.main()
