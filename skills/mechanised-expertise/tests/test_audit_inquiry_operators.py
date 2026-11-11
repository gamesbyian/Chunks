import json
import sys
import unittest
from copy import deepcopy
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'scripts'))
from audit_inquiry_operators import audit,check_catalog,check_cases


class InquiryOperatorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalog_file=ROOT/'assets/inquiry-operators-v1.json'
        cls.cases_file=ROOT/'examples/operator-applications-v1.json'
        cls.catalog=json.loads(cls.catalog_file.read_text(encoding='utf-8'))
        cls.cases=json.loads(cls.cases_file.read_text(encoding='utf-8'))
        cls.operators=check_catalog(cls.catalog)

    def changed_operator(self,key,value):
        x=deepcopy(self.catalog)
        x['operators'][0][key]=value
        return x

    def changed_case(self,key,value):
        x=deepcopy(self.cases)
        x['cases'][0]['applied'][0][key]=value
        return x

    def test_complete_catalog_and_cases(self):
        data=audit(self.catalog_file,self.cases_file)
        self.assertEqual(data['operators'],9)
        self.assertEqual(data['cases'],3)
        self.assertEqual(data['negative_controls'],1)

    def test_duplicate_operator_rejected(self):
        x=deepcopy(self.catalog)
        x['operators'][1]['id']=x['operators'][0]['id']
        with self.assertRaisesRegex(ValueError,'duplicate'):
            check_catalog(x)

    def test_no_false_efficacy(self):
        x=deepcopy(self.catalog)
        x['status']='All operators now universally proved to improve agent expertise and engineering performance.'
        with self.assertRaisesRegex(ValueError,'measured effectiveness'):
            check_catalog(x)

    def test_no_reject_condition_rejected(self):
        with self.assertRaisesRegex(ValueError,'trivial'):
            check_catalog(self.changed_operator('reject_when',''))

    def test_same_trigger_and_reject_rejected(self):
        with self.assertRaisesRegex(ValueError,'opposing'):
            check_catalog(self.changed_operator('reject_when',self.catalog['operators'][0]['recognize']))

    def test_same_opposing_answers_rejected(self):
        a=self.catalog['operators'][0]['contrasting_answers'][0]
        with self.assertRaisesRegex(ValueError,'indistinguishable'):
            check_catalog(self.changed_operator('contrasting_answers',[a,a]))

    def test_question_must_be_parameterized(self):
        with self.assertRaisesRegex(ValueError,'parameterized'):
            check_catalog(self.changed_operator('question_template','Ask all possible questions?'))

    def test_unknown_gap_class_rejected(self):
        with self.assertRaisesRegex(ValueError,'gap class'):
            check_catalog(self.changed_operator('gap_checks',['first_to_market']))

    def test_single_reference_rejected(self):
        with self.assertRaisesRegex(ValueError,'supporting sources'):
            check_catalog(self.changed_operator('references',['https://example.com/a']))

    def test_non_https_reference_rejected(self):
        with self.assertRaisesRegex(ValueError,'citation URL'):
            check_catalog(self.changed_operator('references',['http://example.com','https://example.com/b']))

    def test_unknown_operator_in_application_rejected(self):
        with self.assertRaisesRegex(ValueError,'Unknown'):
            check_cases(self.changed_case('operator_id','INVENTED-TEST'),self.operators)

    def test_application_must_change_choice(self):
        answer=self.cases['cases'][0]['applied'][0]['if_first_answer']
        with self.assertRaisesRegex(ValueError,'change the planned'):
            check_cases(self.changed_case('if_second_answer',answer),self.operators)

    def test_false_success_weakens_case_status(self):
        x=deepcopy(self.cases);x['cases'][0]['evidence_status']='tested_and_shipped'
        with self.assertRaisesRegex(ValueError,'effectiveness claim'):
            check_cases(x,self.operators)

    def test_no_negative_control(self):
        x=deepcopy(self.cases);x['cases']=[i for i in x['cases'] if i['applied']]
        with self.assertRaisesRegex(ValueError,'teaching cases'):
            check_cases(x,self.operators)

    def test_no_model_gate_on_trivial_case(self):
        x=deepcopy(self.cases);x['cases'][-1]['model_gate']='prefer'
        with self.assertRaisesRegex(ValueError,'Negative control'):
            check_cases(x,self.operators)

    def test_no_double_route(self):
        x=deepcopy(self.cases)
        x['cases'][0]['skipped'][0]['operator_id']='MENTAL-MODEL'
        with self.assertRaisesRegex(ValueError,'activated and skipped'):
            check_cases(x,self.operators)


if __name__=='__main__':
    unittest.main()