import json
import sys
import unittest
from copy import deepcopy
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from check_inquiry import validate


class InquiryValidationTests(unittest.TestCase):
    def setUp(self):
        self.card = json.loads((ROOT / 'examples/remote-reconnect.json').read_text(encoding='utf-8'))

    def test_example_is_explicitly_unverified(self):
        self.assertEqual([], validate(self.card))
        self.assertEqual('not-run', self.card['verification']['result'])

    def test_must_not_claim_pass_without_evidence(self):
        c=deepcopy(self.card)
        c['verification']['result']='pass'
        c['verification']['tier']='device'
        self.assertIn('Pass requires concrete evidence references',validate(c))

    def test_must_not_mark_verified_when_not_run(self):
        c=deepcopy(self.card)
        c['status']='verified'
        self.assertIn('Verified card requires successful independent proof',validate(c))

    def test_no_unknown_graph_nodes(self):
        c=deepcopy(self.card)
        c['model']['edges'][0]['to']='Imaginary'
        self.assertTrue(any('undefined node' in e for e in validate(c)))

    def test_no_observed_edges_without_source(self):
        c=deepcopy(self.card)
        c['model']['edges'][0]['truth_kind']='observed'
        self.assertIn('Observed edge requires at least one source',validate(c))

    def test_no_unsupported_choice(self):
        c=deepcopy(self.card)
        c['decision']['status']='accepted'
        c['decision']['selected']='other'
        self.assertTrue(any('absent' in e for e in validate(c)))

    def test_answered_question_requires_answer(self):
        c=deepcopy(self.card)
        c['questions'][0]['status']='answered'
        self.assertTrue(any('no answer' in e for e in validate(c)))

    def test_no_fake_source_links(self):
        c=deepcopy(self.card)
        c['questions'][0]['source_ids']=['unknown-src']
        self.assertTrue(any('Missing evidence source' in e for e in validate(c)))

    def test_not_run_cannot_report_pass(self):
        c=deepcopy(self.card)
        c['verification']['result']='pass'
        self.assertIn('Unexecuted verification cannot be marked pass/fail',validate(c))


if __name__=='__main__':
    unittest.main()