"""Mechanical transformation regression tests, not evidence of agent expertise."""
import json
import sys
import unittest
from copy import deepcopy
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'scripts'))
from compile_inquiry import compile_case, OP_KINDS

class ExecutableInquiryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalog=json.loads((ROOT/'assets/inquiry-operators-v1.json').read_text())
        cls.case=json.loads((ROOT/'examples/mechanical-remote-mutation.json').read_text())
        cls.typo=json.loads((ROOT/'examples/mechanical-routine-typo.json').read_text())

    def make(self,case=None):
        return compile_case(case if case is not None else self.case,self.catalog)

    def change(self,field,value,observation=0):
        x=deepcopy(self.case)
        x['observations'][observation][field]=value
        return x

    def test_remote_generates_two_unanswered_questions(self):
        r=self.make()
        self.assertEqual(len(r['questions']),2)
        self.assertEqual([q['operator_id'] for q in r['questions']],
                         ['BOUNDARY-CROSS','ORACLE-INVERT'])
        self.assertTrue(all(q['status']=='unanswered' for q in r['questions']))
        self.assertTrue(all(q['effectiveness']=='not_measured' for q in r['questions']))

    def test_trivial_fix_generates_nothing(self):
        self.assertEqual(self.make(self.typo)['questions'],[])

    def test_ungrounded_source_rejected(self):
        with self.assertRaisesRegex(ValueError,'ungrounded'):
            self.make(self.change('source_ids',['imaginary']))

    def test_duplicate_source_rejected(self):
        x=deepcopy(self.case);x['sources'].append(x['sources'][0])
        with self.assertRaisesRegex(ValueError,'Duplicate source'):
            self.make(x)

    def test_kind_must_match_operator(self):
        with self.assertRaisesRegex(ValueError,'mismatch'):
            self.make(self.change('kind','goal_obstacle'))

    def test_placeholder_facts_required(self):
        x=deepcopy(self.case);del x['observations'][0]['facts']['after']
        with self.assertRaisesRegex(ValueError,'missing required'):
            self.make(x)

    def test_reject_gate_required(self):
        with self.assertRaisesRegex(ValueError,'rejection gate'):
            self.make(self.change('rejection_checked',False))

    def test_reject_gate_skips_question(self):
        r=self.make(self.change('rejection_applies',True))
        self.assertEqual(len(r['questions']),1)
        self.assertEqual(len(r['skipped']),1)

    def test_no_choice_difference_skips_question(self):
        x=deepcopy(self.case)
        x['observations'][0]['branches'][1]['decision_if_true']=x['observations'][0]['branches'][0]['decision_if_true']
        r=self.make(x)
        self.assertEqual(len(r['questions']),1)
        self.assertIn('same decision',r['skipped'][0]['reason'])

    def test_routine_classification_cannot_override_consequential_cue(self):
        x=deepcopy(self.case);x['risk_tier']='routine'
        with self.assertRaisesRegex(ValueError,'classification conflicts'):
            self.make(x)

    def test_model_condition_not_automatic(self):
        with self.assertRaisesRegex(ValueError,'concept-model gate'):
            self.make(self.change('model_dependency','always_graph'))

    def test_oracle_must_be_observable(self):
        with self.assertRaisesRegex(ValueError,'result oracle'):
            self.make(self.change('oracle',''))

    def test_fake_intuition_source_not_evidence(self):
        x=deepcopy(self.case);x['sources'][0]['kind']='expert_intuition'
        with self.assertRaisesRegex(ValueError,'source type'):
            self.make(x)

    def test_all_nine_templates_can_be_parameterized(self):
        from string import Formatter
        example={'actor':'the user','goal':'the named job','control':'Save',
                 'resource':'selected item','before':'approval','after':'dispatch',
                 'option_a':'list view','option_b':'card view','constraint':'limited space',
                 'result':'visible completion','rule':'automatic retry',
                 'environment':'current app','claim':'remote success',
                 'evidence':'a unit test','system_a':'download manager','system_b':'media library'}
        x=deepcopy(self.case);prototype=x['observations'][0];x['observations']=[]
        for n,(opid,kind) in enumerate(OP_KINDS.items()):
            op=next(i for i in self.catalog['operators'] if i['id']==opid)
            facts={k:example[k] for _,k,_,_ in Formatter().parse(op['question_template']) if k}
            record=deepcopy(prototype);record.update(id=f'O{n}',operator_id=opid,kind=kind,facts=facts)
            x['observations'].append(record)
        self.assertEqual(len(self.make(x)['questions']),9)

if __name__=='__main__':
    unittest.main()
