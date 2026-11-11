"""Executable negative controls for explicit, add-only concept-model proposals."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import propose_model_delta as mod

def read(path):
    return json.loads((ROOT / path).read_text(encoding="utf-8"))

BASE = read("examples/cross-system-journey.json")
STAGED = read("examples/multilingual-ingestion.json")
REVIEW = read("examples/reviewed-delta-decisions.json")


class ReviewedDeltaTests(unittest.TestCase):
    def run_delta(self, model=None, staged=None, review=None):
        return mod.propose(copy.deepcopy(model if model is not None else BASE),
                           copy.deepcopy(staged if staged is not None else STAGED),
                           copy.deepcopy(review if review is not None else REVIEW))

    def test_valid_fixture_is_additive_and_review_only(self):
        out, projection = self.run_delta()
        self.assertEqual("proposal_only_no_original_mutated", out["status"])
        self.assertEqual(["P3"], [c["id"] for c in out["additions"]["claims"]])
        self.assertEqual("SRC_OWNER", out["additions"]["source_records"][0]["id"])
        self.assertEqual([], out["additions"]["concepts"])
        self.assertEqual(3, len(projection["claims"]))
        self.assertEqual([], mod.check(projection))
        self.assertIn("R2", out["pending"]["relations"])

    def test_base_not_modified(self):
        original = copy.deepcopy(BASE)
        self.run_delta(model=BASE)
        self.assertEqual(original, BASE)

    def test_original_claim_and_question_statuses_preserved(self):
        _, projected = self.run_delta()
        self.assertEqual(BASE["claims"], projected["claims"][:len(BASE["claims"])])
        self.assertEqual(BASE["competency_questions"], projected["competency_questions"])
        self.assertEqual(BASE["gaps"], projected["gaps"])

    def test_identical_inputs_have_deterministic_output(self):
        self.assertEqual(self.run_delta(), self.run_delta())

    def test_digest_changes_for_changed_base_without_rewriting_it(self):
        modified = copy.deepcopy(BASE)
        modified["scope"] += " extended"
        self.assertNotEqual(self.run_delta()[0]["base_model_digest"],
                            self.run_delta(model=modified)[0]["base_model_digest"])

    def test_unreviewed_identity_is_never_guessed_from_candidate_id(self):
        out, _ = self.run_delta()
        self.assertIn("M2", out["pending"]["mentions"])
        self.assertIn("M3", out["pending"]["mentions"])

    def test_unreviewed_source_cannot_support_a_new_claim(self):
        review = copy.deepcopy(REVIEW)
        review["source_links"][0]["stage_id"] = "S5"  # not_searched
        with self.assertRaisesRegex(ValueError, "must be inspected"):
            self.run_delta(review=review)

    def test_existing_source_reference_must_match_locator(self):
        review = copy.deepcopy(REVIEW)
        review["source_links"][0].update(
            decision="link_existing", model_id="S1", as_of=None)
        with self.assertRaisesRegex(ValueError, "source identity mismatch"):
            self.run_delta(review=review)

    def test_new_source_id_collision_is_rejected(self):
        review = copy.deepcopy(REVIEW)
        review["source_links"][0]["model_id"] = "S1"
        with self.assertRaisesRegex(ValueError, "collision"):
            self.run_delta(review=review)

    def test_new_source_requires_date_not_revision_pseudodate(self):
        review = copy.deepcopy(REVIEW)
        review["source_links"][0]["as_of"] = "fixture-v1"
        with self.assertRaisesRegex(ValueError, "invalid as_of"):
            self.run_delta(review=review)

    def test_duplicate_source_reviews_rejected(self):
        review = copy.deepcopy(REVIEW)
        review["source_links"].append(copy.deepcopy(review["source_links"][0]))
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            self.run_delta(review=review)

    def test_unknown_mention_cannot_be_accepted(self):
        review = copy.deepcopy(REVIEW)
        review["identity_links"][0]["mention_id"] = "NOPE"
        with self.assertRaisesRegex(ValueError, "Unknown reviewed mention"):
            self.run_delta(review=review)

    def test_unbound_existing_concept_cannot_be_assumed(self):
        review = copy.deepcopy(REVIEW)
        review["identity_links"][0]["concept_id"] = "C_transfer"
        with self.assertRaisesRegex(ValueError, "Unreviewed concept identity"):
            self.run_delta(review=review)

    def test_accepted_relation_requires_both_reviewed_endpoint_identities(self):
        review = copy.deepcopy(REVIEW)
        review["identity_links"].pop()
        with self.assertRaisesRegex(ValueError, "unreviewed endpoint"):
            self.run_delta(review=review)

    def test_accepted_relation_requires_reviewed_cited_sources(self):
        review = copy.deepcopy(REVIEW)
        review["source_links"].clear()
        with self.assertRaisesRegex(ValueError, "unreviewed source"):
            self.run_delta(review=review)

    def test_cross_source_bridge_cannot_be_implicitly_inferred(self):
        review = self.cross_source_review()
        review["relation_reviews"][1]["bridge_source_id"] = None
        review["relation_reviews"][1]["bridge_locator"] = None
        with self.assertRaisesRegex(ValueError, "Cross-source bridge"):
            self.run_delta(review=review)

    def test_cross_source_bridge_must_cite_original_source(self):
        review = self.cross_source_review()
        review["relation_reviews"][1]["bridge_source_id"] = "S3"
        with self.assertRaisesRegex(ValueError, "Cross-source bridge"):
            self.run_delta(review=review)

    def test_cross_source_bridge_with_checked_span_is_additive(self):
        review = self.cross_source_review()
        out, projected = self.run_delta(review=review)
        self.assertEqual(["P3", "P4"], [c["id"] for c in out["additions"]["claims"]])
        self.assertEqual("refuted", projected["claims"][-1]["truth_kind"])
        self.assertNotIn("R2", out["pending"]["relations"])

    def test_accepted_relation_preserves_truth_kind(self):
        review = copy.deepcopy(REVIEW)
        staged = copy.deepcopy(STAGED)
        staged["relations"][0]["truth_kind"] = "inferred"
        _, projected = self.run_delta(staged=staged, review=review)
        self.assertEqual("inferred", projected["claims"][-1]["truth_kind"])

    def test_deferred_relation_cannot_allocate_claim_id(self):
        review = copy.deepcopy(REVIEW)
        review["relation_reviews"][1]["claim_id"] = "P4"
        with self.assertRaisesRegex(ValueError, "Deferred relation"):
            self.run_delta(review=review)

    def test_existing_claim_cannot_be_silently_overwritten(self):
        review = copy.deepcopy(REVIEW)
        review["relation_reviews"][0]["claim_id"] = "P1"
        with self.assertRaisesRegex(ValueError, "Claim ID collision"):
            self.run_delta(review=review)

    def test_challenge_must_match_same_scoped_proposition(self):
        review = copy.deepcopy(REVIEW)
        review["relation_reviews"][0]["challenge_claim_ids"] = ["P1"]
        with self.assertRaisesRegex(ValueError, "same scoped proposition"):
            self.run_delta(review=review)

    def test_challenge_preserves_original_and_adds_refutation(self):
        review = copy.deepcopy(REVIEW)
        staged = copy.deepcopy(STAGED)
        staged["relations"][0].update(truth_kind="refuted", scope="synthetic example only")
        review["identity_links"][1]["concept_id"] = "C3"
        review["relation_reviews"][0]["challenge_claim_ids"] = ["P1"]
        out, projected = self.run_delta(staged=staged, review=review)
        self.assertEqual("documented", projected["claims"][0]["truth_kind"])
        self.assertEqual("refuted", projected["claims"][-1]["truth_kind"])
        self.assertEqual("preserve_both_pending_adjudication",
                         out["challenges_preserved"][0]["disposition"])

    def test_new_concept_requires_explicit_bound_mention(self):
        review = copy.deepcopy(REVIEW)
        review["new_concepts"].append(dict(id="C5", kind="action", label="Download",
            evidence_mention_ids=["M6"], checked_locator="synthetic://forum#L22",
            basis="Reviewed synthetic user-action mention"))
        self.add_user_source(review)
        with self.assertRaisesRegex(ValueError, "Orphan new concept"):
            self.run_delta(review=review)

    def test_new_concept_can_be_added_from_reviewed_mention(self):
        review = copy.deepcopy(REVIEW)
        review["new_concepts"].append(dict(id="C5", kind="action", label="Download",
            evidence_mention_ids=["M6"], checked_locator="synthetic://forum#L22",
            basis="Reviewed synthetic user-action mention"))
        review["identity_links"].append(dict(mention_id="M6", concept_id="C5",
            checked_locator="synthetic://forum#L22", basis="Keep distinct download action",
            boundary_basis=None))
        self.add_user_source(review)
        out, projected = self.run_delta(review=review)
        self.assertEqual("C5", out["additions"]["concepts"][0]["id"])
        self.assertEqual([], mod.check(projected))

    def test_cross_role_concept_merger_needs_boundary_basis(self):
        review = copy.deepcopy(REVIEW)
        review["identity_links"].append(dict(mention_id="M3", concept_id="C2",
            checked_locator="synthetic://forum#L19", basis="Tempting same-label merge",
            boundary_basis=None))
        with self.assertRaisesRegex(ValueError, "Incompatible concept role"):
            self.run_delta(review=review)

    def test_unverified_stage_reference_rejected_by_ingestion_auditor(self):
        staged = copy.deepcopy(STAGED)
        staged["mentions"][0]["source_id"] = "MISSING"
        with self.assertRaisesRegex(ValueError, "inspected source"):
            self.run_delta(staged=staged)

    def test_cli_refuses_to_overwrite_original_model(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d)
            for name, data in (("model.json", BASE), ("stage.json", STAGED),
                               ("review.json", REVIEW)):
                (p / name).write_text(json.dumps(data), encoding="utf-8")
            command = [sys.executable, str(ROOT / "scripts" / "propose_model_delta.py"),
                       str(p / "model.json"), str(p / "stage.json"), str(p / "review.json"),
                       "--preview", str(p / "model.json")]
            completed = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(2, completed.returncode)
            self.assertIn("Refusing to replace", completed.stderr)
            self.assertEqual(BASE, json.loads((p / "model.json").read_text()))

    def test_cli_emits_separate_model_preview_and_proposal(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d)
            for name, data in (("model.json", BASE), ("stage.json", STAGED),
                               ("review.json", REVIEW)):
                (p / name).write_text(json.dumps(data), encoding="utf-8")
            command = [sys.executable, str(ROOT / "scripts" / "propose_model_delta.py"),
                       str(p / "model.json"), str(p / "stage.json"), str(p / "review.json"),
                       "--output", str(p / "delta.json"), "--preview", str(p / "preview.json")]
            completed = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(0, completed.returncode, completed.stderr)
            report = json.loads((p / "delta.json").read_text())
            preview = json.loads((p / "preview.json").read_text())
            self.assertEqual("proposal_only_no_original_mutated", report["status"])
            self.assertEqual([], mod.check(preview))
            self.assertEqual(BASE, json.loads((p / "model.json").read_text()))


    def test_single_incompatible_existing_concept_requires_review(self):
        review = copy.deepcopy(REVIEW)
        review["identity_links"].pop(0)  # no second mention to expose a collision
        review["identity_links"][0]["concept_id"] = "C2"  # state mapped to operation
        with self.assertRaisesRegex(ValueError, "Incompatible concept role"):
            self.run_delta(review=review)

    def test_unlinked_source_cannot_create_new_concept(self):
        review = copy.deepcopy(REVIEW)
        review["new_concepts"].append(dict(id="C5", kind="action", label="Download",
            evidence_mention_ids=["M6"], checked_locator="synthetic://forum#L22",
            basis="A reviewed mention does not establish a model source"))
        review["identity_links"].append(dict(mention_id="M6", concept_id="C5",
            checked_locator="synthetic://forum#L22", basis="Explicit tentative concept",
            boundary_basis=None))
        with self.assertRaisesRegex(ValueError, "requires a reviewed source"):
            self.run_delta(review=review)

    def test_proposal_contains_original_review_trace(self):
        out, _ = self.run_delta()
        self.assertEqual(REVIEW["identity_links"], out["review_trace"]["identity_links"])
        self.assertEqual(REVIEW["relation_reviews"], out["review_trace"]["relation_reviews"])
        self.assertIn("base_model_digest", out)
        self.assertIn("review_decisions_digest", out)

    def test_cli_never_replaces_existing_unrelated_preview(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d)
            for name, data in (("model.json", BASE), ("stage.json", STAGED),
                               ("review.json", REVIEW)):
                (p / name).write_text(json.dumps(data), encoding="utf-8")
            target = p / "other-project.json"
            target.write_text("Do not replace", encoding="utf-8")
            command = [sys.executable, str(ROOT / "scripts" / "propose_model_delta.py"),
                       str(p / "model.json"), str(p / "stage.json"), str(p / "review.json"),
                       "--output", str(p / "delta.json"), "--preview", str(target)]
            completed = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(2, completed.returncode)
            self.assertIn("Refusing to overwrite existing output", completed.stderr)
            self.assertEqual("Do not replace", target.read_text())
            self.assertFalse((p / "delta.json").exists())

    @staticmethod
    def add_user_source(review):
        review["source_links"].append(dict(stage_id="S3", model_id="SRC_USER",
            decision="add", as_of="2026-01-01",
            checked_locator="synthetic://forum#L22",
            basis="Synthetic reviewed user source"))
        return review

    @staticmethod
    def cross_source_review():
        review = copy.deepcopy(REVIEW)
        review["source_links"].append(dict(stage_id="S2", model_id="SRC_IMPL",
            decision="add", as_of="2026-01-01",
            checked_locator="synthetic://code#L44", basis="Inspected synthetic code fixture"))
        review["identity_links"].append(dict(mention_id="M4", concept_id="C4",
            checked_locator="synthetic://code#L44",
            basis="Explicitly reviewed verification identity", boundary_basis=None))
        review["relation_reviews"][1].update(decision="accept", claim_id="P4",
            bridge_source_id="S2", bridge_locator="synthetic://code#L44")
        return review


if __name__ == "__main__":
    unittest.main()
