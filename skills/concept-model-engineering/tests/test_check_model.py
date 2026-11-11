"""Negative controls for a candidate knowledge-frontier model."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location("modelcheck",ROOT/"scripts"/"check_model.py")
mod=importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)
example=json.loads((ROOT/"examples"/"cross-system-journey.json").read_text())

class ModelTests(unittest.TestCase):
    def test_fixture_passes(self):
        self.assertEqual([],mod.check(example))

    def test_source_provenance_required(self):
        item=copy.deepcopy(example)
        item["claims"][0]["source_ids"]=["NO_SOURCE"]
        self.assertTrue(any("provenance" in x for x in mod.check(item)))

    def test_missing_edge_endpoint_fails(self):
        item=copy.deepcopy(example)
        item["claims"][0]["object"]="NO_CONCEPT"
        self.assertTrue(any("unknown object" in x for x in mod.check(item)))

    def test_duplicate_nodes_fails(self):
        item=copy.deepcopy(example)
        item["concepts"].append(copy.deepcopy(item["concepts"][0]))
        self.assertTrue(any("Duplicate" in x for x in mod.check(item)))

    def test_fake_frontier_is_rejected(self):
        item=copy.deepcopy(example)
        item["gaps"][0].update(kind="candidate_frontier",qualification="qualified_frontier")
        self.assertTrue(any("unqualified novelty" in x for x in mod.check(item)))

    def test_frontier_metadata_can_be_well_formed(self):
        item=copy.deepcopy(example)
        item["source_records"].append({"id":"S2","url":"https://example.net",
                                       "role":"fictional","as_of":"2026-01-02"})
        item["gaps"][0].update(kind="candidate_frontier",qualification="qualified_frontier",
                              source_checks=["S1","S2"],
                              disconfirmation_attempts=[{"source_id":"S2","finding":"none in this fictional source"}],
                              novelty_scope="Two fictional sources only")
        self.assertEqual([],mod.check(item))

    def test_answered_question_without_evidence_fails(self):
        item=copy.deepcopy(example)
        item["competency_questions"][0]["claim_ids"]=[]
        self.assertTrue(any("no cited claims" in x for x in mod.check(item)))

if __name__=="__main__":
    unittest.main()
