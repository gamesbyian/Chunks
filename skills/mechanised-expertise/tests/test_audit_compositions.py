"""Validate source and composition guardrails, not efficacy."""
import csv
import json
import sys
import unittest
from copy import deepcopy
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'scripts'))
from audit_compositions import audit,validate_sources,validate_compositions

class CompositionIntegrityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source=ROOT/'assets/source-to-expertise-v09.csv'
        cls.composite=ROOT/'assets/compositions-v1.json'
        cls.sources=validate_sources(cls.source)
        cls.obj=json.loads(cls.composite.read_text())

    def changed(self,key,value):
        obj=deepcopy(self.obj)
        obj['compositions'][0][key]=value
        return obj

    def test_recovered_original_source_corpus(self):
        self.assertEqual(len(self.sources),22)
        self.assertIn('PRX-001',self.sources)

    def test_all_compositions_pass(self):
        result=audit(self.source,self.composite)
        self.assertEqual(result['compositions'],10)

    def test_unknown_source_is_rejected(self):
        with self.assertRaisesRegex(ValueError,'Unrecognized'):
            validate_compositions(self.changed('source_pattern_ids',['PRX-001','PRX-999']),self.sources)

    def test_repeated_source_is_rejected(self):
        with self.assertRaisesRegex(ValueError,'duplicated'):
            validate_compositions(self.changed('source_pattern_ids',['PRX-001','PRX-001']),self.sources)

    def test_same_publisher_not_independent_breadth(self):
        with self.assertRaisesRegex(ValueError,'publisher'):
            validate_compositions(self.changed('source_pattern_ids',['PRX-001','PRX-020']),self.sources)

    def test_no_model_exclusion_is_rejected(self):
        a=self.obj['compositions'][0]
        with self.assertRaisesRegex(ValueError,'disqualifier'):
            validate_compositions(self.changed('model_skip_when',a['model_when']),self.sources)

    def test_false_positivity_of_trigger_is_rejected(self):
        a=self.obj['compositions'][0]
        with self.assertRaisesRegex(ValueError,'opposing'):
            validate_compositions(self.changed('skip_if',a['trigger_cue']),self.sources)

    def test_effectiveness_overclaim_is_rejected(self):
        with self.assertRaisesRegex(ValueError,'overclaim'):
            validate_compositions(self.changed('evidence_status','proved_to_improve_agent_judgment'),self.sources)

    def test_unanswered_question_is_rejected(self):
        with self.assertRaisesRegex(ValueError,'Question'):
            validate_compositions(self.changed('consequential_question','Always implement the first recommendation'),self.sources)

    def test_no_oracle_is_rejected(self):
        with self.assertRaisesRegex(ValueError,'independent_oracle'):
            validate_compositions(self.changed('independent_oracle',''),self.sources)

    def test_no_owner_escalation_rule_is_rejected(self):
        with self.assertRaisesRegex(ValueError,'owner_escalation'):
            validate_compositions(self.changed('owner_escalation',''),self.sources)

    def test_no_disclaimer_is_rejected(self):
        obj=deepcopy(self.obj);obj['editorial_status']='Fully proven'
        with self.assertRaisesRegex(ValueError,'disclaimer'):
            validate_compositions(obj,self.sources)

    def test_duplicate_composition_is_rejected(self):
        obj=deepcopy(self.obj);obj['compositions'][1]['id']=obj['compositions'][0]['id']
        with self.assertRaisesRegex(ValueError,'duplicate'):
            validate_compositions(obj,self.sources)

if __name__=='__main__':
    unittest.main()
