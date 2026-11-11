"""Behavioral guardrails for authored source-to-mechanism instructions."""
import json
import sys
import unittest
from copy import deepcopy
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'scripts'))
from audit_source_mechanisms import audit

class MechanismExtractionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.payload=json.loads((ROOT/'assets/source-mechanisms-v1.json').read_text())
        import csv
        cls.source_ids=set()
        for path in ('source-to-expertise-v09.csv','source-to-expertise-v11-additions.csv'):
            with (ROOT/'assets'/path).open(encoding='utf-8-sig',newline='') as f:
                for row in csv.DictReader(f):
                    assert row['id'] not in cls.source_ids
                    cls.source_ids.add(row['id'])

    def check(self,x):
        return audit(x,self.source_ids)

    def changed(self,field,value):
        obj=deepcopy(self.payload)
        obj['procedures'][0][field]=value
        return obj

    def test_all_30_pinned_patterns_are_converted(self):
        result=self.check(self.payload)
        self.assertEqual(result['curated_procedures'],30)
        self.assertEqual({p['source_pattern_id'] for p in self.payload['procedures']},self.source_ids)

    def test_source_without_original_rejected(self):
        with self.assertRaisesRegex(ValueError,'pinned source'):
            self.check(self.changed('source_pattern_id','PRX-999'))

    def test_no_effectiveness_laundering(self):
        with self.assertRaisesRegex(ValueError,'provenance'):
            self.check(self.changed('provenance_status','proven_to_work'))

    def test_reject_condition_must_differ(self):
        first=self.payload['procedures'][0]['trigger']
        with self.assertRaisesRegex(ValueError,'rejection'):
            self.check(self.changed('exclude',first))

    def test_missing_step_input_fails(self):
        x=deepcopy(self.payload)
        x['procedures'][0]['steps'][1]['uses']=['not_produced_yet']
        with self.assertRaisesRegex(ValueError,'Undefined'):
            self.check(x)

    def test_forward_reference_fails(self):
        x=deepcopy(self.payload)
        x['procedures'][0]['steps'][0]['uses']=['unanswered_contracts']
        with self.assertRaisesRegex(ValueError,'Undefined'):
            self.check(x)

    def test_output_collision_fails(self):
        x=deepcopy(self.payload)
        x['procedures'][0]['steps'][0]['produces']='user_request'
        with self.assertRaisesRegex(ValueError,'overwrites'):
            self.check(x)

    def test_nebulous_command_fails(self):
        x=deepcopy(self.payload)
        x['procedures'][0]['steps'][1]['instruction']='Think like an expert and choose the best possible next step without testing.'
        with self.assertRaisesRegex(ValueError,'Nebulous'):
            self.check(x)

    def test_unbounded_operations_fail(self):
        x=deepcopy(self.payload)
        x['procedures'][0]['steps']=x['procedures'][0]['steps'][:2]
        with self.assertRaisesRegex(ValueError,'bounded'):
            self.check(x)

    def test_unknown_operation_fails(self):
        x=deepcopy(self.payload)
        x['procedures'][0]['steps'][0]['op']='intuition'
        with self.assertRaisesRegex(ValueError,'Unknown'):
            self.check(x)

    def test_same_yes_no_choice_fails(self):
        x=deepcopy(self.payload)
        x['procedures'][0]['decision_gate']['if_no']=x['procedures'][0]['decision_gate']['if_yes']
        with self.assertRaisesRegex(ValueError,'opposing'):
            self.check(x)

    def test_empty_oracle_fails(self):
        with self.assertRaisesRegex(ValueError,'oracle'):
            self.check(self.changed('oracle',''))

if __name__=='__main__':
    unittest.main()
