"""Integrity checks for eight additional pinned sources and editorial mechanisms.

Checks structure and attribution consistency only. The tests do not establish
that publishers' advice is correct or that our transformations improve agents.
"""
import csv
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT/'assets/source-to-expertise-v09.csv'
EXTRA = ROOT/'assets/source-to-expertise-v11-additions.csv'
MECHANISMS = ROOT/'assets/source-mechanisms-v1.json'
EXPECTED = {'PRX-'+str(x).zfill(3) for x in range(23,31)}


def read(path):
    with path.open(encoding='utf-8-sig',newline='') as f:
        return list(csv.DictReader(f))


def verify(row):
    required = ('id','source_repository','source_commit','source_path','source_line',
                'source_url','source_paraphrase','editorial_status','recognition_cue',
                'decision_question','research_strategy','model_trigger',
                'decision_proposal','applicability_exception','independent_oracle')
    if any(not row.get(k) for k in required):
        raise ValueError('Incomplete source row')
    if not re.fullmatch('[0-9a-f]{40}',row['source_commit']):
        raise ValueError('Source commit not pinned')
    if not row['source_line'].isdigit() or int(row['source_line'])<1:
        raise ValueError('Invalid source line')
    expected = ('https://github.com/'+row['source_repository']+'/blob/'+
                row['source_commit']+'/'+row['source_path']+'#L'+row['source_line'])
    if row['source_url'] != expected:
        raise ValueError('Source URL does not match pinned location')
    if 'not effectiveness-tested' not in row['editorial_status']:
        raise ValueError('Unsupported effectiveness claim')
    if '?' not in row['decision_question']:
        raise ValueError('No decision-changing inquiry')
    return True


class ExtendedSourceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.base=read(BASE)
        cls.extra=read(EXTRA)
        cls.procedures=json.loads(MECHANISMS.read_text(encoding='utf-8'))['procedures']

    def test_preserved_original_22_sources(self):
        self.assertEqual(len(self.base),22)
        self.assertEqual({x['id'] for x in self.base},{'PRX-'+str(i).zfill(3) for i in range(1,23)})

    def test_exactly_eight_new_distinct_source_ids(self):
        self.assertEqual({x['id'] for x in self.extra},EXPECTED)
        self.assertEqual(len(self.extra),8)
        self.assertFalse({x['id'] for x in self.extra}&{x['id'] for x in self.base})

    def test_header_and_pinned_links(self):
        self.assertEqual(set(self.base[0]),set(self.extra[0]))
        for row in self.extra:
            self.assertTrue(verify(row))

    def test_publisher_breadth_is_sample_limited(self):
        self.assertGreaterEqual(len({x['source_repository'] for x in self.extra}),7)

    def test_each_source_has_one_mechanised_procedure(self):
        derived=[x for x in self.procedures if x['source_pattern_id'] in EXPECTED]
        self.assertEqual(len(derived),8)
        self.assertEqual({x['source_pattern_id'] for x in derived},EXPECTED)
        for x in derived:
            self.assertNotEqual(x['trigger'],x['exclude'])
            self.assertNotEqual(x['decision_gate']['if_yes'],x['decision_gate']['if_no'])
            self.assertGreaterEqual(len(x['steps']),3)
            self.assertTrue(x['oracle'].strip())

    def test_broken_url_is_rejected(self):
        x=dict(self.extra[0]);x['source_url']=x['source_url'].replace('#L71','#L999')
        with self.assertRaisesRegex(ValueError,'URL'):
            verify(x)

    def test_effectiveness_claim_is_rejected(self):
        x=dict(self.extra[0]);x['editorial_status']='measured, effective'
        with self.assertRaisesRegex(ValueError,'effectiveness'):
            verify(x)


if __name__=='__main__':
    unittest.main()
